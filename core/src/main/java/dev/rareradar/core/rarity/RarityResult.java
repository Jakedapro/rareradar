package dev.rareradar.core.rarity;

import java.util.EnumSet;

/**
 * Outcome of classifying one Pokémon. {@code primary} is the most notable category (null if none qualifies),
 * {@code demandKnown} says whether the D term came from real market data or was left at zero.
 */
public record RarityResult(double score, Category primary, EnumSet<Category> categories, boolean demandKnown,
                           int watchPriority) {
    public static final RarityResult NONE = new RarityResult(0, null, EnumSet.noneOf(Category.class), false, 0);

    public boolean qualifies() { return primary != null; }
}
