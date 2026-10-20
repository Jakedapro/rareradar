package dev.rareradar.core.rarity;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Configurable classifier replacing hardcoded tier checks. Pure and allocation-light; callers cache results per
 * entity because a wild Pokémon's species/form/shiny state does not change while it is loaded.
 */
public final class RarityEngine {
    private final RarityConfig cfg;
    private final Watchlist watchlist;
    /** Demand in [0,1] per species from market data; NaN when there is no data. */
    private final ToDoubleFunction<PokemonObservation> demand;
    /** True when the species is not yet in the player's Pokédex. */
    private final Predicate<String> dexMissing;

    public RarityEngine(RarityConfig cfg, Watchlist watchlist, ToDoubleFunction<PokemonObservation> demand,
                        Predicate<String> dexMissing) {
        this.cfg = cfg;
        this.watchlist = watchlist;
        this.demand = demand == null ? o -> Double.NaN : demand;
        this.dexMissing = dexMissing == null ? s -> false : dexMissing;
    }

    public RarityConfig config() { return cfg; }

    public RarityResult classify(PokemonObservation o) {
        if (o == null || o.owned || o.species == null) return RarityResult.NONE;
        EnumSet<Category> cats = EnumSet.noneOf(Category.class);
        if (cfg.mythical && o.has("mythical")) cats.add(Category.MYTHICAL);
        if (cfg.legendary && o.has("legendary")) cats.add(Category.LEGENDARY);
        if (cfg.ultraBeast && o.has("ultra_beast")) cats.add(Category.ULTRA_BEAST);
        if (cfg.paradox && o.has("paradox")) cats.add(Category.PARADOX);
        if (cfg.shiny && o.isShiny()) cats.add(Category.SHINY);
        int watchPriority = 0;
        double d0 = demand.applyAsDouble(o);
        if (cfg.valuable && Double.isFinite(d0) && d0 >= cfg.valuableDemand) cats.add(Category.VALUABLE);
        if (cfg.watchlist && watchlist != null) {
            Watchlist.Entry e = watchlist.match(o);
            if (e != null) { cats.add(Category.WATCHLIST); watchPriority = Math.max(1, e.priority); }
        }
        if (cfg.competitive && cfg.competitiveSpecies.contains(o.species)) cats.add(Category.COMPETITIVE);
        if (cfg.dexMissing && dexMissing.test(o.species)) cats.add(Category.DEX_MISSING);

        double d = demand.applyAsDouble(o);
        boolean demandKnown = Double.isFinite(d);
        double D = demandKnown ? Math.max(0, Math.min(1, d)) : 0;

        double r = cfg.wShiny * bit(cats, Category.SHINY)
                + cfg.wLegendary * bit(cats, Category.LEGENDARY)
                + cfg.wMythical * bit(cats, Category.MYTHICAL)
                + cfg.wSpecial * (bit(cats, Category.ULTRA_BEAST) + bit(cats, Category.PARADOX) > 0 ? 1 : 0)
                + cfg.wDemand * D
                + cfg.wWatchlist * (watchPriority > 0 ? Math.min(watchPriority, 10) / 10.0 : 0)
                + cfg.wCompetitive * bit(cats, Category.COMPETITIVE)
                + cfg.wDexMissing * bit(cats, Category.DEX_MISSING);

        Category primary = null;
        for (Category c : Category.values()) if (cats.contains(c)) { primary = c; break; }
        if (primary == null || r < cfg.minScore) return new RarityResult(r, null, cats, demandKnown, watchPriority);
        return new RarityResult(r, primary, cats, demandKnown, watchPriority);
    }

    private static int bit(Set<Category> s, Category c) { return s.contains(c) ? 1 : 0; }
}
