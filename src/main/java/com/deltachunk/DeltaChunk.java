package com.deltachunk;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Mod(DeltaChunk.MOD_ID)
public final class DeltaChunk {

    public static final String MOD_ID =
            "deltachunk";

    public static final Logger LOGGER =
            LogUtils.getLogger();

    private static final Map<
            MinecraftServer,
            WamStore
            > STORES =
            new ConcurrentHashMap<>();

    /*
     * server
     *   -> dimension id
     *       -> modified chunk keys
     */
    private static final Map<
            MinecraftServer,
            Map<String, Set<Long>>
            > MODIFIED =
            new ConcurrentHashMap<>();

    /*
     * Dimension paths are remembered before Minecraft closes
     * its normal chunk IO.
     */
    private static final Map<
            MinecraftServer,
            Map<String, DimensionInfo>
            > DIMENSIONS =
            new ConcurrentHashMap<>();

    public DeltaChunk(
            IEventBus modEventBus
    ) {

        NeoForge.EVENT_BUS.addListener(
                this::onServerAboutToStart
        );

        NeoForge.EVENT_BUS.addListener(
                this::onServerStopping
        );

        NeoForge.EVENT_BUS.addListener(
                this::onServerStopped
        );

        NeoForge.EVENT_BUS.addListener(
                this::onBlockBreak
        );

        NeoForge.EVENT_BUS.addListener(
                this::onBlockPlace
        );

        NeoForge.EVENT_BUS.addListener(
                this::onBlockToolModification
        );

        NeoForge.EVENT_BUS.addListener(
                this::onFluidBlock
        );

        NeoForge.EVENT_BUS.addListener(
                this::onExplosion
        );

        NeoForge.EVENT_BUS.addListener(
                this::onPiston
        );

        NeoForge.EVENT_BUS.addListener(
                this::onPlayerInteract
        );

        NeoForge.EVENT_BUS.addListener(
                this::onChunkSave
        );
    }

    private void onServerAboutToStart(
            ServerAboutToStartEvent event
    ) {

        MinecraftServer server =
                event.getServer();

        STORES.put(
                server,
                new WamStore(server)
        );

        MODIFIED.put(
                server,
                new ConcurrentHashMap<>()
        );

        DIMENSIONS.put(
                server,
                new ConcurrentHashMap<>()
        );

        LOGGER.info(
                "[DeltaChunk] WAM store initialized for {}",
                server.getWorldPath(
                        net.minecraft.world.level.storage.LevelResource.ROOT
                )
        );
    }

    private void onServerStopping(
            ServerStoppingEvent event
    ) {

        MinecraftServer server =
                event.getServer();

        /*
         * DeltaChunk is intentionally single-player only.
         *
         * Dedicated/multiplayer servers are never compacted.
         */
        if (!server.isSingleplayer()) {

            LOGGER.warn(
                    "[DeltaChunk] Multiplayer server detected. "
                            + "MCA compaction is disabled."
            );

            return;
        }

        /*
         * Force one final synchronous save.
         *
         * This guarantees that the final ChunkDataEvent.Save
         * events have happened before compaction.
         */
        try {

            LOGGER.info(
                    "[DeltaChunk] Performing final chunk save..."
            );

            server.saveAllChunks(
                    true,
                    true,
                    true
            );

        } catch (Throwable throwable) {

            LOGGER.error(
                    "[DeltaChunk] Final chunk save failed. "
                            + "MCA compaction will be skipped.",
                    throwable
            );

            return;
        }

        /*
         * Remember all dimension paths while ServerLevel objects
         * are still available.
         */
        Map<String, DimensionInfo> dimensions =
                DIMENSIONS.get(server);

        if (dimensions != null) {

            for (
                    ServerLevel level :
                    server.getAllLevels()
            ) {

                String id =
                        level.dimension()
                                .location()
                                .toString();

                dimensions.put(
                        id,
                        new DimensionInfo(
                                id,
                                level.dimension(),
                                getDimensionRoot(
                                        server,
                                        level
                                )
                        )
                );
            }
        }

        LOGGER.info(
                "[DeltaChunk] Final save complete. "
                        + "MCA compaction will run after server IO closes."
        );
    }

    private void onServerStopped(
            ServerStoppedEvent event
    ) {

        MinecraftServer server =
                event.getServer();

        /*
         * ServerStopped is deliberately used here.
         *
         * Minecraft has already closed its normal chunk storage,
         * so DeltaChunk can safely open the MCA files itself.
         */
        if (server.isSingleplayer()) {

            compactServer(
                    server
            );
        }

        STORES.remove(
                server
        );

        MODIFIED.remove(
                server
        );

        DIMENSIONS.remove(
                server
        );

        LOGGER.info(
                "[DeltaChunk] Server stopped."
        );
    }

    private void compactServer(
            MinecraftServer server
    ) {

        WamStore store =
                STORES.get(server);

        Map<String, Set<Long>> modified =
                MODIFIED.get(server);

        Map<String, DimensionInfo> dimensions =
                DIMENSIONS.get(server);

        if (
                store == null ||
                modified == null ||
                dimensions == null
        ) {
            return;
        }

        for (
                Map.Entry<String, Set<Long>> entry :
                modified.entrySet()
        ) {

            String dimensionId =
                    entry.getKey();

            Set<Long> chunks =
                    entry.getValue();

            if (chunks.isEmpty()) {
                continue;
            }

            DimensionInfo dimension =
                    dimensions.get(
                            dimensionId
                    );

            if (dimension == null) {

                LOGGER.warn(
                        "[DeltaChunk] No path known for dimension {}. "
                                + "Skipping compaction.",
                        dimensionId
                );

                continue;
            }

            try {

                LOGGER.info(
                        "[DeltaChunk] Compacting dimension {} "
                                + "with {} modified chunks...",
                        dimensionId,
                        chunks.size()
                );

                McaCompactor.compactDimension(
                        dimension.root(),
                        dimension.key(),
                        dimension.id(),
                        chunks,
                        store
                );

            } catch (Throwable throwable) {

                /*
                 * VERY IMPORTANT:
                 *
                 * A failed region compaction must not prevent
                 * Minecraft from shutting down.
                 *
                 * The original MCA is left intact by the
                 * transactional compactor.
                 */
                LOGGER.error(
                        "[DeltaChunk] Failed to compact dimension {}. "
                                + "Original MCA data was preserved where possible.",
                        dimensionId,
                        throwable
                );
            }
        }
    }

    private void onChunkSave(
            ChunkDataEvent.Save event
    ) {

        ChunkAccess chunk =
                event.getChunk();

        MinecraftServer server =
                findServer(chunk);

        if (server == null) {
            return;
        }

        String dimension =
                getDimensionId(chunk);

        Set<Long> modified =
                getModifiedSet(
                        server,
                        dimension
                );

        if (modified == null) {
            return;
        }

        long chunkKey =
                chunk.getPos().toLong();

        if (!modified.contains(chunkKey)) {
            return;
        }

        WamStore store =
                STORES.get(server);

        if (store == null) {
            return;
        }

        try {

            store.saveChunk(
                    dimension,
                    chunk.getPos(),
                    event.getData()
            );

        } catch (Exception exception) {

            LOGGER.error(
                    "[DeltaChunk] Failed to save WAM chunk {}",
                    chunk.getPos(),
                    exception
            );
        }
    }

    private void onBlockBreak(
            BlockEvent.BreakEvent event
    ) {

        mark(
                event.getLevel(),
                event.getPos()
        );
    }

    private void onBlockPlace(
            BlockEvent.EntityPlaceEvent event
    ) {

        mark(
                event.getLevel(),
                event.getPos()
        );

        if (
                event
                        instanceof BlockEvent.EntityMultiPlaceEvent multi
        ) {

            multi.getReplacedBlockSnapshots()
                    .forEach(
                            snapshot ->
                                    mark(
                                            event.getLevel(),
                                            snapshot.getPos()
                                    )
                    );
        }
    }

    private void onBlockToolModification(
            BlockEvent.BlockToolModificationEvent event
    ) {

        mark(
                event.getLevel(),
                event.getPos()
        );
    }

    private void onFluidBlock(
            BlockEvent.FluidPlaceBlockEvent event
    ) {

        mark(
                event.getLevel(),
                event.getPos()
        );
    }

    private void onExplosion(
            ExplosionEvent.Detonate event
    ) {

        if (
                !(event.getLevel()
                        instanceof ServerLevel level)
        ) {
            return;
        }

        event.getAffectedBlocks()
                .forEach(
                        pos ->
                                mark(
                                        level,
                                        pos
                                )
                );
    }

    private void onPiston(
            PistonEvent.Post event
    ) {

        if (
                !(event.getLevel()
                        instanceof ServerLevel level)
        ) {
            return;
        }

        BlockPos pos =
                event.getPos();

        mark(
                level,
                pos
        );

        /*
         * Piston movement can affect several blocks around
         * the piston. The surrounding 5x5x5 area is conservatively
         * marked.
         */
        for (
                BlockPos blockPos :
                BlockPos.betweenClosed(
                        pos.offset(
                                -2,
                                -2,
                                -2
                        ),
                        pos.offset(
                                2,
                                2,
                                2
                        )
                )
        ) {

            mark(
                    level,
                    blockPos
            );
        }
    }

    private void onPlayerInteract(
            PlayerInteractEvent.RightClickBlock event
    ) {

        if (
                event.getLevel()
                        .isClientSide()
        ) {
            return;
        }

        /*
         * This is intentionally conservative.
         *
         * Opening/interacting with a chest, machine, terminal,
         * modded block entity, etc. can change BlockEntity data
         * without a BlockPlace/BlockBreak event.
         *
         * Therefore the containing chunk is marked.
         */
        mark(
                event.getLevel(),
                event.getPos()
        );
    }

    private void mark(
            LevelAccessor level,
            BlockPos pos
    ) {

        if (
                !(level instanceof ServerLevel serverLevel)
        ) {
            return;
        }

        MinecraftServer server =
                serverLevel.getServer();

        String dimension =
                serverLevel.dimension()
                        .location()
                        .toString();

        Set<Long> modified =
                getModifiedSet(
                        server,
                        dimension
                );

        if (modified == null) {
            return;
        }

        long chunkKey =
                new ChunkPos(pos)
                        .toLong();

        modified.add(
                chunkKey
        );
    }

    private Set<Long> getModifiedSet(
            MinecraftServer server,
            String dimension
    ) {

        Map<String, Set<Long>> dimensions =
                MODIFIED.get(server);

        if (dimensions == null) {
            return null;
        }

        return dimensions.computeIfAbsent(
                dimension,
                ignored ->
                        ConcurrentHashMap.newKeySet()
        );
    }

    private static MinecraftServer findServer(
            ChunkAccess chunk
    ) {

        if (
                chunk instanceof LevelChunk levelChunk
        ) {

            Level level =
                    levelChunk.getLevel();

            if (
                    level instanceof ServerLevel serverLevel
            ) {

                return serverLevel.getServer();
            }
        }

        return null;
    }

    private static String getDimensionId(
            ChunkAccess chunk
    ) {

        if (
                chunk instanceof LevelChunk levelChunk
        ) {

            Level level =
                    levelChunk.getLevel();

            if (
                    level instanceof ServerLevel serverLevel
            ) {

                return serverLevel
                        .dimension()
                        .location()
                        .toString();
            }
        }

        return Level.OVERWORLD
                .location()
                .toString();
    }

    private static Path getDimensionRoot(
            MinecraftServer server,
            ServerLevel level
    ) {

        Path root =
                server.getWorldPath(
                        net.minecraft.world.level.storage.LevelResource.ROOT
                );

        if (
                level.dimension()
                        .equals(Level.OVERWORLD)
        ) {

            return root;
        }

        if (
                level.dimension()
                        .equals(Level.NETHER)
        ) {

            return root.resolve(
                    "DIM-1"
            );
        }

        if (
                level.dimension()
                        .equals(Level.END)
        ) {

            return root.resolve(
                    "DIM1"
            );
        }

        /*
         * Vanilla custom-dimension layout:
         *
         * world/
         *   dimensions/
         *     namespace/
         *       path/
         */
        return root
                .resolve("dimensions")
                .resolve(
                        level.dimension()
                                .location()
                                .getNamespace()
                )
                .resolve(
                        level.dimension()
                                .location()
                                .getPath()
                );
    }

    private record DimensionInfo(
            String id,
            net.minecraft.resources.ResourceKey<Level> key,
            Path root
    ) {
    }
}
