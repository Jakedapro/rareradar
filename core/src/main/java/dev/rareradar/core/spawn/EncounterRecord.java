package dev.rareradar.core.spawn;

/** One first-sighting of a wild Pokémon entity. */
public final class EncounterRecord {
    public long ts;
    public String species, form;
    public boolean shiny;
    public String rarity;          // primary rarity category label, or null
    public boolean target;         // matched a watchlist/hunt target at the time
    public String biome, dimension, weather;
    public TimeBucket time = TimeBucket.UNKNOWN;
    public int x, y, z;
    public String session;

    public String biomeKey() { return biome == null ? "unknown" : biome; }
}
