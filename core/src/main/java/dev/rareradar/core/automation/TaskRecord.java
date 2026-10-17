package dev.rareradar.core.automation;

import java.util.UUID;

/**
 * A persisted unit of work. {@code irreversible} tasks (e.g. spending money) are never resumed automatically after a
 * restart: they are marked INTERRUPTED and need the user's attention.
 */
public final class TaskRecord {
    public enum Status { RUNNING, PAUSED, DONE, FAILED, INTERRUPTED, CANCELLED }

    public String id = UUID.randomUUID().toString();
    public Module module;
    public String objective = "";
    public String server = "";
    public long startedAt, updatedAt;
    public Status status = Status.RUNNING;
    public boolean irreversible;
    public String lastState = "";
    public String note = "";
}
