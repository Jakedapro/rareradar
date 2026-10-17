package dev.rareradar.core.knowledge;

import java.util.List;

/** A Cobblemon biome tag ({@code data/cobblemon/tags/worldgen/biome}). Values are biome ids or {@code #tag} refs. */
public record BiomeTag(String id, Boolean replace, List<Value> values, String sourceFile) {
    public record Value(String ref, boolean required) {
        public boolean isTag() { return ref.startsWith("#"); }
    }
}
