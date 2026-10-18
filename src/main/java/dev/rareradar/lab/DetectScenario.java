package dev.rareradar.lab;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.rareradar.RadarScanner;
import dev.rareradar.RareRadarClient;
import dev.rareradar.Settings;
import dev.rareradar.core.lab.*;
import dev.rareradar.core.rarity.PokemonObservation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Compares what the radar tracks with the Pokémon entities that actually exist in the client world.
 * Ground truth = wild (unowned) PokemonEntity instances within the scan range; nothing is invented.
 * Species/shiny are read from the same Cobblemon fields the scanner uses, so those checks catch caching/refresh
 * faults, not wrong Cobblemon data. If no wild Pokémon are in range the test is SKIPPED, never passed.
 */
final class DetectScenario implements Scenario {
    private static final int WARMUP_TICKS = 40, CHECK_EVERY = 10, TOTAL_TICKS = 140;
    private final Map<Integer, Integer> firstSeenLab = new HashMap<>(), firstTracked = new HashMap<>();
    private Set<Integer> previousTruth = new HashSet<>();
    private int truthMax, trackedMax, missingChecks, extraChecks, speciesMismatch, shinyMismatch, despawns, spawns, checks;
    private int lastMissingCount, lastExtraCount;
    private double maxDistErr, scannerMsSum;

    public String id() { return "DETECT_SCAN"; }
    public String idPrefix() { return "DET"; }
    public String category() { return "detection"; }
    public String description() { return "Radar tracking vs wild Pokémon entities that actually exist in range"; }
    public Layer layer() { return Layer.IN_GAME; }
    public String expected() { return "Every wild Pokémon in range is tracked within ~2 scan intervals, none are phantom, species/shiny agree"; }

    public void begin(TestContext c) {
        c.test.timeoutTicks = 20 * 30;
    }

    public Step tick(TestContext c) {
        if (!c.host.environment().cobblemonPresent()) return Step.skip("Cobblemon is not installed");
        if (!RareRadarClient.enabled) return Step.skip("radar is switched off (press J) — nothing to measure");
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return Step.fail(FailureClass.INVALID_ENTITY_STATE, "world or player missing", "");
        if (c.ticks % CHECK_EVERY == 0) check(c, mc);
        if (c.ticks < TOTAL_TICKS) return Step.RUNNING;

        c.metric("truth_max", truthMax);
        c.metric("tracked_max", trackedMax);
        c.metric("checks", checks);
        c.metric("missing_checks", missingChecks);
        c.metric("phantom_checks", extraChecks);
        c.metric("species_mismatch", speciesMismatch);
        c.metric("shiny_mismatch", shinyMismatch);
        c.metric("spawns_seen", spawns);
        c.metric("despawns_seen", despawns);
        c.metric("max_distance_error_blocks", Math.round(maxDistErr * 100) / 100.0);
        c.metric("scanner_ms_avg", checks == 0 ? 0 : Math.round(scannerMsSum / checks * 1000) / 1000.0);
        long latMax = 0;
        for (var e : firstTracked.entrySet()) latMax = Math.max(latMax, e.getValue() - firstSeenLab.getOrDefault(e.getKey(), e.getValue()));
        c.metric("spawn_to_track_latency_ticks_max", latMax);

        if (truthMax == 0) return Step.skip("no wild Pokémon within " + Settings.S.scanRange + " blocks: nothing to compare (not a pass)");
        // False negatives are judged only on entities still untracked at the end: scan interval lag is expected.
        if (lastMissingCount > 0 || speciesMismatch > 0 || shinyMismatch > 0 || lastExtraCount > 0)
            return Step.fail(FailureClass.DETECTION_MISMATCH, String.format("missing(final)=%d phantom(final)=%d species_mismatch=%d shiny_mismatch=%d over %d checks",
                    lastMissingCount, lastExtraCount, speciesMismatch, shinyMismatch, checks), "see detection.jsonl");
        return Step.pass(String.format("%d wild Pokémon in range at peak; all tracked, %d checks", truthMax, checks));
    }

    private void check(TestContext c, MinecraftClient mc) {
        double range = Settings.S.scanRange, r2 = range * range;
        Map<Integer, PokemonEntity> truth = new HashMap<>();
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof PokemonEntity pe) || pe.getOwnerUuid() != null || e.squaredDistanceTo(mc.player) > r2) continue;
            truth.put(e.getId(), pe);
            firstSeenLab.putIfAbsent(e.getId(), c.ticks);
        }
        Set<Integer> tracked = new HashSet<>();
        for (int id : RadarScanner.trackedIds()) tracked.add(id);
        for (int id : tracked) firstTracked.putIfAbsent(id, c.ticks);
        for (int id : truth.keySet()) if (!previousTruth.contains(id) && c.ticks > 0) spawns++;
        for (int id : previousTruth) if (!truth.containsKey(id)) despawns++;
        previousTruth = new HashSet<>(truth.keySet());
        truthMax = Math.max(truthMax, truth.size());
        trackedMax = Math.max(trackedMax, tracked.size());
        scannerMsSum += RadarScanner.lastScanNanos / 1e6;
        if (c.ticks < WARMUP_TICKS) return;
        checks++;

        Set<Integer> missing = new HashSet<>(truth.keySet());
        missing.removeAll(tracked);
        Set<Integer> phantom = new HashSet<>(tracked);
        phantom.removeAll(truth.keySet());
        lastMissingCount = missing.size();
        lastExtraCount = phantom.size();
        if (!missing.isEmpty()) missingChecks++;
        if (!phantom.isEmpty()) extraChecks++;
        int sp = 0, sh = 0;
        for (var e : truth.entrySet()) {
            PokemonObservation o = RadarScanner.trackedObservation(e.getKey());
            if (o == null) continue;
            Pokemon p = e.getValue().getPokemon();
            if (p == null) continue;
            String species = PokemonObservation.norm(p.getSpecies().getResourceIdentifier().getPath());
            if (!species.equals(o.species)) { sp++; speciesMismatch++; }
            if (p.getShiny() != o.isShiny()) { sh++; shinyMismatch++; }
        }
        for (RadarScanner.Hit h : RadarScanner.hits()) {
            double now = Math.sqrt(h.entity.squaredDistanceTo(mc.player));
            maxDistErr = Math.max(maxDistErr, Math.abs(now - h.distance));
        }
        c.domain("detection.jsonl", "detection_check", "truth", truth.size(), "tracked", tracked.size(), "missing", missing.size(),
                "phantom", phantom.size(), "species_mismatch", sp, "shiny_mismatch", sh, "scanner_ms", Math.round(RadarScanner.lastScanNanos / 1e3) / 1e3);
    }
}
