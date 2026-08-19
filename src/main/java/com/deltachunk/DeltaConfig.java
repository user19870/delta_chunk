package com.deltachunk;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;
import java.util.Set;

/**
 * DeltaChunk's configuration: currently just the list of dimension
 * IDs to exclude from WAM tracking and region compaction entirely.
 *
 * An excluded dimension is left completely alone by this mod: no
 * block changes are recorded into DeltaIndex for it, and
 * RegionCompactor is never run against its region files at unload
 * time. This is useful for dimensions where regeneration would be
 * unsafe or undesirable even in principle -- e.g. a mod's "personal
 * storage" dimension that isn't really generated terrain at all, or
 * a dimension the player wants to keep fully intact for performance/
 * compatibility testing without needing to trust WAM's replay path
 * for it.
 *
 * Registered as a COMMON config (loaded on both physical client and
 * physical server, not synced over network) since this is a
 * single-player-oriented mod and the "server" here is always the
 * integrated server for the local player's own world; there is no
 * multiplayer trust boundary that would require this to be a SERVER
 * config instead.
 */
public final class DeltaConfig {

    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.ConfigValue<List<? extends String>> EXCLUDED_DIMENSIONS;

    static {

        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("dimensions");

        EXCLUDED_DIMENSIONS =
                builder
                        .comment(
                                "Dimension IDs (e.g. \"minecraft:the_end\", " +
                                "\"aether:the_aether\") that DeltaChunk should " +
                                "leave completely alone: no block changes are " +
                                "recorded for these dimensions, and their " +
                                "region files are never compacted. Use the " +
                                "full namespaced ID, exactly as it appears in " +
                                "the F3 debug screen's dimension line."
                        )
                        .translation(
                                "config.deltachunk.excluded_dimensions"
                        )
                        .defineListAllowEmpty(
                                "excluded_dimensions",
                                List.<String>of(),
                                DeltaConfig::isPlausibleDimensionId
                        );

        builder.pop();

        SPEC = builder.build();
    }

    private DeltaConfig() {
    }

    /**
     * @return true if {@code dimension} (as produced by
     * ServerLevel.dimension().location().toString(), e.g.
     * "minecraft:overworld") is in the configured exclusion list.
     * Comparison is a plain exact string match against whatever the
     * user typed in the config -- no wildcard/namespace-only
     * matching, to keep behavior predictable and match what the
     * config comment tells the user to enter.
     */
    public static boolean isExcluded(String dimension) {

        List<? extends String> excluded = EXCLUDED_DIMENSIONS.get();

        if (excluded == null || excluded.isEmpty()) {
            return false;
        }

        return excluded.contains(dimension);
    }

    /**
     * @return an immutable snapshot of the currently configured
     * exclusion list, for diagnostics/logging use (e.g. printing
     * what's excluded at startup).
     */
    public static Set<String> excludedDimensionsSnapshot() {

        List<? extends String> excluded = EXCLUDED_DIMENSIONS.get();

        if (excluded == null) {
            return Set.of();
        }

        return Set.copyOf(excluded);
    }

    /**
     * Loose validation for entries typed into the config screen: must
     * be non-blank and contain a single ':' separating namespace from
     * path, matching the shape of every real dimension ID. This is
     * intentionally not a hard registry lookup -- the config can be
     * edited/loaded before other mods' dimensions are registered, and
     * a config value shouldn't be rejected just because, say, a
     * dimension-adding mod is temporarily uninstalled.
     */
    private static boolean isPlausibleDimensionId(Object candidate) {

        if (!(candidate instanceof String text)) {
            return false;
        }

        String trimmed = text.trim();

        if (trimmed.isEmpty()) {
            return false;
        }

        int colonIndex = trimmed.indexOf(':');

        if (colonIndex <= 0 || colonIndex == trimmed.length() - 1) {
            return false;
        }

        if (trimmed.indexOf(':', colonIndex + 1) != -1) {
            // More than one ':' -- not a valid namespace:path shape.
            return false;
        }

        return true;
    }
}