package dev.rareradar.core.knowledge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Approximate share of natural spawns per species in a biome, following Cobblemon 1.8.2's selection:
 * <ol>
 *   <li>{@code Spawner.chooseBucket}: a bucket is drawn from {@code worldBuckets} in
 *       {@code data/cobblemon/spawning/best-spawner-config.json} (common 94, uncommon 5, rare 0.5, ultra-rare 0.2,
 *       boss 0.3), restricted to buckets used anywhere in the spawn pool — not to buckets that have a local candidate.
 *       If the drawn bucket has no candidate at the chosen location, that attempt spawns nothing.</li>
 *   <li>{@code FlatSpawnablePositionWeightedSelector}: within the bucket, a weighted choice by each entry's weight
 *       ({@code empty} entries take weight and spawn nothing).</li>
 * </ol>
 * Evaluated against a {@link Context}: biome lists (entry and preset), spawnable position type, {@code timeRange},
 * {@code canSeeSky}, sky-light bounds, {@code isRaining}, Y bounds, pool {@code enabled} and required mods. Entries
 * tied to a special location or item (structures, nearby/base blocks other than the "natural" preset, slime chunks,
 * Poké Snacks, lures, bait, rods, moon phase, lava, X bounds) are excluded unless {@link Context#includeSpecial}.
 * <b>Not</b> evaluated: weight multipliers, light level, block-level fluid checks, and any CobbleGalaxy server
 * overrides. Herd entries credit their weight evenly to their herd species. Treat results as a relative ranking.
 */
public final class SpawnProbability {
    private SpawnProbability() {}

    /** {@code worldBuckets} from best-spawner-config.json (Cobblemon 1.8.2). */
    public static final Map<String, Double> WORLD_BUCKETS;
    static {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("common", 94.0); m.put("uncommon", 5.0); m.put("rare", 0.5); m.put("ultra-rare", 0.2); m.put("boss", 0.3);
        WORLD_BUCKETS = java.util.Collections.unmodifiableMap(m);
        Map<String, Double> f = new LinkedHashMap<>();
        f.put("common", 83.25); f.put("uncommon", 11.25); f.put("rare", 4.125); f.put("ultra-rare", 1.375);
        FISHING_BUCKETS = java.util.Collections.unmodifiableMap(f);
    }

    /** {@code fishingBuckets} from the same file; used for the {@code fishing} position type. */
    public static final Map<String, Double> FISHING_BUCKETS;

    /** Named {@code TimeRange}s from Cobblemon's TimeRange.kt, as inclusive tick ranges. */
    static final Map<String, int[][]> TIME_RANGES = Map.ofEntries(
            Map.entry("any", new int[][]{{0, 23999}}),
            Map.entry("day", new int[][]{{23460, 23999}, {0, 12541}}),
            Map.entry("night", new int[][]{{12542, 23459}}),
            Map.entry("morning", new int[][]{{23000, 23999}, {0, 4999}}),
            Map.entry("noon", new int[][]{{5000, 6999}}),
            Map.entry("afternoon", new int[][]{{7000, 12999}}),
            Map.entry("evening", new int[][]{{13000, 16999}}),
            Map.entry("midnight", new int[][]{{17000, 18999}}),
            Map.entry("predawn", new int[][]{{19000, 22999}}),
            Map.entry("dawn", new int[][]{{22300, 23999}, {0, 166}}),
            Map.entry("dusk", new int[][]{{11834, 13701}}),
            Map.entry("twilight", new int[][]{{11834, 13701}, {22300, 23999}, {0, 166}}));

    public static final int NOON = 6000, MIDNIGHT = 18000;

    /**
     * Where and when the player is looking. Null fields are "don't care" (the condition is not evaluated).
     *
     * @param positionType {@code grounded}, {@code submerged}, {@code surface}, … (other types are ignored)
     * @param timeTick     time of day in ticks (0–23999)
     */
    public record Context(String positionType, Integer timeTick, Boolean canSeeSky, Integer skyLight, Boolean raining,
                          Integer y, boolean includeSpecial) {
        /** Open sky in clear weather on the ground at the given tick — the typical overworld hunt. */
        public static Context openSky(String positionType, Integer timeTick) {
            return new Context(positionType, timeTick, true, 15, false, null, false);
        }
        public static Context any(String positionType) { return new Context(positionType, null, null, null, null, null, false); }
    }

    /** Condition keys that tie a spawn to a structure, block set, item or event rather than to the biome. */
    static final Set<String> SPECIAL_KEYS = Set.of("structures", "neededNearbyBlocks", "neededBaseBlocks", "isSlimeChunk",
            "isPokeSnack", "minLureLevel", "maxLureLevel", "bait", "rodType", "moonPhase", "minX", "maxX", "minZ", "maxZ", "isThundering");

    /**
     * @param speciesId   {@code cobblemon:<name>}
     * @param share       expected fraction of successful spawns (for this position type) that are this species
     * @param perAttempt  probability that one spawn attempt yields this species (empty buckets/entries count as misses)
     * @param buckets     buckets the species was found in
     * @param viaHerdOnly true if every contributing entry was a herd (even split is an approximation)
     */
    public record Share(String speciesId, double share, double perAttempt, Set<String> buckets, boolean viaHerdOnly) {}

    public static List<Share> inBiome(KnowledgeBase kb, String biomeId, Collection<String> runtimeTags, Context ctx) {
        Set<String> refs = kb.biomeRefs(biomeId, runtimeTags);
        return compute(kb.spawnsInBiome(biomeId, runtimeTags), ctx, kb::preset, refs);
    }

    static List<Share> compute(List<SpawnEntry> entries, Context ctx) { return compute(entries, ctx, n -> null, Set.of()); }

    static List<Share> compute(List<SpawnEntry> entries, Context ctx, java.util.function.Function<String, String> presets, Set<String> biomeRefs) {
        Map<String, Double> bucketTotal = new HashMap<>();
        Map<String, Map<String, Double>> perSpecies = new HashMap<>();
        Map<String, Set<String>> buckets = new HashMap<>();
        Map<String, Boolean> herdOnly = new HashMap<>();
        for (SpawnEntry e : entries) {
            if (!eligible(e, ctx, presets, biomeRefs)) continue;
            double w = e.weight();
            bucketTotal.merge(e.bucket(), w, Double::sum);
            List<String> ids = new ArrayList<>();
            boolean herd = e.speciesId() == null;
            if (!herd) ids.add(e.speciesId());
            else ids.addAll(new LinkedHashSet<>(e.herdSpeciesIds()));
            for (String id : ids) {
                perSpecies.computeIfAbsent(id, k -> new HashMap<>()).merge(e.bucket(), w / ids.size(), Double::sum);
                buckets.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(e.bucket());
                herdOnly.merge(id, herd, Boolean::logicalAnd);
            }
        }
        Map<String, Double> bucketWeights = "fishing".equals(ctx.positionType()) ? FISHING_BUCKETS : WORLD_BUCKETS;
        double all = 0, present = 0;
        for (var b : bucketWeights.entrySet()) {
            all += b.getValue();
            if (bucketTotal.getOrDefault(b.getKey(), 0.0) > 0) present += b.getValue();
        }
        List<Share> out = new ArrayList<>();
        if (present == 0) return out;
        // Fraction of attempts that produce a Pokémon (non-empty entry) at all, for normalising to "share of spawns".
        double nonEmpty = 0;
        for (var s : perSpecies.values()) for (var bw : s.entrySet()) {
            Double pb = bucketWeights.get(bw.getKey());
            if (pb != null) nonEmpty += pb / all * bw.getValue() / bucketTotal.get(bw.getKey());
        }
        for (var s : perSpecies.entrySet()) {
            double p = 0;
            for (var bw : s.getValue().entrySet()) {
                Double pb = bucketWeights.get(bw.getKey());
                if (pb != null) p += pb / all * bw.getValue() / bucketTotal.get(bw.getKey());
            }
            if (p > 0) out.add(new Share(s.getKey(), p / nonEmpty, p, buckets.get(s.getKey()), herdOnly.get(s.getKey())));
        }
        out.sort(Comparator.comparingDouble(Share::share).reversed().thenComparing(Share::speciesId));
        return out;
    }

    static boolean eligible(SpawnEntry e, Context ctx, java.util.function.Function<String, String> presets, Set<String> biomeRefs) {
        if (e.weight() == null || e.weight() <= 0 || e.bucket() == null) return false;
        if (Boolean.FALSE.equals(e.poolEnabled()) || !e.neededInstalledMods().isEmpty()) return false;
        if (ctx.positionType() != null && e.positionType() != null && !ctx.positionType().equals(e.positionType())) return false;
        Effective eff = effective(e, presets);
        if (eff == null) return false;
        if (eff.special && !ctx.includeSpecial()) return false;
        JsonObject cond = eff.cond, anti = eff.anti;
        if (e.biomes().isEmpty() && cond.has("biomes") && !biomeRefs.isEmpty()) {
            boolean any = false;
            for (JsonElement b : cond.getAsJsonArray("biomes")) if (biomeRefs.contains(b.getAsString())) { any = true; break; }
            if (!any) return false;
        }
        if (ctx.timeTick() != null) {
            String t = str(cond, "timeRange");
            if (t != null && !inTimeRange(t, ctx.timeTick())) return false;
            String at = str(anti, "timeRange");
            if (at != null && inTimeRange(at, ctx.timeTick())) return false;
        }
        if (ctx.canSeeSky() != null && cond.has("canSeeSky") && cond.get("canSeeSky").getAsBoolean() != ctx.canSeeSky()) return false;
        if (ctx.skyLight() != null) {
            if (cond.has("minSkyLight") && ctx.skyLight() < cond.get("minSkyLight").getAsInt()) return false;
            if (cond.has("maxSkyLight") && ctx.skyLight() > cond.get("maxSkyLight").getAsInt()) return false;
        }
        if (ctx.raining() != null && cond.has("isRaining") && cond.get("isRaining").getAsBoolean() != ctx.raining()) return false;
        if (ctx.y() != null) {
            if (cond.has("minY") && ctx.y() < cond.get("minY").getAsInt()) return false;
            if (cond.has("maxY") && ctx.y() > cond.get("maxY").getAsInt()) return false;
        }
        return true;
    }

    private record Effective(JsonObject cond, JsonObject anti, boolean special) {}
    private static final Map<SpawnEntry, Effective> EFFECTIVE = java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    /** Preset conditions first, the entry's own keys override (as Cobblemon applies presets). Cached per entry. */
    private static Effective effective(SpawnEntry e, java.util.function.Function<String, String> presets) {
        Effective cached = EFFECTIVE.get(e);
        if (cached != null) return cached;
        JsonObject cond = new JsonObject(), anti = new JsonObject();
        boolean special = false;
        for (String name : e.presets()) {
            if (SPECIAL_PRESETS.contains(name)) special = true;
            if (TERRAIN_PRESETS.contains(name)) continue; // ordinary ground/water/leaves block checks: not location-specific
            String raw = presets.apply(name);
            if (raw == null) continue;
            JsonElement def = com.google.gson.JsonParser.parseString(raw);
            if (!def.isJsonObject()) continue;
            merge(cond, def.getAsJsonObject().get("condition"));
            merge(anti, def.getAsJsonObject().get("anticondition"));
        }
        merge(cond, e.condition());
        merge(anti, e.anticondition());
        for (String k : SPECIAL_KEYS) if (cond.has(k) && !isFalse(cond.get(k))) special = true;
        Effective eff = new Effective(cond, anti, special);
        EFFECTIVE.put(e, eff);
        return eff;
    }

    /** Presets whose only conditions are ordinary terrain blocks or fluids (not evaluated, not "special"). */
    static final Set<String> TERRAIN_PRESETS = Set.of("natural", "wild", "water", "foliage", "treetop");

    /** Presets that only apply at a structure or special block set ("natural", "water", "wild", "foliage" etc. are normal terrain). */
    static final Set<String> SPECIAL_PRESETS = Set.of("ancient_city", "derelict", "desert_pyramid", "end_city", "illager_structures",
            "jungle_pyramid", "lava", "mansion", "mansion_bedrooms", "mansion_dining", "nether_fossil", "nether_structures",
            "ocean_monument", "ocean_ruins", "pillager_outpost", "redstone", "ruined_portal", "saccharine_tree", "stronghold",
            "trail_ruins", "urban");

    private static void merge(JsonObject into, JsonElement from) {
        if (from == null || !from.isJsonObject()) return;
        for (var en : from.getAsJsonObject().entrySet()) into.add(en.getKey(), en.getValue());
    }

    private static boolean isFalse(JsonElement v) { return v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() && !v.getAsBoolean(); }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }

    /** Unknown names (or raw tick strings) are treated as "matches" so nothing is hidden on a guess. */
    static boolean inTimeRange(String name, int tick) {
        int[][] ranges = TIME_RANGES.get(name.toLowerCase(java.util.Locale.ROOT));
        if (ranges == null) return true;
        int t = Math.floorMod(tick, 24000);
        for (int[] r : ranges) if (t >= r[0] && t <= r[1]) return true;
        return false;
    }

    /** Every concrete biome id listed in a Cobblemon biome tag — the candidates for {@link #bestBiomes}. */
    public static Set<String> knownBiomes(KnowledgeBase kb) {
        Set<String> out = new java.util.TreeSet<>();
        for (BiomeTag t : kb.biomeTags().values()) for (BiomeTag.Value v : t.values()) if (!v.isTag()) out.add(v.ref());
        return out;
    }

    public record BiomeRank(String biomeId, String positionType, Share share) {}

    /**
     * Biomes ranked by the species' share of spawns. Only Cobblemon's own biome tags are known offline, so a biome
     * matched solely through a vanilla/modded runtime tag is missed; the in-game page passes live tags instead.
     */
    public static List<BiomeRank> bestBiomes(KnowledgeBase kb, String speciesIdOrName, Context base, int limit) {
        Map<String, Collection<String>> biomes = new LinkedHashMap<>();
        for (String b : knownBiomes(kb)) biomes.put(b, List.of());
        return bestBiomes(kb, speciesIdOrName, base, limit, biomes);
    }

    /** As above over the given biomes (id → runtime tags), e.g. the biomes registered on the connected server. */
    public static List<BiomeRank> bestBiomes(KnowledgeBase kb, String speciesIdOrName, Context base, int limit,
                                             Map<String, ? extends Collection<String>> biomes) {
        Species sp = kb.species(speciesIdOrName);
        if (sp == null) return List.of();
        Set<String> types = new LinkedHashSet<>();
        for (SpawnEntry e : kb.spawnsFor(sp.id())) if (e.positionType() != null) types.add(e.positionType());
        List<BiomeRank> out = new ArrayList<>();
        for (var bt : biomes.entrySet()) {
            String biome = bt.getKey();
            List<SpawnEntry> local = kb.spawnsInBiome(biome, bt.getValue());
            if (local.stream().noneMatch(e -> e.involves(sp.id()))) continue;
            Set<String> refs = kb.biomeRefs(biome, bt.getValue());
            for (String type : types) {
                if (base.positionType() != null && !base.positionType().equals(type)) continue;
                Context ctx = new Context(type, base.timeTick(), base.canSeeSky(), base.skyLight(), base.raining(), base.y(), base.includeSpecial());
                for (Share s : compute(local, ctx, kb::preset, refs)) {
                    if (s.speciesId().equals(sp.id())) { out.add(new BiomeRank(biome, type, s)); break; }
                }
            }
        }
        out.sort(Comparator.comparingDouble((BiomeRank r) -> r.share().share()).reversed().thenComparing(BiomeRank::biomeId));
        return out.size() > limit ? List.copyOf(out.subList(0, limit)) : out;
    }
}
