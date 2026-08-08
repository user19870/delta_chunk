package com.deltachunk;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.minecraft.world.level.storage.LevelResource;
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

    private static final Map<
            MinecraftServer,
            Set<String>
    > MODIFIED =
            new ConcurrentHashMap<>();

    public DeltaChunk(IEventBus modEventBus) {

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

        NeoForge.EVENT_BUS.addListener(
                this::onChunkDataLoad
        );

        NeoForge.EVENT_BUS.addListener(
                this::onChunkLoad
        );
    }

    private void onServerAboutToStart(
            ServerAboutToStartEvent event
    ) {

        MinecraftServer server =
                event.getServer();

        WamStore store = new WamStore(server);

        STORES.put(
                server,
                store
        );

        Set<String> modified =
                ConcurrentHashMap.newKeySet();

        /*
         * Load the persisted "modified chunk" index from the
         * previous session(s). Without this, every server restart
         * would forget which chunks were ever touched by the
         * player, and the next compaction pass on shutdown would
         * wrongly strip out chunks containing real player builds.
         */
        try {

            modified.addAll(
                    store.loadModifiedIndex()
            );

            LOGGER.info(
                    "[DeltaChunk] Loaded {} previously-modified chunk entries.",
                    modified.size()
            );

        } catch (Exception exception) {

            LOGGER.error(
                    "[DeltaChunk] Failed to load modified chunk index, " +
                    "starting with an empty set. This risks the next " +
                    "compaction pass stripping chunks that were actually " +
                    "modified previously.",
                    exception
            );
        }

        MODIFIED.put(
                server,
                modified
        );

        LOGGER.info(
                "[DeltaChunk] WAM initialized."
        );
    }

    /*
     * IMPORTANT TIMING ASSUMPTION:
     *
     * This assumes that by the time ServerStoppingEvent fires, the
     * vanilla save-all-chunks pass has ALREADY completed and every
     * .mca file on disk reflects the final state of the world for
     * this session. If that assumption is wrong for some code path
     * (e.g. a forced/crash shutdown that skips the normal save),
     * this compaction pass could run against a stale or partially
     * written .mca and strip chunks that were never actually
     * flushed. If you see corruption after a crash, verify save
     * ordering here first.
     */
    private void onServerStopping(
            ServerStoppingEvent event
    ) {

        MinecraftServer server =
                event.getServer();

        WamStore store =
                STORES.get(server);

        Set<String> modified =
                MODIFIED.get(server);

        if (store == null || modified == null) {

            LOGGER.warn(
                    "[DeltaChunk] Missing store/modified set on " +
                    "shutdown, skipping persistence and compaction."
            );

            return;
        }

        /*
         * Persist the modified-chunk index FIRST, before touching
         * any .mca file. If compaction below throws partway
         * through, we still want the index on disk to be accurate
         * for next startup.
         */
        try {

            store.saveModifiedIndex(modified);

            LOGGER.info(
                    "[DeltaChunk] Persisted {} modified chunk entries.",
                    modified.size()
            );

        } catch (Exception exception) {

            LOGGER.error(
                    "[DeltaChunk] Failed to persist modified chunk " +
                    "index. Aborting compaction this session to avoid " +
                    "stripping chunks based on an incomplete picture.",
                    exception
            );

            return;
        }

        try {

            compactAllDimensions(server, modified);

        } catch (Exception exception) {

            LOGGER.error(
                    "[DeltaChunk] Region compaction failed.",
                    exception
            );
        }

        LOGGER.info(
                "[DeltaChunk] Server stopping."
        );
    }

    /*
     * Walk every loaded level's region folder and strip out any
     * chunk that was never recorded as modified, using
     * RegionCompactor. This operates on the .mca files directly on
     * disk, AFTER vanilla's own save has already written them.
     */
    private void compactAllDimensions(
            MinecraftServer server,
            Set<String> modified
    ) {

        for (ServerLevel level : server.getAllLevels()) {

            String dimension = dimensionId(level);

            Path regionDir =
                    resolveRegionDir(server, level);

            if (regionDir == null) {

                LOGGER.warn(
                        "[DeltaChunk] Could not resolve region " +
                        "directory for dimension {}, skipping " +
                        "compaction for it.",
                        dimension
                );

                continue;
            }

            try {

                RegionCompactor.compactDimension(
                        regionDir,
                        (chunkX, chunkZ) ->
                                modified.contains(
                                        dimension + "|" + chunkX + "|" + chunkZ
                                )
                );

                LOGGER.info(
                        "[DeltaChunk] Compacted region files for {} ({}).",
                        dimension,
                        regionDir
                );

            } catch (IOException exception) {

                LOGGER.error(
                        "[DeltaChunk] Failed to compact region files for {}.",
                        dimension,
                        exception
                );
            }
        }
    }

    /*
     * Resolve the on-disk "region" folder for a given dimension.
     *
     * This targets the pre-26.1 Java Edition level layout (which is
     * what NeoForge 1.21.1 uses):
     *
     *   <world>/region/                              (minecraft:overworld)
     *   <world>/DIM-1/region/                         (minecraft:the_nether)
     *   <world>/DIM1/region/                          (minecraft:the_end)
     *   <world>/dimensions/<namespace>/<path>/region/ (any other/modded dim)
     *
     * IMPORTANT: if this mod is ever ported to a Minecraft version
     * using the restructured save layout introduced in 26.1 snap6
     * (where the overworld's region folder moves under
     * dimensions/minecraft/overworld/region), this method must be
     * updated accordingly, or compaction will silently do nothing
     * because the resolved path won't exist.
     */
    private static Path resolveRegionDir(
            MinecraftServer server,
            ServerLevel level
    ) {

        Path worldRoot =
                server.getWorldPath(LevelResource.ROOT);

        net.minecraft.resources.ResourceKey<Level> dimensionKey =
                level.dimension();

        if (dimensionKey.equals(Level.OVERWORLD)) {
            return worldRoot.resolve("region");
        }

        if (dimensionKey.equals(Level.NETHER)) {
            return worldRoot.resolve("DIM-1").resolve("region");
        }

        if (dimensionKey.equals(Level.END)) {
            return worldRoot.resolve("DIM1").resolve("region");
        }

        // Any other (modded/datapack) dimension.
        String namespace =
                dimensionKey.location().getNamespace();

        String path =
                dimensionKey.location().getPath();

        return worldRoot
                .resolve("dimensions")
                .resolve(namespace)
                .resolve(path)
                .resolve("region");
    }

    private void onServerStopped(
            ServerStoppedEvent event
    ) {

        MinecraftServer server =
                event.getServer();

        STORES.remove(server);

        MODIFIED.remove(server);

        LOGGER.info(
                "[DeltaChunk] Server stopped."
        );
    }

    /*
     * Existing MCA -> WAM.
     *
     * This is deliberately kept separate from the
     * generation overlay.
     */
    private void onChunkSave(
            ChunkDataEvent.Save event
    ) {

        ChunkAccess chunk =
                event.getChunk();

        ServerLevel level =
                getServerLevel(chunk);

        if (level == null) {
            return;
        }

        MinecraftServer server =
                level.getServer();

        if (!isModified(
                server,
                level,
                chunk.getPos()
        )) {
            return;
        }

        WamStore store =
                STORES.get(server);

        if (store == null) {
            return;
        }

        try {

            store.saveChunk(
                    dimensionId(level),
                    chunk.getPos(),
                    event.getData()
            );

        } catch (Exception exception) {

            LOGGER.error(
                    "[DeltaChunk] Failed to save WAM {} {}",
                    dimensionId(level),
                    chunk.getPos(),
                    exception
            );
        }
    }

    /*
     * Existing MCA data.
     *
     * If a WAM snapshot exists, replace the incoming
     * NBT with the WAM snapshot.
     *
     * IMPORTANT:
     * This only handles chunks that already have MCA
     * data. Newly generated chunks are handled by
     * onChunkLoad().
     */
    private void onChunkDataLoad(
            ChunkDataEvent.Load event
    ) {

        ChunkAccess chunk =
                event.getChunk();

        ServerLevel level =
                getServerLevel(chunk);

        if (level == null) {
            return;
        }

        MinecraftServer server =
                level.getServer();

        WamStore store =
                STORES.get(server);

        if (store == null) {
            return;
        }

        try {

            var wam =
                    store.loadChunk(
                            dimensionId(level),
                            chunk.getPos()
                    );

            if (wam == null) {
                return;
            }

            replaceTag(
                    event.getData(),
                    wam
            );

            LOGGER.debug(
                    "[DeltaChunk] Restored WAM chunk {} {}",
                    dimensionId(level),
                    chunk.getPos()
            );

        } catch (Exception exception) {

            LOGGER.error(
                    "[DeltaChunk] Failed to restore WAM chunk {} {}",
                    dimensionId(level),
                    chunk.getPos(),
                    exception
            );
        }
    }

    /*
     * Newly generated Chunk.
     *
     * We deliberately DON'T modify it yet here.
     *
     * The reason is that ChunkEvent.Load can happen
     * before FULL status and touching block entities
     * or level state from this callback can deadlock.
     *
     * This method currently only records diagnostics.
     *
     * The final WAM overlay will be installed in the
     * generation pipeline.
     */
    private void onChunkLoad(
            ChunkEvent.Load event
    ) {

        if (!event.isNewChunk()) {
            return;
        }

        ChunkAccess chunk =
                event.getChunk();

        ServerLevel level =
                getServerLevel(chunk);

        if (level == null) {
            return;
        }

        MinecraftServer server =
                level.getServer();

        WamStore store =
                STORES.get(server);

        if (store == null) {
            return;
        }

        try {

            if (store.hasChunk(
                    dimensionId(level),
                    chunk.getPos()
            )) {

                LOGGER.debug(
                        "[DeltaChunk] New generated chunk has WAM data: {} {}",
                        dimensionId(level),
                        chunk.getPos()
                );
            }

        } catch (Exception exception) {

            LOGGER.error(
                    "[DeltaChunk] Failed checking WAM for {}",
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
                event instanceof
                        BlockEvent.EntityMultiPlaceEvent multi
        ) {

            multi.getReplacedBlockSnapshots()
                    .forEach(snapshot ->
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
                .forEach(pos ->
                        mark(level, pos)
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

        BlockPos origin =
                event.getPos();

        mark(
                level,
                origin
        );

        /*
         * Mark a conservative area around the piston.
         *
         * This intentionally over-marks rather than
         * risking loss of a moved block.
         */
        for (
                BlockPos pos :
                BlockPos.betweenClosed(
                        origin.offset(
                                -2,
                                -2,
                                -2
                        ),
                        origin.offset(
                                2,
                                2,
                                2
                        )
                )
        ) {

            mark(level, pos);
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

        Set<String> modified =
                MODIFIED.get(server);

        if (modified == null) {
            return;
        }

        ChunkPos chunkPos =
                new ChunkPos(pos);

        modified.add(
                modificationKey(
                        serverLevel,
                        chunkPos
                )
        );
    }

    private static boolean isModified(
            MinecraftServer server,
            ServerLevel level,
            ChunkPos pos
    ) {

        Set<String> modified =
                MODIFIED.get(server);

        if (modified == null) {
            return false;
        }

        return modified.contains(
                modificationKey(
                        level,
                        pos
                )
        );
    }

    private static String modificationKey(
            ServerLevel level,
            ChunkPos pos
    ) {

        return dimensionId(level) +
                "|" +
                pos.x +
                "|" +
                pos.z;
    }

    private static String dimensionId(
            ServerLevel level
    ) {

        return level
                .dimension()
                .location()
                .toString();
    }

    private static ServerLevel getServerLevel(
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

                return serverLevel;
            }
        }

        return null;
    }

    private static void replaceTag(
            net.minecraft.nbt.CompoundTag target,
            net.minecraft.nbt.CompoundTag source
    ) {

        /*
         * CompoundTag has no public "replace all"
         * operation that is suitable for every version,
         * so copy every key from the source after
         * removing the existing keys.
         */

        for (
                String key :
                target.getAllKeys().toArray(
                        new String[0]
                )
        ) {

            target.remove(key);
        }

        target.merge(
                source.copy()
        );
    }
}
