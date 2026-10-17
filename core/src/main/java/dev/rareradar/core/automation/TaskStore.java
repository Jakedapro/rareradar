package dev.rareradar.core.automation;

import dev.rareradar.core.store.AsyncWriter;
import dev.rareradar.core.store.JsonlStore;

import java.nio.file.Path;
import java.util.*;

/**
 * Persistent task records. On load, anything still RUNNING/PAUSED from a previous run is marked INTERRUPTED: nothing
 * resumes automatically after a crash or restart — the user re-enables automation, and irreversible tasks are never
 * re-executed.
 */
public final class TaskStore {
    private final JsonlStore<TaskRecord> store;
    private final List<TaskRecord> tasks = new ArrayList<>();
    private final List<TaskRecord> interruptedOnLoad = new ArrayList<>();

    public TaskStore(Path file, AsyncWriter writer) {
        store = new JsonlStore<>(file, TaskRecord.class, writer);
        boolean changed = false;
        for (TaskRecord t : store.load()) {
            if (t == null || t.module == null || t.status == null) continue;
            if (t.status == TaskRecord.Status.RUNNING || t.status == TaskRecord.Status.PAUSED) {
                t.status = TaskRecord.Status.INTERRUPTED;
                t.note = (t.note == null || t.note.isBlank() ? "" : t.note + "; ") + "interrupted by restart" + (t.irreversible ? " (irreversible: not resumed)" : "");
                interruptedOnLoad.add(t);
                changed = true;
            }
            tasks.add(t);
        }
        while (tasks.size() > 500) tasks.remove(0); // bounded history
        if (changed) store.rewrite(tasks);
    }

    public List<TaskRecord> all() { return Collections.unmodifiableList(tasks); }
    public List<TaskRecord> interruptedOnLoad() { return Collections.unmodifiableList(interruptedOnLoad); }

    public TaskRecord start(Module m, String objective, String server, boolean irreversible, long now) {
        TaskRecord t = new TaskRecord();
        t.module = m; t.objective = objective; t.server = server; t.irreversible = irreversible;
        t.startedAt = t.updatedAt = now;
        tasks.add(t);
        if (tasks.size() > 500) tasks.remove(0);
        store.rewrite(tasks);
        return t;
    }

    public void update(TaskRecord t, TaskRecord.Status s, String state, String note, long now) {
        t.status = s;
        if (state != null) t.lastState = state;
        if (note != null) t.note = note;
        t.updatedAt = now;
        store.rewrite(tasks);
    }

    public TaskRecord active(Module m) {
        for (int i = tasks.size() - 1; i >= 0; i--) {
            TaskRecord t = tasks.get(i);
            if (t.module == m && (t.status == TaskRecord.Status.RUNNING || t.status == TaskRecord.Status.PAUSED)) return t;
        }
        return null;
    }
}
