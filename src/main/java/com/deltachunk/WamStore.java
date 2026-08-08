package com.deltachunk;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

public final class WamStore {

    private static final int FORMAT_VERSION = 2;

    private final Path root;

    public WamStore(MinecraftServer server) {
        this.root = server
                .getWorldPath(LevelResource.ROOT)
                .resolve("wam");
    }

    public synchronized void saveChunk(
            String dimension,
            ChunkPos pos,
            CompoundTag chunkData
    ) throws IOException {

        Path file = getRegionFile(dimension, pos);

        Files.createDirectories(file.getParent());

        Map<Long, CompoundTag> chunks;

        if (Files.exists(file)) {
            chunks = read(file);
        } else {
            chunks = new HashMap<>();
        }

        chunks.put(pos.toLong(), chunkData.copy());

        write(file, chunks);
    }

    public synchronized CompoundTag loadChunk(
            String dimension,
            ChunkPos pos
    ) throws IOException {

        Path file = getRegionFile(dimension, pos);

        if (!Files.exists(file)) {
            return null;
        }

        Map<Long, CompoundTag> chunks = read(file);

        CompoundTag tag = chunks.get(pos.toLong());

        return tag == null ? null : tag.copy();
    }

    public synchronized boolean hasChunk(
            String dimension,
            ChunkPos pos
    ) throws IOException {

        Path file = getRegionFile(dimension, pos);

        if (!Files.exists(file)) {
            return false;
        }

        Map<Long, CompoundTag> chunks = read(file);

        return chunks.containsKey(pos.toLong());
    }

    private Path getRegionFile(
            String dimension,
            ChunkPos pos
    ) {
        int regionX = pos.getRegionX();
        int regionZ = pos.getRegionZ();

        String safeDimension = dimension
                .replace(':', '_')
                .replace('/', '_')
                .replace('\\', '_');

        return root
                .resolve(safeDimension)
                .resolve(
                        "r." +
                        regionX +
                        "." +
                        regionZ +
                        ".wam"
                );
    }

    private static Map<Long, CompoundTag> read(
            Path file
    ) throws IOException {

        Map<Long, CompoundTag> result =
                new HashMap<>();

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
                        "Unsupported WAM format version: " +
                        version
                );
            }

            int count = input.readInt();

            if (count < 0 || count > 1024) {
                throw new IOException(
                        "Invalid WAM chunk count: " +
                        count
                );
            }

            for (int i = 0; i < count; i++) {

                long chunkKey =
                        input.readLong();

                CompoundTag tag =
                        NbtIo.read(
                                input,
                                NbtAccounter.unlimitedHeap()
                        );

                result.put(
                        chunkKey,
                        tag
                );
            }
        }

        return result;
    }

    private static void write(
            Path file,
            Map<Long, CompoundTag> chunks
    ) throws IOException {

        Path temporary =
                file.resolveSibling(
                        file.getFileName() +
                        ".tmp"
                );

        try (
                DataOutputStream output =
                        new DataOutputStream(
                                new BufferedOutputStream(
                                        Files.newOutputStream(
                                                temporary
                                        )
                                )
                        )
        ) {
            output.writeInt(FORMAT_VERSION);

            output.writeInt(
                    chunks.size()
            );

            for (
                    Map.Entry<Long, CompoundTag> entry :
                    chunks.entrySet()
            ) {

                output.writeLong(
                        entry.getKey()
                );

                NbtIo.write(
                        entry.getValue(),
                        output
                );
            }
        }

        try {
            Files.move(
                    temporary,
                    file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (
                java.nio.file.AtomicMoveNotSupportedException exception
        ) {
            Files.move(
                    temporary,
                    file,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }
}
