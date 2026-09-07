package com.deltachunk;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

/**
 * Applies recorded WAM block deltas onto a chunk AFTER the world
 * generator (vanilla or any other mod's generator) has fully
 * generated it.
 *
 * ORDERING RATIONALE:
 * The generator runs first and is allowed to produce whatever
 * terrain it wants, completely unaware that any WAM data exists.
 * Once that's done, every recorded delta for this chunk is applied
 * on top, unconditionally overwriting whatever the generator placed
 * at that position. This is what guarantees a player's chest,
 * machine, or build reappears exactly where they left it, regardless
 * of which world generator produced the surrounding terrain -- even
 * if that generator is a completely different one than was active
 * when the delta was recorded.
 *
 * CONFLICT HANDLING:
 * A "conflict" here means the generator placed something at a
 * recorded position that makes the overwrite look suspicious --
 * most notably lava, since a chest silently overwriting lava (or
 * lava overwriting a chest visually) is the kind of thing a player
 * would want to know happened. Per design, conflicts are logged but
 * never block the overwrite: the recorded delta always wins. This
 * mod is explicitly single-player-oriented and prioritizes "the
 * player's stuff is never silently lost or reverted" over "the
 * result always looks physically tidy". A logged conflict is a
 * signal for the player/pack author to go look, not a reason to
 * suppress the restoration.
 */
public final class DeltaReplay {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(DeltaReplay.class);

    private DeltaReplay() {
    }

    public static void apply(
            ServerLevel level,
            ChunkPos chunkPos,
            WamStore store,
            String dimension
    ) {

        Map<Long, BlockDelta> deltas;

        try {

            deltas =
                    store.loadChunkDeltas(dimension, chunkPos);

        } catch (IOException exception) {

            LOGGER.error(
                    "[DeltaChunk] Failed to load WAM deltas for " +
                    "chunk {} {} - this chunk will NOT have its " +
                    "player modifications restored this load. If " +
                    "this keeps happening, check that the .wam file " +
                    "for this region isn't corrupted.",
                    dimension,
                    chunkPos,
                    exception
            );

            return;
        }

        if (deltas.isEmpty()) {
            return;
        }

        int appliedCount = 0;
        int conflictCount = 0;
        int skippedUnknownBlockCount = 0;

        for (Map.Entry<Long, BlockDelta> entry : deltas.entrySet()) {

            BlockPos pos = BlockPos.of(entry.getKey());

            BlockDelta delta = entry.getValue();

            BlockState newState =
                    BlockDelta.parseState(
                            delta.blockStateString()
                    );

            if (newState == null) {

                /*
                 * The recorded block no longer exists in the active
                 * registry (e.g. a mod that added it was removed).
                 * Skip this single position rather than crash chunk
                 * generation for the whole chunk -- one unknown
                 * block should never take down the rest of a
                 * player's build.
                 */
                skippedUnknownBlockCount++;

                LOGGER.warn(
                        "[DeltaChunk] Could not restore block at {} " +
                        "in {} {}: recorded state '{}' does not " +
                        "resolve against the current block registry " +
                        "(mod removed/renamed?). Leaving generator's " +
                        "block in place at this position only.",
                        pos,
                        dimension,
                        chunkPos,
                        delta.blockStateString()
                );

                continue;
            }

            BlockState existingState =
                    level.getBlockState(pos);

            if (looksLikeConflict(existingState)) {

                conflictCount++;

                LOGGER.warn(
                        "[DeltaChunk] Conflict while restoring {} {}: " +
                        "generator placed '{}' at {} where a recorded " +
                        "player change ('{}') exists. Overwriting per " +
                        "policy (recorded changes always win).",
                        dimension,
                        chunkPos,
                        existingState,
                        pos,
                        delta.blockStateString()
                );
            }

            /*
             * Flags: update block + notify neighbors (standard
             * placement flags), but suppress the render/physics
             * update chain being triggered while generation
             * machinery may still be mid-flight for this chunk.
             * setBlock with flag 2 (send to clients, no neighbor
             * updates) mirrors what structure-piece placement code
             * in vanilla uses for exactly this "placing into a
             * chunk that's still settling" situation.
             */
            level.setBlock(pos, newState, 2);

            if (delta.hasBlockEntity()) {

                applyBlockEntity(level, pos, delta.blockEntity());

            } else {

                /*
                 * No block entity recorded for this position. If the
                 * generator's block (now overwritten) or the new
                 * block type would normally carry one, make sure no
                 * stale block entity lingers -- e.g. the recorded
                 * delta is "player broke the chest, block is now
                 * air", and air must not keep a chest's block
                 * entity attached underneath it.
                 */
                level.removeBlockEntity(pos);
            }

            appliedCount++;
        }

        LOGGER.debug(
                "[DeltaChunk] Restored {} {}: applied={} conflicts={} " +
                "skippedUnknownBlocks={}",
                dimension,
                chunkPos,
                appliedCount,
                conflictCount,
                skippedUnknownBlockCount
        );
    }

    /**
     * Deliberately narrow: only flags cases that are actively
     * destructive or confusing to overwrite silently (lava being the
     * main one -- a chest "eating" a lava source block is the kind
     * of thing worth a log line). This is NOT meant to catch every
     * possible mismatch; per the design, we always overwrite
     * regardless, so this only controls whether a warning is logged.
     */
    private static boolean looksLikeConflict(BlockState existingState) {

        return existingState.getFluidState().is(
                net.minecraft.tags.FluidTags.LAVA
        );
    }

    private static void applyBlockEntity(
            ServerLevel level,
            BlockPos pos,
            CompoundTag blockEntityTag
    ) {

        BlockEntity blockEntity =
                level.getBlockEntity(pos);

        if (blockEntity == null) {

            /*
             * setBlock() above should have created a fresh block
             * entity of the right type already if the new BlockState
             * is one that has a block entity (chests, signs, etc).
             * If it's still null here, the state we just placed
             * doesn't actually have a block entity type associated
             * with it -- most likely the recorded delta is stale
             * relative to the current block (e.g. the mod that owned
             * this block entity changed which states carry one).
             * Nothing sensible to attach the NBT to; log and move on
             * rather than throwing.
             */
            LOGGER.warn(
                    "[DeltaChunk] Recorded block entity NBT at {} has " +
                    "no matching block entity after placing the " +
                    "recorded state; the NBT will be dropped for this " +
                    "position.",
                    pos
            );

            return;
        }

        try {

            CompoundTag toLoad = blockEntityTag.copy();

            /*
             * Force the position fields in the saved NBT to match
             * where we're actually placing it. This matters because
             * the same delta data is meant to be replay-safe even if
             * internal loading pipelines expect x/y/z to be
             * self-consistent with the tag; keeping stale coordinates
             * from a previous session would be a subtle source of
             * "block entity loaded but acts like it's somewhere
             * else" bugs.
             */
            toLoad.putInt("x", pos.getX());
            toLoad.putInt("y", pos.getY());
            toLoad.putInt("z", pos.getZ());

            blockEntity.loadWithComponents(
                    toLoad,
                    level.registryAccess()
            );

            blockEntity.setChanged();

        } catch (Exception exception) {

            LOGGER.error(
                    "[DeltaChunk] Failed to load recorded block " +
                    "entity NBT at {}. The block was placed correctly " +
                    "but its contents (inventory, text, etc) could " +
                    "not be restored.",
                    pos,
                    exception
            );
        }
    }
}
