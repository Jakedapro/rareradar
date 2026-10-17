package dev.rareradar.core.market;

import java.util.HashMap;
import java.util.Map;

/** Valuation tuning. Defaults contain no prices: premiums are empty until the user enters them. */
public final class ValuationConfig {
    /** Only observations newer than this are used when enough exist (falls back to all data otherwise). */
    public int recentDays = 60;
    /** Minimum recent observations before the recency window is applied. */
    public int recentMin = 5;
    /** Sales/week at which demand D = 0.5 (D = s / (s + this)). */
    public double demandHalfSalesPerWeek = 1.0;
    /** Optional user-entered premium per rarity category label (e.g. "Legendary" → 50000). Empty = unknown. */
    public Map<String, Double> rarityPremium = new HashMap<>();
    /** Owned Pokémon with at least this many 31 IVs are flagged as potential breeding parents. */
    public int breedingParentPerfectIvs = 4;
    /** Buckets with a lower-bound sales/week above this are flagged as high demand. */
    public double highDemandSalesPerWeek = 1.0;

    public void sanitize() {
        recentDays = Math.max(1, recentDays);
        recentMin = Math.max(1, recentMin);
        if (!(demandHalfSalesPerWeek > 0)) demandHalfSalesPerWeek = 1.0;
        if (rarityPremium == null) rarityPremium = new HashMap<>();
        rarityPremium.values().removeIf(v -> v == null || !Double.isFinite(v) || v < 0);
        breedingParentPerfectIvs = Math.max(1, Math.min(6, breedingParentPerfectIvs));
    }
}
