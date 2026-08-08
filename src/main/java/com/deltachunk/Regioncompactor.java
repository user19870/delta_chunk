 package com.deltachunk;

import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Post-process compactor for Anvil (.mca) region files.
 *
 * This operates purely on the on-disk region file format and does
 * NOT go through any vanilla chunk serialization code. It is meant
 * to run AFTER the server has finished its normal save (i.e. on
 * ServerStoppingEvent), once every chunk that vanilla wants to write
 * has already been written normally.
 *
 * Region file layout (Anvil format):
 *   [0      .. 4096)   : 1024 entries, 4 bytes each -> (offset:3 bytes in 4KiB sectors, sectorCount:1 byte)
 *   [4096   .. 8192)   : 1024 timestamps, 4 bytes each
 *   [8192   .. EOF)    : chunk payloads, sector (4096 byte) aligned
 *
 * To mark a chunk as "not present" (equivalent to "never generated"
 * from the game's point of view), we zero out its 4-byte header
 * entry. We deliberately do NOT rewrite/shrink the payload area in
 * this first pass: the now-unreferenced sectors simply become dead
 * space. This keeps the implementation simple and safe (no risk of
 * corrupting offsets of chunks we decide to keep). A follow-up
 * "shrink" pass can later rewrite the file to reclaim that space.
 */
public final class RegionCompactor {

    private static final int SECTOR_BYTES = 4096;
    private static final int HEADER_BYTES = SECTOR_BYTES; // offsets table
    private static final int CHUNKS_PER_REGION = 1024; // 32 * 32

    private static final Pattern REGION_FILE_PATTERN =
            Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private RegionCompactor() {
    }

    /**
     * Compact every region file under a dimension's region folder,
     * keeping only chunks present in {@code keepPredicate}.
     *
     * @param regionDir     directory containing r.X.Z.mca files
     * @param keepPredicate returns true if the given chunk must be
     *                      kept (i.e. it was modified by the player)
     */
    public static void compactDimension(
            Path regionDir,
            ChunkKeepPredicate keepPredicate
    ) throws IOException {

        if (!Files.isDirectory(regionDir)) {
            return;
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

                compactRegionFile(
                        file,
                        regionX,
                        regionZ,
                        keepPredicate
                );
            }
        }
    }

    private static void compactRegionFile(
            Path file,
            int regionX,
            int regionZ,
            ChunkKeepPredicate keepPredicate
    ) throws IOException {

        try (
                RandomAccessFile raf =
                        new RandomAccessFile(file.toFile(), "rw");
                FileChannel channel = raf.getChannel()
        ) {

            if (channel.size() < HEADER_BYTES) {
                // Malformed / empty region file, nothing to do.
                return;
            }

            FileLock lock = channel.lock();

            try {

                byte[] header = new byte[HEADER_BYTES];

                raf.seek(0);
                raf.readFully(header);

                boolean changed = false;

                for (int index = 0; index < CHUNKS_PER_REGION; index++) {

                    int offsetEntry = readInt24(
                            header,
                            index * 4
                    );

                    int sectorCount = header[index * 4 + 3] & 0xFF;

                    if (offsetEntry == 0 && sectorCount == 0) {
                        // Already absent, nothing to strip.
                        continue;
                    }

                    int localX = index & 31;
                    int localZ = index >> 5;

                    int chunkX = (regionX << 5) + localX;
                    int chunkZ = (regionZ << 5) + localZ;

                    boolean keep =
                            keepPredicate.shouldKeep(
                                    chunkX,
                                    chunkZ
                            );

                    if (keep) {
                        continue;
                    }

                    // Zero the 4-byte header entry: offset=0, sectorCount=0.
                    // This is the documented "chunk not present" marker.
                    header[index * 4] = 0;
                    header[index * 4 + 1] = 0;
                    header[index * 4 + 2] = 0;
                    header[index * 4 + 3] = 0;

                    changed = true;
                }

                if (changed) {
                    raf.seek(0);
                    raf.write(header);
                }

            } finally {
                lock.release();
            }
        }
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

    public static boolean matches(ChunkPos pos, int chunkX, int chunkZ) {
        return pos.x == chunkX && pos.z == chunkZ;
    }
}
