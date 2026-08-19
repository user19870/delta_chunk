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
     * config comment tells the user to enter. Entries that don't
     * actually look like a finished "namespace:path" ID (e.g. a
     * leftover stray entry from mid-edit, or one missing its colon)
     * are simply skipped here rather than rejected at input time --
     * see isPlausibleDimensionId's javadoc for why strict validation
     * had to move out of the text field's live validator.
     */
    public static boolean isExcluded(String dimension) {

        List<? extends String> excluded = EXCLUDED_DIMENSIONS.get();

        if (excluded == null || excluded.isEmpty()) {
            return false;
        }

        for (String entry : excluded) {

            if (entry == null) {
                continue;
            }

            String trimmed = entry.trim();

            if (!looksLikeFinishedDimensionId(trimmed)) {
                continue;
            }

            if (trimmed.equals(dimension)) {
                return true;
            }
        }

        return false;
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
     * Validation for entries in the config screen's text field.
     *
     * IMPORTANT: NeoForge's ConfigurationScreen re-runs this
     * validator on every keystroke against the in-progress text, not
     * just on the final committed value. A strict "must look like a
     * complete namespace:path" check (as an earlier version of this
     * method did) rejects every intermediate state while typing --
     * e.g. the moment after typing "minecraft" but before the ':' is
     * typed -- which made the text field appear to refuse input
     * entirely. This validator is therefore deliberately permissive:
     * it only rejects shapes that can NEVER be valid even as a
     * finished value (blank text, or text containing whitespace,
     * which no real ResourceLocation permits). The stricter
     * "does this actually look like namespace:path" check is applied
     * separately, at read time in isExcluded()/normalize(), where
     * being strict doesn't interfere with typing.
     */
    private static boolean isPlausibleDimensionId(Object candidate) {

        if (!(candidate instanceof String text)) {
            return false;
        }

        if (text.isEmpty()) {
            // Allow typing to start from an empty field.
            return true;
        }

        for (int i = 0; i < text.length(); i++) {

            if (Character.isWhitespace(text.charAt(i))) {
                return false;
            }
        }

        return true;
    }

    /**
     * Strict "is this a complete namespace:path shape" check, used
     * only when READING the config (isExcluded), never as the text
     * field's live input validator. Safe to be strict here since a
     * half-typed value simply won't match any real dimension yet,
     * rather than blocking the keystroke that would have completed
     * it.
     */
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