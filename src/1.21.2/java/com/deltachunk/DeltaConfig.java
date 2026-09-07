package com.deltachunk;

import net.neoforged.neoforge.common.ModConfigSpec;

 
import java.util.Set;

 
public final class DeltaConfig {

    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.ConfigValue<String> EXCLUDED_DIMENSIONS;
    
//註冊
    static {

        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("dimensions");

        EXCLUDED_DIMENSIONS =
                builder
                        .comment(
                                "Dimension IDs (e.g. \"minecraft:the_end\", " +
                                "\"twilightforest:twilight_forest\") that DeltaChunk should " +
                                "leave completely alone: no block changes are " +
                                "recorded for these dimensions, and their " +
                                "region files are never compacted. Use the " +
                                "full namespaced ID, exactly as it appears in " +
                                "the F3 debug screen's dimension line."
                        )
                        .translation(
                                "config.deltachunk.excluded_dimensions"
                        )
                        .define("excluded_dimensions", "twilightforest:twilight_forest");

        builder.pop();

        SPEC = builder.build();
    }

    private DeltaConfig() {
    }

 //維度是否被排除
    public static boolean isExcluded(String dimension) {
    String value = EXCLUDED_DIMENSIONS.get();

    if (value == null || value.isBlank()) {
        return false;
    }

    for (String entry : value.split(",")) {
        String trimmed = entry.trim();

        if (looksLikeFinishedDimensionId(trimmed)
                && trimmed.equals(dimension)) {
            return true;
        }
    }

    return false;
}

  //被排除維度
    public static Set<String> excludedDimensionsSnapshot() {

    String value = EXCLUDED_DIMENSIONS.get();

    if (value == null || value.isBlank()) {
        return Set.of();
    }

    Set<String> result = new java.util.HashSet<>();

    for (String entry : value.split(",")) {

        String trimmed = entry.trim();

        if (looksLikeFinishedDimensionId(trimmed)) {
            result.add(trimmed);
        }
    }

    return Set.copyOf(result);
}

  
    

     //是不是完整維度id
    private static boolean looksLikeFinishedDimensionId(String text) {

        if (text.isEmpty()) {
            return false;
        }

        int colonIndex = text.indexOf(':');

        if (colonIndex <= 0 || colonIndex == text.length() - 1) {
            return false;
        }

        return text.indexOf(':', colonIndex + 1) == -1;
    }
}