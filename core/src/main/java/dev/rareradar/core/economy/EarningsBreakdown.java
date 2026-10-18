package dev.rareradar.core.economy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Keeps different kinds of money numbers apart so that speculation is never presented as income:
 * observed asking prices, confirmed sales/payouts (realised revenue), costs, net profit, and a hypothetical ceiling.
 * Only transactions the player labelled from real server messages count as realised.
 */
public final class EarningsBreakdown {
    public record Result(int askCount, double askMedian, double askLow, double askHigh,
                         int confirmedSales, double realisedRevenue, double otherIncome, double costs, double netProfit,
                         double hypotheticalPerHour, String headline, List<String> notes) {
        public boolean hasRealisedData() { return confirmedSales > 0 || otherIncome > 0; }
    }

    private EarningsBreakdown() {}

    /**
     * @param asks                  observed asking prices (never revenue)
     * @param txs                   confirmed money events
     * @param hypotheticalPerHour   modelled upside (NaN = none); reported separately and labelled
     */
    public static Result compute(List<Double> asks, List<Transaction> txs, double hypotheticalPerHour) {
        double[] a = asks.stream().filter(d -> d != null && Double.isFinite(d) && d > 0).mapToDouble(Double::doubleValue).sorted().toArray();
        double median = a.length == 0 ? Double.NaN : (a.length % 2 == 1 ? a[a.length / 2] : (a[a.length / 2 - 1] + a[a.length / 2]) / 2);
        double lo = a.length == 0 ? Double.NaN : a[(int) Math.floor((a.length - 1) * 0.25)];
        double hi = a.length == 0 ? Double.NaN : a[(int) Math.floor((a.length - 1) * 0.75)];
        int sales = 0;
        double revenue = 0, other = 0, costs = 0;
        for (Transaction t : txs) {
            if (t.kind == null || !(t.amount > 0)) continue;
            switch (t.kind) {
                case STS_PAYOUT, GTS_SALE -> { revenue += t.amount; sales++; }
                case INCOME -> other += t.amount;
                case GTS_PURCHASE, EXPENSE -> costs += t.amount;
            }
        }
        List<String> notes = new ArrayList<>();
        if (a.length > 0) notes.add(a.length + " asking price(s) observed — asking prices are not sale prices");
        if (sales == 0) notes.add("no confirmed sales yet: realised revenue is $0, not an estimate");
        if (Double.isFinite(hypotheticalPerHour)) notes.add("hypothetical earning potential is a model output, not income");
        double net = revenue + other - costs;
        String headline = String.format(Locale.ROOT, "Realised revenue $%,.0f from %d confirmed sale(s)/payout(s); costs $%,.0f; net $%,.0f.", revenue, sales, costs, net);
        return new Result(a.length, median, lo, hi, sales, revenue, other, costs, net, hypotheticalPerHour, headline, notes);
    }

    /** Amount received after a percentage fee (0..1). NaN when the fee is unknown — an unknown fee is not treated as zero. */
    public static double netOfFee(double gross, double feeFraction) {
        if (!Double.isFinite(gross) || !Double.isFinite(feeFraction) || feeFraction < 0 || feeFraction >= 1) return Double.NaN;
        return gross * (1 - feeFraction);
    }

}
