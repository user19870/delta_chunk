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
import org.slf4j.Logger;

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

        STORES.put(
                server,
                new WamStore(server)
        );

        MODIFIED.put(
                server,
                ConcurrentHashMap.newKeySet()
        );

        LOGGER.info(
                "[DeltaChunk] WAM initialized."
        );
    }

    private void onServerStopping(
            ServerStoppingEvent event
    ) {

        MinecraftServer server =
                event.getServer();

        LOGGER.info(
                "[DeltaChunk] Server stopping."
        );
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
