package dev.rareradar.core.pokebuilder;

import dev.rareradar.core.util.Estimate;

/**
 * A possible PokéBuilder change for one Pokémon. Options sharing a {@code pokemonId} are mutually exclusive
 * alternatives (e.g. "IVs only" vs "IVs + nature"), so combine changes into one option when they should be bought together.
 */
public record UpgradeOption(String pokemonId, String label, double tokenCost, Estimate valueBefore, Estimate valueAfter) {
    /** Expected value gain; NaN when either value is unknown. */
    public double gain() {
        return valueBefore.known() && valueAfter.known() ? valueAfter.value() - valueBefore.value() : Double.NaN;
    }

    /** Pessimistic gain: lower bound after minus upper bound before. */
    public double gainLow() {
        return valueBefore.known() && valueAfter.known() && Double.isFinite(valueAfter.low()) && Double.isFinite(valueBefore.high())
                ? valueAfter.low() - valueBefore.high() : Double.NaN;
    }
}
