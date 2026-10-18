package dev.rareradar.core.lab;

/** What the Minecraft layer provides to the controller. Kept tiny so the controller is testable without Minecraft. */
public interface LabHost {
    EnvironmentVerifier.Env environment();
    /** Releases every key/control the Lab may have pressed. Must be safe to call at any time. */
    void releaseControls();
    /** Current performance reading, or null. */
    PerfSample samplePerf();
    default void message(String text) {}
}
