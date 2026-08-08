package com.deltachunk;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class McaCompactor {

    private McaCompactor() {
    }

    public static void compactDimension(
            Path dimensionRoot,
            ResourceKey<Level> dimension,
            String dimensionId,
            Set<Long> modifiedChunks,
            WamStore wamStore
    ) throws IOException {

        if (modifiedChunks.isEmpty()) {
            return;
        }

        Path regionDirectory = dimensionRoot.resolve("region");

        if (!Files.isDirectory(regionDirectory)) {
            return;
        }

        Map<Long, Set<Long>> chunksByRegion = new HashMap<>();

        for (long chunkKey : modifiedChunks) {
            ChunkPos chunkPos = new ChunkPos(chunkKey);

            int regionX = chunkPos.x >> 5;
            int regionZ = chunkPos.z >> 5;

            long regionKey = ChunkPos.asLong(regionX, regionZ);

            chunksByRegion
                    .computeIfAbsent(regionKey, ignored -> new HashSet<>())
                    .add(chunkKey);
        }

        for (Map.Entry<Long, Set<Long>> entry : chunksByRegion.entrySet()) {
            long regionKey = entry.getKey();

            int regionX = (int) regionKey;
            int regionZ = (int) (regionKey >> 32);

            compactRegion(
                    regionDirectory,
                    dimension,
                    dimensionId,
                    regionX,
                    regionZ,
                    entry.getValue(),
                    wamStore
            );
        }
    }

    private static void compactRegion(
            Path regionDirectory,
            ResourceKey<Level> dimension,
            String dimensionId,
            int regionX,
            int regionZ,
            Set<Long> modifiedChunks,
            WamStore wamStore
    ) throws IOException {

        String fileName =
                "r." + regionX + "." + regionZ + ".mca";

        Path original =
                regionDirectory.resolve(fileName);

        if (!Files.exists(original)) {
            return;
        }

        /*
         * IMPORTANT:
         *
         * We do not copy from the old MCA.
         *
         * WAM is the verified source for every modified chunk.
         *
         * Therefore if a chunk was marked modified but WAM was not
         * successfully written, this region is NOT compacted.
         */
        Map<Long, CompoundTag> keep = new HashMap<>();

        for (long chunkKey : modifiedChunks) {
            CompoundTag tag =
                    wamStore.loadChunk(
                            dimensionId,
                            new ChunkPos(chunkKey)
                    );

            if (tag == null) {
                throw new IOException(
                        "WAM does not contain modified chunk "
                                + new ChunkPos(chunkKey)
                                + " in "
                                + dimensionId
                );
            }

            keep.put(chunkKey, tag);
        }

        Path temp =
                regionDirectory.resolve(
                        fileName + ".deltachunk.tmp"
                );

        Path tempExternal =
                regionDirectory.resolve(
                        ".deltachunk-external-"
                                + regionX
                                + "-"
                                + regionZ
        );

        Files.deleteIfExists(temp);

        if (Files.exists(tempExternal)) {
            deleteRecursively(tempExternal);
        }

        Files.createDirectories(tempExternal);

        RegionStorageInfo storageInfo =
                new RegionStorageInfo(
                        "DeltaChunk",
                        dimension,
                        "chunk"
                );

        /*
         * Create a completely new MCA.
         *
         * This is what actually reduces the physical file size.
         */
        try (RegionFile output =
                     new RegionFile(
                             storageInfo,
                             temp,
                             tempExternal,
                             true
                     )) {

            for (Map.Entry<Long, CompoundTag> entry :
                    keep.entrySet()) {

                ChunkPos pos =
                        new ChunkPos(entry.getKey());

                try (DataOutputStream stream =
                             output.getChunkDataOutputStream(pos)) {

                    NbtIo.write(
                            entry.getValue(),
                            stream
                    );
                }
            }

            output.flush();
        }

        /*
         * Re-open the generated file and verify every retained chunk
         * exists before touching the original MCA.
         */
        try (RegionFile verify =
                     new RegionFile(
                             storageInfo,
                             temp,
                             tempExternal,
                             true
                     )) {

            for (long chunkKey : keep.keySet()) {
                ChunkPos pos = new ChunkPos(chunkKey);

                if (!verify.hasChunk(pos)) {
                    throw new IOException(
                            "Verification failed for "
                                    + pos
                    );
                }
            }

            verify.flush();
        }

        /*
         * Backup the original first.
         *
         * If anything goes wrong after this point, we still have
         * the original region available.
         */
        Path backup =
                regionDirectory.resolve(
                        fileName + ".deltachunk-backup"
                );

        Files.deleteIfExists(backup);

        Files.move(
                original,
                backup,
                StandardCopyOption.REPLACE_EXISTING
        );

        try {
            Files.move(
                    temp,
                    original,
                    StandardCopyOption.REPLACE_EXISTING
            );

            /*
             * Move oversized-chunk files, if RegionFile created any.
             */
            if (Files.isDirectory(tempExternal)) {
                try (var stream =
                             Files.list(tempExternal)) {

                    stream.forEach(path -> {
                        try {
                            Files.move(
                                    path,
                                    regionDirectory.resolve(
                                            path.getFileName()
                                    ),
                                    StandardCopyOption.REPLACE_EXISTING
                            );
                        } catch (IOException exception) {
                            throw new RuntimeException(
                                    exception
                            );
                        }
                    });
                }
            }

            deleteRecursively(tempExternal);

            /*
             * The new MCA is now the canonical file.
             *
             * We deliberately remove the backup only after the
             * replacement succeeded.
             */
            Files.deleteIfExists(backup);

        } catch (RuntimeException | IOException exception) {

            /*
             * Roll back if possible.
             */
            Files.deleteIfExists(original);

            Files.move(
                    backup,
                    original,
                    StandardCopyOption.REPLACE_EXISTING
            );

            throw exception;
        }

        DeltaChunk.LOGGER.info(
                "[DeltaChunk] Compacted {}: {} modified chunks kept",
                original,
                keep.size()
        );
    }

    private static void deleteRecursively(Path path)
            throws IOException {

        if (!Files.exists(path)) {
            return;
        }

        List<Path> paths = new ArrayList<>();

        try (var stream = Files.walk(path)) {
            stream.forEach(paths::add);
        }

        paths.sort(
                (a, b) ->
                        Integer.compare(
                                b.getNameCount(),
                                a.getNameCount()
                        )
        );

        for (Path current : paths) {
            Files.deleteIfExists(current);
        }
    }
}
