package com.deltachunk;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

public final class WamStore {

    private static final int FORMAT_VERSION = 1;

    private final MinecraftServer server;

    private final Path root;

    public WamStore(MinecraftServer server) {
        this.server = server;

        this.root = server
                .getWorldPath(LevelResource.ROOT)
                .resolve("wam");
    }

    public void saveChunk(
            String dimension,
            ChunkPos pos,
            CompoundTag chunkData
    ) throws IOException {

        Path file = getFile(dimension);

        Files.createDirectories(file.getParent());

        Map<Long, CompoundTag> chunks;

        if (Files.exists(file)) {
            chunks = read(file);
        } else {
            chunks = new HashMap<>();
        }

        chunks.put(
                pos.toLong(),
                chunkData.copy()
        );

        write(file, chunks);
    }

    public CompoundTag loadChunk(
            String dimension,
            ChunkPos pos
    ) throws IOException {

        Path file = getFile(dimension);

        if (!Files.exists(file)) {
            return null;
        }

        Map<Long, CompoundTag> chunks = read(file);

        CompoundTag tag = chunks.get(pos.toLong());

        return tag == null ? null : tag.copy();
    }

    private Path getFile(String dimension) {
        String safeName = dimension
                .replace(':', '_')
                .replace('/', '_')
                .replace('\\', '_');

        return root.resolve(
                safeName + ".wam"
        );
    }

    private static Map<Long, CompoundTag> read(
            Path file
    ) throws IOException {

        Map<Long, CompoundTag> result = new HashMap<>();

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
                        "Unsupported WAM version: " + version
                );
            }

            int count = input.readInt();

            if (count < 0 || count > 10_000_000) {
                throw new IOException(
                        "Invalid WAM chunk count: " + count
                );
            }

            for (int i = 0; i < count; i++) {
                long chunkKey = input.readLong();

                CompoundTag tag =
                        NbtIo.read(
                                input,
                                NbtAccounter.unlimitedHeap()
                        );

                result.put(chunkKey, tag);
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
                        file.getFileName() + ".tmp"
                );

        try (
                DataOutputStream output =
                        new DataOutputStream(
                                new BufferedOutputStream(
                                        Files.newOutputStream(temporary)
                                )
                        )
        ) {
            output.writeInt(FORMAT_VERSION);
            output.writeInt(chunks.size());

            for (Map.Entry<Long, CompoundTag> entry :
                    chunks.entrySet()) {

                output.writeLong(entry.getKey());

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
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
            );
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(
                    temporary,
                    file,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
            );
        }
    }
}
