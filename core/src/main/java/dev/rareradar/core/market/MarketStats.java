package dev.rareradar.core.market;

import dev.rareradar.core.util.Stats;

import java.util.ArrayList;
import java.util.List;

/**
 * Descriptive statistics for one market bucket. Listings and confirmed sales are kept strictly separate.
 * NaN means "no data". {@code volatility} is a robust coefficient of variation (MAD / median).
 * {@code trendPerWeek} is the Theil–Sen slope of log-price, expressed as fractional change per week.
 * {@code salesPerWeek} with its interval is the liquidity estimate.
 */
public record MarketStats(MarketKey key,
                          int listings, double listMedian, double listMin, double listMax, double listQ1,
                          int sales, double saleMedian, double saleQ1, double saleQ3,
                          int sts, double stsMedian,
                          double volatility, double trendPerWeek, boolean trendFromSales,
                          double salesPerWeek, double salesPerWeekLow, double salesPerWeekHigh,
                          long firstSeen, long lastSeen) {

    static final double WEEK_MS = 7 * 24 * 3600_000.0;

    public static MarketStats compute(MarketKey key, List<PriceObservation> obsIn, long now) {
        List<PriceObservation> obs = Listings.distinct(obsIn);
        List<Double> list = new ArrayList<>(), sale = new ArrayList<>(), st = new ArrayList<>();
        List<double[]> saleTs = new ArrayList<>(), listTs = new ArrayList<>();
        long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
        for (PriceObservation p : obs) {
            first = Math.min(first, p.timestamp);
            last = Math.max(last, p.timestamp);
            switch (p.kind) {
                case LISTING -> { list.add(p.price); listTs.add(new double[]{p.timestamp, p.price}); }
                case SALE -> { sale.add(p.price); saleTs.add(new double[]{p.timestamp, p.price}); }
                case STS -> st.add(p.price);
                case PURCHASE -> { sale.add(p.price); saleTs.add(new double[]{p.timestamp, p.price}); }
            }
        }
        double[] l = Stats.toArray(list), s = Stats.toArray(sale), t = Stats.toArray(st);
        double[] basis = s.length >= 3 ? s : l;
        double med = Stats.median(basis);
        double vol = basis.length >= 3 && med > 0 ? Stats.mad(basis) / med : Double.NaN;

        boolean fromSales = saleTs.size() >= 3;
        List<double[]> ts = fromSales ? saleTs : listTs;
        double trend = Double.NaN;
        if (ts.size() >= 3) {
            double[] x = new double[ts.size()], y = new double[ts.size()];
            for (int i = 0; i < ts.size(); i++) {
                x[i] = ts.get(i)[0] / WEEK_MS;
                y[i] = Math.log(Math.max(1, ts.get(i)[1]));
            }
            double slope = Stats.theilSen(x, y);
            if (Double.isFinite(slope)) trend = Math.expm1(slope);
        }

        // Liquidity: confirmed sales per week over the period we have been observing this bucket.
        double spanWeeks = obs.isEmpty() ? 0 : Math.max(1.0 / 7, (Math.max(now, last) - first) / WEEK_MS);
        double spw = Double.NaN, spwLo = Double.NaN, spwHi = Double.NaN;
        if (spanWeeks > 0) {
            spw = sale.size() / spanWeeks;
            double[] ci = Stats.poissonRate(sale.size(), spanWeeks, 0.9);
            spwLo = ci[0]; spwHi = ci[1];
        }

        return new MarketStats(key,
                l.length, Stats.median(l), min(l), max(l), Stats.quantile(l, 0.25),
                s.length, Stats.median(s), Stats.quantile(s, 0.25), Stats.quantile(s, 0.75),
                t.length, Stats.median(t),
                vol, trend, fromSales, spw, spwLo, spwHi,
                obs.isEmpty() ? 0 : first, obs.isEmpty() ? 0 : last);
    }

    private static double min(double[] a) { double m = Double.NaN; for (double v : a) m = Double.isNaN(m) ? v : Math.min(m, v); return m; }
    private static double max(double[] a) { double m = Double.NaN; for (double v : a) m = Double.isNaN(m) ? v : Math.max(m, v); return m; }

    public int total() { return listings + sales + sts; }
}
