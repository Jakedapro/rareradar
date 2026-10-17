package dev.rareradar.core.pokebuilder;

import java.util.*;

/**
 * Chooses which Pokémon receive limited PokéBuilder tokens: a multiple-choice knapsack maximising expected value
 * gain, at most one option per Pokémon, within the token budget. Options are skipped when their value is unknown,
 * their gain is not positive, or the gain doesn't beat the opportunity cost of the tokens
 * ({@code tokenValue} Pokédollars per token, NaN = ignore).
 */
public final class TokenPlanner {
    public record Plan(List<UpgradeOption> chosen, double tokensUsed, double expectedGain, List<String> rejected) {}

    private static final int MAX_CELLS = 2_000_000;

    private TokenPlanner() {}

    public static Plan plan(List<UpgradeOption> options, double budget, double tokenValue) {
        List<String> rejected = new ArrayList<>();
        Map<String, List<UpgradeOption>> groups = new LinkedHashMap<>();
        for (UpgradeOption o : options) {
            double g = o.gain();
            if (!Double.isFinite(o.tokenCost()) || o.tokenCost() < 0) { rejected.add(o.label() + ": token cost unknown"); continue; }
            if (!Double.isFinite(g)) { rejected.add(o.label() + ": value before/after unknown — log market prices first"); continue; }
            double net = g - (Double.isFinite(tokenValue) ? tokenValue * o.tokenCost() : 0);
            if (net <= 0) { rejected.add(o.label() + ": expected gain does not justify " + Math.round(o.tokenCost()) + " tokens"); continue; }
            if (o.tokenCost() > budget) { rejected.add(o.label() + ": needs " + Math.round(o.tokenCost()) + " tokens, budget " + Math.round(budget)); continue; }
            groups.computeIfAbsent(o.pokemonId(), k -> new ArrayList<>()).add(o);
        }
        if (groups.isEmpty() || !(budget > 0)) return new Plan(List.of(), 0, 0, rejected);

        // Scale costs to integers (tokens may be fractional).
        double scale = 1;
        for (List<UpgradeOption> l : groups.values()) for (UpgradeOption o : l)
            while (Math.abs(o.tokenCost() * scale - Math.round(o.tokenCost() * scale)) > 1e-9 && scale < 100) scale *= 10;
        int cap = (int) Math.floor(budget * scale);
        List<List<UpgradeOption>> gl = new ArrayList<>(groups.values());
        if ((long) cap * gl.size() > MAX_CELLS) return greedy(gl, budget, tokenValue, rejected);

        double[][] best = new double[gl.size() + 1][cap + 1];
        int[][] pick = new int[gl.size() + 1][cap + 1];
        for (int i = 1; i <= gl.size(); i++) {
            List<UpgradeOption> g = gl.get(i - 1);
            for (int c = 0; c <= cap; c++) {
                best[i][c] = best[i - 1][c];
                pick[i][c] = -1;
                for (int j = 0; j < g.size(); j++) {
                    int w = (int) Math.round(g.get(j).tokenCost() * scale);
                    if (w > c) continue;
                    double v = best[i - 1][c - w] + net(g.get(j), tokenValue);
                    if (v > best[i][c] + 1e-9) { best[i][c] = v; pick[i][c] = j; }
                }
            }
        }
        List<UpgradeOption> chosen = new ArrayList<>();
        int c = cap;
        for (int i = gl.size(); i >= 1; i--) {
            int j = pick[i][c];
            if (j >= 0) { UpgradeOption o = gl.get(i - 1).get(j); chosen.add(o); c -= (int) Math.round(o.tokenCost() * scale); }
        }
        Collections.reverse(chosen);
        return summarize(chosen, rejected);
    }

    private static double net(UpgradeOption o, double tokenValue) {
        return o.gain() - (Double.isFinite(tokenValue) ? tokenValue * o.tokenCost() : 0);
    }

    private static Plan greedy(List<List<UpgradeOption>> gl, double budget, double tokenValue, List<String> rejected) {
        List<UpgradeOption> all = new ArrayList<>();
        for (List<UpgradeOption> g : gl) all.addAll(g);
        all.sort(Comparator.comparingDouble((UpgradeOption o) -> net(o, tokenValue) / Math.max(1e-9, o.tokenCost())).reversed());
        Set<String> used = new HashSet<>();
        List<UpgradeOption> chosen = new ArrayList<>();
        double left = budget;
        for (UpgradeOption o : all) {
            if (used.contains(o.pokemonId()) || o.tokenCost() > left) continue;
            chosen.add(o); used.add(o.pokemonId()); left -= o.tokenCost();
        }
        return summarize(chosen, rejected);
    }

    private static Plan summarize(List<UpgradeOption> chosen, List<String> rejected) {
        double t = 0, g = 0;
        for (UpgradeOption o : chosen) { t += o.tokenCost(); g += o.gain(); }
        return new Plan(chosen, t, g, rejected);
    }
}
