package com.deltachunk;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory record of every block-level change made during the
 * current session, keyed by dimension.
 *
 * This is intentionally block-granular (BlockPos -> BlockDelta), NOT
 * chunk-granular. A chunk is considered "modified" for compaction
 * purposes if and only if it has at least one entry here. This
 * replaces the old coarse Set<String> "modified chunk" bookkeeping
 * in DeltaChunk with something that can actually answer "which
 * blocks", not just "which chunks".
 *
 * Loading previous sessions' WAM data into this structure is
 * deliberately NOT done at startup: WAM files on disk are the
 * authoritative long-term record, and are read directly by WamStore
 * on demand (per chunk, on generation) rather than being pulled
 * wholesale into memory. This index only accumulates NEW changes
 * made in the current session; at shutdown those new changes are
 * merged into the on-disk WAM files by WamStore.
 */
public final class DeltaIndex {

    /**
     * dimension id -> (block pos long -> delta)
     */
    private final Map<String, Map<Long, BlockDelta>> perDimension =
            new ConcurrentHashMap<>();

    /**
     * dimension id -> set of chunk keys (packed long) touched this
     * session. Kept alongside the block map purely as a fast lookup
     * for "does this chunk have ANY new changes this session",
     * avoiding an O(chunk block count) scan during compaction.
     */
    private final Map<String, Set<Long>> touchedChunks =
            new ConcurrentHashMap<>();

    public void record(
            String dimension,
            BlockPos pos,
            BlockDelta delta
    ) {

        Map<Long, BlockDelta> blocks =
                perDimension.computeIfAbsent(
                        dimension,
                        key -> new ConcurrentHashMap<>()
                );

        blocks.put(
                pos.asLong(),
                delta
        );

        Set<Long> chunks =
                touchedChunks.computeIfAbsent(
                        dimension,
                        key -> ConcurrentHashMap.newKeySet()
                );

        chunks.add(
                new ChunkPos(pos).toLong()
        );
    }

    /**
     * @return an immutable-ish snapshot map of everything recorded
     * this session for the given dimension. Never null (empty map if
     * nothing recorded).
     */
    public Map<Long, BlockDelta> snapshotForDimension(
            String dimension
    ) {

        Map<Long, BlockDelta> blocks =
                perDimension.get(dimension);

        if (blocks == null) {
            return Map.of();
        }

        return Map.copyOf(blocks);
    }

    public boolean hasAnyChangesThisSession(
            String dimension,
            ChunkPos pos
    ) {

        Set<Long> chunks =
                touchedChunks.get(dimension);

        if (chunks == null) {
            return false;
        }

        return chunks.contains(
                pos.toLong()
        );
    }

    public boolean isEmpty() {
        return perDimension.isEmpty();
    }
}
