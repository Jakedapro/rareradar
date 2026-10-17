package dev.rareradar.core.stats;

import dev.rareradar.core.util.Stats;

import java.time.*;
import java.time.temporal.IsoFields;
import java.util.*;

/** Daily/weekly aggregation and trends over session history. */
public final class Reports {
    public record Period(String key, int sessions, double hours, int encountered, int rare, int shiny, int caught,
                         int hunts, double realisedProfit) {
        public double profitPerHour() { return hours > 0 ? realisedProfit / hours : Double.NaN; }
        public double encountersPerHour() { return hours > 0 ? encountered / hours : Double.NaN; }
    }

    private Reports() {}

    public static List<Period> daily(Collection<SessionRecord> s, ZoneId zone) {
        return group(s, r -> Instant.ofEpochMilli(r.start).atZone(zone).toLocalDate().toString());
    }

    public static List<Period> weekly(Collection<SessionRecord> s, ZoneId zone) {
        return group(s, r -> {
            LocalDate d = Instant.ofEpochMilli(r.start).atZone(zone).toLocalDate();
            return String.format(Locale.ROOT, "%d-W%02d", d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        });
    }

    private static List<Period> group(Collection<SessionRecord> s, java.util.function.Function<SessionRecord, String> key) {
        Map<String, List<SessionRecord>> m = new TreeMap<>();
        for (SessionRecord r : s) m.computeIfAbsent(key.apply(r), k -> new ArrayList<>()).add(r);
        List<Period> out = new ArrayList<>();
        for (var e : m.entrySet()) {
            int n = 0, enc = 0, rare = 0, sh = 0, c = 0, h = 0; double hrs = 0, p = 0;
            for (SessionRecord r : e.getValue()) {
                n++; enc += r.encountered; rare += r.rareEncounters; sh += r.shinyEncounters; c += r.caught; h += r.huntCompletions;
                hrs += r.minutes() / 60; p += r.realisedProfit();
            }
            out.add(new Period(e.getKey(), n, hrs, enc, rare, sh, c, h, p));
        }
        return out;
    }

    /**
     * Lifetime totals plus the last 7 days and the 7 days before, for a "how am I doing" summary.
     *
     * @param ballsPerCatch balls thrown per recorded catch over all sessions (NaN without catches)
     * @param best          session with the highest realised profit per hour among sessions of 10+ minutes, or null
     */
    public record Lifetime(Period all, Period last7, Period prev7, int ballsThrown, double ballsPerCatch, SessionRecord best) {
        /** Relative change of profit/hour, last 7 days vs the 7 before (NaN when either is unknown or zero). */
        public double profitPerHourChange() {
            double a = last7.profitPerHour(), b = prev7.profitPerHour();
            return Double.isFinite(a) && Double.isFinite(b) && b != 0 ? (a - b) / Math.abs(b) : Double.NaN;
        }
    }

    public static Lifetime lifetime(Collection<SessionRecord> s, long now) {
        long week = 7 * 86_400_000L;
        List<SessionRecord> last = new ArrayList<>(), prev = new ArrayList<>();
        int balls = 0, caught = 0;
        SessionRecord best = null;
        for (SessionRecord r : s) {
            if (r.start > now - week) last.add(r); else if (r.start > now - 2 * week) prev.add(r);
            balls += r.ballsThrown; caught += r.caught;
            if (r.minutes() >= 10 && (best == null || r.realisedProfit() / r.minutes() > best.realisedProfit() / best.minutes())) best = r;
        }
        return new Lifetime(total("all", s), total("last 7 days", last), total("previous 7 days", prev), balls,
                caught > 0 ? (double) balls / caught : Double.NaN, best);
    }

    private static Period total(String key, Collection<SessionRecord> s) {
        List<Period> g = group(s, r -> key);
        return g.isEmpty() ? new Period(key, 0, 0, 0, 0, 0, 0, 0, 0) : g.get(0);
    }

    /** Robust trend (Theil–Sen) of a per-period metric; returns change per period, NaN with fewer than 3 periods. */
    public static double trend(List<Period> periods, java.util.function.ToDoubleFunction<Period> metric) {
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < periods.size(); i++) {
            double v = metric.applyAsDouble(periods.get(i));
            if (Double.isFinite(v)) pts.add(new double[]{i, v});
        }
        if (pts.size() < 3) return Double.NaN;
        double[] x = new double[pts.size()], y = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) { x[i] = pts.get(i)[0]; y[i] = pts.get(i)[1]; }
        return Stats.theilSen(x, y);
    }
}
