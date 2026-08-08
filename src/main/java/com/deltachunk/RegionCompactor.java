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
 * The new file then atomically replaces the original.
 *
 * This is a real compaction pass, not a "mark absent, deal with it
 * later" pass: dropped chunks' bytes are actually removed from disk,
 * which is what makes the .mca file shrink. An earlier version of
 * this class only zeroed header entries and left the old payload
 * bytes as dead space; that version made removed chunks regenerate
 * correctly but did NOT reduce file size, which is why file size
 * appeared unchanged after compaction until this rewrite was added.
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

                CompactionStats fileStats =
                        compactRegionFile(
                                file,
                                regionX,
                                regionZ,
                                keepPredicate
                        );

                total.filesScanned += 1;
                total.entriesKept += fileStats.entriesKept;
                total.entriesStripped += fileStats.entriesStripped;
                total.entriesAlreadyAbsent += fileStats.entriesAlreadyAbsent;
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

                /*
                 * Decide, per slot, whether to keep it, and if so,
                 * read its actual payload bytes NOW (before we start
                 * writing anything), since we're about to build an
                 * entirely new file layout.
                 *
                 * A chunk's payload on disk is:
                 *   [4 bytes length][1 byte compression type][length-1 bytes data]
                 * starting at sector offsetEntry, spanning sectorCount
                 * sectors (sectorCount is only an upper bound / disk
                 * allocation, the real length is the leading 4-byte
                 * field).
                 */
                byte[][] keptPayloads = new byte[CHUNKS_PER_REGION][];

                for (int index = 0; index < CHUNKS_PER_REGION; index++) {

                    int offsetEntry = readInt24(
                            header,
                            index * 4
                    );

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
                            keepPredicate.shouldKeep(
                                    chunkX,
                                    chunkZ
                            );

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
                            || payloadOffsetBytes + 4 + declaredLength > channel.size()
                    ) {
                        // Corrupt/garbage entry: drop it defensively
                        // instead of writing garbage into the new file.
                        stats.entriesKept -= 1;
                        stats.entriesStripped += 1;
                        continue;
                    }

                    // Full on-disk record for this chunk = 4 length
                    // bytes + declaredLength bytes (which itself
                    // starts with the 1-byte compression type).
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

                boolean nothingKept = stats.entriesKept == 0;

                if (nothingKept && stats.entriesStripped == 0) {
                    // Nothing changed at all, no need to touch the
                    // file further (avoids pointless rewrites on
                    // every single shutdown for untouched regions).
                    return stats;
                }

                /*
                 * Build the new file layout in memory:
                 *   header (4096) + timestamps (4096) + payloads,
                 * sector-aligned, in slot order. Slot order (rather
                 * than original disk order) keeps this simple and
                 * deterministic; region files don't require payloads
                 * to be in any particular order.
                 */
                byte[] newHeader = new byte[HEADER_BYTES];
                byte[] newTimestamps = new byte[HEADER_BYTES];

                java.io.ByteArrayOutputStream payloadStream =
                        new java.io.ByteArrayOutputStream();

                int nextSector = 2; // sectors 0-1 are header+timestamps

                for (int index = 0; index < CHUNKS_PER_REGION; index++) {

                    byte[] record = keptPayloads[index];

                    if (record == null) {
                        // Header/timestamp entries already zero by
                        // default in a freshly allocated byte[].
                        continue;
                    }

                    int sectorsNeeded =
                            (record.length + SECTOR_BYTES - 1) / SECTOR_BYTES;

                    if (sectorsNeeded == 0) {
                        sectorsNeeded = 1;
                    }

                    if (sectorsNeeded > 255) {
                        // Anvil format caps sectorCount at 1 byte.
                        // This should be extraordinarily rare; drop
                        // defensively rather than write a corrupt
                        // header entry.
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

                    // Preserve the original timestamp for this slot.
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

                Path temporary =
                        file.resolveSibling(
                                file.getFileName() + ".compact.tmp"
                        );

                try (
                        java.io.OutputStream out =
                                java.nio.file.Files.newOutputStream(
                                        temporary,
                                        java.nio.file.StandardOpenOption.CREATE,
                                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
                                )
                ) {
                    out.write(newHeader);
                    out.write(newTimestamps);
                    out.write(payloadStream.toByteArray());
                }

                try {
                    java.nio.file.Files.move(
                            temporary,
                            file,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE
                    );
                } catch (
                        java.nio.file.AtomicMoveNotSupportedException exception
                ) {
                    java.nio.file.Files.move(
                            temporary,
                            file,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    );
                }

            } finally {
                lock.release();
            }

            return stats;
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
