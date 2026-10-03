package com.deltachunk;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import java.util.Map;
import java.util.List;
import java.util.stream.Stream;
/**
 * A single "the player changed this" record for one block position.
 *
 * This is deliberately NOT a full chunk snapshot. It only carries:
 *   - the block position (within the world, absolute coordinates)
 *   - the resulting BlockState after the change, serialized as a
 *     string (registry name + property map) rather than a raw state
 *     id, because state ids are NOT stable across game versions or
 *     between vanilla/modded registries. A string form can always be
 *     re-parsed against whatever block registry is active when the
 *     chunk is re-generated, even if the underlying id numbers moved.
 *   - optionally, the BlockEntity NBT for that position (chests,
 *     signs, redstone-adjacent block entities, etc), if one exists.
 *
 * A BlockDelta with blockEntity == null just means "no block entity
 * at this position" -- it does NOT mean "don't touch the block
 * entity". If a position used to have a block entity and the player
 * removed it (e.g. broke a chest), that is represented correctly:
 * the new BlockState is air (or whatever replaced it) and
 * blockEntity is null, so replay will correctly clear any stale
 * block entity the generator might otherwise leave behind.
 */
public final class BlockDelta {

    private final String blockStateString;

    private final CompoundTag blockEntity;

    public BlockDelta(
            String blockStateString,
            CompoundTag blockEntity
    ) {

        this.blockStateString = blockStateString;

        this.blockEntity =
                blockEntity == null
                        ? null
                        : blockEntity.copy();
    }

    public String blockStateString() {
        return blockStateString;
    }

    public CompoundTag blockEntity() {
        return blockEntity == null
                ? null
                : blockEntity.copy();
    }

    public boolean hasBlockEntity() {
        return blockEntity != null;
    }

    /*
     * Serialize a BlockState the same way vanilla structure/NBT
     * tooling does: "modid:block_name[prop1=val1,prop2=val2]".
     * This format is human-inspectable and is exactly what
     * net.minecraft.commands.arguments.blocks.BlockStateParser can
     * parse back, so we reuse that parser on the read side instead
     * of inventing a bespoke format.
     */
    public static String serializeState(BlockState state) {

        StringBuilder builder = new StringBuilder();

        Block block = state.getBlock();

        builder.append(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .getKey(block)
                        .toString()
        );

        Stream<Property.Value<?>> values = state.getValues();

List<Property.Value<?>> valueList = values.toList();

if (!valueList.isEmpty()) {

    builder.append('[');

    boolean first = true;

    for (Property.Value<?> value : valueList) {
        Property<?> property = value.property();
        Comparable<?> selectedValue = value.value();

        if (!first) {
            builder.append(',');
        }

        first = false;

        builder.append(property.getName());
        builder.append('=');
        builder.append(selectedValue);
    }

    builder.append(']');
}

        return builder.toString();
    }

    /**
     * Parse a previously-serialized state string back into a live
     * BlockState, resolved against whatever block registry /
     * property set is active right now. If the block no longer
     * exists (e.g. a mod was removed), this returns null rather than
     * throwing, so callers can decide how to degrade (skip, log,
     * substitute barrier, etc) instead of crashing chunk generation.
     */
    public static BlockState parseState(String serialized) {

        try {

            net.minecraft.commands.arguments.blocks.BlockStateParser.BlockResult result =
                    net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(
                            net.minecraft.core.registries.BuiltInRegistries.BLOCK,
                            serialized,
                            false
                    );

            return result.blockState();

        } catch (Exception exception) {

            return null;
        }
    }

    @Override
    public String toString() {

        return "BlockDelta{" +
                "state=" + blockStateString +
                ", hasBlockEntity=" + (blockEntity != null) +
                "}";
    }
}
