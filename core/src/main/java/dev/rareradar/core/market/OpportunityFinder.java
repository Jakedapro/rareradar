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

    /**
     * Theoretical resale margin for buying {@code listing} and reselling at the typical confirmed-sale price, after
     * the seller-side GTS fee: {@code typical × (1 − fee/100) − price}. NaN when the fee or the typical value is
     * unknown, or when the typical value is not backed by at least MEDIUM-confidence (i.e. confirmed-sale) evidence.
     */
    public double netAfterFee(PriceObservation listing, double feePercent, long now) {
        if (!Double.isFinite(feePercent) || feePercent < 0 || feePercent >= 100) return Double.NaN;
        Estimate e = val.bucketValue(listing.key(), now);
        if (!e.known() || e.confidence().ordinal() < Confidence.MEDIUM.ordinal()) return Double.NaN;
        return e.value() * (1 - feePercent / 100) - listing.price;
    }

    /** Minimum lower-bound (90%) confirmed sales per week for a resale to be considered liquid. */
    public static final double MIN_SALES_PER_WEEK = 0.5;

    public enum Verdict { BUY_CANDIDATE, MARGINAL, NO_MARGIN, ILLIQUID, NOT_ENOUGH_EVIDENCE }

    /**
     * A listing judged as a resale: buy at the asking price, resell at confirmed-sale prices after the fee.
     *
     * @param expectedNet     typical (median) sale × (1 − fee) − price
     * @param conservativeNet lower-quartile sale × (1 − fee) − price — what a below-typical resale would still earn
     * @param daysToSell      7 / observed sales per week (rough expected wait for one sale)
     */
    public record Assessment(PriceObservation listing, Estimate resale, double feePercent, double expectedNet, double conservativeNet,
                             double salesPerWeekLow, double daysToSell, Verdict verdict, String reason) {}

    /**
     * Judges one listing as a resale opportunity. Requires MEDIUM+ confirmed-sale evidence; accounts for the fee
     * (an unknown fee caps the verdict at MARGINAL), uncertainty (the lower quartile must still profit) and liquidity
     * (the 90% lower bound of sales/week must reach {@link #MIN_SALES_PER_WEEK}). Purchasing stays with the player.
     */
    public Assessment assess(PriceObservation listing, double feePercent, long now) {
        Estimate e = val.bucketValue(listing.key(), now);
        if (listing.kind != PriceObservation.Kind.LISTING || !e.known() || e.confidence().ordinal() < Confidence.MEDIUM.ordinal())
            return new Assessment(listing, e, feePercent, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Verdict.NOT_ENOUGH_EVIDENCE,
                    "needs MEDIUM+ confirmed-sale evidence (" + e.basis() + ")");
        boolean feeKnown = Double.isFinite(feePercent) && feePercent >= 0 && feePercent < 100;
        double keep = feeKnown ? 1 - feePercent / 100 : 1;
        double expected = e.value() * keep - listing.price, conservative = e.low() * keep - listing.price;
        MarketStats st = db.stats(listing.key(), now);
        double spwLow = st.salesPerWeekLow(), days = Double.isFinite(st.salesPerWeek()) && st.salesPerWeek() > 0 ? 7 / st.salesPerWeek() : Double.NaN;
        Verdict v; String why;
        if (!(expected > 0)) { v = Verdict.NO_MARGIN; why = "no margin at the typical sale price" + (feeKnown ? " after fee" : ""); }
        else if (!(spwLow >= MIN_SALES_PER_WEEK)) { v = Verdict.ILLIQUID; why = String.format(Locale.ROOT, "margin exists but sales are too rare to resell reliably (≥%.1f/week at 90%%: %.2f)", MIN_SALES_PER_WEEK, spwLow); }
        else if (!feeKnown) { v = Verdict.MARGINAL; why = "margin before fee only — set your GTS fee"; }
        else if (conservative > 0) { v = Verdict.BUY_CANDIDATE; why = "even a lower-quartile resale profits after fee"; }
        else { v = Verdict.MARGINAL; why = "profits at a typical resale, not at a below-typical one"; }
        return new Assessment(listing, e, feePercent, expected, conservative, spwLow, days, v, why);
    }

    /** Recent (3-day) listings judged BUY_CANDIDATE or MARGINAL, best conservative margin first. */
    public List<Assessment> assessRecentListings(double feePercent, long now) {
        List<Assessment> out = new ArrayList<>();
        for (MarketKey k : db.keys())
            for (PriceObservation p : Listings.distinct(db.of(k)))
                if (p.kind == PriceObservation.Kind.LISTING && now - p.timestamp < 3 * 86_400_000L) {
                    Assessment a = assess(p, feePercent, now);
                    if (a.verdict() == Verdict.BUY_CANDIDATE || a.verdict() == Verdict.MARGINAL) out.add(a);
                }
        out.sort(Comparator.comparingDouble((Assessment a) -> a.conservativeNet()).reversed());
        return out;
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
