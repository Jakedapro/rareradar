package dev.rareradar.core.lab;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/** Everything a running scenario may touch: its result record, a seeded RNG and the structured logs. */
public final class TestContext {
    public final TestResult test;
    public final Random rng;
    public final LabHost host;
    public final SuiteConfig config;
    private final SessionRecorder rec;
    public int ticks;
    public long nowMillis;

    TestContext(TestResult test, LabHost host, SuiteConfig config, SessionRecorder rec) {
        this.test = test;
        this.rng = new Random(test.seed);
        this.host = host;
        this.config = config;
        this.rec = rec;
    }

    /** Counts one deliberate action (a key press burst, a throw, a replan). The controller enforces the limit. */
    public void action() { test.actions++; }
    public void metric(String name, double v) { test.metrics.put(name, v); }
    public void addMetric(String name, double d) { test.metrics.merge(name, d, Double::sum); }
    public void tag(String t) { test.tags.add(t); }

    /** Writes an event to events.jsonl. Arguments are alternating key, value pairs. */
    public void event(String name, Object... kv) { rec.event(withIds(name, kv)); }

    /** Writes a line to a per-domain file (navigation.jsonl, detection.jsonl, captures.jsonl). */
    public void domain(String file, String name, Object... kv) { rec.domain(file, withIds(name, kv)); }

    private Map<String, Object> withIds(String name, Object[] kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event", name);
        m.put("test_id", test.testId);
        m.put("scenario", test.scenario);
        m.put("elapsed_ms", Math.max(0, nowMillis - test.startMillis));
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}
