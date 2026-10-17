package dev.rareradar.core.market;

import dev.rareradar.core.rarity.PokemonObservation;

import java.util.UUID;

/**
 * One price data point entered or imported by the user. A LISTING is an asking price and proves nothing about value;
 * SALE is a confirmed completed transaction; STS is a server-sell payout; PURCHASE is what the user paid.
 */
public final class PriceObservation {
    public enum Kind { LISTING, SALE, STS, PURCHASE }
    public enum Source { MANUAL, IMPORT, OWN_TRADE }

    public String id = UUID.randomUUID().toString();
    public String species;
    public String form;
    public Boolean shiny;
    public int[] ivs;          // null when not recorded
    public String nature, ability;
    public Kind kind = Kind.LISTING;
    public double price;
    public long timestamp;
    public Source source = Source.MANUAL;
    public String note = "";

    public static PriceObservation of(String species, boolean shiny, Kind kind, double price, long ts) {
        PriceObservation p = new PriceObservation();
        p.species = PokemonObservation.norm(species);
        p.shiny = shiny;
        p.kind = kind;
        p.price = price;
        p.timestamp = ts;
        return p;
    }

    public PriceObservation ivs(int... v) { this.ivs = v.length == 6 ? v.clone() : null; return this; }
    public PriceObservation form(String f) { this.form = PokemonObservation.norm(f); return this; }

    public MarketKey key() { return new MarketKey(species, form, Boolean.TRUE.equals(shiny)); }

    public int perfectIvs() {
        if (ivs == null) return -1;
        int n = 0;
        for (int v : ivs) if (v == 31) n++;
        return n;
    }

    /** Rejects records that would poison statistics. Returns an error message or null when valid. */
    public String validate() {
        if (species == null || species.isBlank()) return "missing species";
        if (kind == null) return "missing kind";
        if (!Double.isFinite(price) || price < 0) return "price must be a non-negative number";
        if (price > 1e12) return "price is implausibly large";
        if (timestamp <= 0) return "missing timestamp";
        if (ivs != null) {
            if (ivs.length != 6) return "IVs need 6 values";
            for (int v : ivs) if (v < 0 || v > 31) return "IVs must be 0-31";
        }
        return null;
    }

    public void normalize() {
        species = PokemonObservation.norm(species);
        form = form == null || form.isBlank() ? null : PokemonObservation.norm(form);
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (source == null) source = Source.MANUAL;
        if (note == null) note = "";
        // Cobblemon ids such as "cobblemon.ability.hugepower" / "cobblemon:jolly" → "hugepower" / "jolly"
        ability = lastSegment(ability);
        nature = lastSegment(nature);
    }

    static String lastSegment(String s) {
        if (s == null || s.isBlank()) return null;
        int i = Math.max(s.lastIndexOf('.'), s.lastIndexOf(':'));
        return (i >= 0 ? s.substring(i + 1) : s).trim().toLowerCase(java.util.Locale.ROOT);
    }
}
