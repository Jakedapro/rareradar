package dev.rareradar.core.lab.scenarios;

import dev.rareradar.core.lab.*;

/** Exercises the Lab pipeline itself (events, metrics, tick loop) without touching Minecraft. */
public final class SelfCheckScenario implements Scenario {
    public String id() { return "LAB_SELF_CHECK"; }
    public String idPrefix() { return "SELF"; }
    public String category() { return "core"; }
    public String description() { return "Lab pipeline self-check: tick loop, seeded RNG, event logging"; }
    public Layer layer() { return Layer.FIXTURE; }
    public String expected() { return "Scenario runs 5 ticks, seeded RNG is deterministic, events are written"; }

    private long first;

    public void begin(TestContext c) { first = c.rng.nextLong(); c.event("self_check_begin", "first_random", first); }

    public Step tick(TestContext c) {
        c.action();
        if (c.ticks < 5) return Step.RUNNING;
        long again = new java.util.Random(c.test.seed).nextLong();
        c.metric("ticks", c.ticks);
        if (again != first) return Step.fail(FailureClass.UNEXPECTED_EXCEPTION, "seeded RNG was not deterministic", "");
        return Step.pass("5 ticks, deterministic RNG");
    }
}
