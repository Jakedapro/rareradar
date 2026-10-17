package dev.rareradar.core.market;

import dev.rareradar.core.rarity.PokemonObservation;
import dev.rareradar.core.util.Confidence;
import dev.rareradar.core.util.Estimate;
import dev.rareradar.core.util.Stats;

import java.util.ArrayList;
import java.util.List;

/** Turns market observations into value estimates with uncertainty. */
public final class Valuator {
    private final MarketDatabase db;
    private final ValuationConfig cfg;
    private dev.rareradar.core.ml.PriceModel model;

    public Valuator(MarketDatabase db, ValuationConfig cfg) {
        this.db = db;
        this.cfg = cfg;
    }

    /** Optional learned model; consulted only when it passed cross-validation and bucket data is weak. */
    public void setModel(dev.rareradar.core.ml.PriceModel m) { this.model = m; }

    /** Market estimate for one bucket from its own observations (recent window when enough data). */
    public Estimate bucketValue(MarketKey k, long now) {
        List<PriceObservation> obs = recent(db.of(k), now);
        if (obs.isEmpty()) return Estimate.unknown("no observations for " + k);
        MarketStats s = MarketStats.compute(k, obs, now);
        double floor = s.sts() > 0 ? s.stsMedian() : Double.NaN;
        Estimate e;
        if (s.sales() >= 3) {
            e = Estimate.of(s.saleMedian(), s.saleQ1(), s.saleQ3(), s.sales(), s.sales() + " confirmed sales");
        } else if (s.sales() > 0) {
            double[] sv = obs.stream().filter(p -> p.kind == PriceObservation.Kind.SALE || p.kind == PriceObservation.Kind.PURCHASE)
                    .mapToDouble(p -> p.price).toArray();
            double lo = Double.MAX_VALUE, hi = 0;
            for (double v : sv) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
            e = new Estimate(s.saleMedian(), lo, hi, s.sales(), Confidence.LOW, s.sales() + " confirmed sale(s) only");
        } else if (s.listings() > 0) {
            // Asking prices are upper-ish bounds: use the lower quartile of asks as the central guess.
            e = new Estimate(s.listQ1(), s.listMin(), s.listMedian(), s.listings(), Confidence.LOW,
                    "asking prices only (" + s.listings() + " listings); real sale prices may be lower");
        } else {
            e = new Estimate(floor, floor, floor, s.sts(), Confidence.LOW, "server-sell (STS) payouts only");
        }
        if (Double.isFinite(floor) && e.known() && e.low() < floor) {
            e = new Estimate(Math.max(e.value(), floor), floor, Math.max(e.high(), floor), e.samples(), e.confidence(),
                    e.basis() + "; STS payout used as a floor");
        }
        return e;
    }

    private List<PriceObservation> recent(List<PriceObservation> all, long now) {
        long cutoff = now - cfg.recentDays * 86_400_000L;
        List<PriceObservation> r = new ArrayList<>();
        for (PriceObservation p : all) if (p.timestamp >= cutoff) r.add(p);
        return r.size() >= cfg.recentMin ? r : all;
    }

    /**
     * Shiny multiplier learned from species that have confirmed sale medians for both shiny and non-shiny buckets.
     * Returns {median ratio, low, high, pairs} or NaNs when no pairs exist.
     */
    public double[] shinyMultiplier(long now) {
        List<Double> ratios = new ArrayList<>();
        for (MarketKey k : db.keys()) {
            if (!k.shiny()) continue;
            MarketStats sh = db.stats(k, now), base = db.stats(k.base(), now);
            if (sh.sales() > 0 && base.sales() > 0 && base.saleMedian() > 0) ratios.add(sh.saleMedian() / base.saleMedian());
        }
        if (ratios.isEmpty()) return new double[]{Double.NaN, Double.NaN, Double.NaN, 0};
        double[] r = Stats.toArray(ratios);
        return new double[]{Stats.median(r), Stats.quantile(r, 0.25), Stats.quantile(r, 0.75), r.length};
    }

    /** OLS slope of price per extra 31-IV within a species' observations with recorded IVs; NaN when underdetermined. */
    public double ivPremiumPerPerfect(String species, String form, boolean shiny) {
        List<double[]> pts = new ArrayList<>();
        for (PriceObservation p : db.of(new MarketKey(species, form, shiny))) {
            if (p.ivs != null && p.kind != PriceObservation.Kind.LISTING) pts.add(new double[]{p.perfectIvs(), p.price});
        }
        if (pts.size() < 4) return Double.NaN;
        double mx = 0, my = 0;
        for (double[] q : pts) { mx += q[0]; my += q[1]; }
        mx /= pts.size(); my /= pts.size();
        double sxx = 0, sxy = 0;
        for (double[] q : pts) { sxx += (q[0] - mx) * (q[0] - mx); sxy += (q[0] - mx) * (q[1] - my); }
        return sxx < 1e-9 ? Double.NaN : sxy / sxx;
    }

    public Valuation value(PokemonObservation o, long now) {
        List<String> notes = new ArrayList<>();
        MarketKey exact = new MarketKey(o.species, o.form, o.isShiny());
        Estimate direct = bucketValue(exact, now);
        Estimate base = o.isShiny() ? bucketValue(exact.base(), now) : direct;

        // Shiny component
        Estimate shiny;
        if (!o.isShiny()) shiny = new Estimate(0, 0, 0, 0, Confidence.HIGH, "not shiny");
        else if (base.known()) {
            double[] m = shinyMultiplier(now);
            if (Double.isFinite(m[0])) {
                shiny = new Estimate(base.value() * (m[0] - 1), base.value() * (m[1] - 1), base.value() * (m[2] - 1),
                        (int) m[3], Confidence.of((int) m[3], (m[2] - m[1]) / Math.max(1e-9, m[0])),
                        "shiny multiplier ×" + String.format(java.util.Locale.ROOT, "%.2f", m[0]) + " learned from " + (int) m[3] + " species");
            } else {
                shiny = Estimate.unknown("no species with both shiny and non-shiny confirmed sales yet");
            }
        } else shiny = Estimate.unknown("non-shiny value unknown");

        // Competitive (IV) component
        Estimate comp;
        if (o.ivs == null) comp = new Estimate(0, 0, 0, 0, Confidence.NONE, "IVs not visible; IV premium not applied");
        else {
            double slope = ivPremiumPerPerfect(o.species, o.form, o.isShiny());
            if (Double.isFinite(slope)) {
                double medPerfect = medianPerfect(exact);
                double v = slope * (o.perfectIvs() - medPerfect);
                comp = new Estimate(v, Math.min(0, v), Math.max(0, v), db.of(exact).size(), Confidence.LOW,
                        "linear IV premium " + Math.round(slope) + "/perfect IV vs typical " + medPerfect);
            } else comp = new Estimate(0, 0, 0, 0, Confidence.NONE, "not enough IV-tagged sales to price IVs");
        }

        // Rarity component (user-entered only)
        Estimate rar = new Estimate(0, 0, 0, 0, Confidence.NONE, "no user-entered rarity premium");
        for (String label : List.of("mythical", "legendary", "ultra_beast", "paradox")) {
            Double p = cfg.rarityPremium.get(label);
            if (o.has(label) && p != null) { rar = new Estimate(p, p, p, 1, Confidence.LOW, "user-entered " + label + " premium"); break; }
        }

        Estimate total;
        String method;
        if (direct.known()) {
            method = "direct market data for " + exact;
            double adj = comp.known() ? comp.value() : 0;
            total = new Estimate(direct.value() + adj, direct.low() + Math.min(0, adj), direct.high() + Math.max(0, adj),
                    direct.samples(), direct.confidence(), direct.basis());
        } else if (base.known() && (shiny.known())) {
            method = "component model V_species + V_shiny + V_competitive + V_rarity";
            double v = base.value() + shiny.value() + comp.value() + rar.value();
            double lo = base.low() + shiny.low() + Math.min(0, comp.low()) + rar.low();
            double hi = base.high() + shiny.high() + Math.max(0, comp.high()) + rar.high();
            total = new Estimate(v, Math.max(0, lo), hi, Math.min(base.samples(), shiny.samples()),
                    base.confidence().min(shiny.confidence()).min(Confidence.MEDIUM), "component model");
            notes.add("No direct data for this exact bucket; value is extrapolated");
        } else if (rar.value() > 0) {
            method = "user rarity premium only";
            total = rar;
            notes.add("No market data; using only your entered rarity premium");
        } else {
            method = "insufficient data";
            total = Estimate.unknown("no usable market data for " + exact + "; add GTS observations");
        }
        if (model != null && model.usable() && (!total.known() || total.confidence() == Confidence.LOW)) {
            Estimate learned = model.predict(o);
            if (learned.known()) {
                notes.add("Heuristic estimate was " + (total.known() ? "low-confidence" : "unavailable") + "; using the learned model (" + model.metrics().summary() + ")");
                total = learned;
                method = "learned model";
            }
        }
        if (!direct.known() && direct.basis() != null) notes.add(direct.basis());
        if (o.ivs == null) notes.add("IVs unknown (not visible to the client for wild Pokémon)");
        if (direct.known() && direct.basis().startsWith("asking")) notes.add("Listings are not sales: a high asking price does not prove value");
        return new Valuation(total, base, shiny, comp, rar, method, notes);
    }

    private double medianPerfect(MarketKey k) {
        List<Double> v = new ArrayList<>();
        for (PriceObservation p : db.of(k)) if (p.ivs != null) v.add((double) p.perfectIvs());
        return v.isEmpty() ? 0 : Stats.median(Stats.toArray(v));
    }

    /** Market demand D ∈ [0,1] for the rarity score, from liquidity; NaN when there is no data for the species. */
    public double demand(String species, String form, long now) {
        MarketStats a = db.stats(new MarketKey(species, form, false), now);
        MarketStats b = db.stats(new MarketKey(species, form, true), now);
        if (a.total() + b.total() == 0) return Double.NaN;
        double spw = (Double.isFinite(a.salesPerWeek()) ? a.salesPerWeek() : 0) + (Double.isFinite(b.salesPerWeek()) ? b.salesPerWeek() : 0);
        return spw / (spw + cfg.demandHalfSalesPerWeek);
    }
}
