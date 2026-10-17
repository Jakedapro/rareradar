package dev.rareradar.core.market;

/** Market bucket: species + form + shiny. IV/nature differences are handled inside a bucket. */
public record MarketKey(String species, String form, boolean shiny) {
    public MarketKey base() { return new MarketKey(species, form, false); }
    public MarketKey asShiny() { return new MarketKey(species, form, true); }

    @Override public String toString() {
        return (shiny ? "Shiny " : "") + species + (form == null ? "" : " (" + form + ")");
    }
}
