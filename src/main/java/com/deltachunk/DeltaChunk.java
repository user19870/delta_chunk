package com.deltachunk;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.LevelAccessor;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
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

    public static final String MOD_ID = "deltachunk";

    public static final Logger LOGGER =
            LogUtils.getLogger();

    private static final Map<MinecraftServer, WamStore> STORES =
            new ConcurrentHashMap<>();

    private static final Map<MinecraftServer, Set<Long>> MODIFIED =
            new ConcurrentHashMap<>();

    public DeltaChunk(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(this::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);

        NeoForge.EVENT_BUS.addListener(this::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(this::onBlockPlace);
        NeoForge.EVENT_BUS.addListener(this::onBlockToolModification);
        NeoForge.EVENT_BUS.addListener(this::onFluidBlock);
        NeoForge.EVENT_BUS.addListener(this::onExplosion);
        NeoForge.EVENT_BUS.addListener(this::onPiston);
        NeoForge.EVENT_BUS.addListener(this::onPlayerInteract);

        NeoForge.EVENT_BUS.addListener(this::onChunkSave);
    }

    private void onServerAboutToStart(ServerAboutToStartEvent event) {
        MinecraftServer server = event.getServer();

        WamStore store = new WamStore(server);

        STORES.put(server, store);
        MODIFIED.put(server, ConcurrentHashMap.newKeySet());

        LOGGER.info(
                "[DeltaChunk] WAM store initialized for {}",
                server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
        );
    }

    private void onServerStopping(ServerStoppingEvent event) {
        MinecraftServer server = event.getServer();

        WamStore store = STORES.get(server);

        if (store == null) {
            return;
        }

        LOGGER.info("[DeltaChunk] Server stopping; WAM data is already updated by chunk-save events.");
    }

    private void onServerStopped(ServerStoppedEvent event) {
        MinecraftServer server = event.getServer();

        STORES.remove(server);
        MODIFIED.remove(server);

        LOGGER.info("[DeltaChunk] Server stopped.");
    }

    private void onChunkSave(ChunkDataEvent.Save event) {
        ChunkAccess chunk = event.getChunk();

        MinecraftServer server = findServer(chunk);

        if (server == null) {
            return;
        }

        Set<Long> modified = MODIFIED.get(server);

        if (modified == null) {
            return;
        }

        long chunkKey = chunk.getPos().toLong();

        if (!modified.contains(chunkKey)) {
            return;
        }

        WamStore store = STORES.get(server);

        if (store == null) {
            return;
        }

        try {
            store.saveChunk(
                    getDimensionId(chunk),
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

    private void onBlockBreak(BlockEvent.BreakEvent event) {
        mark(event.getLevel(), event.getPos());
    }

    private void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        mark(event.getLevel(), event.getPos());

        if (event instanceof BlockEvent.EntityMultiPlaceEvent multi) {
            multi.getReplacedBlockSnapshots()
                    .forEach(snapshot ->
                            mark(event.getLevel(), snapshot.getPos())
                    );
        }
    }

    private void onBlockToolModification(
            BlockEvent.BlockToolModificationEvent event
    ) {
        mark(event.getLevel(), event.getPos());
    }

    private void onFluidBlock(BlockEvent.FluidPlaceBlockEvent event) {
        mark(event.getLevel(), event.getPos());
    }

    private void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        event.getAffectedBlocks()
                .forEach(pos -> mark(level, pos));
    }

    private void onPiston(PistonEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        BlockPos pos = event.getPos();

        mark(level, pos);

        for (BlockPos blockPos : BlockPos.betweenClosed(
                pos.offset(-2, -2, -2),
                pos.offset(2, 2, 2)
        )) {
            mark(level, blockPos);
        }
    }

    private void onPlayerInteract(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) {
            return;
        }

        mark(
                event.getLevel(),
                event.getPos()
        );
    }

    private void mark(LevelAccessor level, BlockPos pos) {
    if (!(level instanceof ServerLevel serverLevel)) {
        return;
    }

    MinecraftServer server = serverLevel.getServer();

    Set<Long> modified = MODIFIED.get(server);

    if (modified == null) {
        return;
    }

    long chunkKey = new net.minecraft.world.level.ChunkPos(pos).toLong();

    modified.add(chunkKey);
}
    

    private static MinecraftServer findServer(ChunkAccess chunk) {
        if (chunk instanceof LevelChunk levelChunk) {
            Level level = levelChunk.getLevel();

            if (level instanceof ServerLevel serverLevel) {
                return serverLevel.getServer();
            }
        }

        return null;
    }

    private static String getDimensionId(ChunkAccess chunk) {
        if (chunk instanceof LevelChunk levelChunk) {
            Level level = levelChunk.getLevel();

            if (level instanceof ServerLevel serverLevel) {
                return serverLevel.dimension().location().toString();
            }
        }

        return "minecraft:overworld";
    }
}
