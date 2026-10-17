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
    private final Path checkpointFile;
    private final AsyncWriter writer;

    public SessionTracker(Path file, AsyncWriter writer) {
        this.writer = writer;
        this.checkpointFile = file.resolveSibling(file.getFileName() + ".open");
        store = new JsonlStore<>(file, SessionRecord.class, writer);
        for (SessionRecord r : store.load()) if (r.start > 0 && r.end >= r.start) {
            if (r.biomes == null) r.biomes = new java.util.TreeSet<>();
            history.add(r);
        }
        recoverCheckpoint();
    }

    /** A session left open by a crash is closed at its last checkpoint and added to history. */
    private void recoverCheckpoint() {
        try {
            if (!java.nio.file.Files.exists(checkpointFile)) return;
            SessionRecord r = JsonlStore.GSON.fromJson(java.nio.file.Files.readString(checkpointFile), SessionRecord.class);
            java.nio.file.Files.deleteIfExists(checkpointFile);
            if (r == null || r.start <= 0 || r.end < r.start) return;
            for (SessionRecord h : history) if (h.id.equals(r.id)) return;
            if (r.biomes == null) r.biomes = new java.util.TreeSet<>();
            history.add(r);
            store.append(r);
            dev.rareradar.core.util.Log.info("Recovered an unfinished session from the last run");
        } catch (Exception e) {
            dev.rareradar.core.util.Log.warn("Could not recover the unfinished session: " + e);
        }
    }

    /** Writes the live session to a side file so a crash loses at most one checkpoint interval. */
    public void checkpoint() {
        if (current == null) return;
        String json = JsonlStore.GSON.toJson(current);
        writer.submit(() -> {
            try {
                java.nio.file.Files.createDirectories(checkpointFile.toAbsolutePath().getParent());
                Path tmp = checkpointFile.resolveSibling(checkpointFile.getFileName() + ".tmp");
                java.nio.file.Files.writeString(tmp, json);
                java.nio.file.Files.move(tmp, checkpointFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                dev.rareradar.core.util.Log.warn("Session checkpoint failed: " + e);
            }
        });
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
        writer.submit(() -> { try { java.nio.file.Files.deleteIfExists(checkpointFile); } catch (Exception ignored) { /* stale checkpoint is de-duplicated by id */ } });
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
