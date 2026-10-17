package dev.rareradar.core.market;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * STS (sell to server: instant, fixed payout) versus GTS (player market: higher price, fee, wait, may not sell).
 * Uses only recorded observations: STS payouts the player logged and <b>confirmed</b> GTS sales. Asking prices never
 * count as what a GTS sale would earn.
 */
public final class SellChannelAdvisor {
    private SellChannelAdvisor() {}

    public enum Verdict { STS, GTS, EITHER, UNKNOWN }

    /**
     * @param stsPayout         median logged STS payout (NaN if none)
     * @param gtsNet            median confirmed sale × (1 − fee) (NaN if fewer than {@link #MIN_SALES} sales or fee unknown)
     * @param gtsNetLow         lower-quartile sale × (1 − fee)
     * @param salesPerWeek      confirmed sales/week for this bucket (a rough liquidity signal; NaN if none)
     * @param expectedWaitDays  rough days until one sale at the observed rate, if it continued (NaN if unknown)
     */
    public record Comparison(MarketKey key, double stsPayout, int stsSamples, double gtsNet, double gtsNetLow, int sales,
                             double salesPerWeek, double expectedWaitDays, Verdict verdict, String reason) {}

    public static final int MIN_SALES = 3;
    /** GTS must beat STS by this fraction (at its lower quartile) to be worth the wait and risk. */
    public static final double MARGIN = 0.10;

    public static Comparison compare(MarketStats s, double feePercent) {
        double sts = s.sts() > 0 ? s.stsMedian() : Double.NaN;
        boolean feeKnown = Double.isFinite(feePercent) && feePercent >= 0 && feePercent < 100;
        double keep = feeKnown ? 1 - feePercent / 100 : Double.NaN;
        boolean salesOk = s.sales() >= MIN_SALES;
        double net = salesOk && feeKnown ? s.saleMedian() * keep : Double.NaN;
        double netLo = salesOk && feeKnown ? s.saleQ1() * keep : Double.NaN;
        double spw = s.salesPerWeek();
        double wait = Double.isFinite(spw) && spw > 0 ? 7 / spw : Double.NaN;
        Verdict v; String why;
        if (!Double.isFinite(sts) && !Double.isFinite(net)) {
            v = Verdict.UNKNOWN;
            why = !feeKnown && salesOk ? "set your GTS fee to compare" : "no STS payout logged and fewer than " + MIN_SALES + " confirmed GTS sales";
        } else if (!Double.isFinite(net)) {
            v = Verdict.UNKNOWN;
            why = !feeKnown && salesOk ? "STS known; set your GTS fee to compare" : "STS known; need " + MIN_SALES + "+ confirmed GTS sales to compare (asking prices don't count)";
        } else if (!Double.isFinite(sts)) {
            v = Verdict.UNKNOWN;
            why = "GTS sales known; log an STS payout to compare";
        } else if (netLo > sts * (1 + MARGIN)) {
            v = Verdict.GTS;
            why = String.format(Locale.ROOT, "even a lower-quartile sale after fee beats STS by %.0f%%", 100 * (netLo / sts - 1));
        } else if (net < sts) {
            v = Verdict.STS;
            why = "the typical GTS sale after fee pays less than STS — sell instantly";
        } else {
            v = Verdict.EITHER;
            why = "GTS pays a little more typically but not reliably; STS is instant and certain";
        }
        return new Comparison(s.key(), sts, s.sts(), net, netLo, s.sales(), spw, wait, v, why);
    }

    /** All buckets with any STS or sale data, GTS wins first (by extra coin over STS), then the rest. */
    public static List<Comparison> compareAll(MarketDatabase db, double feePercent, long now) {
        List<Comparison> out = new ArrayList<>();
        for (MarketKey k : db.keys()) {
            MarketStats s = db.stats(k, now);
            if (s.sts() == 0 && s.sales() == 0) continue;
            out.add(compare(s, feePercent));
        }
        out.sort(Comparator.comparing(Comparison::verdict)
                .thenComparing(Comparator.comparingDouble((Comparison c) -> Double.isFinite(c.gtsNet()) && Double.isFinite(c.stsPayout()) ? c.gtsNet() - c.stsPayout() : Double.NEGATIVE_INFINITY).reversed()));
        return out;
    }
}
