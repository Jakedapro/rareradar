package dev.rareradar.core.profit;

import dev.rareradar.core.store.AsyncWriter;
import dev.rareradar.core.store.JsonlStore;
import dev.rareradar.core.util.Log;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Persistent activity log. */
public final class ActivityLog {
    private final JsonlStore<ActivityRecord> store;
    private final List<ActivityRecord> records = new ArrayList<>();

    public ActivityLog(Path file, AsyncWriter writer) {
        store = new JsonlStore<>(file, ActivityRecord.class, writer);
        for (ActivityRecord r : store.load()) {
            if (r.validate() == null) records.add(r); else Log.warn("Activity log: skipped invalid record " + r.id);
        }
    }

    public void add(ActivityRecord r) {
        String err = r.validate();
        if (err != null) throw new IllegalArgumentException(err);
        records.add(r);
        store.append(r);
    }

    public boolean remove(String id) {
        boolean removed = records.removeIf(r -> r.id.equals(id));
        if (removed) store.rewrite(records);
        return removed;
    }

    public List<ActivityRecord> all() { return Collections.unmodifiableList(records); }
}
