package dev.rareradar.core.autohunt;

/** Result of one throw, as distinguished from client-visible evidence. */
public enum CaptureOutcome {
    CAPTURED("Captured (ball reached CAPTURED state)"),
    BROKE_FREE("Broke free"),
    MISSED("Ball missed the target"),
    TARGET_DESPAWNED("Target despawned before the ball connected"),
    TARGET_IN_BATTLE("Target entered a battle"),
    NO_BALLS("No usable Poké Balls"),
    INTERRUPTED("Interrupted"),
    UNKNOWN("Unknown — not counted as a capture");

    public final String label;

    CaptureOutcome(String label) { this.label = label; }

    public boolean success() { return this == CAPTURED; }
}
