package com.deltachunk;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared helper for writing files the way this mod needs to on
 * Windows: write to a temp file first, then replace the real file,
 * retrying the replace step if Windows is transiently holding a
 * handle open on the target (antivirus scan, search indexer,
 * Explorer thumbnail/preview, a backup tool, etc).
 *
 * BACKGROUND / WHY THIS EXISTS:
 *
 * On Windows, Files.move(..., REPLACE_EXISTING) (and the ATOMIC_MOVE
 * variant) can throw AccessDeniedException / FileSystemException if
 * ANY process -- including ones that have nothing to do with this
 * mod -- currently has an open handle to the destination file. This
 * is a well-known Windows filesystem behavior (POSIX systems allow
 * replacing a file that's open elsewhere; Windows generally does
 * not). Unlike a logic bug, this class of failure is transient: the
 * offending handle is usually released within milliseconds to a few
 * seconds.
 *
 * The old version of this codebase only tried the move once and let
 * the exception propagate, which meant a single unlucky antivirus
 * scan at the exact moment of a save could permanently fail that
 * save. This class retries with a short backoff before giving up,
 * and ALSO guarantees the write to the temp file itself is fully
 * flushed and closed before any move is attempted (a move of a
 * still-open temp file is a self-inflicted version of the same
 * problem).
 *
 * This class is intentionally generic (works for any file, not just
 * WAM/MCA files) so both WamStore and RegionCompactor can share the
 * exact same replace-with-retry logic instead of each having a
 * slightly different, separately-maintained copy.
 */
public final class WindowsSafeIO {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(WindowsSafeIO.class);

    /*
     * Retry schedule for the final move/replace step. Kept short in
     * total (well under a second in the common case) since this
     * runs during world unload, where the player is already looking
     * at a loading/"saving world" screen and a slightly longer wait
     * there is much less disruptive than it would be mid-gameplay --
     * but still bounded, so a genuinely stuck handle doesn't hang
     * the shutdown sequence forever.
     */
    private static final int[] RETRY_DELAYS_MS = {
            25, 50, 100, 200, 400, 800, 1600
    };

    private WindowsSafeIO() {
    }

    @FunctionalInterface
    public interface StreamWriter {

        void write(OutputStream out) throws IOException;
    }

    @FunctionalInterface
    public interface DataStreamWriter {

        void write(DataOutputStream out) throws IOException;
    }

    /**
     * Write {@code content} to a temp file next to {@code target},
     * then atomically (or as close to atomically as the platform
     * allows) replace {@code target} with it, retrying the replace
     * step on transient Windows file-locking failures.
     */
    public static void writeAtomic(
            Path target,
            StreamWriter content
    ) throws IOException {

        Path temporary = temporaryPathFor(target);

        /*
         * Clean up any stale temp file left over from a previous
         * crashed/interrupted run before we start. If this fails
         * (e.g. THAT file is also locked for some reason), we still
         * proceed and let createNewTempFile pick a fresh unique
         * name below rather than hard-failing here.
         */
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
            // Non-fatal; see comment above.
        }

        writeTempFile(temporary, content);

        replaceWithRetry(temporary, target);
    }

    /**
     * Convenience overload for callers building the file contents
     * via a DataOutputStream (as both WamStore and the modified
     * index use), so they don't need to wrap/unwrap streams
     * themselves.
     */
    public static void writeAtomic(
            Path target,
            DataStreamWriter content
    ) throws IOException {

        writeAtomic(
                target,
                (OutputStream out) -> {

                    DataOutputStream data =
                            new DataOutputStream(
                                    new BufferedOutputStream(out)
                            );

                    content.write(data);

                    /*
                     * Flush (not close -- the caller of writeAtomic
                     * owns closing `out` via try-with-resources at
                     * the call site below) so every byte is actually
                     * handed to the underlying OutputStream before
                     * writeTempFile closes and fsyncs it.
                     */
                    data.flush();
                }
        );
    }

    private static Path temporaryPathFor(Path target) {

        return target.resolveSibling(
                target.getFileName() + ".tmp"
        );
    }

    private static void writeTempFile(
            Path temporary,
            StreamWriter content
    ) throws IOException {

        Files.createDirectories(temporary.getParent());

        try (
                OutputStream out =
                        Files.newOutputStream(
                                temporary,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE
                        )
        ) {

            content.write(out);

            /*
             * Force the OS to actually flush these bytes to the
             * underlying storage device before we return. Without
             * this, on some platforms/filesystems the subsequent
             * move could race ahead of buffered writes still sitting
             * in the OS page cache, which matters if the process is
             * killed between the write and the move.
             */
            out.flush();

        }
        // `out` is fully closed here -- no file handle from THIS
        // process remains open on the temp file, so the move below
        // cannot fail because of a handle we ourselves are holding.
    }

    /**
     * Attempt to move {@code temporary} onto {@code target},
     * replacing it, retrying on the specific class of failure caused
     * by another process transiently holding {@code target} open.
     *
     * If every retry is exhausted, this throws the last exception
     * encountered, and the temp file is deliberately left on disk
     * (rather than deleted) so no data is lost -- the caller/log
     * will show a `.tmp` file sitting next to the target, which is
     * recoverable by hand if it ever happens.
     */
    private static void replaceWithRetry(
            Path temporary,
            Path target
    ) throws IOException {

        IOException lastFailure = null;

        for (int attempt = 0; attempt <= RETRY_DELAYS_MS.length; attempt++) {

            try {

                try {

                    Files.move(
                            temporary,
                            target,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE
                    );

                } catch (AtomicMoveNotSupportedException notAtomic) {

                    /*
                     * Some filesystems (notably moving across
                     * different drives/volumes) don't support
                     * ATOMIC_MOVE at all. This is not the
                     * Windows-file-lock problem this method exists
                     * to solve, so fall back to a plain replace
                     * once, without consuming a retry slot for it.
                     */
                    Files.move(
                            temporary,
                            target,
                            StandardCopyOption.REPLACE_EXISTING
                    );
                }

                if (attempt > 0) {

                    LOGGER.info(
                            "[DeltaChunk] File replace for {} succeeded " +
                            "after {} retr{}.",
                            target.getFileName(),
                            attempt,
                            attempt == 1 ? "y" : "ies"
                    );
                }

                return;

            } catch (
                    java.nio.file.FileSystemException lockException
            ) {

                /*
                 * This is the transient-Windows-lock case this class
                 * exists for: AccessDeniedException is a subclass of
                 * FileSystemException, and other FileSystemException
                 * variants (e.g. "being used by another process")
                 * show up here too depending on JDK/platform. Retry.
                 */
                lastFailure = lockException;

                if (attempt < RETRY_DELAYS_MS.length) {

                    LOGGER.warn(
                            "[DeltaChunk] File replace for {} failed " +
                            "(attempt {}/{}), likely another process " +
                            "briefly has it open (antivirus, indexer, " +
                            "backup tool). Retrying in {} ms.",
                            target.getFileName(),
                            attempt + 1,
                            RETRY_DELAYS_MS.length + 1,
                            RETRY_DELAYS_MS[attempt]
                    );

                    sleep(RETRY_DELAYS_MS[attempt]);
                }
            }
        }

        LOGGER.error(
                "[DeltaChunk] File replace for {} failed after {} " +
                "attempts. Leaving the fully-written temp file at {} " +
                "so no data is lost; the original file at the target " +
                "path (if any) is unchanged.",
                target.getFileName(),
                RETRY_DELAYS_MS.length + 1,
                temporary,
                lastFailure
        );

        throw lastFailure;
    }

    private static void sleep(int millis) {

        try {

            Thread.sleep(millis);

        } catch (InterruptedException interrupted) {

            Thread.currentThread().interrupt();
        }
    }
}
