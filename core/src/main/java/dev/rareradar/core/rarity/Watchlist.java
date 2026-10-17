package dev.rareradar.core.rarity;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** User watchlist of specific species/forms. Import/export is plain JSON so lists can be shared. */
public final class Watchlist {
    public static final class Entry {
        public String species;
        public String form;          // null = any form
        public boolean shinyOnly;
        public int priority = 5;     // 1..10, higher = more important
        public String note = "";

        public Entry() {}
        public Entry(String species, String form, boolean shinyOnly, int priority, String note) {
            this.species = PokemonObservation.norm(species);
            this.form = PokemonObservation.norm(form);
            this.shinyOnly = shinyOnly;
            this.priority = priority;
            this.note = note == null ? "" : note;
        }

        boolean valid() { return species != null && !species.isBlank(); }

        void normalize() {
            species = PokemonObservation.norm(species);
            form = form == null || form.isBlank() ? null : PokemonObservation.norm(form);
            priority = Math.max(1, Math.min(10, priority));
            if (note == null) note = "";
        }

        @Override public boolean equals(Object o) {
            return o instanceof Entry e && Objects.equals(species, e.species) && Objects.equals(form, e.form) && shinyOnly == e.shinyOnly;
        }
        @Override public int hashCode() { return Objects.hash(species, form, shinyOnly); }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final List<Entry> entries = new ArrayList<>();

    public List<Entry> entries() { return List.copyOf(entries); }

    public void add(Entry e) {
        e.normalize();
        if (!e.valid()) throw new IllegalArgumentException("Watchlist entry needs a species");
        entries.remove(e);
        entries.add(e);
    }

    public boolean remove(String species, String form, boolean shinyOnly) {
        return entries.remove(new Entry(species, form, shinyOnly, 1, null));
    }

    /** Highest-priority entry matching the observation, or null. */
    public Entry match(PokemonObservation o) {
        Entry best = null;
        for (Entry e : entries) {
            if (!e.species.equals(o.species)) continue;
            if (e.form != null && !e.form.equals(o.form)) continue;
            if (e.shinyOnly && !o.isShiny()) continue;
            if (best == null || e.priority > best.priority) best = e;
        }
        return best;
    }

    public String exportJson() { return GSON.toJson(entries); }

    /**
     * Imports entries from JSON, skipping invalid ones. With {@code replace} the list is replaced, otherwise merged
     * (imported entries win on conflict). Returns the number of entries accepted.
     */
    public int importJson(String json, boolean replace) {
        List<Entry> in;
        try {
            in = GSON.fromJson(json, new TypeToken<List<Entry>>() {}.getType());
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Not a valid watchlist JSON array: " + e.getMessage(), e);
        }
        if (in == null) in = List.of();
        if (replace) entries.clear();
        int n = 0;
        for (Entry e : in) {
            if (e == null) continue;
            e.normalize();
            if (!e.valid()) continue;
            entries.remove(e);
            entries.add(e);
            n++;
        }
        return n;
    }
}
