package dev.rareradar.core.lab;

/**
 * One kind of test, ticked from the client tick until it returns a terminal {@link Step}. A fresh instance is created
 * per test. {@link #end} always runs afterwards and must release anything the scenario took.
 */
public interface Scenario {
    /** How trustworthy a pass is: FIXTURE = synthetic data, SIMULATED = modelled world, IN_GAME = real Minecraft state. */
    enum Layer { FIXTURE, SIMULATED, IN_GAME }

    String id();
    /** Short prefix for test ids, e.g. "NAV" → NAV_0042. */
    String idPrefix();
    String category();
    String description();
    Layer layer();
    /** What a pass means, recorded with every test. */
    String expected();
    /** Needs a loaded world (skipped when the Lab runs without one). */
    default boolean needsWorld() { return layer() == Layer.IN_GAME; }

    void begin(TestContext c) throws Exception;
    Step tick(TestContext c) throws Exception;
    default void end(TestContext c) {}

    record Step(Kind kind, String observed, FailureClass failure, String reason) {
        public enum Kind { RUNNING, PASS, FAIL, SKIP }
        public static final Step RUNNING = new Step(Kind.RUNNING, "", null, "");
        public static Step pass(String observed) { return new Step(Kind.PASS, observed, null, ""); }
        public static Step fail(FailureClass cls, String reason, String observed) { return new Step(Kind.FAIL, observed, cls, reason); }
        public static Step skip(String reason) { return new Step(Kind.SKIP, "", null, reason); }
        public boolean terminal() { return kind != Kind.RUNNING; }
    }
}
