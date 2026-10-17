package dev.rareradar.core.report;

import dev.rareradar.core.dex.DexPlanner;
import dev.rareradar.core.hunt.HuntObjective;
import dev.rareradar.core.hunt.HuntOptimizer;
import dev.rareradar.core.hunt.HuntPointGoal;
import dev.rareradar.core.market.*;
import dev.rareradar.core.profit.ActivityRecord;
import dev.rareradar.core.profit.Activity;
import dev.rareradar.core.profit.ProfitEngine;
import dev.rareradar.core.stats.SessionRecord;
import dev.rareradar.core.store.AsyncWriter;
import dev.rareradar.core.util.Estimate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DashboardTextTest {
    @TempDir Path dir;

    static String all(List<Line> l) { StringBuilder sb = new StringBuilder(); for (Line x : l) sb.append(x.text()).append('\n'); return sb.toString(); }

    @Test void sessionNeverShowsUnknownAsZero() {
        List<Line> out = new ArrayList<>();
        SessionRecord s = new SessionRecord();
        s.start = 0; s.end = 2 * 60_000;
        DashboardText.session(out, s, Estimate.unknown("x"));
        String t = all(out);
        assertTrue(t.contains("unknown — add item prices"));
        assertTrue(t.contains("needs ≥5 min"));
        assertTrue(t.contains("00:02:00"));
    }

    @Test void economyAndRecommendation() {
        List<ActivityRecord> log = List.of(ActivityRecord.of(Activity.HUNTS, 1, 60, 8000, 0, 0), ActivityRecord.of(Activity.HUNTS, 1, 60, 9000, 0, 0));
        List<Line> out = new ArrayList<>();
        var opts = ProfitEngine.measured(log, 0, true);
        DashboardText.activities(out, opts);
        DashboardText.recommendation(out, ProfitEngine.recommend(opts));
        DashboardText.recentActivities(out, log, 5);
        String t = all(out);
        assertTrue(t.contains("Completing /hunt objectives"));
        assertTrue(t.contains("assumes:"));
    }

    @Test void marketPage() {
        MarketDatabase db = new MarketDatabase(dir.resolve("m.jsonl"), AsyncWriter.inline());
        db.add(PriceObservation.of("gible", false, PriceObservation.Kind.LISTING, 5000, 10));
        List<Line> out = new ArrayList<>();
        Valuator v = new Valuator(db, new ValuationConfig());
        DashboardText.marketSummary(out, db, v, 20, 10);
        DashboardText.opportunities(out, new OpportunityFinder(db, v, new ValuationConfig()).marketWide(20), 5);
        DashboardText.valuation(out, "gible", v.value(dev.rareradar.core.rarity.PokemonObservation.of("gible"), 20));
        String t = all(out);
        assertTrue(t.contains("gible: value $5,000"));
        assertTrue(t.contains("Low"));
        assertTrue(t.contains("not proof of value"));
    }

    @Test void huntPage() {
        HuntObjective h = HuntObjective.manual("Catch Zubat", "zubat", 3, 0);
        h.deadline = 3_600_000L;
        h.stars = 2;
        List<Line> out = new ArrayList<>();
        DashboardText.hunts(out, HuntOptimizer.rank(List.of(h), new HuntOptimizer.Inputs()), 0);
        HuntPointGoal g = new HuntPointGoal();
        DashboardText.huntGoal(out, g, g.project(List.of()));
        String t = all(out);
        assertTrue(t.contains("★★ Catch Zubat — 0/3 · 01:00:00 left"));
        assertTrue(t.contains("? HP"));
        assertTrue(t.contains("Mega Bracelet"));
        assertTrue(t.contains("unknown"));
    }

    @Test void dexEmptyAndFilled() {
        List<Line> out = new ArrayList<>();
        DexPlanner d = new DexPlanner();
        DashboardText.dex(out, d, List.of());
        assertTrue(all(out).contains("No Pokédex data yet"));
        d.universe.addAll(List.of("a", "b"));
        d.caught.add("a");
        out.clear();
        DashboardText.dex(out, d, d.suggest(s -> null, s -> List.of(), 5));
        assertTrue(all(out).contains("Owned 1 / 2 (50.0%)"));
        assertTrue(all(out).contains("b — spawn conditions unknown"));
    }
}
