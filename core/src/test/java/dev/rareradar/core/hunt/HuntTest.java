package dev.rareradar.core.hunt;

import dev.rareradar.core.rarity.PokemonObservation;
import dev.rareradar.core.store.AsyncWriter;
import dev.rareradar.core.util.Confidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HuntTest {
    static final long NOW = 1_800_000_000_000L;
    @TempDir Path dir;

    @Test void overlayParsing() {
        HuntObjective h = OverlayHuntParser.parse("★★ Medium", "Catch Pikachu", "cobblemon:pikachu", List.of("shiny"),
                List.of("Progress: 2/5", "Reward: 12 Hunt Points, $5k"), 3_600_000, NOW);
        assertEquals("pikachu", h.species);
        assertEquals(2, h.progress);
        assertEquals(5, h.required);
        assertEquals(12.0, h.rewardHuntPoints);
        assertEquals(5_000.0, h.rewardMoney);
        assertEquals(2, h.stars);
        assertEquals(NOW + 3_600_000, h.deadline);
        assertTrue(h.requiresShiny());
    }

    @Test void overlayParsingLeavesUnknownsUnknown() {
        HuntObjective h = OverlayHuntParser.parse("Daily", "Catch a Zubat", "zubat", List.of(), List.of("Somewhere dark"), 0, NOW);
        assertNull(h.rewardHuntPoints);
        assertNull(h.rewardMoney);
        assertNull(h.deadline);
        assertEquals(1, h.required);
        assertEquals(0, h.stars);
    }

    @Test void matchingRespectsShinyAndRegionalAspects() {
        HuntObjective h = OverlayHuntParser.parse("g", "t", "vulpix", List.of("alolan"), List.of(), 0, NOW);
        assertFalse(h.matches(PokemonObservation.of("vulpix")));
        assertTrue(h.matches(PokemonObservation.of("vulpix").form("alolan")));
        HuntObjective s = HuntObjective.manual("shiny", "zubat", 1, NOW);
        s.aspects.add("shiny");
        assertFalse(s.matches(PokemonObservation.of("zubat")));
        assertTrue(s.matches(PokemonObservation.of("zubat").shiny(true)));
    }

    @Test void overlaySyncMergesAndCompletes() {
        HuntTracker t = new HuntTracker(dir.resolve("h.jsonl"), AsyncWriter.inline());
        HuntObjective a = OverlayHuntParser.parse("g", "Catch 3 Zubat", "zubat", List.of(), List.of("0/3"), 0, NOW);
        assertTrue(t.syncOverlay(List.of(a), NOW));
        HuntObjective a2 = OverlayHuntParser.parse("g", "Catch 3 Zubat", "zubat", List.of(), List.of("3/3"), 0, NOW + 1000);
        t.syncOverlay(List.of(a2), NOW + 1000);
        assertEquals(1, t.all().size());
        assertEquals(NOW + 1000, t.all().get(0).completedAt);
        assertEquals(1, new HuntTracker(dir.resolve("h.jsonl"), AsyncWriter.inline()).completed().size());
    }

    @Test void overlayKeyIgnoresProgressAndStaleHuntsExpire() {
        HuntTracker t = new HuntTracker(dir.resolve("k.jsonl"), AsyncWriter.inline());
        t.syncOverlay(List.of(OverlayHuntParser.parse("Daily", "Catch Zubat (1/3)", "zubat", List.of(), List.of(), 0, NOW)), NOW);
        t.syncOverlay(List.of(OverlayHuntParser.parse("Daily", "Catch Zubat (2/3)", "zubat", List.of(), List.of(), 0, NOW + 1)), NOW + 1);
        assertEquals(1, t.all().size());
        assertEquals(2, t.all().get(0).progress);
        assertEquals(1, t.active(NOW + 1000).size());
        assertEquals(0, t.active(NOW + HuntObjective.OVERLAY_STALE_MS + 10).size(), "unpinned overlay hunt goes stale");
        HuntObjective manual = HuntObjective.manual("m", "x", 1, NOW);
        assertTrue(manual.active(NOW + HuntObjective.OVERLAY_STALE_MS * 10), "manual hunts never go stale");
    }

    @Test void completedOverlayHuntIsNotResurrected() {
        HuntTracker t = new HuntTracker(dir.resolve("r.jsonl"), AsyncWriter.inline());
        t.syncOverlay(List.of(OverlayHuntParser.parse("Daily", "Catch Zubat", "zubat", List.of(), List.of("0/1"), 0, NOW)), NOW);
        t.complete(t.all().get(0).id, NOW + 10);
        t.syncOverlay(List.of(OverlayHuntParser.parse("Daily", "Catch Zubat", "zubat", List.of(), List.of("0/1"), 0, NOW + 20)), NOW + 20);
        assertEquals(1, t.all().size(), "still listed by the overlay, but already completed by the user");
        long nextDay = NOW + HuntTracker.RESURRECT_AFTER_MS + 100;
        t.syncOverlay(List.of(OverlayHuntParser.parse("Daily", "Catch Zubat", "zubat", List.of(), List.of("0/1"), 0, nextDay)), nextDay);
        assertEquals(2, t.all().size(), "the same daily hunt can come back later");
    }

    @Test void manualProgress() {
        HuntTracker t = new HuntTracker(dir.resolve("h.jsonl"), AsyncWriter.inline());
        HuntObjective h = HuntObjective.manual("x", "zubat", 2, NOW);
        t.add(h);
        t.progress(h.id, 1, NOW);
        assertEquals(1, t.matching(PokemonObservation.of("zubat"), NOW).size());
        t.progress(h.id, 5, NOW + 5);
        assertEquals(NOW + 5, t.all().get(0).completedAt);
        assertTrue(t.matching(PokemonObservation.of("zubat"), NOW).isEmpty());
    }

    @Test void completionTimeFromRates() {
        HuntObjective h = HuntObjective.manual("x", "zubat", 3, NOW);
        HuntOptimizer.Inputs in = new HuntOptimizer.Inputs();
        in.catchAttempts = 98; in.catchSuccesses = 98; // p ≈ 0.99
        in.rate = x -> new HuntOptimizer.RateInput(59, 10, "in cave"); // λ = 5.95/h
        var t = HuntOptimizer.completionMinutes(h, in);
        assertEquals(60.0 * 3 / (5.95 * 0.99), t.value(), 0.5);
        assertTrue(t.low() < t.value() && t.value() < t.high());
    }

    @Test void unknownRateIsNotInvented() {
        HuntObjective h = HuntObjective.manual("x", "mew", 1, NOW);
        assertFalse(HuntOptimizer.completionMinutes(h, new HuntOptimizer.Inputs()).known());
    }

    @Test void rankingPrefersBetterRewardPerTime() {
        HuntObjective fast = HuntObjective.manual("fast", "zubat", 1, NOW);
        fast.rewardMoney = 1_000.0;
        HuntObjective slow = HuntObjective.manual("slow", "gible", 1, NOW);
        slow.rewardMoney = 2_000.0;
        HuntOptimizer.Inputs in = new HuntOptimizer.Inputs();
        in.rate = x -> x.species.equals("zubat") ? new HuntOptimizer.RateInput(100, 10, "") : new HuntOptimizer.RateInput(5, 10, "");
        var ranked = HuntOptimizer.rank(List.of(slow, fast), in);
        assertEquals("fast", ranked.get(0).hunt().title);
        assertTrue(ranked.get(0).valuePerHour().known());
    }

    @Test void huntPointGoal() {
        HuntPointGoal g = new HuntPointGoal();
        assertEquals(150, g.cost);
        g.currentPoints = 30;
        assertEquals(120, g.remaining());
        assertFalse(g.project(List.of()).huntsNeeded().known());
        HuntObjective a = HuntObjective.manual("a", "x", 1, NOW); a.rewardHuntPoints = 10.0; a.completedAt = NOW + 600_000;
        HuntObjective b = HuntObjective.manual("b", "x", 1, NOW); b.rewardHuntPoints = 20.0; b.completedAt = NOW + 1_200_000;
        var p = g.project(List.of(a, b));
        assertEquals(8, p.huntsNeeded().value()); // 120 / 15
        assertEquals(Confidence.LOW, p.huntsNeeded().confidence());
        assertTrue(p.minutesNeeded().known());
    }
}
