package dev.rareradar.core.spawn;

import dev.rareradar.core.store.AsyncWriter;
import dev.rareradar.core.store.JsonlStore;

import java.nio.file.Path;
import java.util.*;

/** Persistent first-sighting log with per-entity de-duplication (bounded LRU so memory stays flat). */
public final class EncounterLog {
    private static final int DEDUPE_CAP = 8192;
    private final JsonlStore<EncounterRecord> store;
    private final List<EncounterRecord> records = new ArrayList<>();
    private final LinkedHashMap<UUID, Boolean> seen = new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> e) { return size() > DEDUPE_CAP; }
    };

    public EncounterLog(Path file, AsyncWriter writer) {
        store = new JsonlStore<>(file, EncounterRecord.class, writer);
        for (EncounterRecord r : store.load()) if (r.species != null && r.ts > 0) {
            if (r.time == null) r.time = TimeBucket.UNKNOWN;
            records.add(intern(r));
        }
    }

    /** Records the entity if it has not been seen before. Returns true when it was new. */
    public boolean record(UUID entity, EncounterRecord r) {
        if (entity != null && seen.put(entity, Boolean.TRUE) != null) return false;
        records.add(intern(r));
        store.append(r);
        return true;
    }

    /**
     * Encounters are kept in memory for statistics; the few distinct species/biome/dimension/weather/session
     * strings are shared so a long history costs tens of bytes per record instead of hundreds.
     */
    private final Map<String, String> pool = new HashMap<>();

    private EncounterRecord intern(EncounterRecord r) {
        r.species = pooled(r.species); r.form = pooled(r.form); r.rarity = pooled(r.rarity);
        r.biome = pooled(r.biome); r.dimension = pooled(r.dimension); r.weather = pooled(r.weather); r.session = pooled(r.session);
        return r;
    }

    private String pooled(String s) { return s == null ? null : pool.computeIfAbsent(s, k -> k); }

    public boolean alreadySeen(UUID entity) { return entity != null && seen.containsKey(entity); }

    /** Forget de-duplication state (e.g. after a teleport the same entity ids won't recur anyway). */
    public void resetDedupe() { seen.clear(); }

    public List<EncounterRecord> all() { return Collections.unmodifiableList(records); }

    public List<EncounterRecord> since(long ts) {
        List<EncounterRecord> r = new ArrayList<>();
        for (int i = records.size() - 1; i >= 0 && records.get(i).ts >= ts; i--) r.add(records.get(i));
        Collections.reverse(r);
        return r;
    }
}
