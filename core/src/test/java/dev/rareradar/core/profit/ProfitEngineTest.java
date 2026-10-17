package dev.rareradar.core.profit;

import dev.rareradar.core.store.AsyncWriter;
import dev.rareradar.core.util.Confidence;
import dev.rareradar.core.util.Estimate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProfitEngineTest {
    @TempDir Path dir;

    @Test void profitPerHourFormula() {
        // P = R - C = 10000 - 2000 = 8000 over 30 min → 16000/h
        Estimate e = ProfitEngine.perHour(List.of(ActivityRecord.of(Activity.HUNTS, 1, 30, 10_000, 0, 2_000)), true);
        assertEquals(16_000, e.value(), 1e-9);
        assertEquals(Confidence.LOW, e.confidence());
    }

    @Test void ratioEstimatorWeighsLongSessions() {
        List<ActivityRecord> l = List.of(
                ActivityRecord.of(Activity.HUNTS, 1, 10, 10_000, 0, 0),   // 60k/h but short
                ActivityRecord.of(Activity.HUNTS, 1, 110, 11_000, 0, 0)); // 6k/h but long
        assertEquals(21_000 / 2.0, ProfitEngine.perHour(l, true).value(), 1e-9);
    }

    @Test void realisedExcludesEstimates() {
        var r = ActivityRecord.of(Activity.RARE_HUNTING, 1, 60, 0, 50_000, 1_000);
        assertEquals(-1_000, ProfitEngine.perHour(List.of(r), true).value(), 1e-9);
        assertEquals(49_000, ProfitEngine.perHour(List.of(r), false).value(), 1e-9);
    }

    @Test void intervalNarrowsWithConsistentData() {
        List<ActivityRecord> l = new ArrayList<>();
        for (int i = 0; i < 30; i++) l.add(ActivityRecord.of(Activity.HUNTS, 1, 60, 10_000 + (i % 3) * 100, 0, 0));
        Estimate e = ProfitEngine.perHour(l, true);
        assertTrue(e.low() < e.value() && e.value() < e.high());
        assertTrue(e.high() - e.low() < 200);
        assertEquals(Confidence.HIGH, e.confidence());
    }

    @Test void recommendationExplainsAndFlagsOverlap() {
        List<ActivityRecord> log = new ArrayList<>();
        for (int i = 0; i < 10; i++) log.add(ActivityRecord.of(Activity.HUNTS, 100, 60, 60_000 + i * 500, 0, 0));
        for (int i = 0; i < 10; i++) log.add(ActivityRecord.of(Activity.RARE_HUNTING, 100, 60, i % 2 == 0 ? 0 : 40_000, 0, 1_000));
        var rec = ProfitEngine.recommend(ProfitEngine.measured(log, 0, true));
        assertEquals(Activity.HUNTS, rec.best().activity());
        assertTrue(rec.text().startsWith("Your recent results suggest"));
        assertTrue(rec.text().contains("clearly ahead"));
        assertFalse(rec.assumptions().isEmpty());

        List<ActivityRecord> noisy = new ArrayList<>();
        noisy.add(ActivityRecord.of(Activity.HUNTS, 100, 60, 10_000, 0, 0));
        noisy.add(ActivityRecord.of(Activity.HUNTS, 100, 60, 50_000, 0, 0));
        noisy.add(ActivityRecord.of(Activity.BREEDING, 100, 60, 25_000, 0, 0));
        noisy.add(ActivityRecord.of(Activity.BREEDING, 100, 60, 20_000, 0, 0));
        var rec2 = ProfitEngine.recommend(ProfitEngine.measured(noisy, 0, true));
        assertTrue(rec2.text().contains("not clearly better"));
    }

    @Test void noDataRecommendation() {
        var rec = ProfitEngine.recommend(List.of());
        assertNull(rec.best());
        assertTrue(rec.text().contains("Not enough data"));
    }

    @Test void logPersistsAndValidates() {
        ActivityLog log = new ActivityLog(dir.resolve("act.jsonl"), AsyncWriter.inline());
        log.add(ActivityRecord.of(Activity.BREEDING, 5, 30, 1, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> log.add(ActivityRecord.of(Activity.BREEDING, 5, 0, 1, 2, 3)));
        assertEquals(1, new ActivityLog(dir.resolve("act.jsonl"), AsyncWriter.inline()).all().size());
    }
}
