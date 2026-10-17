package dev.rareradar.core.spawn;

import java.util.ArrayList;
import java.util.List;

/**
 * A spawn condition imported from Cobblemon spawn-pool JSON (or a server datapack). Values are exactly as in the
 * source file; {@code source} records where it came from because servers can override default Cobblemon data.
 */
public final class SpawnRule {
    public String id, species, bucket, context, positionType, level, timeRange, source;
    public double weight;
    public List<String> biomes = new ArrayList<>(), excludedBiomes = new ArrayList<>(), presets = new ArrayList<>();
    public Boolean raining, thundering, canSeeSky;
    public Integer minY, maxY, minSkyLight, maxSkyLight;
    public List<String> otherConditions = new ArrayList<>();

    /** Whether this rule could apply in a biome, given its id and the tags it carries (e.g. "#cobblemon:is_forest"). */
    public boolean biomeAllowed(java.util.Set<String> biomeIdAndTags) {
        for (String b : excludedBiomes) if (biomeIdAndTags.contains(b)) return false;
        if (biomes.isEmpty()) return true;
        for (String b : biomes) if (biomeIdAndTags.contains(b)) return true;
        return false;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(bucket == null ? "?" : bucket).append(" · ").append(biomes.isEmpty() ? "any biome" : String.join(", ", biomes));
        if (timeRange != null) sb.append(" · time ").append(timeRange);
        if (Boolean.TRUE.equals(raining)) sb.append(" · rain");
        if (Boolean.TRUE.equals(thundering)) sb.append(" · thunder");
        if (minY != null || maxY != null) sb.append(" · y ").append(minY == null ? "…" : minY).append("–").append(maxY == null ? "…" : maxY);
        if (Boolean.TRUE.equals(canSeeSky)) sb.append(" · open sky");
        if (Boolean.FALSE.equals(canSeeSky)) sb.append(" · covered");
        if (positionType != null) sb.append(" · ").append(positionType);
        if (!otherConditions.isEmpty()) sb.append(" · ").append(String.join(", ", otherConditions));
        return sb.toString();
    }
}
