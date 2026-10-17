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
