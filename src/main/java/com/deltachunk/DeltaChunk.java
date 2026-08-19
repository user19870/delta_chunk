package com.deltachunk;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DeltaChunk: single-player-oriented world-size reduction.
 *
 * DESIGN SUMMARY (see project discussion for full rationale):
 *
 *   1. Every block change the player is responsible for (break,
 *      place, tool interaction, fluid placement, explosion, piston
 *      push/pull, container/interact-driven changes) is recorded at
 *      BLOCK granularity, not chunk granularity, into an in-memory
 *      DeltaIndex as it happens.
 *
 *   2. Vanilla (or whatever generator/mods are installed) continues
 *      to save .mca files completely normally. This mod never
 *      intercepts or rewrites chunk serialization itself.
 *
 *   3. Right before a dimension is fully unloaded (LevelEvent.Unload,
 *      which -- for a single-player world going back to the title
 *      screen or a server stopping -- fires after that dimension's
 *      own chunks have already been saved to disk by vanilla), this
 *      mod:
 *        a. flushes this session's new DeltaIndex entries into the
 *           per-region .wam files (WamStore.mergeRegion), and
 *        b. compacts that dimension's .mca region files, stripping
 *           out any chunk that has never had a single recorded WAM
 *           entry across any session (RegionCompactor).
 *
 *   4. When a chunk that has WAM data is loaded/generated again
 *      later, DeltaReplay applies those recorded block deltas on top
 *      of whatever the generator produced, unconditionally
 *      overwriting the generator's blocks at those positions.
 *
 * WHY LevelEvent.Unload INSTEAD OF ServerStoppingEvent:
 * ServerStoppingEvent can fire before per-dimension unload/save
 * bookkeeping is fully settled, and does not naturally give a
 * "this specific dimension's chunks are done, safe to touch its
 * files now" signal per dimension. LevelEvent.Unload fires once per
 * ServerLevel as it is individually torn down, which is the more
 * precise "this dimension's files are now final for this session"
 * signal this mod needs. ServerStoppedEvent is kept only as a final
 * safety net / cleanup point for the in-memory maps.
 *
 * WHY NOT WRITE .wam CONTINUOUSLY DURING PLAY:
 * Every write to disk is a Windows file-replace opportunity to hit a
 * transient lock (antivirus, indexer, backup tool). Writing once per
 * dimension per session, right at unload time, minimizes both how
 * often that risk is taken AND how disruptive a retry delay would be
 * if it happens (the player is already at a loading/leaving screen,
 * not mid-build).
 */
@Mod(DeltaChunk.MOD_ID)
public final class DeltaChunk {

    public static final String MOD_ID = "deltachunk";

    public static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<MinecraftServer, WamStore> STORES =
            new ConcurrentHashMap<>();

    private static final Map<MinecraftServer, DeltaIndex> INDICES =
            new ConcurrentHashMap<>();

    public DeltaChunk(IEventBus modEventBus, ModContainer container) {

        NeoForge.EVENT_BUS.addListener(this::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);

        NeoForge.EVENT_BUS.addListener(this::onLevelUnload);

        NeoForge.EVENT_BUS.addListener(this::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(this::onBlockPlace);
        NeoForge.EVENT_BUS.addListener(this::onBlockToolModification);
        NeoForge.EVENT_BUS.addListener(this::onFluidBlock);
        NeoForge.EVENT_BUS.addListener(this::onExplosion);
        NeoForge.EVENT_BUS.addListener(this::onPiston);
        NeoForge.EVENT_BUS.addListener(this::onPlayerInteract);
 
        NeoForge.EVENT_BUS.addListener(this::onChunkLoad);

        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        container.registerConfig(
        net.neoforged.fml.config.ModConfig.Type.COMMON,
        DeltaConfig.SPEC
);

container.registerExtensionPoint(
        IConfigScreenFactory.class,
        (modContainer, screen) ->
                new net.neoforged.neoforge.client.gui.ConfigurationScreen(
                        modContainer,
                        screen
                )
);
    }

    /**
     * Registers /deltachunk add|delete, letting a player manually
     * add or remove WAM entries for a coordinate range in-game
     * without needing to physically break/place every block.
     * See DeltaCommand for the full behavior of each subcommand.
     */
    private void onRegisterCommands(RegisterCommandsEvent event) {

        DeltaCommand.register(event.getDispatcher());
    }

    /**
     * Package-visible accessor so DeltaCommand can reach the active
     * WamStore for a running server without this mod needing a
     * separate service-locator class. Returns null if called outside
     * an active session (should not normally happen, since commands
     * can only run while a server/world is up).
     */
    static WamStore getStore(MinecraftServer server) {
        return STORES.get(server);
    }

    // ------------------------------------------------------------
    // Session lifecycle
    // ------------------------------------------------------------

    private void onServerAboutToStart(ServerAboutToStartEvent event) {

        MinecraftServer server = event.getServer();

        STORES.put(server, new WamStore(server));
        INDICES.put(server, new DeltaIndex());

        LOGGER.info("[DeltaChunk] Initialized for new session.");
    }

    /**
     * Fires once per ServerLevel as it is individually torn down.
     * For a single-player world this happens for every dimension in
     * turn when the player quits to title / the integrated server
     * stops, AFTER vanilla has already saved that dimension's
     * chunks.
     *
     * This is the ONE place this mod writes to disk (both the .wam
     * flush and the .mca compaction), by design -- see class javadoc.
     */
    private void onLevelUnload(LevelEvent.Unload event) {

        if (!(event.getLevel() instanceof ServerLevel level)) {
            // Client-side LevelEvent.Unload; not relevant here.
            return;
        }

        MinecraftServer server = level.getServer();

        WamStore store = STORES.get(server);

        DeltaIndex index = INDICES.get(server);

        if (store == null || index == null) {

            LOGGER.warn(
                    "[DeltaChunk] No store/index registered for this " +
                    "server on level unload; skipping flush and " +
                    "compaction for {}. This should only happen if " +
                    "ServerAboutToStartEvent never fired for this " +
                    "server instance.",
                    dimensionId(level)
            );

            return;
        }

        //配置不生效維度
        if (DeltaConfig.isExcluded(dimensionId(level))) {
    return;
}

        String dimension = dimensionId(level);

        LOGGER.info(
                "[DeltaChunk] Level unloading for {}, flushing WAM " +
                "and compacting region files.",
                dimension
        );

        try {

            flushDimension(store, index, dimension);

        } catch (Exception exception) {

            LOGGER.error(
                    "[DeltaChunk] Failed to flush WAM data for {}. " +
                    "Skipping compaction for this dimension this " +
                    "session to avoid stripping chunks based on an " +
                    "incomplete on-disk WAM record.",
                    dimension,
                    exception
            );

            return;
        }

        try {

            compactDimension(server, level, store, dimension);

        } catch (Exception exception) {

            LOGGER.error(
                    "[DeltaChunk] Region compaction failed for {}.",
                    dimension,
                    exception
            );
        }
    }

    private void onServerStopped(ServerStoppedEvent event) {

        MinecraftServer server = event.getServer();

        STORES.remove(server);
        INDICES.remove(server);

        LOGGER.info("[DeltaChunk] Server stopped, session state cleared.");
    }

    /**
     * Merge every new block delta recorded this session for this
     * dimension into the on-disk .wam files, grouped by region so
     * each region file is only opened/rewritten once.
     */
    private void flushDimension(
            WamStore store,
            DeltaIndex index,
            String dimension
    ) throws IOException {

        Map<Long, BlockDelta> all =
                index.snapshotForDimension(dimension);

        if (all.isEmpty()) {
            return;
        }

        Map<Long, Map<Long, BlockDelta>> byRegion =
                new java.util.HashMap<>();

        for (Map.Entry<Long, BlockDelta> entry : all.entrySet()) {

            BlockPos pos = BlockPos.of(entry.getKey());

            ChunkPos chunkPos = new ChunkPos(pos);

            long regionKey =
                    packRegionKey(
                            chunkPos.getRegionX(),
                            chunkPos.getRegionZ()
                    );

            byRegion
                    .computeIfAbsent(
                            regionKey,
                            key -> new java.util.HashMap<>()
                    )
                    .put(entry.getKey(), entry.getValue());
        }

        int regionsWritten = 0;

        for (Map.Entry<Long, Map<Long, BlockDelta>> entry : byRegion.entrySet()) {

            int regionX = unpackRegionX(entry.getKey());
            int regionZ = unpackRegionZ(entry.getKey());

            store.mergeRegion(
                    dimension,
                    regionX,
                    regionZ,
                    entry.getValue()
            );

            regionsWritten++;
        }

        LOGGER.info(
                "[DeltaChunk] Flushed {} new block deltas across {} " +
                "region file(s) for {}.",
                all.size(),
                regionsWritten,
                dimension
        );
    }

    private void compactDimension(
            MinecraftServer server,
            ServerLevel level,
            WamStore store,
            String dimension
    ) throws IOException {

        Path regionDir = resolveRegionDir(server, level);

        if (regionDir == null || !Files.isDirectory(regionDir)) {

            LOGGER.warn(
                    "[DeltaChunk] Could not resolve region directory " +
                    "for {}, skipping compaction.",
                    dimension
            );

            return;
        }

        RegionCompactor.CompactionStats stats =
                RegionCompactor.compactDimension(
                        regionDir,
                        (chunkX, chunkZ) -> {

                            try {

                                return store.hasAnyDeltaInChunk(
                                        dimension,
                                        new ChunkPos(chunkX, chunkZ)
                                );

                            } catch (IOException exception) {

                                /*
                                 * If we can't determine whether a
                                 * chunk has recorded deltas, the safe
                                 * default is to KEEP it. Losing a
                                 * player's build to an I/O hiccup is
                                 * far worse than failing to reclaim
                                 * some disk space this session.
                                 */
                                LOGGER.error(
                                        "[DeltaChunk] Could not check " +
                                        "WAM data for chunk {},{} in " +
                                        "{}; keeping this chunk's MCA " +
                                        "data to be safe.",
                                        chunkX,
                                        chunkZ,
                                        dimension,
                                        exception
                                );

                                return true;
                            }
                        }
                );

        LOGGER.info(
                "[DeltaChunk] Compacted {}: {}",
                dimension,
                stats
        );
    }

    // ------------------------------------------------------------
    // Region directory resolution
    // ------------------------------------------------------------

    private static Path resolveRegionDir(
            MinecraftServer server,
            ServerLevel level
    ) {

        Path worldRoot = server.getWorldPath(LevelResource.ROOT);

        ResourceKey<Level> dimensionKey = level.dimension();

        if (dimensionKey.equals(Level.OVERWORLD)) {
            return worldRoot.resolve("region");
        }

        if (dimensionKey.equals(Level.NETHER)) {
            return worldRoot.resolve("DIM-1").resolve("region");
        }

        if (dimensionKey.equals(Level.END)) {
            return worldRoot.resolve("DIM1").resolve("region");
        }

        String namespace = dimensionKey.location().getNamespace();
        String path = dimensionKey.location().getPath();

        return worldRoot
                .resolve("dimensions")
                .resolve(namespace)
                .resolve(path)
                .resolve("region");
    }

    // ------------------------------------------------------------
    // Chunk generation: replay recorded deltas onto new chunks
    // ------------------------------------------------------------

    private void onChunkLoad(ChunkEvent.Load event) {

        /*
         * Only newly generated chunks need replay. A chunk loaded
         * from an existing, non-compacted (or compaction-retained)
         * .mca entry already reflects the correct on-disk state as
         * of the last time it was saved -- re-applying WAM deltas on
         * top of it would be redundant work at best. Replay is only
         * needed for the specific case this mod creates: a chunk
         * whose .mca payload was stripped by RegionCompactor, which
         * makes the game treat it as never-generated and therefore
         * generate it fresh (surfacing here as isNewChunk() == true),
         * onto which recorded deltas must be reapplied.
         */
        if (!event.isNewChunk()) {
            return;
        }

        ChunkAccess chunk = event.getChunk();

        ServerLevel level = getServerLevel(chunk);
        if (DeltaConfig.isExcluded(dimensionId(level))) {
    return;
}

        if (level == null) {
            return;
        }

        MinecraftServer server = level.getServer();

        WamStore store = STORES.get(server);

        if (store == null) {
            return;
        }

        if (!(chunk instanceof LevelChunk)) {
            // Not a fully-realized chunk yet; nothing to replay onto.
            return;
        }

        /*
         * DeltaReplay directly calls level.setBlock/getBlockEntity,
         * which requires the chunk to already be at a status where
         * block entities and light are valid. ChunkEvent.Load can
         * fire earlier than that for newly generated chunks. Schedule
         * the actual replay for the next server tick, by which point
         * the chunk is fully loaded and safe to mutate directly, to
         * avoid the deadlock/inconsistent-state risk called out in
         * the previous version of this class.
         */
        String dimension = dimensionId(level);

        ChunkPos pos = chunk.getPos();

        server.execute(() -> {

            ServerLevel currentLevel = server.getLevel(level.dimension());

            if (currentLevel == null) {
                return;
            }

            if (!currentLevel.hasChunk(pos.x, pos.z)) {
                return;
            }

            DeltaReplay.apply(currentLevel, pos, store, dimension);
        });
    }

    // ------------------------------------------------------------
    // Block-level change tracking
    // ------------------------------------------------------------

    private void onBlockBreak(BlockEvent.BreakEvent event) {

        /*
         * The block is about to become air. Record air explicitly
         * (rather than relying on some later air-state generic
         * catch-all) so a broken block correctly overwrites whatever
         * the generator would otherwise place there on regen, INCLUDING
         * clearing any block entity that used to be here (see
         * DeltaReplay's null-block-entity handling).
         */
        mark(
                event.getLevel(),
                event.getPos(),
                net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                null
        );
    }

    private void onBlockPlace(BlockEvent.EntityPlaceEvent event) {

        markFromWorld(event.getLevel(), event.getPos());

        if (
                event instanceof
                        BlockEvent.EntityMultiPlaceEvent multi
        ) {

            multi.getReplacedBlockSnapshots()
                    .forEach(snapshot ->
                            markFromWorld(
                                    event.getLevel(),
                                    snapshot.getPos()
                            )
                    );
        }
    }

    private void onBlockToolModification(
            BlockEvent.BlockToolModificationEvent event
    ) {

        /*
         * This event fires BEFORE the tool interaction (e.g. axe
         * stripping a log) actually applies in some NeoForge
         * versions, so read-from-world immediately after may still
         * see the old state. We mark from world on the next tick
         * instead of trusting a snapshot taken at event time.
         */
        LevelAccessor accessor = event.getLevel();

        BlockPos pos = event.getPos();

        if (accessor instanceof ServerLevel level) {

            level.getServer().execute(
                    () -> markFromWorld(level, pos)
            );
        }
    }

    private void onFluidBlock(BlockEvent.FluidPlaceBlockEvent event) {

        markFromWorld(event.getLevel(), event.getPos());
    }

    private void onExplosion(ExplosionEvent.Detonate event) {

        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        /*
         * Explosions are processed by vanilla immediately after this
         * event fires, so the affected positions aren't air YET at
         * the time of this callback. Defer reading each position's
         * final state to the next tick, once vanilla's explosion
         * block-removal pass has actually run.
         */
        var affected = java.util.List.copyOf(
                event.getAffectedBlocks()
        );

        level.getServer().execute(() -> {

            for (BlockPos pos : affected) {
                markFromWorld(level, pos);
            }
        });
    }

    private void onPiston(PistonEvent.Post event) {

        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        /*
         * Precise tracking instead of the old conservative 5x5x5
         * blast-radius mark: a piston only ever affects the pusher
         * block itself plus the single line of blocks in its facing
         * direction that actually moved. PistonEvent.Post doesn't
         * hand us the exact moved-block list directly, so we derive
         * it the same way vanilla's own piston structure resolver
         * does: walk from the piston head along its facing direction
         * until hitting a non-pushable block, which is exactly the
         * set of positions that changed state as a result of this
         * piston action (plus the block behind the retracted head,
         * for retraction).
         *
         * This intentionally still reads current world state (post
         * move) rather than trying to reconstruct "which blocks were
         * at which old positions", since what DeltaReplay needs is
         * only "what does this position look like now", not a
         * full move history.
         */
        BlockPos pistonPos = event.getPos();

        net.minecraft.core.Direction facing =
                event.getDirection();

        boolean extending =
                event.getPistonMoveType()
                        == PistonEvent.PistonMoveType.EXTEND;

        java.util.Set<BlockPos> toCheck = new java.util.HashSet<>();

        toCheck.add(pistonPos);
        toCheck.add(pistonPos.relative(facing));

        /*
         * Walk along the push direction up to the vanilla max push
         * distance (12 blocks) from the piston head, recording every
         * position along that line. This covers the true set of
         * positions a piston can ever affect in one action without
         * resorting to a fixed-radius cube guess.
         */
        BlockPos cursor = pistonPos.relative(facing);

        for (int i = 0; i < 13; i++) {

            toCheck.add(cursor);

            cursor = cursor.relative(facing);
        }

        if (!extending) {
            // Retraction can also pull a block from one further
            // step away (sticky piston).
            toCheck.add(
                    pistonPos.relative(facing, 2)
            );
        }

        level.getServer().execute(() -> {

            for (BlockPos pos : toCheck) {
                markFromWorld(level, pos);
            }
        });
    }

    private void onPlayerInteract(
            PlayerInteractEvent.RightClickBlock event
    ) {

        if (event.getLevel().isClientSide()) {
            return;
        }

        /*
         * Right-click covers things like opening a chest, adjusting
         * a repeater, ringing a bell, etc. Most of these don't
         * change the BlockState at all (opening a chest is a
         * transient animation state, not a persisted property), but
         * some DO carry meaningful container/BlockEntity state
         * (hopper filters, lectern pages, etc) that should be
         * captured. Marking from world here is deliberately
         * over-inclusive for interact events specifically, since
         * interact-driven state changes are exactly the category
         * most likely to be silently missed by the other listeners.
         */
        markFromWorld(event.getLevel(), event.getPos());
    }

    /**
     * Read the CURRENT state and any block entity at {@code pos}
     * from the world and record it. Used whenever the event doesn't
     * hand us an authoritative "this is the new state" value
     * directly (placement, interact, fluids, post-explosion,
     * post-piston).
     */
    private void markFromWorld(LevelAccessor level, BlockPos pos) {

        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        BlockState state = serverLevel.getBlockState(pos);

        BlockEntity blockEntity = serverLevel.getBlockEntity(pos);

        CompoundTag blockEntityTag = null;

        if (blockEntity != null) {

            blockEntityTag =
                    blockEntity.saveWithFullMetadata(
                            serverLevel.registryAccess()
                    );
        }

        mark(serverLevel, pos, state, blockEntityTag);
    }

    /**
     * Record an explicit (state, block entity) pair for {@code pos}.
     * Used when the event itself already tells us the resulting
     * state unambiguously (block break -> air), avoiding a redundant
     * world read.
     */
    private void mark(
            LevelAccessor level,
            BlockPos pos,
            BlockState state,
            CompoundTag blockEntityTag
    ) {

        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        MinecraftServer server = serverLevel.getServer();
        if (DeltaConfig.isExcluded(dimensionId(serverLevel))) {
    return;
}

        DeltaIndex index = INDICES.get(server);

        if (index == null) {
            return;
        }

        index.record(
                dimensionId(serverLevel),
                pos.immutable(),
                new BlockDelta(
                        BlockDelta.serializeState(state),
                        blockEntityTag
                )
        );
    }

    // ------------------------------------------------------------
    // Small shared helpers
    // ------------------------------------------------------------

    private static long packRegionKey(int regionX, int regionZ) {

        return (((long) regionX) << 32)
                ^ (regionZ & 0xFFFFFFFFL);
    }

    private static int unpackRegionX(long key) {
        return (int) (key >> 32);
    }

    private static int unpackRegionZ(long key) {
        return (int) key;
    }

    private static String dimensionId(ServerLevel level) {

        return level.dimension().location().toString();
    }

    private static ServerLevel getServerLevel(ChunkAccess chunk) {

        if (chunk instanceof LevelChunk levelChunk) {

            Level level = levelChunk.getLevel();

            if (level instanceof ServerLevel serverLevel) {
                return serverLevel;
            }
        }

        return null;
    }
}
