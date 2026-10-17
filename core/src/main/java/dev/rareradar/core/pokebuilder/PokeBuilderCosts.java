package dev.rareradar.core.pokebuilder;

import java.util.HashMap;
import java.util.Map;

/**
 * Token prices as observed in CobbleGalaxy's PokéBuilder screen (captured read-only from the data the server sends
 * when the menu is open) or typed in by the user. NaN / missing = unknown; never guessed.
 */
public final class PokeBuilderCosts {
    public long observedAt;
    public String species;                 // the Pokémon the menu was open for (costs may differ per Pokémon)
    public double balance = Double.NaN;    // tokens the player had
    public double costPerIv = Double.NaN;
    public double costPerEv = Double.NaN;
    public int ivMax = 31;
    public double shinyCost = Double.NaN;
    public boolean shinyAvailable;
    public double levelUpCost = Double.NaN;
    public Map<String, Double> natureCosts = new HashMap<>();
    public Map<String, Double> abilityCosts = new HashMap<>();
    public Map<String, Double> ballCosts = new HashMap<>();
    public String source = "manual";

    /** Tokens to raise each IV to {@code target} (assumes costPerIv is charged per IV point, as the field name says). */
    public double ivUpgradeCost(int[] ivs, int target) {
        if (!Double.isFinite(costPerIv) || ivs == null) return Double.NaN;
        int points = 0;
        for (int v : ivs) points += Math.max(0, Math.min(target, ivMax) - v);
        return points * costPerIv;
    }

    public double natureCost(String nature) {
        Double c = nature == null ? null : natureCosts.get(nature.toLowerCase(java.util.Locale.ROOT));
        return c == null ? Double.NaN : c;
    }
}
