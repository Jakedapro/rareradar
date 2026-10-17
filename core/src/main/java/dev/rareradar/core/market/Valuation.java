package dev.rareradar.core.market;

import dev.rareradar.core.util.Estimate;

import java.util.List;

/**
 * Heuristic value estimate: V = V_species + V_shiny + V_competitive + V_rarity, or a direct market estimate when the
 * exact bucket has data. This is an initial heuristic, not a verified market equation; {@code assumptions} lists
 * every gap so the UI can show it.
 */
public record Valuation(Estimate total, Estimate species, Estimate shiny, Estimate competitive, Estimate rarity,
                        String method, List<String> assumptions) {}
