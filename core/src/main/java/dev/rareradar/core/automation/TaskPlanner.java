package dev.rareradar.core.automation;

import dev.rareradar.core.profit.Activity;
import dev.rareradar.core.profit.ProfitEngine;
import dev.rareradar.core.util.Confidence;
import dev.rareradar.core.util.Estimate;
import dev.rareradar.core.util.Money;

import java.util.*;

/**
 * Chooses the next objective. Ranks options by a risk-adjusted value (the lower end of the estimate's interval, or
 * a confidence-discounted point value when there is no interval), calibrated by past prediction error, and then says
 * whether RareRadar can execute it on this server or can only recommend it.
 */
public final class TaskPlanner {
    public record Plan(ProfitEngine.Option option, double riskAdjustedPerHour, Module module, boolean executable,
                       String why, List<String> knownFacts, List<String> estimates, List<String> assumptions) {}

    private TaskPlanner() {}

    /** Which automation module could carry out an activity (null = recommendation only). */
    public static Module moduleFor(Activity a) {
        return switch (a) {
            case RARE_HUNTING -> Module.HUNTING;
            case HUNTS -> Module.HUNTING;
            case OTHER -> null;
            default -> null; // trading, breeding, selling, dailies… are advice only
        };
    }

    static double riskAdjusted(Estimate e) {
        if (!e.known()) return Double.NEGATIVE_INFINITY;
        if (Double.isFinite(e.low())) return e.low();
        double k = switch (e.confidence()) { case HIGH -> 0.9; case MEDIUM -> 0.7; case LOW -> 0.4; case NONE -> 0; };
        return e.value() * k;
    }

    public static List<Plan> plan(List<ProfitEngine.Option> options, AutomationController ctl, StrategyEvaluator eval, long now) {
        List<Plan> out = new ArrayList<>();
        for (ProfitEngine.Option o : options) {
            if (!o.perHour().known()) continue;
            double ra = riskAdjusted(o.perHour());
            if (eval != null) ra = eval.calibrated(o.name(), ra);
            Module m = moduleFor(o.activity());
            boolean exec = false;
            String why;
            if (m == null) why = "advice only: RareRadar has no automation for " + o.activity().label;
            else {
                AutomationController.Decision d = ctl == null ? AutomationController.Decision.no("no controller") : ctl.mayRun(m, now);
                exec = d.allowed();
                why = exec ? "can run with the " + m.label + " module" : "recommendation only: " + d.reason();
            }
            List<String> facts = new ArrayList<>(), est = new ArrayList<>(), ass = new ArrayList<>();
            if (o.measured()) facts.add("Measured from your own log: " + o.perHour().basis());
            else est.add("Model estimate: " + o.explanation());
            est.add(String.format(Locale.ROOT, "Expected %s/h, risk-adjusted %s/h (%s confidence)", Money.fmt(o.perHour().value()), Money.fmt(ra), o.perHour().confidence().label));
            ass.add("Past results are representative of the near future.");
            if (o.perHour().confidence().ordinal() <= Confidence.LOW.ordinal()) ass.add("Few observations: treat as a rough guide.");
            out.add(new Plan(o, ra, m, exec, why, facts, est, ass));
        }
        out.sort(Comparator.comparingDouble(Plan::riskAdjustedPerHour).reversed());
        return out;
    }
}
