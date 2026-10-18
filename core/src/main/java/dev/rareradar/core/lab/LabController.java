package dev.rareradar.core.lab;

import dev.rareradar.core.store.AsyncWriter;
import dev.rareradar.core.util.Log;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;

/**
 * Runs a suite of scenarios one at a time, driven by {@link #tick}. Owns the safety limits (duration, test count,
 * consecutive failures, per-test timeout/action limit, critical failure, environment change, emergency stop) and
 * guarantees controls are released after every test and when the session ends. Scenario exceptions are isolated.
 */
public final class LabController {
    public enum State { IDLE, RUNNING, PAUSED }

    public record StartResult(boolean started, String message) {}

    public record Snapshot(State state, String suite, String sessionId, int testsDone, int passed, int failed, int skipped,
                           String currentTest, long elapsedMs, double navRate, double captureRate,
                           List<String> recentFailures, String stopReason, String sessionDir, String lastReportDir) {}

    private static final DateTimeFormatter ID_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final int PERF_EVERY_TICKS = 20;
    private static final int MAX_CONSECUTIVE_SKIPS = 20;

    private final LabHost host;
    private final ScenarioRegistry registry;
    private final Path sessionsDir;
    private final HistoryStore history;
    private final AsyncWriter io;
    private final BuildInfo build;
    private final Supplier<List<String>> allowlist;

    private State state = State.IDLE;
    private SuiteConfig cfg;
    private SessionRecorder rec;
    private String sessionId, stopReason = "";
    private EnvironmentVerifier.Env startEnv;
    private long startMs, pausedAt, pausedTotal, lastNow;
    private int started, consecutiveFails, consecutiveSkips, runningTicks;
    private final Map<String, Integer> idCounters = new HashMap<>();
    private final List<TestResult> results = new ArrayList<>();
    private final Deque<String> recentFailures = new ArrayDeque<>();
    private final Deque<String> recentErrors = new ArrayDeque<>();
    private PerformanceMonitor perf = new PerformanceMonitor();
    private double labTickMs;
    private Scenario current;
    private TestContext ctx;
    private String lastDir = "";

    public LabController(LabHost host, ScenarioRegistry registry, Path labDir, AsyncWriter io, BuildInfo build, Supplier<List<String>> allowlist) {
        this.host = host;
        this.registry = registry;
        this.sessionsDir = labDir.resolve("sessions");
        this.history = new HistoryStore(labDir.resolve("history"));
        this.io = io;
        this.build = build;
        this.allowlist = allowlist;
    }

    public State state() { return state; }
    public boolean running() { return state != State.IDLE; }
    public List<String> recentErrors() { return List.copyOf(recentErrors); }
    public String lastSessionDir() { return lastDir; }
    public ScenarioRegistry registry() { return registry; }

    // ------------------------------------------------------------------ lifecycle

    public StartResult start(SuiteConfig requested, long now) {
        if (state != State.IDLE) return new StartResult(false, "a session is already running");
        SuiteConfig c = requested.copy().sanitize();
        if (!registry.hasSuite(c.suite)) return new StartResult(false, "unknown suite '" + c.suite + "'");
        EnvironmentVerifier.Env env = host.environment();
        EnvironmentVerifier.Verdict v = EnvironmentVerifier.verify(env, allowlist.get());
        if (!v.allowed()) return new StartResult(false, "Lab refused: " + v.reason());

        String base = "session_" + LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), ZoneId.systemDefault()).format(ID_FMT);
        String id = base;
        for (int i = 2; java.nio.file.Files.exists(sessionsDir.resolve(id)); i++) id = base + "_" + i;
        cfg = c; sessionId = id; startEnv = env; startMs = now; lastNow = now;
        pausedTotal = 0; started = 0; consecutiveFails = 0; consecutiveSkips = 0; runningTicks = 0; stopReason = "";
        idCounters.clear(); results.clear(); recentFailures.clear(); recentErrors.clear();
        perf = new PerformanceMonitor();
        rec = new SessionRecorder(sessionsDir.resolve(id), id, io, c.export_diagnostics, SessionRecorder.DEFAULT_MAX_BYTES);
        lastDir = rec.dir().toString();
        writeEnvironment(env, v);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("event", "session_started"); e.put("suite", c.suite); e.put("seed", c.random_seed);
        e.put("duration_minutes", c.duration_minutes); e.put("max_tests", c.max_tests);
        e.put("environment", v.reason());
        rec.event(e);
        state = State.RUNNING;
        return new StartResult(true, "started " + id + " (" + v.reason() + ")");
    }

    public void pause(long now) {
        if (state != State.RUNNING) return;
        safeRelease();
        state = State.PAUSED;
        pausedAt = now;
        sessionEvent("session_paused", "");
    }

    public void resume(long now) {
        if (state != State.PAUSED) return;
        pausedTotal += Math.max(0, now - pausedAt);
        state = State.RUNNING;
        sessionEvent("session_resumed", "");
    }

    public void stop(long now, String reason) {
        if (state == State.IDLE) return;
        finish(now, reason, null);
    }

    /** Immediate stop; also releases controls when idle (harmless, and covers a stuck key). */
    public void emergencyStop(long now, String reason) {
        safeRelease();
        if (state == State.IDLE) return;
        finish(now, "EMERGENCY STOP: " + reason, FailureClass.EMERGENCY_STOP);
    }

    // ------------------------------------------------------------------ tick

    public void tick(long now) {
        if (state == State.IDLE) return;
        long t0 = System.nanoTime();
        lastNow = now;
        try {
            tickInner(now);
        } catch (Throwable t) {
            noteError("controller: " + t);
            Log.error("Lab controller failed", t);
            try { finish(now, "internal Lab error: " + t, FailureClass.UNEXPECTED_EXCEPTION); } catch (Throwable ignored) { state = State.IDLE; safeRelease(); }
        }
        labTickMs = (System.nanoTime() - t0) / 1e6;
    }

    private void tickInner(long now) {
        EnvironmentVerifier.Env env = host.environment();
        EnvironmentVerifier.Verdict v = EnvironmentVerifier.verify(env, allowlist.get());
        if (!v.allowed() || !env.key().equals(startEnv.key())) {
            finish(now, "environment changed (" + (v.allowed() ? "different connection" : v.reason()) + ")", FailureClass.ENVIRONMENT_CHANGED);
            return;
        }
        if (state == State.PAUSED) return;

        long activeMs = now - startMs - pausedTotal;
        if (activeMs > cfg.duration_minutes * 60_000L) { finish(now, "duration limit reached", null); return; }

        if (current == null) {
            if (started >= cfg.max_tests) { finish(now, "max tests reached", null); return; }
            if (consecutiveFails >= cfg.max_consecutive_failures) { finish(now, "too many consecutive failures (" + consecutiveFails + ")", null); return; }
            if (consecutiveSkips >= MAX_CONSECUTIVE_SKIPS) { finish(now, "only skipped tests: scenario preconditions were never met", null); return; }
            startNext(now, env);
            return;
        }

        runningTicks++;
        ctx.nowMillis = now;
        ctx.ticks++;
        Scenario.Step step;
        try {
            step = current.tick(ctx);
        } catch (Throwable t) {
            noteError(ctx.test.testId + ": " + t);
            step = Scenario.Step.fail(FailureClass.UNEXPECTED_EXCEPTION, Redact.clean(stack(t)), "exception");
        }
        if (!step.terminal()) {
            if (ctx.test.actions > ctx.test.maxActions) step = Scenario.Step.fail(FailureClass.ACTION_LIMIT, "more than " + ctx.test.maxActions + " actions", "");
            else if (ctx.ticks > ctx.test.timeoutTicks) step = Scenario.Step.fail(FailureClass.TEST_TIMEOUT, "no result after " + ctx.test.timeoutTicks / 20 + " s", "");
        }
        if (runningTicks % PERF_EVERY_TICKS == 0) samplePerf(now);
        if (step.terminal()) {
            endCurrent(now, step);
            if (step.kind() == Scenario.Step.Kind.FAIL && step.failure() != null && step.failure().critical && cfg.stop_on_critical_failure)
                finish(now, "critical failure " + step.failure() + " in " + results.get(results.size() - 1).testId, null);
        }
    }

    private void startNext(long now, EnvironmentVerifier.Env env) {
        List<String> ids = registry.suiteScenarios(cfg.suite);
        String sid = ids.get(started % ids.size());
        Scenario sc = registry.create(sid);
        TestResult r = new TestResult();
        r.scenario = sid; r.category = sc.category(); r.layer = sc.layer().name();
        int n = idCounters.merge(sc.idPrefix(), 1, Integer::sum);
        r.testId = sc.idPrefix() + "_" + String.format(Locale.ROOT, "%04d", n);
        r.seed = cfg.random_seed * 1_000_003L + started;
        r.startMillis = now; r.expected = sc.expected();
        r.timeoutTicks = cfg.test_timeout_seconds * 20; r.maxActions = cfg.max_actions_per_test;
        started++;
        current = sc;
        ctx = new TestContext(r, host, cfg, rec);
        ctx.nowMillis = now;
        ctx.event("test_started", "layer", r.layer, "seed", r.seed);
        if (sc.needsWorld() && !env.inWorld()) { endCurrent(now, Scenario.Step.skip("no world loaded")); return; }
        try {
            sc.begin(ctx);
        } catch (Throwable t) {
            noteError(r.testId + ": " + t);
            endCurrent(now, Scenario.Step.fail(FailureClass.UNEXPECTED_EXCEPTION, Redact.clean(stack(t)), "exception in begin()"));
        }
    }

    private void endCurrent(long now, Scenario.Step step) {
        TestResult r = ctx.test;
        try { current.end(ctx); } catch (Throwable t) { noteError(r.testId + " end(): " + t); }
        safeRelease();
        r.endMillis = now; r.ticks = ctx.ticks; r.observed = step.observed();
        switch (step.kind()) {
            case PASS -> { r.status = TestResult.Status.PASS; consecutiveFails = 0; consecutiveSkips = 0; }
            case SKIP -> { r.status = TestResult.Status.SKIPPED; r.failureReason = step.reason(); consecutiveSkips++; }
            default -> {
                r.status = TestResult.Status.FAIL; r.failureClass = step.failure(); r.failureReason = step.reason();
                consecutiveFails++; consecutiveSkips = 0;
                recentFailures.addFirst(r.testId + " · " + r.failureClass + " · " + shorten(r.failureReason));
                while (recentFailures.size() > 8) recentFailures.removeLast();
            }
        }
        ctx.event("test_completed", "result", r.status.name(), "failure_class", r.failureClass == null ? null : r.failureClass.name(),
                "reason", r.failureReason, "actions", r.actions, "ticks", r.ticks, "metrics", r.metrics, "tags", r.tags);
        results.add(r);
        current = null; ctx = null;
    }

    private void samplePerf(long now) {
        try {
            PerfSample s = host.samplePerf();
            if (s == null) return;
            s = s.withLabTick(labTickMs);
            perf.record(s);
            rec.perf(s, current == null ? "" : current.id());
        } catch (Throwable t) { noteError("perf sample: " + t); }
    }

    private void finish(long now, String reason, FailureClass abortClass) {
        if (current != null) {
            TestResult r = ctx.test;
            try { current.end(ctx); } catch (Throwable t) { noteError(r.testId + " end(): " + t); }
            safeRelease();
            r.endMillis = now; r.ticks = ctx.ticks; r.status = TestResult.Status.ABORTED;
            r.failureClass = abortClass; r.failureReason = reason;
            ctx.event("test_completed", "result", "ABORTED", "failure_class", abortClass == null ? null : abortClass.name(), "reason", reason);
            results.add(r);
            current = null; ctx = null;
        }
        safeRelease();
        stopReason = reason;
        ReportGenerator.Input in = new ReportGenerator.Input();
        in.sessionId = sessionId; in.stopReason = reason; in.cfg = cfg; in.env = startEnv; in.build = build;
        in.startMillis = startMs; in.endMillis = now; in.results = new ArrayList<>(results);
        in.performance = perf.summary(); in.notRun = notRun(); in.rotations = rec.rotations();
        Summary s = ReportGenerator.summarize(in);
        List<String> cmp = HistoryStore.compare(history.previousFor(sessionId, cfg.suite), s);
        sessionEvent("session_finished", reason);
        rec.writeJson("summary.json", s);
        rec.writeJson("failures.json", ReportGenerator.failures(in));
        rec.writeText("report.md", ReportGenerator.markdown(in, s, cmp));
        history.save(s);
        state = State.IDLE;
    }

    private List<String> notRun() {
        List<String> l = new ArrayList<>();
        for (String id : registry.notInSuite(cfg.suite)) l.add(id + " — registered but not part of suite '" + cfg.suite + "'");
        Set<String> ran = new HashSet<>();
        for (TestResult r : results) if (r.status != TestResult.Status.SKIPPED) ran.add(r.scenario);
        for (String id : registry.suiteScenarios(cfg.suite)) if (!ran.contains(id)) l.add(id + " — in the suite but never completed a test (skipped or not reached)");
        return l;
    }

    private void writeEnvironment(EnvironmentVerifier.Env env, EnvironmentVerifier.Verdict v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session", sessionId);
        m.put("environment_kind", env.kind().name());
        m.put("environment_verdict", v.reason());
        if (env.kind() == EnvironmentVerifier.Kind.MULTIPLAYER) m.put("server_address", env.address());
        m.put("cobblemon_present", env.cobblemonPresent());
        m.put("rareradar_version", build.modVersion());
        m.put("git_sha", build.gitSha());
        m.put("minecraft_version", build.minecraftVersion());
        m.put("fabric_version", build.fabricVersion());
        m.put("cobblemon_version", build.cobblemonVersion());
        m.put("java_version", System.getProperty("java.version"));
        m.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        m.put("config", cfg);
        rec.writeJson("environment.json", m);
    }

    // ------------------------------------------------------------------ helpers / UI

    public Snapshot snapshot(long now) {
        int p = 0, f = 0, s = 0;
        int[] nav = new int[2], cap = new int[2];
        for (TestResult r : results) {
            if (r.status == TestResult.Status.PASS) p++;
            else if (r.status == TestResult.Status.FAIL) f++;
            else if (r.status == TestResult.Status.SKIPPED) s++;
            if (r.counted()) {
                int[] t = r.category.equals("navigation") ? nav : r.category.equals("capture") ? cap : null;
                if (t != null) { t[1]++; if (r.status == TestResult.Status.PASS) t[0]++; }
            }
        }
        long active = state == State.IDLE ? 0 : Math.max(0, (state == State.PAUSED ? pausedAt : now) - startMs - pausedTotal);
        return new Snapshot(state, cfg == null ? "" : cfg.suite, sessionId == null ? "" : sessionId, results.size(), p, f, s,
                ctx == null ? "" : ctx.test.testId + " (" + ctx.test.scenario + ")", active,
                nav[1] == 0 ? Double.NaN : (double) nav[0] / nav[1], cap[1] == 0 ? Double.NaN : (double) cap[0] / cap[1],
                List.copyOf(recentFailures), stopReason, rec == null ? "" : rec.dir().toString(), lastDir);
    }

    private void sessionEvent(String name, String detail) {
        if (rec == null) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event", name);
        if (!detail.isEmpty()) m.put("detail", detail);
        m.put("tests", results.size());
        rec.event(m);
    }

    private void safeRelease() {
        try { host.releaseControls(); } catch (Throwable t) { noteError("releaseControls: " + t); }
    }

    private void noteError(String s) {
        recentErrors.addFirst(Redact.clean(s));
        while (recentErrors.size() > 20) recentErrors.removeLast();
    }

    private static String shorten(String s) { return s == null ? "" : s.length() > 90 ? s.substring(0, 90) + "…" : s; }

    private static String stack(Throwable t) {
        StringBuilder b = new StringBuilder(t.toString());
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < Math.min(6, st.length); i++) b.append("\n  at ").append(st[i]);
        return b.toString();
    }
}
