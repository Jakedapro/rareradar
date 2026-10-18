package dev.rareradar.core.lab;

import dev.rareradar.core.lab.EnvironmentVerifier.Env;
import dev.rareradar.core.lab.EnvironmentVerifier.Kind;
import dev.rareradar.core.store.AsyncWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class LabTest {
    // ---------------------------------------------------------------- environment

    @Test
    void environmentRules() {
        List<String> allow = List.of("192.168.1.20", "cobblegalaxy.com", "play.example.com", "localhost");
        assertTrue(EnvironmentVerifier.verify(new Env(Kind.SINGLEPLAYER, null, true), List.of()).allowed());
        assertFalse(EnvironmentVerifier.verify(new Env(Kind.NOT_IN_WORLD, null, true), allow).allowed());
        // Never CobbleGalaxy, even when someone puts it on the allowlist.
        assertFalse(EnvironmentVerifier.verify(new Env(Kind.MULTIPLAYER, "play.cobblegalaxy.com:25565", true), allow).allowed());
        assertFalse(EnvironmentVerifier.verify(new Env(Kind.MULTIPLAYER, "cobblegalaxy.com", true), allow).allowed());
        // Public hosts are refused even when allowlisted.
        assertFalse(EnvironmentVerifier.verify(new Env(Kind.MULTIPLAYER, "play.example.com", true), allow).allowed());
        // Private but not allowlisted.
        assertFalse(EnvironmentVerifier.verify(new Env(Kind.MULTIPLAYER, "192.168.1.99", true), allow).allowed());
        // Private and allowlisted (port ignored).
        assertTrue(EnvironmentVerifier.verify(new Env(Kind.MULTIPLAYER, "192.168.1.20:25565", true), allow).allowed());
        assertTrue(EnvironmentVerifier.verify(new Env(Kind.MULTIPLAYER, "localhost", true), allow).allowed());
        assertFalse(EnvironmentVerifier.verify(new Env(Kind.MULTIPLAYER, "172.32.0.1", true), List.of("172.32.0.1")).allowed(), "172.32 is public");
        assertFalse(EnvironmentVerifier.verify(new Env(Kind.MULTIPLAYER, "", true), allow).allowed());
    }

    // ---------------------------------------------------------------- stuck detector

    @Test
    void stuckDetectorClassifiesProgressStallAndOscillation() {
        StuckDetector walking = new StuckDetector(100, 2.0, 3);
        for (int t = 0; t < 400; t++) assertEquals(StuckDetector.Reason.NONE, walking.observe(t, t * 0.2, 0, 100 - t * 0.2), "steady progress must never be stuck");

        StuckDetector still = new StuckDetector(100, 2.0, 3);
        StuckDetector.Reason last = StuckDetector.Reason.NONE;
        for (int t = 0; t < 150; t++) last = still.observe(t, 5, 5, 40);
        assertEquals(StuckDetector.Reason.NO_PROGRESS, last);

        StuckDetector osc = new StuckDetector(100, 2.0, 3);
        for (int t = 0; t < 150; t++) last = osc.observe(t, (t / 10) % 2 == 0 ? 0 : 6, 0, 40 + ((t / 10) % 2 == 0 ? 0 : 0.5));
        assertEquals(StuckDetector.Reason.OSCILLATION, last);

        StuckDetector replan = new StuckDetector(100, 2.0, 2);
        assertFalse(replan.noteReplan());
        assertFalse(replan.noteReplan());
        assertTrue(replan.noteReplan(), "third replan of the same goal exceeds the limit of 2");
        assertEquals(StuckDetector.Reason.REPEATED_REPLAN, replan.observe(1, 0, 0, 10));
    }

    // ---------------------------------------------------------------- misc units

    @Test
    void redactRemovesCredentials() {
        String s = Redact.clean("failed with accessToken=abc123XYZ and password: hunter2 for user");
        assertFalse(s.contains("abc123XYZ"));
        assertFalse(s.contains("hunter2"));
        assertTrue(s.contains("<redacted>"));
    }

    @Test
    void heapSlopeNeedsEnoughDataAndDetectsGrowth() {
        PerformanceMonitor m = new PerformanceMonitor();
        for (int i = 0; i < 10; i++) m.record(new PerfSample(i * 1000L, 60, 1, 500, 0, 0, 0, 0, 0));
        assertTrue(Double.isNaN(m.heapSlopeMbPerMin()), "too few / too short to judge");
        PerformanceMonitor g = new PerformanceMonitor();
        for (int i = 0; i < 60; i++) g.record(new PerfSample(i * 5000L, 60, 1, 500 + i * 10, 0, 0, 0, 0, 0)); // +120 MB/min
        assertEquals(120, g.heapSlopeMbPerMin(), 0.5);
        assertEquals(Boolean.TRUE, g.summary().get("heap_growth_suspected"));
    }

    // ---------------------------------------------------------------- controller

    static final class FakeHost implements LabHost {
        Env env = new Env(Kind.SINGLEPLAYER, null, true);
        final AtomicInteger releases = new AtomicInteger();
        public Env environment() { return env; }
        public void releaseControls() { releases.incrementAndGet(); }
        public PerfSample samplePerf() { return new PerfSample(System.currentTimeMillis(), 60, 0, 300, 2, 100, 0.2, 1.0, 0); }
    }

    /** Test scenario with scripted behaviour. */
    static final class Scripted implements Scenario {
        final String id; final Step.Kind result; final int ticksToRun; final boolean throwInTick; final FailureClass cls;
        Scripted(String id, Step.Kind result, int ticksToRun, boolean throwInTick, FailureClass cls) {
            this.id = id; this.result = result; this.ticksToRun = ticksToRun; this.throwInTick = throwInTick; this.cls = cls;
        }
        public String id() { return id; }
        public String idPrefix() { return id.substring(0, 3); }
        public String category() { return id.startsWith("NAV") ? "navigation" : "core"; }
        public String description() { return id; }
        public Layer layer() { return Layer.IN_GAME; }
        public String expected() { return "scripted"; }
        public void begin(TestContext c) {}
        public Step tick(TestContext c) {
            if (throwInTick) throw new IllegalStateException("boom accessToken=SECRET123");
            c.metric("x", 2);
            if (c.ticks < ticksToRun) return Step.RUNNING;
            return switch (result) {
                case PASS -> Step.pass("ok");
                case FAIL -> Step.fail(cls, "scripted failure", "bad");
                case SKIP -> Step.skip("scripted skip");
                default -> Step.RUNNING;
            };
        }
    }

    private LabController controller(Path dir, FakeHost host, ScenarioRegistry reg) {
        return new LabController(host, reg, dir, AsyncWriter.inline(), new BuildInfo("4.0.0-test", "abc1234", "1.21.1", "0.17.2", "1.8.2"), () -> List.of());
    }

    private static long runToEnd(LabController c, long now, int maxTicks) {
        for (int i = 0; i < maxTicks && c.running(); i++) { now += 50; c.tick(now); }
        return now;
    }

    @Test
    void smokeSuiteRunsEndToEndAndWritesTheFullPackage(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        LabController c = controller(dir, host, CoreScenarios.register(new ScenarioRegistry()));
        SuiteConfig cfg = new SuiteConfig();
        cfg.suite = "smoke"; cfg.max_tests = 8; cfg.random_seed = 777;
        long now = 1_700_000_000_000L;
        assertTrue(c.start(cfg, now).started());
        runToEnd(c, now, 5000);
        assertFalse(c.running());
        Path s = Path.of(c.lastSessionDir());
        for (String f : List.of("summary.json", "events.jsonl", "failures.json", "environment.json", "report.md"))
            assertTrue(Files.exists(s.resolve(f)), f + " missing");
        String report = Files.readString(s.resolve("report.md"));
        assertTrue(report.contains("Tests run"));
        assertTrue(report.contains("No IN_GAME test completed"), "must not claim in-game verification: " + report);
        assertTrue(report.contains("do **not** validate any real server's economy"));
        String summary = Files.readString(s.resolve("summary.json"));
        assertTrue(summary.contains("\"testsRun\": 8"), summary);
        assertTrue(summary.contains("\"failed\": 0"), "economy fixtures and self-check must all pass:\n" + report);
        assertTrue(Files.readString(s.resolve("events.jsonl")).contains("\"event\":\"test_completed\""));
        assertTrue(Files.readString(s.resolve("environment.json")).contains("4.0.0-test"));
        assertTrue(host.releases.get() >= 8, "controls released after every test");
        assertEquals("max tests reached", c.snapshot(now).stopReason());
    }

    @Test
    void failuresAreIsolatedClassifiedAndReported(@TempDir Path dir) throws Exception {
        ScenarioRegistry reg = new ScenarioRegistry()
                .register("NAV_BAD", () -> new Scripted("NAV_BAD", Scenario.Step.Kind.FAIL, 2, false, FailureClass.NAVIGATION_STUCK))
                .register("NAV_OK", () -> new Scripted("NAV_OK", Scenario.Step.Kind.PASS, 2, false, null))
                .register("COR_THROW", () -> new Scripted("COR_THROW", Scenario.Step.Kind.PASS, 2, true, null))
                .suite("mix", "NAV_BAD", "NAV_OK", "COR_THROW");
        FakeHost host = new FakeHost();
        LabController c = controller(dir, host, reg);
        SuiteConfig cfg = new SuiteConfig();
        cfg.suite = "mix"; cfg.max_tests = 6; cfg.stop_on_critical_failure = false;
        long now = 1_700_000_000_000L;
        c.start(cfg, now);
        runToEnd(c, now, 2000);
        String fail = Files.readString(Path.of(c.lastSessionDir()).resolve("failures.json"));
        assertTrue(fail.contains("NAVIGATION_STUCK"));
        assertTrue(fail.contains("UNEXPECTED_EXCEPTION"));
        assertFalse(fail.contains("SECRET123"), "credentials must be redacted from failure text");
        assertTrue(fail.contains("Priority 1: UNEXPECTED_EXCEPTION"), "critical class is prioritised first:\n" + fail);
        String report = Files.readString(Path.of(c.lastSessionDir()).resolve("report.md"));
        assertTrue(report.contains("Navigation: 4 test(s) — 2 passed, 2 failed"), report);
        assertFalse(c.recentErrors().isEmpty());
    }

    @Test
    void criticalFailureStopsTheSessionWhenConfigured(@TempDir Path dir) {
        ScenarioRegistry reg = new ScenarioRegistry()
                .register("COR_THROW", () -> new Scripted("COR_THROW", Scenario.Step.Kind.PASS, 1, true, null)).suite("t", "COR_THROW");
        LabController c = controller(dir, new FakeHost(), reg);
        SuiteConfig cfg = new SuiteConfig();
        cfg.suite = "t"; cfg.max_tests = 50;
        c.start(cfg, 1000);
        runToEnd(c, 1000, 100);
        assertFalse(c.running());
        assertEquals(1, c.snapshot(0).failed(), "stopped after the first critical failure");
        assertTrue(c.snapshot(0).stopReason().startsWith("critical failure"));
    }

    @Test
    void timeoutActionLimitAndConsecutiveFailureLimitsHold(@TempDir Path dir) {
        ScenarioRegistry reg = new ScenarioRegistry()
                .register("SLO_ONE", () -> new Scripted("SLO_ONE", Scenario.Step.Kind.RUNNING, 0, false, null))
                .suite("slow", "SLO_ONE");
        LabController c = controller(dir, new FakeHost(), reg);
        SuiteConfig cfg = new SuiteConfig();
        cfg.suite = "slow"; cfg.test_timeout_seconds = 5; cfg.max_consecutive_failures = 2; cfg.max_tests = 50;
        c.start(cfg, 1000);
        runToEnd(c, 1000, 1000);
        assertFalse(c.running(), "never loops forever");
        assertEquals(2, c.snapshot(0).failed());
        assertTrue(c.snapshot(0).stopReason().contains("consecutive failures"));
    }

    @Test
    void environmentChangeEmergencyStopAndPauseAreSafe(@TempDir Path dir) {
        ScenarioRegistry reg = new ScenarioRegistry()
                .register("SLO_ONE", () -> new Scripted("SLO_ONE", Scenario.Step.Kind.RUNNING, 0, false, null)).suite("slow", "SLO_ONE");
        // environment changes to a public server mid-run
        FakeHost host = new FakeHost();
        LabController c = controller(dir, host, reg);
        SuiteConfig cfg = new SuiteConfig();
        cfg.suite = "slow";
        c.start(cfg, 1000);
        long now = runToEnd(c, 1000, 5);
        host.env = new Env(Kind.MULTIPLAYER, "play.cobblegalaxy.com", true);
        c.tick(now + 50);
        assertFalse(c.running());
        assertTrue(c.snapshot(0).stopReason().contains("environment changed"));

        // emergency stop and pause/resume
        FakeHost h2 = new FakeHost();
        LabController c2 = controller(dir.resolve("b"), h2, reg);
        c2.start(cfg, 1000);
        now = runToEnd(c2, 1000, 3);
        int before = h2.releases.get();
        c2.pause(now);
        assertEquals(LabController.State.PAUSED, c2.state());
        assertTrue(h2.releases.get() > before, "pause releases controls");
        int testsBefore = c2.snapshot(now).testsDone();
        for (int i = 0; i < 100; i++) c2.tick(now + 50L * i);
        assertEquals(testsBefore, c2.snapshot(now).testsDone(), "nothing advances while paused");
        c2.resume(now + 6000);
        c2.tick(now + 6050);
        assertEquals(LabController.State.RUNNING, c2.state());
        c2.emergencyStop(now + 6100, "test");
        assertFalse(c2.running());
        assertTrue(c2.snapshot(0).stopReason().startsWith("EMERGENCY STOP"));
        assertTrue(Files.exists(Path.of(c2.lastSessionDir()).resolve("report.md")), "report still exported after emergency stop");
    }

    @Test
    void refusesToStartOnPublicOrUnknownEnvironments(@TempDir Path dir) {
        FakeHost host = new FakeHost();
        host.env = new Env(Kind.MULTIPLAYER, "play.cobblegalaxy.com", true);
        LabController c = controller(dir, host, CoreScenarios.register(new ScenarioRegistry()));
        var r = c.start(new SuiteConfig(), 1000);
        assertFalse(r.started());
        assertTrue(r.message().contains("refused"));
        assertEquals(LabController.State.IDLE, c.state());
        assertFalse(Files.exists(dir.resolve("sessions")), "no session directory is created for a refused start");
    }

    @Test
    void skippedOnlySuitesTerminate(@TempDir Path dir) {
        ScenarioRegistry reg = new ScenarioRegistry()
                .register("SKP_ONE", () -> new Scripted("SKP_ONE", Scenario.Step.Kind.SKIP, 0, false, null)).suite("s", "SKP_ONE");
        LabController c = controller(dir, new FakeHost(), reg);
        SuiteConfig cfg = new SuiteConfig();
        cfg.suite = "s"; cfg.max_tests = 500;
        c.start(cfg, 1000);
        runToEnd(c, 1000, 5000);
        assertFalse(c.running());
        assertTrue(c.snapshot(0).stopReason().contains("only skipped"));
        assertEquals(0, c.snapshot(0).passed());
    }

    @Test
    void secondSessionIsComparedWithTheFirst(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        ScenarioRegistry reg = CoreScenarios.register(new ScenarioRegistry());
        LabController c = controller(dir, host, reg);
        SuiteConfig cfg = new SuiteConfig();
        cfg.suite = "economy_fixtures"; cfg.max_tests = 6;
        long now = 1_700_000_000_000L;
        c.start(cfg, now);
        now = runToEnd(c, now, 2000);
        c.start(cfg, now + 5000_000L);
        runToEnd(c, now + 5000_000L, 2000);
        String report = Files.readString(Path.of(c.lastSessionDir()).resolve("report.md"));
        assertTrue(report.contains("percentage points"), report);
        assertTrue(report.contains("Previous: session_"), report);
    }

    @Test
    void logFilesAreBoundedByRotation(@TempDir Path dir) throws Exception {
        SessionRecorder r = new SessionRecorder(dir, "s", AsyncWriter.inline(), true, 1000);
        for (int i = 0; i < 200; i++) r.event(new java.util.LinkedHashMap<>(java.util.Map.of("event", "x", "n", i, "pad", "0123456789012345678901234567890123456789")));
        assertTrue(Files.size(dir.resolve("events.jsonl")) <= 1100);
        assertTrue(Files.exists(dir.resolve("events.jsonl.1")));
        assertTrue(r.rotations() > 5);
    }

    @Test
    void comparisonRefusesToInventDataWhenNothingOverlaps() {
        Summary a = new Summary(), b = new Summary();
        a.sessionId = "session_1"; b.sessionId = "session_2"; a.suite = b.suite = "x";
        var lines = HistoryStore.compare(a, b);
        assertTrue(lines.stream().anyMatch(l -> l.contains("nothing is comparable")));
        assertTrue(HistoryStore.compare(null, b).get(0).startsWith("No earlier session"));
    }
}
