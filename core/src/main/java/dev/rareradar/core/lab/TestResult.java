package dev.rareradar.core.lab;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** One executed test. Nothing here is filled in unless the test actually ran. */
public final class TestResult {
    public enum Status { PASS, FAIL, SKIPPED, ABORTED }

    public String testId, scenario, category, layer;
    public long seed;
    public long startMillis, endMillis;
    public int timeoutTicks, maxActions, actions, ticks;
    public String expected = "", observed = "";
    public Status status = Status.FAIL;
    public FailureClass failureClass;
    public String failureReason = "";
    public final Map<String, Double> metrics = new LinkedHashMap<>();
    /** Free-form labels such as terrain tags; failures are also grouped by these. */
    public final Set<String> tags = new LinkedHashSet<>();

    public long elapsedMs() { return Math.max(0, endMillis - startMillis); }
    public boolean counted() { return status == Status.PASS || status == Status.FAIL; }
}
