package dev.rareradar.core.report;

import dev.rareradar.core.breeding.BreedingAnalyzer;
import dev.rareradar.core.dex.DexPlanner;
import dev.rareradar.core.hunt.HuntObjective;
import dev.rareradar.core.hunt.HuntOptimizer;
import dev.rareradar.core.hunt.HuntPointGoal;
import dev.rareradar.core.market.*;
import dev.rareradar.core.profit.ActivityRecord;
import dev.rareradar.core.profit.ProfitEngine;
import dev.rareradar.core.spawn.BiomeRecommender;
import dev.rareradar.core.stats.Reports;
import dev.rareradar.core.stats.SessionRecord;
import dev.rareradar.core.util.Estimate;
import dev.rareradar.core.util.Money;

import java.util.*;

/**
 * Pure text builders for the dashboard pages, so the content (and its honesty about uncertainty) is unit-tested
 * without Minecraft. Unknown values are always printed as "unknown"/"?" — never as zero.
 */
public final class DashboardText {
    private DashboardText() {}

    public static String duration(double minutes) {
        long s = Math.round(minutes * 60);
        return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    public static String est(Estimate e) { return Money.range(e); }

    public static String pct(double p) { return Double.isFinite(p) ? String.format(Locale.ROOT, "%.1f%%", p * 100) : "?"; }

    public static void session(List<Line> out, SessionRecord s, Estimate estimatedInventory) {
        out.add(Line.h("SESSION STATISTICS"));
        if (s == null) { out.add(Line.dim("No active session (join a world to start one).")); return; }
        double min = s.minutes();
        out.add(Line.t("Session time:          " + duration(min)));
        out.add(Line.t("Pokémon encountered:   " + s.encountered));
        out.add(Line.t("Rare encounters:       " + s.rareEncounters));
        out.add(Line.t("Shinies encountered:   " + s.shinyEncounters));
        out.add(Line.t("Caught (verified):     " + s.caught));
        out.add(Line.blank());
        out.add(Line.h("ECONOMY"));
        out.add(Line.t("Realised profit:       " + Money.fmt(s.realisedProfit()) + "  (logged rewards + trades − spending)"));
        out.add(Line.t("Estimated inventory:   " + (estimatedInventory.known() ? est(estimatedInventory) : "unknown — add item prices")));
        out.add(Line.t("Realised profit/hour:  " + (min >= 5 ? Money.fmt(s.realisedProfit() / min * 60) : "— (needs ≥5 min)")));
    }

    public static void recommendation(List<Line> out, ProfitEngine.Recommendation rec) {
        out.add(Line.h("CURRENT RECOMMENDATION"));
        out.add(rec.best() == null ? Line.dim(rec.text()) : Line.good(rec.text()));
        for (String a : rec.assumptions()) out.add(Line.dim("  assumes: " + a));
    }

    public static void activities(List<Line> out, List<ProfitEngine.Option> options) {
        out.add(Line.h("ACTIVITY COMPARISON  (estimated profit/hour · 90% range · confidence)"));
        if (options.isEmpty()) { out.add(Line.dim("No logged activities yet. Use the form above after each session.")); return; }
        List<ProfitEngine.Option> sorted = new ArrayList<>(options);
        sorted.sort(Comparator.comparingDouble((ProfitEngine.Option o) -> o.perHour().known() ? o.perHour().value() : Double.NEGATIVE_INFINITY).reversed());
        for (ProfitEngine.Option o : sorted) {
            out.add(Line.t(String.format(Locale.ROOT, "%-30s %s", o.name(), o.perHour().known() ? est(o.perHour()) + "/h" : "unknown")));
            out.add(Line.dim("    " + o.explanation()));
        }
    }

    public static void recentActivities(List<Line> out, List<ActivityRecord> log, int n) {
        out.add(Line.h("RECENT LOG"));
        for (int i = log.size() - 1, k = 0; i >= 0 && k < n; i--, k++) {
            ActivityRecord r = log.get(i);
            out.add(Line.t(String.format(Locale.ROOT, "%-28s %5.0f min  realised %s  est. %s  costs %s", r.activity.label, r.minutes,
                    Money.fmt(r.realisedRevenue), Money.fmt(r.estimatedValue), Money.fmt(r.costs))));
        }
        if (log.isEmpty()) out.add(Line.dim("(empty)"));
    }

    public static void marketSummary(List<Line> out, MarketDatabase db, Valuator v, long now, int limit) {
        out.add(Line.h("MARKET DATABASE  (" + db.size() + " observations)"));
        List<MarketKey> keys = new ArrayList<>(db.keys());
        keys.sort(Comparator.comparingInt((MarketKey k) -> -db.of(k).size()).thenComparing(MarketKey::toString));
        int shown = 0;
        for (MarketKey k : keys) {
            if (shown++ >= limit) { out.add(Line.dim("… " + (keys.size() - limit) + " more buckets")); break; }
            MarketStats s = db.stats(k, now);
            out.add(Line.t(k + ": value " + est(v.bucketValue(k, now))));
            out.add(Line.dim(String.format(Locale.ROOT, "    listings %d (median %s, min %s, max %s) · sales %d (median %s) · volatility %s · trend %s/wk · %s sales/wk",
                    s.listings(), Money.fmt(s.listMedian()), Money.fmt(s.listMin()), Money.fmt(s.listMax()), s.sales(), Money.fmt(s.saleMedian()),
                    Double.isFinite(s.volatility()) ? String.format(Locale.ROOT, "%.0f%%", s.volatility() * 100) : "?",
                    Double.isFinite(s.trendPerWeek()) ? String.format(Locale.ROOT, "%+.0f%%", s.trendPerWeek() * 100) + (s.trendFromSales() ? "" : " (asks)") : "?",
                    Double.isFinite(s.salesPerWeek()) ? String.format(Locale.ROOT, "%.1f", s.salesPerWeek()) : "?")));
        }
        if (keys.isEmpty()) out.add(Line.dim("No prices yet. Enter GTS prices above, import a CSV, or browse the GTS with the CobbleGalaxy bridge on."));
        out.add(Line.dim("Listings are asking prices, not proof of value. Confirmed sales carry the most weight."));
    }

    public static void opportunities(List<Line> out, List<Opportunity> ops, int limit) {
        out.add(Line.h("OPPORTUNITIES  (possibilities, never guarantees)"));
        if (ops.isEmpty()) { out.add(Line.dim("Nothing flagged yet — more confirmed sales make this useful.")); return; }
        for (int i = 0; i < Math.min(limit, ops.size()); i++) {
            Opportunity o = ops.get(i);
            out.add(Line.t("• " + o.type().name().replace('_', ' ').toLowerCase(Locale.ROOT) + " — " + o.subject() + " [" + o.confidence().label + "]"));
            out.add(Line.dim("    " + o.explanation()));
        }
    }

    public static void valuation(List<Line> out, String what, Valuation v) {
        out.add(Line.t(what + ": " + est(v.total()) + "  via " + v.method()));
        out.add(Line.dim(String.format("    species %s · shiny %s · IVs %s · rarity %s", short_(v.species()), short_(v.shiny()), short_(v.competitive()), short_(v.rarity()))));
        for (String a : v.assumptions()) out.add(Line.dim("    note: " + a));
    }

    private static String short_(Estimate e) { return e.known() ? Money.fmt(e.value()) : "?"; }

    public static void hunts(List<Line> out, List<HuntOptimizer.Ranked> ranked, long now) {
        out.add(Line.h("ACTIVE HUNTS  (best first: reward ÷ expected time)"));
        if (ranked.isEmpty()) { out.add(Line.dim("No active hunts. Pin hunts in /hunts (CobbleGalaxy) or add one above.")); return; }
        int i = 1;
        for (HuntOptimizer.Ranked r : ranked) {
            HuntObjective h = r.hunt();
            String left = h.deadline == null ? "" : " · " + duration(Math.max(0, h.deadline - now) / 60_000.0) + " left";
            out.add(Line.t(String.format(Locale.ROOT, "%d. %s%s — %d/%d%s [%s]", i++, h.stars > 0 ? "★".repeat(h.stars) + " " : "",
                    h.title.isBlank() ? h.species : h.title, h.progress, h.required, left, h.source)));
            out.add(Line.dim("    " + r.explanation()));
            out.add(Line.dim("    reward: " + (h.rewardHuntPoints == null ? "? HP" : Math.round(h.rewardHuntPoints) + " HP") + ", "
                    + (h.rewardMoney == null ? "$?" : Money.fmt(h.rewardMoney))
                    + (r.valuePerHour().known() ? " · " + est(r.valuePerHour()) + "/h" : "")
                    + (r.pointsPerHour().known() ? String.format(Locale.ROOT, " · %.1f HP/h", r.pointsPerHour().value()) : "")));
        }
    }

    public static void huntGoal(List<Line> out, HuntPointGoal g, HuntPointGoal.Projection p) {
        out.add(Line.h("HUNT POINT GOAL — " + g.name));
        out.add(Line.t(String.format(Locale.ROOT, "Current %.0f / %.0f · remaining %.0f", g.currentPoints, g.cost, p.remaining())));
        out.add(Line.t("Estimated hunts needed: " + (p.huntsNeeded().known() ? Math.round(p.huntsNeeded().value()) + " (" + p.huntsNeeded().confidence().label + ")" : "unknown — " + p.huntsNeeded().basis())));
        out.add(Line.t("Estimated time: " + (p.minutesNeeded().known() ? duration(p.minutesNeeded().value()) + " (" + p.minutesNeeded().confidence().label + ")" : "unknown")));
        out.add(Line.dim("Cost source: " + g.costSource));
    }

    public static void biomes(List<Line> out, String target, List<BiomeRecommender.Suggestion> s, int limit) {
        out.add(Line.h("WHERE TO LOOK: " + target));
        out.add(Line.good(BiomeRecommender.headline(s, target)));
        for (int i = 0; i < Math.min(limit, s.size()); i++) out.add(Line.dim("  " + s.get(i).text()));
        out.add(Line.dim("Observed rates depend on where/when you looked, movement, players nearby and modifiers — not true spawn odds."));
    }

    public static void breeding(List<Line> out, List<BreedingAnalyzer.Result> results) {
        out.add(Line.h("BREEDING PAIRS FROM YOUR PARTY"));
        if (results.isEmpty()) { out.add(Line.dim("No compatible pairs in your party (needs shared egg group + opposite genders, or Ditto).")); return; }
        for (BreedingAnalyzer.Result r : results) {
            out.add(Line.t(r.a().species() + " × " + r.b().species() + ": P(target) " + pct(r.pHit())
                    + (Double.isFinite(r.expectedProfitPerEgg()) ? " · E[profit]/egg " + Money.fmt(r.expectedProfitPerEgg()) : "")
                    + (Double.isFinite(r.expectedProfitPerHour()) ? " · /h " + Money.fmt(r.expectedProfitPerHour()) : "")));
            double[] d = r.perfectDist();
            out.add(Line.dim(String.format(Locale.ROOT, "    perfect IVs 0:%s 1:%s 2:%s 3:%s 4:%s 5:%s 6:%s", pct(d[0]), pct(d[1]), pct(d[2]), pct(d[3]), pct(d[4]), pct(d[5]), pct(d[6]))));
            double[] e = r.expectedIvs();
            out.add(Line.dim(String.format(Locale.ROOT, "    expected IVs %.0f/%.0f/%.0f/%.0f/%.0f/%.0f · nature odds %s", e[0], e[1], e[2], e[3], e[4], e[5], pct(r.pNature()))));
        }
        out.add(Line.dim("Probabilities, not promises. " + results.get(0).assumptions().get(0)));
    }

    public static void dex(List<Line> out, DexPlanner d, List<DexPlanner.Suggestion> suggestions) {
        out.add(Line.h("POKÉDEX"));
        if (d.total() == 0) { out.add(Line.dim("No Pokédex data yet: open the Dex Rewards menu on CobbleGalaxy, or press “Sync from Cobblemon”.")); return; }
        out.add(Line.t(String.format(Locale.ROOT, "Owned %d / %d (%.1f%%) · shinies %d · source: %s", d.owned(), d.total(), d.percent(), d.shinyCaught.size(), d.source)));
        d.nextTier().ifPresentOrElse(
                e -> out.add(Line.good("Next milestone: " + e.getKey().name() + " at " + e.getKey().percentage() + "% — " + e.getValue() + " more species")),
                () -> out.add(Line.dim("No upcoming milestone known (tiers come from the server's Dex Rewards menu).")));
        for (var t : d.claimable()) out.add(Line.warn("Claimable now: " + t.name() + " (" + t.view() + ")"));
        out.add(Line.h("CATCH NEXT"));
        for (DexPlanner.Suggestion s : suggestions) out.add(Line.t(s.species() + " — " + s.why()));
    }

    public static void periods(List<Line> out, String title, List<Reports.Period> periods, int limit) {
        out.add(Line.h(title));
        out.add(Line.dim(String.format("%-10s %4s %6s %6s %5s %5s %5s %10s %10s", "period", "sess", "hours", "enc", "rare", "shiny", "hunts", "profit", "profit/h")));
        int start = Math.max(0, periods.size() - limit);
        for (int i = start; i < periods.size(); i++) {
            Reports.Period p = periods.get(i);
            out.add(Line.t(String.format(Locale.ROOT, "%-10s %4d %6.1f %6d %5d %5d %5d %10s %10s", p.key(), p.sessions(), p.hours(), p.encountered(),
                    p.rare(), p.shiny(), p.hunts(), Money.fmt(p.realisedProfit()), Money.fmt(p.profitPerHour()))));
        }
        double trend = Reports.trend(periods, Reports.Period::profitPerHour);
        out.add(Line.dim("Profit/hour trend: " + (Double.isFinite(trend) ? Money.fmt(trend) + " per period (Theil–Sen)" : "needs ≥3 periods")));
    }
}
