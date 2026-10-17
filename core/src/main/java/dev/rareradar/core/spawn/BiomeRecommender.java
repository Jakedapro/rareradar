package dev.rareradar.core.spawn;

import dev.rareradar.core.util.Confidence;

import java.util.*;
import java.util.function.Predicate;

/**
 * Recommends biomes for a target using the user's own exposure-normalised history. Biomes with too little
 * exposure are listed as "explore more" instead of being ranked on noise.
 */
public final class BiomeRecommender {
    public record Suggestion(String biome, double perHour, double low, double high, double hours, long events,
                             Confidence confidence, String text) {}

    private BiomeRecommender() {}

    public static List<Suggestion> recommend(Collection<EncounterRecord> log, ExposureLedger exp,
                                             Predicate<EncounterRecord> target, String targetName, double minHours) {
        List<Suggestion> ranked = new ArrayList<>(), explore = new ArrayList<>();
        for (EncounterStats.Rate r : EncounterStats.byBiome(log, exp, target)) {
            // Jeffreys posterior mean: never exactly zero, so unexplored biomes are not written off.
            double mean = (r.events() + 0.5) / Math.max(r.hours(), 1e-9);
            if (r.hours() < minHours) {
                explore.add(new Suggestion(r.key(), Double.NaN, Double.NaN, Double.NaN, r.hours(), r.events(), Confidence.NONE,
                        String.format(Locale.ROOT, "%s: only %.1f h observed — not enough to judge.", r.key(), r.hours())));
                continue;
            }
            ranked.add(new Suggestion(r.key(), mean, r.perHour().low(), r.perHour().high(), r.hours(), r.events(), r.perHour().confidence(),
                    String.format(Locale.ROOT, "%s: %d %s in %.1f h (≈%.2f/h, 90%% range %.2f–%.2f).",
                            r.key(), r.events(), targetName, r.hours(), r.events() / r.hours(), r.perHour().low(), r.perHour().high())));
        }
        ranked.sort(Comparator.comparingDouble(Suggestion::perHour).reversed());
        List<Suggestion> out = new ArrayList<>(ranked);
        out.addAll(explore);
        return out;
    }

    /** One-sentence summary in the style "Based on your previous encounters, …". */
    public static String headline(List<Suggestion> s, String targetName) {
        if (s.isEmpty() || !Double.isFinite(s.get(0).perHour()))
            return "Not enough observation time yet to compare biomes for " + targetName + ".";
        Suggestion best = s.get(0);
        boolean clear = s.size() < 2 || !Double.isFinite(s.get(1).perHour()) || best.low() > s.get(1).high();
        return "Based on your previous encounters, " + best.biome() + " has produced the most " + targetName
                + " per hour of your recorded locations" + (clear ? "." : ", but the difference from " + s.get(1).biome() + " is within the noise.");
    }
}
