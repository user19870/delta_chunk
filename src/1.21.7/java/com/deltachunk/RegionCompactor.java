package com.deltachunk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Post-process compactor for Anvil (.mca) region files.
 *
 * This operates purely on the on-disk region file format and does
 * NOT go through any vanilla chunk serialization code. It is meant
 * to run once, right before the world is fully unloaded (see
 * DeltaChunk's LevelEvent.Unload handling), after vanilla has
 * finished writing every chunk it wants to write for this session.
 *
 * Region file layout (Anvil format):
 *   [0      .. 4096)   : 1024 header entries, 4 bytes each -> (offset:3 bytes in 4KiB sectors, sectorCount:1 byte)
 *   [4096   .. 8192)   : 1024 timestamps, 4 bytes each
 *   [8192   .. EOF)    : chunk payloads, sector (4096 byte) aligned.
 *                        Each payload record is [4 byte big-endian
 *                        length][1 byte compression type][length-1
 *                        bytes of compressed NBT data].
 *
 * For every chunk NOT present in the caller-provided keepPredicate,
 * this rewrites the entire region file so that chunk's payload is
 * gone entirely (not just its header pointer zeroed): a brand new
 * file is built containing only the header/timestamp slots and
 * payload bytes for chunks we're keeping, sector-aligned and packed
 * back-to-back starting right after the header+timestamp sectors.
 * The new file then replaces the original via WindowsSafeIO, which
 * retries the replace step if some other process (antivirus,
 * indexer, backup tool) is transiently holding the file open.
 *
 * A chunk with no vanilla payload remaining at all -- because either
 * it never existed or its entry was dropped here -- is treated by
 * the game the same way as "never generated", which is exactly what
 * makes the world generator regenerate it fresh the next time it's
 * loaded.
 */
public final class RegionCompactor {

    private static final int SECTOR_BYTES = 4096;
    private static final int HEADER_BYTES = SECTOR_BYTES; // offsets table
    private static final int CHUNKS_PER_REGION = 1024; // 32 * 32

    private static final Pattern REGION_FILE_PATTERN =
            Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RegionCompactor.class);

    private RegionCompactor() {
    }

    /**
     * Compact every region file under a dimension's region folder,
     * keeping only chunks for which {@code keepPredicate} returns
     * true.
     *
     * @param regionDir     directory containing r.X.Z.mca files
     * @param keepPredicate returns true if the given chunk must be
     *                      kept (i.e. it has at least one recorded
     *                      WAM delta, ever)
     */
    public static CompactionStats compactDimension(
            Path regionDir,
            ChunkKeepPredicate keepPredicate
    ) throws IOException {

        CompactionStats total = new CompactionStats();

        if (!Files.isDirectory(regionDir)) {
            return total;
        }

        try (Stream<Path> files = Files.list(regionDir)) {

            for (Path file : files.toList()) {

                String name = file.getFileName().toString();
                Matcher matcher = REGION_FILE_PATTERN.matcher(name);

                if (!matcher.matches()) {
                    continue;
                }

                int regionX = Integer.parseInt(matcher.group(1));
                int regionZ = Integer.parseInt(matcher.group(2));

                try {

                    CompactionStats fileStats =
                            compactRegionFile(
                                    file,
                                    regionX,
                                    regionZ,
                                    keepPredicate
                            );

                    total.filesScanned++;
                    total.entriesKept += fileStats.entriesKept;
                    total.entriesStripped += fileStats.entriesStripped;
                    total.entriesAlreadyAbsent +=
                            fileStats.entriesAlreadyAbsent;

                } catch (IOException exception) {

                    LOGGER.warn(
                            "[DeltaChunk] Could not compact region {}. " +
                            "Leaving original MCA untouched.",
                            file,
                            exception
                    );
                }
            }
        }

        return total;
    }

    /**
     * Simple mutable counter bag returned to the caller so it can
     * log what actually happened, instead of this class silently
     * doing work with no visibility from the outside.
     */
    public static final class CompactionStats {

        public int filesScanned = 0;
        public int entriesKept = 0;
        public int entriesStripped = 0;
        public int entriesAlreadyAbsent = 0;

        @Override
        public String toString() {

            return "CompactionStats{filesScanned=" + filesScanned +
                    ", entriesKept=" + entriesKept +
                    ", entriesStripped=" + entriesStripped +
                    ", entriesAlreadyAbsent=" + entriesAlreadyAbsent +
                    "}";
        }
    }

    private static CompactionStats compactRegionFile(
            Path file,
            int regionX,
            int regionZ,
            ChunkKeepPredicate keepPredicate
    ) throws IOException {

        CompactionStats stats = new CompactionStats();

        byte[] newHeader = new byte[HEADER_BYTES];
        byte[] newTimestamps = new byte[HEADER_BYTES];
        ByteArrayOutputStream payloadStream =
                new ByteArrayOutputStream();
        boolean somethingChanged;

        /*
         * READ PHASE.
         *
         * Everything we need from the original file is read here.
         * This try-with-resources block (and therefore the file
         * handle / lock on the original file) is fully closed before
         * we touch the filesystem again below.
         *
         * IMPORTANT (Windows): you cannot rename/replace a file that
         * this process still has open, even via a separate
         * RandomAccessFile/FileChannel instance. Keeping the read
         * and the eventual replace in two separate scopes, with the
         * try-with-resources fully exited in between, is required.
         * DO NOT move the temp-file write or replace call back
         * inside this try block.
         */
        try (
                RandomAccessFile raf =
                        new RandomAccessFile(file.toFile(), "rw");
                FileChannel channel = raf.getChannel()
        ) {

            if (channel.size() < HEADER_BYTES * 2) {
                // Malformed / empty region file, nothing to do.
                return stats;
            }

            FileLock lock = channel.lock();

            try {

                byte[] header = new byte[HEADER_BYTES];
                byte[] timestamps = new byte[HEADER_BYTES];

                raf.seek(0);
                raf.readFully(header);
                raf.readFully(timestamps);

                byte[][] keptPayloads = new byte[CHUNKS_PER_REGION][];

                for (int index = 0; index < CHUNKS_PER_REGION; index++) {

                    int offsetEntry =
                            readInt24(header, index * 4);

                    int sectorCount = header[index * 4 + 3] & 0xFF;

                    if (offsetEntry == 0 && sectorCount == 0) {
                        stats.entriesAlreadyAbsent += 1;
                        continue;
                    }

                    int localX = index & 31;
                    int localZ = index >> 5;

                    int chunkX = (regionX << 5) + localX;
                    int chunkZ = (regionZ << 5) + localZ;

                    boolean keep =
                            keepPredicate.shouldKeep(chunkX, chunkZ);

                    if (!keep) {
                        stats.entriesStripped += 1;
                        continue;
                    }

                    stats.entriesKept += 1;

                    long payloadOffsetBytes =
                            (long) offsetEntry * SECTOR_BYTES;

                    if (payloadOffsetBytes + 4 > channel.size()) {
                        // Corrupt entry pointing past EOF: drop it
                        // rather than crash the whole compaction.
                        stats.entriesKept -= 1;
                        stats.entriesStripped += 1;
                        continue;
                    }

                    raf.seek(payloadOffsetBytes);

                    byte[] lengthBytes = new byte[4];
                    raf.readFully(lengthBytes);

                    int declaredLength =
                            ((lengthBytes[0] & 0xFF) << 24)
                            | ((lengthBytes[1] & 0xFF) << 16)
                            | ((lengthBytes[2] & 0xFF) << 8)
                            | (lengthBytes[3] & 0xFF);

                    if (
                            declaredLength <= 0
                            || payloadOffsetBytes + 4 + declaredLength
                                    > channel.size()
                    ) {
                        // Corrupt/garbage entry: drop it defensively
                        // instead of writing garbage into the new
                        // file.
                        stats.entriesKept -= 1;
                        stats.entriesStripped += 1;
                        continue;
                    }

                    byte[] fullRecord = new byte[4 + declaredLength];

                    System.arraycopy(
                            lengthBytes, 0,
                            fullRecord, 0,
                            4
                    );

                    raf.seek(payloadOffsetBytes + 4);
                    raf.readFully(
                            fullRecord,
                            4,
                            declaredLength
                    );

                    keptPayloads[index] = fullRecord;
                }

                somethingChanged =
                        stats.entriesKept > 0
                        || stats.entriesStripped > 0;

                if (!somethingChanged) {
                    // Nothing changed at all; avoid pointless
                    // rewrites on every shutdown for untouched
                    // regions.
                    return stats;
                }

                int nextSector = 2; // sectors 0-1 are header+timestamps

                for (int index = 0; index < CHUNKS_PER_REGION; index++) {

                    byte[] record = keptPayloads[index];

                    if (record == null) {
                        // Header/timestamp entries already zero by
                        // default in a freshly allocated byte[].
                        continue;
                    }

                    int sectorsNeeded =
                            (record.length + SECTOR_BYTES - 1)
                                    / SECTOR_BYTES;

                    if (sectorsNeeded == 0) {
                        sectorsNeeded = 1;
                    }

                    if (sectorsNeeded > 255) {
                        // Anvil format caps sectorCount at 1 byte.
                        // Extraordinarily rare; drop defensively
                        // rather than write a corrupt header entry.
                        stats.entriesKept -= 1;
                        stats.entriesStripped += 1;
                        continue;
                    }

                    newHeader[index * 4] =
                            (byte) ((nextSector >> 16) & 0xFF);
                    newHeader[index * 4 + 1] =
                            (byte) ((nextSector >> 8) & 0xFF);
                    newHeader[index * 4 + 2] =
                            (byte) (nextSector & 0xFF);
                    newHeader[index * 4 + 3] =
                            (byte) sectorsNeeded;

                    System.arraycopy(
                            timestamps, index * 4,
                            newTimestamps, index * 4,
                            4
                    );

                    payloadStream.write(record);

                    int paddedLength = sectorsNeeded * SECTOR_BYTES;
                    int padding = paddedLength - record.length;

                    if (padding > 0) {
                        payloadStream.write(new byte[padding]);
                    }

                    nextSector += sectorsNeeded;
                }

            } finally {
                lock.release();
            }

        }
        // <-- try-with-resources fully exited here: `raf` and
        //     `channel` are closed and the original file handle is
        //     released. It is now safe to replace the file on disk.

        WindowsSafeIO.writeAtomic(
                file,
                (OutputStream out) -> {
                    out.write(newHeader);
                    out.write(newTimestamps);
                    out.write(payloadStream.toByteArray());
                }
        );

        return stats;
    }

    private static int readInt24(byte[] data, int offset) {

        return ((data[offset] & 0xFF) << 16)
                | ((data[offset + 1] & 0xFF) << 8)
                | (data[offset + 2] & 0xFF);
    }

    @FunctionalInterface
    public interface ChunkKeepPredicate {

        boolean shouldKeep(int chunkX, int chunkZ);
    }
}
