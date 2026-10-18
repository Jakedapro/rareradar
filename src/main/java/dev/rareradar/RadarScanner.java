package dev.rareradar;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.rareradar.core.hunt.HuntObjective;
import dev.rareradar.core.market.Valuation;
import dev.rareradar.core.rarity.PokemonObservation;
import dev.rareradar.core.rarity.RarityResult;
import dev.rareradar.core.rarity.TargetRanker;
import dev.rareradar.core.spawn.EncounterRecord;
import dev.rareradar.core.spawn.TeleportDetector;
import dev.rareradar.core.spawn.TimeBucket;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Rate-limited radar scan with a per-entity classification cache. Replaces V1's per-tick full scan and per-frame
 * classification from the outline mixins (which allocated a HashSet per entity per frame). The mixins now do an
 * O(1) primitive map lookup via {@link #color(int)}.
 */
public final class RadarScanner {
    public static final class Hit {
        public final PokemonEntity entity;
        public final PokemonObservation obs;
        public final RarityResult rarity;
        public double distance;
        public boolean huntTarget;
        Valuation value;   // computed lazily, once per entity
        Hit(PokemonEntity e, PokemonObservation o, RarityResult r) { entity = e; obs = o; rarity = r; }
    }

    private static final class Cached {
        final PokemonObservation obs;
        final RarityResult rarity;
        final boolean owned;
        final long at;
        Valuation value;
        Cached(PokemonObservation o, RarityResult r, boolean owned, long at) { obs = o; rarity = r; this.owned = owned; this.at = at; }
    }

    private static final Int2ObjectOpenHashMap<Cached> cache = new Int2ObjectOpenHashMap<>();
    private static final Int2IntOpenHashMap colors = new Int2IntOpenHashMap();
    private static final IntOpenHashSet seenThisScan = new IntOpenHashSet();
    private static List<Hit> hits = List.of();
    private static int tickCounter;
    private static List<HuntObjective> activeHunts = List.of();
    private static final TeleportDetector teleport = new TeleportDetector(96);
    public static long localSince = System.currentTimeMillis();
    public static String currentBiome = "unknown";
    public static TimeBucket currentTime = TimeBucket.UNKNOWN;
    /** Tags of the current biome ("#namespace:path"), refreshed when the biome changes. */
    public static java.util.Set<String> currentBiomeTags = java.util.Set.of();
    /** Live spawn-condition context at the player (for the knowledge-base spawn model); null until the first scan. */
    public static volatile dev.rareradar.core.knowledge.SpawnProbability.Context spawnContext;
    private static int teleportNoticeIn = -1;

    // Benchmarks (exposed on the dashboard)
    public static long lastScanNanos, maxScanNanos, scans, totalScanNanos;

    static { colors.defaultReturnValue(-1); }

    private RadarScanner() {}

    public static List<Hit> hits() { return hits; }

    /** Entity ids the scanner currently tracks (any rarity), for RareRadar Lab ground-truth comparison. */
    public static int[] trackedIds() { return cache.keySet().toIntArray(); }

    /** Cached observation for a tracked entity id, or null. */
    public static PokemonObservation trackedObservation(int id) { Cached c = cache.get(id); return c == null ? null : c.obs; }

    /** Outline colour for an entity id, or -1. Called from render mixins; allocation-free. */
    public static int color(int entityId) { return colors.get(entityId); }

    public static void clear() {
        cache.clear();
        colors.clear();
        hits = List.of();
        teleport.reset();
    }

    public static void tick(MinecraftClient client) {
        if (client.world == null || client.player == null) { if (!hits.isEmpty() || !colors.isEmpty()) clear(); return; }
        Galaxy g = Galaxy.get();
        Vec3d pos = client.player.getPos();
        String dim = client.world.getRegistryKey().getValue().toString();
        if (teleport.update(dim, pos.x, pos.z)) onTeleport(client, g);
        if (teleportNoticeIn > 0 && --teleportNoticeIn == 0) announceArea(client);
        if (++tickCounter % g.cfg.scanIntervalTicks != 0) return;
        if (!RareRadarClient.enabled) { if (!colors.isEmpty()) { colors.clear(); hits = List.of(); } return; }
        long t0 = System.nanoTime();
        try {
            scan(client, g, pos);
        } finally {
            long dt = System.nanoTime() - t0;
            lastScanNanos = dt; totalScanNanos += dt; scans++;
            if (dt > maxScanNanos) maxScanNanos = dt;
        }
    }

    private static void scan(MinecraftClient client, Galaxy g, Vec3d me) {
        long now = System.currentTimeMillis();
        if (tickCounter % 40 == 0 || activeHunts.isEmpty()) activeHunts = g.hunts.active(now);
        BlockPos bp = client.player.getBlockPos();
        var biomeEntry = client.world.getBiome(bp);
        String biome = biomeEntry.getKey().map(k -> k.getValue().toString()).orElse("unknown");
        if (!biome.equals(currentBiome)) {
            java.util.Set<String> tags = new java.util.HashSet<>();
            biomeEntry.streamTags().forEach(t -> tags.add("#" + t.id()));
            currentBiomeTags = tags;
        }
        currentBiome = biome;
        currentTime = TimeBucket.of(client.world.getTimeOfDay());
        spawnContext = new dev.rareradar.core.knowledge.SpawnProbability.Context(null, (int) Math.floorMod(client.world.getTimeOfDay(), 24000L),
                client.world.isSkyVisible(bp.up()), client.world.getLightLevel(net.minecraft.world.LightType.SKY, bp.up()),
                client.world.isRaining(), bp.getY(), false);
        g.exposure.add(currentBiome, currentTime, g.cfg.scanIntervalTicks / 20.0);
        g.sessions.touch(now);

        double range = Settings.S.scanRange, r2 = range * range;
        seenThisScan.clear();
        List<Hit> out = new ArrayList<>();
        for (Entity e : client.world.getEntities()) {
            if (!(e instanceof PokemonEntity pe)) continue;
            double d2 = e.squaredDistanceTo(me);
            if (d2 > r2) continue;
            int id = e.getId();
            seenThisScan.add(id);
            boolean owned = pe.getOwnerUuid() != null;
            Cached c = cache.get(id);
            // Wild species/shiny state doesn't change while loaded; refresh only on ownership change or every 30 s
            // (the demand term can move as market data arrives).
            if (c == null || c.owned != owned || now - c.at > 30_000) {
                PokemonObservation o = CobblemonCompat.observe(pe);
                if (o == null) { colors.remove(id); continue; }
                o.x = e.getX(); o.y = e.getY(); o.z = e.getZ();
                o.biome = currentBiome;
                o.dimension = client.world.getRegistryKey().getValue().toString();
                o.timeOfDay = client.world.getTimeOfDay();
                o.weather = client.world.isThundering() ? "thunder" : client.world.isRaining() ? "rain" : "clear";
                o.timestamp = now;
                boolean first = c == null;
                c = new Cached(o, g.rarity.classify(o), owned, now);
                cache.put(id, c);
                if (first && !owned) recordEncounter(g, pe, c, now);
            }
            boolean huntTarget = false;
            if (!owned && g.cfg.showHuntMarkers) for (HuntObjective h : activeHunts) if (h.remaining() > 0 && h.matches(c.obs)) { huntTarget = true; break; }
            if (c.rarity.qualifies() || huntTarget) {
                Hit h = new Hit(pe, c.obs, c.rarity);
                h.distance = Math.sqrt(d2);
                h.huntTarget = huntTarget;
                h.value = c.value;
                out.add(h);
                colors.put(id, huntTarget && !c.rarity.qualifies() ? 0x7CFC00 : c.rarity.primary().color);
            } else {
                colors.remove(id);
            }
        }
        // Drop entities that unloaded or left range.
        if (cache.size() > seenThisScan.size()) {
            // Explicit iterator: fastutil's removeIf overloads are ambiguous with a lambda.
            IntIterator it = cache.keySet().iterator();
            while (it.hasNext()) {
                int id = it.nextInt();
                if (!seenThisScan.contains(id)) { it.remove(); colors.remove(id); }
            }
        }
        double half = g.cfg.distanceHalfLife;
        out.sort((a, b) -> {
            if (a.huntTarget != b.huntTarget) return a.huntTarget ? -1 : 1;
            int w = Integer.compare(b.rarity.watchPriority(), a.rarity.watchPriority());
            if (w != 0) return w;
            return Double.compare(TargetRanker.utility(b.rarity, b.distance, half), TargetRanker.utility(a.rarity, a.distance, half));
        });
        hits = Collections.unmodifiableList(out);
    }

    /** Value estimate for a hit (computed on first request, then cached with the entity). */
    public static Valuation value(Hit h) {
        if (h.value == null) {
            h.value = Galaxy.get().valuator.value(h.obs, System.currentTimeMillis());
            Cached c = cache.get(h.entity.getId());
            if (c != null) c.value = h.value;
        }
        return h.value;
    }

    private static void recordEncounter(Galaxy g, PokemonEntity pe, Cached c, long now) {
        if (!g.cfg.recordEncounters) return;
        EncounterRecord r = new EncounterRecord();
        r.ts = now;
        r.species = c.obs.species;
        r.form = c.obs.form;
        r.shiny = c.obs.isShiny();
        r.rarity = c.rarity.qualifies() ? c.rarity.primary().label : null;
        r.target = c.rarity.watchPriority() > 0;
        r.biome = c.obs.biome;
        r.dimension = c.obs.dimension;
        r.weather = c.obs.weather;
        r.time = TimeBucket.of(c.obs.timeOfDay);
        r.x = (int) c.obs.x; r.y = (int) c.obs.y; r.z = (int) c.obs.z;
        if (g.encounters.record(pe.getUuid(), r)) g.sessions.encounter(r.rarity != null, r.shiny, r.biome);
    }

    private static void onTeleport(MinecraftClient client, Galaxy g) {
        // Manually initiated travel (e.g. /wild, /home, warps) or a dimension change: reset local stats and rescan.
        cache.clear();
        colors.clear();
        hits = List.of();
        localSince = System.currentTimeMillis();
        tickCounter = g.cfg.scanIntervalTicks - 1; // scan on the next tick
        teleportNoticeIn = 60;                    // let chunks/entities arrive before summarising
        RareRadarClient.LOG.info("Teleport detected; local statistics reset");
    }

    private static void announceArea(MinecraftClient client) {
        if (client.player == null) return;
        int n = hits.size();
        String best = n == 0 ? "none yet" : hits.get(0).obs.species + (hits.get(0).obs.isShiny() ? " (shiny)" : "");
        client.player.sendMessage(Text.literal("§bRareRadar§r: new area " + currentBiome + " — " + n + " qualifying Pokémon nearby, top: " + best), false);
    }
}
