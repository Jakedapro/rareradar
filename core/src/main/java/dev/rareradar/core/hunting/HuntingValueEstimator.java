package dev.rareradar.core.hunting;

import dev.rareradar.core.util.Confidence;
import dev.rareradar.core.util.Estimate;
import dev.rareradar.core.util.Stats;

import java.util.*;
import java.util.function.ToDoubleFunction;

/**
 * Expected value per hour of hunting, built only from the user's own encounter log and market estimates:
 * <pre>EV/h = Σ_species  encounterRate_s × P(event | encounter) × value_s × P(capture)</pre>
 * Species whose value is unknown contribute nothing and are reported as "coverage" so the estimate is never padded
 * with invented prices. Rates use Poisson intervals; the result is an asking-price-based estimate, not realised profit.
 */
public final class HuntingValueEstimator {
    /** Default 1.8.2 Cobblemon config: shinyRate = 8192 (CobblemonConfig.kt line 223). Servers may change it. */
    public static final double COBBLEMON_DEFAULT_SHINY_RATE = 8192;

    public record SpeciesCount(String species, long encounters) {}

    public record Result(Estimate perHour, double coverage, int speciesValued, int speciesSeen, List<String> assumptions) {}

    private HuntingValueEstimator() {}

    /**
     * Shiny probability per encounter: Beta prior centred on the documented rate (worth {@code priorWeight} encounters)
     * updated with the user's observed shinies. Returns {mean, low, high} (90%).
     */
    public static double[] shinyProbability(long shinies, long encounters, double documentedRate, double priorWeight) {
        double a = priorWeight / documentedRate + shinies, b = priorWeight - priorWeight / documentedRate + Math.max(0, encounters - shinies);
        double mean = a / (a + b);
        double[] w = Stats.wilson(shinies, Math.max(1, encounters), 0.9);
        double lo = encounters > 0 ? Math.min(mean, w[0]) : mean * 0.5, hi = encounters > 0 ? Math.max(mean, w[1]) : mean * 2;
        return new double[]{mean, Math.max(0, lo), Math.min(1, hi)};
    }

    /**
     * @param counts       encounters per species over {@code hours} of observation
     * @param pEvent       probability per encounter of the event being valued (1 for "any", shiny probability for shinies)
     * @param value        species → estimated value of the event's Pokémon (NaN = unknown)
     * @param pCapture     probability a valued encounter is actually captured (NaN = unknown → result reported before capture)
     */
    public static Result estimate(List<SpeciesCount> counts, double hours, double pEvent, ToDoubleFunction<String> value, double pCapture) {
        List<String> assumptions = new ArrayList<>();
        if (!(hours > 0)) return new Result(Estimate.unknown("no observation time"), 0, 0, 0, assumptions);
        long total = 0, valuedEnc = 0;
        double ev = 0, var = 0;
        int valued = 0;
        for (SpeciesCount c : counts) {
            total += c.encounters();
            double v = value.applyAsDouble(c.species());
            if (!Double.isFinite(v) || v <= 0) continue;
            valued++;
            valuedEnc += c.encounters();
            double rate = c.encounters() / hours;
            ev += rate * pEvent * v;
            var += (c.encounters() / (hours * hours)) * Math.pow(pEvent * v, 2); // Poisson variance of rate × constant
        }
        double coverage = total == 0 ? 0 : (double) valuedEnc / total;
        if (valued == 0) return new Result(Estimate.unknown("no encountered species has a market value yet"), coverage, 0, counts.size(), assumptions);
        double capture = Double.isFinite(pCapture) ? pCapture : 1;
        if (!Double.isFinite(pCapture)) assumptions.add("capture success not measured yet — value shown before capture losses");
        assumptions.add("values are market estimates (mostly asking prices), not realised sales");
        assumptions.add(String.format(Locale.ROOT, "only %.0f%% of encounters have a known value; the rest count as $0", coverage * 100));
        double sd = Math.sqrt(var) * capture, mean = ev * capture;
        double z = Stats.zFor(0.9);
        Confidence c = Confidence.of((int) valuedEnc, mean > 0 ? 2 * z * sd / mean : Double.POSITIVE_INFINITY).min(Confidence.MEDIUM);
        if (coverage < 0.5) c = c.min(Confidence.LOW);
        return new Result(new Estimate(mean, Math.max(0, mean - z * sd), mean + z * sd, (int) valuedEnc, c,
                valued + " valued species over " + String.format(Locale.ROOT, "%.1f", hours) + " h"), coverage, valued, counts.size(), assumptions);
    }
}
