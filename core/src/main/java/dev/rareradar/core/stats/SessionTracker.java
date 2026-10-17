package dev.rareradar.core.stats;

import dev.rareradar.core.store.AsyncWriter;
import dev.rareradar.core.store.JsonlStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Live session counters plus the persistent session history. Call from the client thread only. */
public final class SessionTracker {
    private final JsonlStore<SessionRecord> store;
    private final List<SessionRecord> history = new ArrayList<>();
    private SessionRecord current;

    public SessionTracker(Path file, AsyncWriter writer) {
        store = new JsonlStore<>(file, SessionRecord.class, writer);
        for (SessionRecord r : store.load()) if (r.start > 0 && r.end >= r.start) {
            if (r.biomes == null) r.biomes = new java.util.TreeSet<>();
            history.add(r);
        }
    }

    public SessionRecord current() { return current; }
    public List<SessionRecord> history() { return Collections.unmodifiableList(history); }

    public void begin(long now, String world) {
        if (current != null) end(now);
        current = new SessionRecord();
        current.start = now;
        current.end = now;
        current.world = world == null ? "" : world;
    }

    /** Ends and persists the session; sessions shorter than a minute with no activity are discarded. */
    public void end(long now) {
        if (current == null) return;
        current.end = Math.max(current.start, now);
        if (current.minutes() >= 1 || current.encountered > 0 || current.realisedProfit() != 0) {
            history.add(current);
            store.append(current);
        }
        current = null;
    }

    public void touch(long now) { if (current != null) current.end = now; }

    public void encounter(boolean rare, boolean shiny, String biome) {
        if (current == null) return;
        current.encountered++;
        if (rare) current.rareEncounters++;
        if (shiny) current.shinyEncounters++;
        if (biome != null && current.biomes.size() < 256) current.biomes.add(biome);
    }

    public void caught() { if (current != null) current.caught++; }
    public void ballThrown() { if (current != null) current.ballsThrown++; }
    public void huntCompleted() { if (current != null) current.huntCompletions++; }
    public void reward(double money) { if (current != null && Double.isFinite(money)) current.rewardsEarned += money; }
    public void trade(double profit) { if (current != null && Double.isFinite(profit)) current.tradingProfit += profit; }
    public void spend(double cost) { if (current != null && Double.isFinite(cost) && cost > 0) current.resourceSpend += cost; }

    /** All sessions including the live one (for reports). */
    public List<SessionRecord> allIncludingCurrent() {
        List<SessionRecord> l = new ArrayList<>(history);
        if (current != null) l.add(current);
        return l;
    }
}
