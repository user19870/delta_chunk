package com.deltachunk;

import java.io.DataInputStream;
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
 * inspect a world's on-disk state directly:
 *
 *   - for each .mca file: how many chunk header entries are present
 *     vs zeroed/absent, and the raw file size
 *   - for the matching .wam file (if any) in the sibling wam/<dim>
 *     folder: how many block-level delta entries it holds
 *
 * This is a read-only inspection tool; it never modifies any file.
 * It exists to answer "is compaction actually doing anything, and
 * does the WAM data look sane" without needing to boot a full
 * server.
 *
 * Usage:
 *   javac DiagnosticTool.java WamStore.java BlockDelta.java ...
 *   java com.deltachunk.DiagnosticTool /path/to/saves/myworld overworld
 *
 * The second argument selects which dimension's wam subfolder to
 * cross-reference (must match the sanitized folder name WamStore
 * uses, e.g. "minecraft_overworld" for the overworld, or just
 * "overworld" -- pass whatever the actual folder name under
 * <world>/wam/ is; run once with no match to see what's there).
 */
public final class DiagnosticTool {

    private static final int SECTOR_BYTES = 4096;
    private static final int HEADER_BYTES = SECTOR_BYTES;
    private static final int CHUNKS_PER_REGION = 1024;

    private static final Pattern MCA_FILE_PATTERN =
            Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private static final Pattern WAM_FILE_PATTERN =
            Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.wam");

    public static void main(String[] args) throws IOException {

        if (args.length < 1) {

            System.err.println(
                    "Usage: java com.deltachunk.DiagnosticTool " +
                    "<world-dir> [dimension-folder-under-wam]"
            );

            System.err.println(
                    "  <world-dir> is the save folder itself (the one " +
                    "containing region/, DIM-1/, wam/, etc), not the " +
                    "region/ folder directly."
            );

            System.exit(1);

            return;
        }

        Path worldDir = Path.of(args[0]);

        if (!Files.isDirectory(worldDir)) {

            System.err.println("Not a directory: " + worldDir);
            System.exit(1);
            return;
        }

        String dimensionFolder =
                args.length >= 2 ? args[1] : null;

        System.out.println("=== .mca region files ===");
        System.out.println();

        inspectMca(worldDir.resolve("region"), "overworld");
        inspectMca(worldDir.resolve("DIM-1").resolve("region"), "the_nether");
        inspectMca(worldDir.resolve("DIM1").resolve("region"), "the_end");

        Path dimensionsDir = worldDir.resolve("dimensions");

        if (Files.isDirectory(dimensionsDir)) {

            try (Stream<Path> namespaces = Files.list(dimensionsDir)) {

                for (Path namespace : namespaces.toList()) {

                    if (!Files.isDirectory(namespace)) {
                        continue;
                    }

                    try (Stream<Path> dims = Files.list(namespace)) {

                        for (Path dim : dims.toList()) {

                            Path regionDir = dim.resolve("region");

                            String label =
                                    namespace.getFileName() + ":"
                                            + dim.getFileName();

                            inspectMca(regionDir, label);
                        }
                    }
                }
            }
        }

        System.out.println();
        System.out.println("=== .wam files ===");
        System.out.println();

        Path wamRoot = worldDir.resolve("wam");

        if (!Files.isDirectory(wamRoot)) {

            System.out.println(
                    "No wam/ folder found under " + worldDir +
                    " (no WAM data has been flushed yet)."
            );

        } else if (dimensionFolder != null) {

            inspectWam(wamRoot.resolve(dimensionFolder));

        } else {

            try (Stream<Path> dims = Files.list(wamRoot)) {

                for (Path dim : dims.toList()) {

                    if (!Files.isDirectory(dim)) {
                        continue;
                    }

                    System.out.println(
                            "-- " + dim.getFileName() + " --"
                    );

                    inspectWam(dim);
                }
            }
        }
    }

    private static void inspectMca(
            Path regionDir,
            String label
    ) throws IOException {

        if (!Files.isDirectory(regionDir)) {
            return;
        }

        long totalBytes = 0;
        int totalPresent = 0;
        int totalAbsent = 0;
        int fileCount = 0;

        try (Stream<Path> files = Files.list(regionDir)) {

            for (Path file : files.sorted().toList()) {

                String name = file.getFileName().toString();
                Matcher matcher = MCA_FILE_PATTERN.matcher(name);

                if (!matcher.matches()) {
                    continue;
                }

                fileCount++;

                long size = Files.size(file);
                totalBytes += size;

                int[] counts = countMcaEntries(file);

                totalPresent += counts[0];
                totalAbsent += counts[1];
            }
        }

        if (fileCount == 0) {
            return;
        }

        System.out.printf(
                "%-24s files=%-4d bytes=%-12d present=%-5d absent=%-5d%n",
                label,
                fileCount,
                totalBytes,
                totalPresent,
                totalAbsent
        );
    }

    private static int[] countMcaEntries(Path file) throws IOException {

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

    private static void inspectWam(Path dimDir) throws IOException {

        if (!Files.isDirectory(dimDir)) {

            System.out.println(
                    "  (no such folder: " + dimDir + ")"
            );

            return;
        }

        int fileCount = 0;
        long totalEntries = 0;
        long totalBytes = 0;

        try (Stream<Path> files = Files.list(dimDir)) {

            for (Path file : files.sorted().toList()) {

                String name = file.getFileName().toString();
                Matcher matcher = WAM_FILE_PATTERN.matcher(name);

                if (!matcher.matches()) {
                    continue;
                }

                fileCount++;

                long size = Files.size(file);
                totalBytes += size;

                int entries = countWamEntries(file);

                totalEntries += entries;

                System.out.printf(
                        "  %-20s bytes=%-10d blockEntries=%d%n",
                        name,
                        size,
                        entries
                );
            }
        }

        System.out.printf(
                "  (%d wam file(s), %d total bytes, %d total block " +
                "delta entries)%n",
                fileCount,
                totalBytes,
                totalEntries
        );
    }

    /**
     * Reads just the entry count header of a .wam file without
     * pulling in NBT parsing (this tool is meant to have zero
     * Minecraft/NeoForge dependency), so this only validates the
     * format version + count fields, not the full NBT payloads.
     */
    private static int countWamEntries(Path file) throws IOException {

        try (
                DataInputStream input =
                        new DataInputStream(
                                Files.newInputStream(file)
                        )
        ) {

            int version = input.readInt();

            if (version != 3) {

                System.out.println(
                        "    WARNING: unexpected format version " +
                        version + " in " + file.getFileName() +
                        " (this tool expects version 3)"
                );

                return -1;
            }

            return input.readInt();
        }
    }

    private DiagnosticTool() {
    }
}
