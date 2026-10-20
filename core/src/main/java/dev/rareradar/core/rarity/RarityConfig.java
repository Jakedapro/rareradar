package dev.rareradar.core.rarity;

import java.util.HashSet;
import java.util.Set;

/**
 * User-tunable classification settings. The score {@code R = w1·S + w2·L + w3·M + w4·P + w5·D} is a priority
 * heuristic for sorting targets. It is NOT a spawn probability and NOT a price.
 */
public final class RarityConfig {
    public double wShiny = 3.0, wLegendary = 4.0, wMythical = 5.0, wSpecial = 2.5, wDemand = 2.0;
    public double wWatchlist = 6.0, wDexMissing = 1.0, wCompetitive = 1.5;

    public boolean shiny = true, legendary = true, mythical = true, ultraBeast = true, paradox = true;
    public boolean watchlist = true, dexMissing = false, competitive = false;
    /** Also target Pokémon the market data says are in demand (not only shinies/legendaries/watchlist). */
    public boolean valuable = true;
    /** Demand in [0,1] from market data at or above which a species counts as valuable. Needs market data; unknown demand never qualifies. */
    public double valuableDemand = 0.6;

    /** Species the user considers competitively valuable (manually curated; empty by default, no assumptions). */
    public Set<String> competitiveSpecies = new HashSet<>();
    /** Minimum score for a Pokémon to appear on the radar. */
    public double minScore = 0.01;

    public void sanitize() {
        wShiny = clamp(wShiny); wLegendary = clamp(wLegendary); wMythical = clamp(wMythical);
        wSpecial = clamp(wSpecial); wDemand = clamp(wDemand); wWatchlist = clamp(wWatchlist);
        wDexMissing = clamp(wDexMissing); wCompetitive = clamp(wCompetitive);
        if (competitiveSpecies == null) competitiveSpecies = new HashSet<>();
        if (!Double.isFinite(minScore) || minScore < 0) minScore = 0.01;
    }

    private static double clamp(double w) { return Double.isFinite(w) ? Math.max(0, Math.min(100, w)) : 0; }
}
