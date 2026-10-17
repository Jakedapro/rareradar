package dev.rareradar.core.market;

import dev.rareradar.core.store.AsyncWriter;
import dev.rareradar.core.store.JsonlStore;
import dev.rareradar.core.util.Log;

import java.nio.file.Path;
import java.util.*;

/**
 * Local Pokémon market database: manual GTS entries and user imports, persisted as JSON Lines.
 * All reads are in-memory; writes go to the background IO thread. Not thread-safe: use from the client thread.
 */
public final class MarketDatabase {
    private final JsonlStore<PriceObservation> store;
    private final List<PriceObservation> all = new ArrayList<>();
    private final Map<MarketKey, List<PriceObservation>> byKey = new HashMap<>();
    private final Map<MarketKey, MarketStats> statsCache = new HashMap<>();
    private long statsCacheTime = -1;

    public MarketDatabase(Path file, AsyncWriter writer) {
        this.store = new JsonlStore<>(file, PriceObservation.class, writer);
        int bad = 0;
        for (PriceObservation p : store.load()) {
            p.normalize();
            String err = p.validate();
            if (err != null) { bad++; continue; }
            index(p);
        }
        if (bad > 0) Log.warn("Market DB: ignored " + bad + " invalid record(s)");
    }

    private void index(PriceObservation p) {
        all.add(p);
        byKey.computeIfAbsent(p.key(), k -> new ArrayList<>()).add(p);
        statsCache.remove(p.key());
    }

    /** Validates and stores an observation. Throws IllegalArgumentException with a user-readable reason. */
    public void add(PriceObservation p) {
        p.normalize();
        String err = p.validate();
        if (err != null) throw new IllegalArgumentException(err);
        index(p);
        store.append(p);
    }

    public boolean remove(String id) {
        Iterator<PriceObservation> it = all.iterator();
        while (it.hasNext()) {
            PriceObservation p = it.next();
            if (p.id.equals(id)) {
                it.remove();
                List<PriceObservation> l = byKey.get(p.key());
                if (l != null) { l.remove(p); if (l.isEmpty()) byKey.remove(p.key()); }
                statsCache.remove(p.key());
                store.rewrite(all);
                return true;
            }
        }
        return false;
    }

    public List<PriceObservation> all() { return Collections.unmodifiableList(all); }
    public List<PriceObservation> of(MarketKey k) { return byKey.getOrDefault(k, List.of()); }
    public Set<MarketKey> keys() { return Collections.unmodifiableSet(byKey.keySet()); }
    public int size() { return all.size(); }

    /** Cached per bucket; cache is invalidated on writes to that bucket and when {@code now} moves by over an hour. */
    public MarketStats stats(MarketKey k, long now) {
        if (statsCacheTime < 0 || Math.abs(now - statsCacheTime) > 3600_000L) { statsCache.clear(); statsCacheTime = now; }
        return statsCache.computeIfAbsent(k, kk -> MarketStats.compute(kk, of(kk), now));
    }

    /** Imports observations, rejecting invalid rows. Returns {accepted, rejected}. */
    public int[] importAll(Collection<PriceObservation> in) {
        int ok = 0, bad = 0;
        List<PriceObservation> accepted = new ArrayList<>();
        for (PriceObservation p : in) {
            if (p == null) { bad++; continue; }
            p.normalize();
            if (p.source == PriceObservation.Source.MANUAL) p.source = PriceObservation.Source.IMPORT;
            if (p.validate() != null) { bad++; continue; }
            index(p);
            accepted.add(p);
            ok++;
        }
        if (!accepted.isEmpty()) store.appendAll(accepted);
        return new int[]{ok, bad};
    }
}
