package dev.rareradar.core.rarity;

import java.util.Locale;
import java.util.Set;

/**
 * What the client legitimately knows about one Pokémon at one moment. Every optional attribute is nullable:
 * {@code null} means "not visible to the client", never a default. Wild Pokémon normally expose species, form,
 * shiny, level and labels only; nature/ability/IVs are usually known only for the player's own Pokémon.
 */
public final class PokemonObservation {
    public String species;          // lower-case Cobblemon species id, e.g. "dratini"
    public String form;             // form name or null for the base form
    public Boolean shiny;
    public Set<String> labels = Set.of(); // Cobblemon species + form labels ("legendary", "paradox", ...)
    public Integer level;
    public String nature;
    public String ability;
    public int[] ivs;               // HP, Atk, Def, SpA, SpD, Spe; null when unknown
    public String gender;
    public boolean owned;           // belongs to a player (never a wild target)
    public double x, y, z;
    public String dimension, biome, weather;
    public Long timeOfDay;          // world time-of-day ticks, 0..23999
    public long timestamp;          // epoch millis of observation
    public long entityKey;          // stable per-entity key for caching (e.g. entity id), 0 if none

    public static PokemonObservation of(String species) {
        PokemonObservation o = new PokemonObservation();
        o.species = norm(species);
        return o;
    }

    public PokemonObservation shiny(boolean s) { this.shiny = s; return this; }
    public PokemonObservation labels(String... l) { this.labels = Set.of(l); return this; }
    public PokemonObservation form(String f) { this.form = f == null ? null : norm(f); return this; }
    public PokemonObservation ivs(int... v) { this.ivs = v.length == 6 ? v.clone() : null; return this; }

    public boolean isShiny() { return Boolean.TRUE.equals(shiny); }
    public boolean has(String label) { return labels != null && labels.contains(label); }

    /** Sum of IVs, or -1 when unknown. */
    public int ivTotal() {
        if (ivs == null) return -1;
        int s = 0;
        for (int v : ivs) s += v;
        return s;
    }

    /** Number of 31 IVs, or -1 when unknown. */
    public int perfectIvs() {
        if (ivs == null) return -1;
        int n = 0;
        for (int v : ivs) if (v == 31) n++;
        return n;
    }

    public static String norm(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }
}
