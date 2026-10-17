package dev.rareradar.core.hunt;

import dev.rareradar.core.rarity.PokemonObservation;
import dev.rareradar.core.store.AsyncWriter;
import dev.rareradar.core.store.JsonlStore;

import java.nio.file.Path;
import java.util.*;

/** Active hunts and completion history. Overlay hunts are merged by key so re-sent overlays don't duplicate. */
public final class HuntTracker {
    private final JsonlStore<HuntObjective> store;
    private final List<HuntObjective> hunts = new ArrayList<>();

    public HuntTracker(Path file, AsyncWriter writer) {
        store = new JsonlStore<>(file, HuntObjective.class, writer);
        for (HuntObjective h : store.load()) if (h.validate() == null) {
            if (h.aspects == null) h.aspects = new ArrayList<>();
            if (h.details == null) h.details = new ArrayList<>();
            hunts.add(h);
        }
    }

    public List<HuntObjective> all() { return Collections.unmodifiableList(hunts); }

    public List<HuntObjective> active(long now) {
        List<HuntObjective> a = new ArrayList<>();
        for (HuntObjective h : hunts) if (h.active(now)) a.add(h);
        return a;
    }

    public List<HuntObjective> completed() {
        List<HuntObjective> c = new ArrayList<>();
        for (HuntObjective h : hunts) if (h.completedAt != null) c.add(h);
        return c;
    }

    public void add(HuntObjective h) {
        String err = h.validate();
        if (err != null) throw new IllegalArgumentException(err);
        hunts.add(h);
        persist();
    }

    /**
     * Applies a fresh overlay snapshot: updates matching hunts (progress/details/deadline), adds new ones and marks
     * overlay hunts that disappeared as completed if they had reached their target, otherwise leaves them (they may
     * just have been un-pinned). Returns true if anything changed.
     */
    public boolean syncOverlay(List<HuntObjective> snapshot, long now) {
        boolean changed = false;
        Map<String, HuntObjective> byKey = new HashMap<>();
        for (HuntObjective h : hunts) if (h.source == HuntObjective.Source.OVERLAY && h.completedAt == null) byKey.put(h.overlayKey, h);
        Set<String> seen = new HashSet<>();
        for (HuntObjective s : snapshot) {
            seen.add(s.overlayKey);
            HuntObjective cur = byKey.get(s.overlayKey);
            if (cur == null) { hunts.add(s); changed = true; continue; }
            if (cur.progress != s.progress || cur.required != s.required || !Objects.equals(cur.details, s.details)) {
                cur.progress = s.progress; cur.required = s.required; cur.details = s.details; changed = true;
            }
            if (s.deadline != null) cur.deadline = s.deadline;
            cur.lastSeen = s.lastSeen;
            if (cur.rewardHuntPoints == null) cur.rewardHuntPoints = s.rewardHuntPoints;
            if (cur.rewardMoney == null) cur.rewardMoney = s.rewardMoney;
            if (cur.stars == 0) cur.stars = s.stars;
            if (cur.remaining() == 0 && cur.completedAt == null) { cur.completedAt = now; changed = true; }
        }
        for (HuntObjective h : byKey.values()) {
            if (!seen.contains(h.overlayKey) && h.remaining() == 0 && h.completedAt == null) { h.completedAt = now; changed = true; }
        }
        if (changed) persist();
        return changed;
    }

    /** Manual progress update; completes the hunt when the target is reached. */
    public void progress(String id, int delta, long now) {
        for (HuntObjective h : hunts) if (h.id.equals(id)) {
            h.progress = Math.max(0, Math.min(h.required, h.progress + delta));
            if (h.remaining() == 0 && h.completedAt == null) h.completedAt = now;
            persist();
            return;
        }
    }

    public void complete(String id, long now) {
        for (HuntObjective h : hunts) if (h.id.equals(id)) { h.progress = h.required; h.completedAt = now; persist(); return; }
    }

    public void abandon(String id) {
        for (HuntObjective h : hunts) if (h.id.equals(id)) { h.abandoned = true; persist(); return; }
    }

    /** Active hunts the observed Pokémon would count toward. */
    public List<HuntObjective> matching(PokemonObservation o, long now) {
        List<HuntObjective> r = new ArrayList<>();
        for (HuntObjective h : hunts) if (h.active(now) && h.remaining() > 0 && h.matches(o)) r.add(h);
        return r;
    }

    private void persist() { store.rewrite(hunts); }
}
