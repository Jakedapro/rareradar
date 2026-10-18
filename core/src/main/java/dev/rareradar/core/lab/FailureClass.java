package dev.rareradar.core.lab;

/** Root-cause buckets used to group failures in reports. Critical ones can stop a session. */
public enum FailureClass {
    NAVIGATION_STUCK(false, "Navigation stopped making progress and bounded recovery did not help"),
    PATH_NOT_FOUND(false, "Pathfinder could not produce a usable path"),
    TARGET_DESPAWNED(false, "The target despawned"),
    CAPTURE_TIMEOUT(false, "A capture attempt did not finish in time"),
    CAPTURE_FAILED(false, "A capture attempt completed without a confirmed capture"),
    INVALID_ENTITY_STATE(false, "Entity state was missing or inconsistent"),
    INVENTORY_DESYNC(false, "Inventory changes did not match the observed outcome"),
    DETECTION_MISMATCH(false, "Scanner output disagreed with observable entity state"),
    ECONOMY_ASSERTION(false, "An economy fixture assertion failed"),
    PERFORMANCE_DEGRADED(false, "Performance below the stated threshold"),
    TEST_TIMEOUT(false, "The test hit its time limit"),
    ACTION_LIMIT(false, "The test hit its action limit"),
    EMERGENCY_STOP(false, "Stopped by the user"),
    UNEXPECTED_EXCEPTION(true, "An exception escaped a scenario"),
    ENVIRONMENT_CHANGED(true, "The connection or world changed during the session"),
    SAFETY_ABORT(true, "A safety limit aborted the test (for example the player took damage)");

    public final boolean critical;
    public final String description;

    FailureClass(boolean critical, String description) { this.critical = critical; this.description = description; }
}
