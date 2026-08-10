package com.deltachunk;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * On-disk store for block-level "what already modified" (WAM)
 * records.
 *
 * Layout: one .wam file per region, mirroring the .mca naming
 * scheme, under <world>/wam/<dimension>/r.X.Z.wam. Each file is a
 * flat map of (absolute block position, long-packed) -> BlockDelta,
 * covering every block ever recorded as modified inside that
 * region's 32x32 chunk area, across every session.
 *
 * This is a genuine delta store, not a chunk-snapshot store: a
 * single player-placed torch costs one entry, not a whole chunk's
 * worth of NBT.
 *
 * WRITE TIMING:
 * WamStore itself has no opinion about WHEN it is written. Historic
 * versions of this mod wrote WAM data continuously, on every single
 * chunk save event -- including chunk saves that happen constantly
 * during normal play (autosave ticks, chunk unload as the player
 * walks away, etc). That meant frequent small file rewrites while
 * the player was actively playing, which is exactly the situation
 * where Windows file locking (antivirus scanning the file, Explorer
 * holding a read handle, a backup tool mid-scan) is most likely to
 * turn a rename/replace into an AccessDeniedException.
 *
 * This version is written to be called EXACTLY ONCE per dimension,
 * right before the world is torn down (see DeltaChunk's
 * LevelEvent.Unload handling), flushing the ENTIRE session's worth
 * of new block deltas for that dimension in one pass per affected
 * region file. This does not eliminate the possibility of a Windows
 * file-replace failure, but it reduces the number of opportunities
 * for one to occur from "every autosave, forever" to "once per
 * dimension per session", and each occurrence now happens at a
 * moment when the player is already leaving, not mid-play.
 */
public final class WamStore {

    private static final int FORMAT_VERSION = 3;

    private final Path root;

    /**
     * Per-region-file lock objects, so concurrent flushes to
     * DIFFERENT region files never block each other, but two threads
     * racing to flush the SAME region file are serialized instead of
     * corrupting each other's read-modify-write.
     */
    private final Map<Path, Object> fileLocks =
            new ConcurrentHashMap<>();

    public WamStore(MinecraftServer server) {

        this.root =
                server.getWorldPath(LevelResource.ROOT)
                        .resolve("wam");
    }

    /**
     * Merge a batch of new block deltas (all belonging to the same
     * region file) into that region's .wam file on disk.
     *
     * This performs a read-modify-write: existing entries for other
     * positions in the same region are preserved untouched; entries
     * for positions present in {@code newDeltas} are overwritten
     * (last write wins, which is correct since newDeltas represents
     * the final state at end of session).
     */
    public void mergeRegion(
            String dimension,
            int regionX,
            int regionZ,
            Map<Long, BlockDelta> newDeltas
    ) throws IOException {

        if (newDeltas.isEmpty()) {
            return;
        }

        Path file =
                regionFile(dimension, regionX, regionZ);

        Object lock =
                fileLocks.computeIfAbsent(
                        file,
                        key -> new Object()
                );

        synchronized (lock) {

            Files.createDirectories(file.getParent());

            Map<Long, BlockDelta> existing =
                    Files.exists(file)
                            ? read(file)
                            : new HashMap<>();

            existing.putAll(newDeltas);

            WindowsSafeIO.writeAtomic(
                    file,
                    (WindowsSafeIO.DataStreamWriter)
                            out -> write(out, existing)
            );
        }
    }

     public void removeRegion(
            String dimension,
            int regionX,
            int regionZ,
            java.util.Set<Long> positionsToRemove
    ) throws IOException {
 
        if (positionsToRemove.isEmpty()) {
            return;
        }
 
        Path file =
                regionFile(dimension, regionX, regionZ);
 
        Object lock =
                fileLocks.computeIfAbsent(
                        file,
                        key -> new Object()
                );
 
        synchronized (lock) {
 
            if (!Files.exists(file)) {
                return;
            }
 
            Map<Long, BlockDelta> existing = read(file);
 
            boolean anyRemoved = false;
 
            for (Long pos : positionsToRemove) {
 
                if (existing.remove(pos) != null) {
                    anyRemoved = true;
                }
            }
 
            if (!anyRemoved) {
                return;
            }
 
            if (existing.isEmpty()) {
 
                Files.deleteIfExists(file);
 
                return;
            }
 
            WindowsSafeIO.writeAtomic(
                    file,
                    (WindowsSafeIO.DataStreamWriter)
                            out -> write(out, existing)
            );
        }
    }

    /**
     * Load every recorded delta for a single chunk. Never returns
     * null; returns an empty map if the region file doesn't exist or
     * has no entries for this chunk.
     */
    public Map<Long, BlockDelta> loadChunkDeltas(
            String dimension,
            ChunkPos pos
    ) throws IOException {

        Path file =
                regionFile(
                        dimension,
                        pos.getRegionX(),
                        pos.getRegionZ()
                );

        if (!Files.exists(file)) {
            return Map.of();
        }

        Object lock =
                fileLocks.computeIfAbsent(
                        file,
                        key -> new Object()
                );

        Map<Long, BlockDelta> all;

        synchronized (lock) {
            all = read(file);
        }

        if (all.isEmpty()) {
            return Map.of();
        }

        Map<Long, BlockDelta> result = new HashMap<>();

        long minX = (long) pos.getMinBlockX();
        long maxX = (long) pos.getMaxBlockX();
        long minZ = (long) pos.getMinBlockZ();
        long maxZ = (long) pos.getMaxBlockZ();

        for (Map.Entry<Long, BlockDelta> entry : all.entrySet()) {

            BlockPos blockPos =
                    BlockPos.of(entry.getKey());

            if (
                    blockPos.getX() >= minX
                    && blockPos.getX() <= maxX
                    && blockPos.getZ() >= minZ
                    && blockPos.getZ() <= maxZ
            ) {

                result.put(
                        entry.getKey(),
                        entry.getValue()
                );
            }
        }

        return result;
    }

    /**
     * @return true if this chunk has at least one recorded delta
     * ANYWHERE on disk (previous sessions included). Used by the
     * region compactor to decide whether a chunk's vanilla payload
     * must be kept.
     */
    public boolean hasAnyDeltaInChunk(
            String dimension,
            ChunkPos pos
    ) throws IOException {

        return !loadChunkDeltas(dimension, pos).isEmpty();
    }

    private Path regionFile(
            String dimension,
            int regionX,
            int regionZ
    ) {

        String safeDimension =
                dimension
                        .replace(':', '_')
                        .replace('/', '_')
                        .replace('\\', '_');

        return root
                .resolve(safeDimension)
                .resolve(
                        "r." + regionX + "." + regionZ + ".wam"
                );
    }

    /*
     * File format:
     *   int    formatVersion
     *   int    entryCount
     *   repeated entryCount times:
     *     long   packed BlockPos
     *     string blockStateString (NBT-style modified UTF-8, via
     *            DataOutputStream.writeUTF/readUTF)
     *     byte   hasBlockEntity (0 or 1)
     *     [if hasBlockEntity] NBT compound (NbtIo)
     */
    private static Map<Long, BlockDelta> read(
            Path file
    ) throws IOException {

        Map<Long, BlockDelta> result = new HashMap<>();

        try (
                DataInputStream input =
                        new DataInputStream(
                                new BufferedInputStream(
                                        Files.newInputStream(file)
                                )
                        )
        ) {

            int version = input.readInt();

            if (version != FORMAT_VERSION) {
                throw new IOException(
                        "Unsupported WAM format version: " + version
                );
            }

            int count = input.readInt();

            if (count < 0) {
                throw new IOException(
                        "Invalid WAM entry count: " + count
                );
            }

            for (int i = 0; i < count; i++) {

                long posKey = input.readLong();

                String stateString = input.readUTF();

                boolean hasBlockEntity =
                        input.readByte() != 0;

                CompoundTag blockEntity = null;

                if (hasBlockEntity) {

                    blockEntity =
                            NbtIo.read(
                                    input,
                                    NbtAccounter.unlimitedHeap()
                            );
                }

                result.put(
                        posKey,
                        new BlockDelta(
                                stateString,
                                blockEntity
                        )
                );
            }
        }

        return result;
    }

    private static void write(
            DataOutputStream output,
            Map<Long, BlockDelta> deltas
    ) throws IOException {

        output.writeInt(FORMAT_VERSION);

        output.writeInt(deltas.size());

        for (Map.Entry<Long, BlockDelta> entry : deltas.entrySet()) {

            output.writeLong(entry.getKey());

            output.writeUTF(
                    entry.getValue().blockStateString()
            );

            CompoundTag blockEntity =
                    entry.getValue().blockEntity();

            if (blockEntity == null) {

                output.writeByte(0);

            } else {

                output.writeByte(1);

                NbtIo.write(blockEntity, output);
            }
        }
    }

}
