package com.deltachunk;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Standalone diagnostic tool (no NeoForge/Minecraft dependency) to
 * inspect a .mca region file's header directly and report:
 *   - how many chunk entries are present vs "zeroed" (absent)
 *   - the raw file size
 *
 * This exists purely to answer the question "did the header actually
 * get zeroed?" without needing to boot the full mod / server. Run it
 * against a saved world's region folder.
 *
 * Usage:
 *   javac DiagnosticTool.java
 *   java com.deltachunk.DiagnosticTool /path/to/saves/newworld/region
 */
public final class DiagnosticTool {

    private static final int SECTOR_BYTES = 4096;
    private static final int HEADER_BYTES = SECTOR_BYTES;
    private static final int CHUNKS_PER_REGION = 1024;

    private static final Pattern REGION_FILE_PATTERN =
            Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    public static void main(String[] args) throws IOException {

        if (args.length < 1) {
            System.err.println(
                    "Usage: java com.deltachunk.DiagnosticTool <region-dir>"
            );
            System.exit(1);
            return;
        }

        Path regionDir = Path.of(args[0]);

        if (!Files.isDirectory(regionDir)) {
            System.err.println(
                    "Not a directory: " + regionDir
            );
            System.exit(1);
            return;
        }

        long totalBytes = 0;
        int totalPresent = 0;
        int totalAbsent = 0;
        int fileCount = 0;

        try (Stream<Path> files = Files.list(regionDir)) {

            for (Path file : files.sorted().toList()) {

                String name = file.getFileName().toString();
                Matcher matcher = REGION_FILE_PATTERN.matcher(name);

                if (!matcher.matches()) {
                    continue;
                }

                fileCount++;

                long size = Files.size(file);
                totalBytes += size;

                int[] counts = countEntries(file);

                totalPresent += counts[0];
                totalAbsent += counts[1];

                System.out.printf(
                        "%-24s size=%10d bytes  present=%4d  absent(zeroed)=%4d%n",
                        name,
                        size,
                        counts[0],
                        counts[1]
                );
            }
        }

        System.out.println();
        System.out.println("=== Summary ===");
        System.out.println("Region files scanned : " + fileCount);
        System.out.println("Total bytes on disk  : " + totalBytes);
        System.out.println("Chunk entries present: " + totalPresent);
        System.out.println("Chunk entries absent  (zeroed header, dead space still on disk): " + totalAbsent);
        System.out.println();
        System.out.println(
                "NOTE: 'absent' chunks still occupy their old sectors on " +
                "disk in this version (header-only compaction). Total " +
                "bytes will NOT shrink until a rewrite/compaction pass " +
                "is added. This tool only tells you whether the header " +
                "zeroing itself is actually happening."
        );
    }

    /**
     * @return {presentCount, absentCount}
     */
    private static int[] countEntries(Path file) throws IOException {

        int present = 0;
        int absent = 0;

        try (
                RandomAccessFile raf =
                        new RandomAccessFile(file.toFile(), "r");
                FileChannel channel = raf.getChannel()
        ) {

            if (channel.size() < HEADER_BYTES) {
                return new int[] { 0, 0 };
            }

            byte[] header = new byte[HEADER_BYTES];

            raf.seek(0);
            raf.readFully(header);

            for (int index = 0; index < CHUNKS_PER_REGION; index++) {

                int offsetEntry =
                        ((header[index * 4] & 0xFF) << 16)
                        | ((header[index * 4 + 1] & 0xFF) << 8)
                        | (header[index * 4 + 2] & 0xFF);

                int sectorCount = header[index * 4 + 3] & 0xFF;

                if (offsetEntry == 0 && sectorCount == 0) {
                    absent++;
                } else {
                    present++;
                }
            }
        }

        return new int[] { present, absent };
    }

    private DiagnosticTool() {
    }
}
