package dev.rareradar.core.market;

import dev.rareradar.core.rarity.PokemonObservation;
import dev.rareradar.core.util.Confidence;
import dev.rareradar.core.util.Estimate;
import dev.rareradar.core.util.Money;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Scans the market database and the user's own Pokémon for things worth a closer look. */
public final class OpportunityFinder {
    private final MarketDatabase db;
    private final Valuator val;
    private final ValuationConfig cfg;

    public OpportunityFinder(MarketDatabase db, Valuator val, ValuationConfig cfg) {
        this.db = db; this.val = val; this.cfg = cfg;
    }

    /** A listing priced below the lower quartile of confirmed sales (needs ≥ MEDIUM confidence). */
    public Opportunity checkListing(PriceObservation listing, long now) {
        if (listing.kind != PriceObservation.Kind.LISTING) return null;
        Estimate e = val.bucketValue(listing.key(), now);
        if (!e.known() || e.confidence().ordinal() < Confidence.MEDIUM.ordinal() || !(listing.price < e.low())) return null;
        double gap = e.value() - listing.price;
        return new Opportunity(Opportunity.Type.POTENTIALLY_UNDERVALUED, listing.key().toString(), gap, e.confidence(),
                String.format(Locale.ROOT, "Asking %s vs typical sale %s (%s). Possibly undervalued — check IVs/nature; resale is not guaranteed.",
                        Money.fmt(listing.price), Money.range(e), e.basis()));
    }

    public List<Opportunity> marketWide(long now) {
        List<Opportunity> out = new ArrayList<>();
        for (MarketKey k : db.keys()) {
            MarketStats s = db.stats(k, now);
            if (Double.isFinite(s.salesPerWeekLow()) && s.salesPerWeekLow() >= cfg.highDemandSalesPerWeek) {
                out.add(new Opportunity(Opportunity.Type.STRONG_DEMAND, k.toString(), s.salesPerWeek(),
                        s.sales() >= 10 ? Confidence.MEDIUM : Confidence.LOW,
                        String.format(Locale.ROOT, "%.1f recorded sales/week (90%% interval %.1f–%.1f) from %d sales you logged.",
                                s.salesPerWeek(), s.salesPerWeekLow(), s.salesPerWeekHigh(), s.sales())));
            }
            if (k.shiny()) {
                MarketStats b = db.stats(k.base(), now);
                if (s.sales() > 0 && b.sales() > 0 && b.saleMedian() > 0) {
                    double ratio = s.saleMedian() / b.saleMedian();
                    out.add(new Opportunity(Opportunity.Type.HIGH_SHINY_PREMIUM, k.toString(), ratio,
                            Math.min(s.sales(), b.sales()) >= 5 ? Confidence.MEDIUM : Confidence.LOW,
                            String.format(Locale.ROOT, "Shiny sells at ×%.1f the non-shiny median (%d vs %d confirmed sales).",
                                    ratio, s.sales(), b.sales())));
                }
            }
            for (PriceObservation p : db.of(k)) {
                if (p.kind == PriceObservation.Kind.LISTING && now - p.timestamp < 3 * 86_400_000L) {
                    Opportunity o = checkListing(p, now);
                    if (o != null) out.add(o);
                }
            }
        }
        out.sort(Comparator.comparing(Opportunity::type).thenComparing(Comparator.comparingDouble(Opportunity::score).reversed()));
        return out;
    }

    /** Keep / sell / breed advice for one of the player's own Pokémon. */
    public Opportunity advise(PokemonObservation own, long now) {
        int perfect = own.perfectIvs();
        Valuation v = val.value(own, now);
        if (perfect >= cfg.breedingParentPerfectIvs) {
            return new Opportunity(Opportunity.Type.BREEDING_CANDIDATE, own.species, perfect, Confidence.MEDIUM,
                    perfect + " perfect IVs: may be worth keeping as a breeding parent (Destiny Knot passes 5 IVs per the server wiki). "
                            + "Estimated sale value: " + Money.range(v.total()) + ".");
        }
        if (!v.total().known()) {
            return new Opportunity(Opportunity.Type.CONSIDER_KEEPING, own.species, 0, Confidence.NONE,
                    "No market data — log a few GTS prices for " + own.species + " before deciding to sell.");
        }
        MarketStats s = db.stats(new MarketKey(own.species, own.form, own.isShiny()), now);
        boolean liquid = Double.isFinite(s.salesPerWeek()) && s.salesPerWeek() >= 0.5;
        if (liquid) {
            return new Opportunity(Opportunity.Type.CONSIDER_SELLING, own.species, v.total().value(), v.total().confidence(),
                    "Sells regularly (" + String.format(Locale.ROOT, "%.1f", s.salesPerWeek()) + "/week). Estimated value "
                            + Money.range(v.total()) + ".");
        }
        return new Opportunity(Opportunity.Type.CONSIDER_KEEPING, own.species, v.total().value(), Confidence.LOW,
                "Few recorded sales; it may take time to sell. Estimated value " + Money.range(v.total()) + ".");
    }
}
