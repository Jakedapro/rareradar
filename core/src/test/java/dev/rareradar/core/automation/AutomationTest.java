package dev.rareradar.core.automation;

import dev.rareradar.core.profit.Activity;
import dev.rareradar.core.profit.ProfitEngine;
import dev.rareradar.core.store.AsyncWriter;
import dev.rareradar.core.util.Confidence;
import dev.rareradar.core.util.Estimate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AutomationTest {
    @TempDir Path dir;

    static AutomationController ctl(List<AuditEvent> sink) {
        return new AutomationController(new AutomationController.Limits(), sink::add);
    }

    @Test void cobbleGalaxyProfileRecognition() {
        ServerProfiles p = ServerProfiles.defaults();
        ServerProfile cg = p.resolve(false, "forge.cobblegalaxy.com:25565");
        assertEquals("cobblegalaxy", cg.id);
        assertTrue(cg.automationAuthorized);
        assertTrue(cg.authorizationSource.contains("User statement"));
        assertTrue(cg.requireUserEnable);
        assertEquals("cobblegalaxy", p.resolve(false, "PLAY.CobbleGalaxy.com").id);
        assertEquals("cobblegalaxy", p.resolve(false, "cobblegalaxy.com").id);
        assertNotEquals("cobblegalaxy", p.resolve(false, "notcobblegalaxy.com").id, "suffix match is on a label boundary");
        assertEquals(ModuleStatus.UNSUPPORTED, cg.status(Module.TRADING));
    }

    @Test void unknownServersRequireExplicitConfirmation() {
        ServerProfiles p = ServerProfiles.defaults();
        ServerProfile other = p.resolve(false, "mc.example.org");
        assertFalse(other.automationAuthorized);
        assertSame(other, p.resolve(false, "mc.example.org"), "profile is remembered");
        assertThrows(IllegalArgumentException.class, () -> ServerProfiles.authorize(other, " ", 1));
        ServerProfiles.authorize(other, "I own this server", 5);
        assertTrue(other.automationAuthorized);
        assertEquals(5, other.authorizedAt);
        ServerProfiles.revoke(other);
        assertFalse(other.automationAuthorized);
        assertTrue(p.resolve(true, null).singleplayer);
    }

    @Test void tradingCannotBeEnabledByConfig() {
        ServerProfiles p = ServerProfiles.defaults();
        ServerProfile sp = p.byId("singleplayer");
        sp.modules.put(Module.TRADING, ModuleStatus.AVAILABLE);
        p.sanitize();
        assertEquals(ModuleStatus.UNSUPPORTED, sp.status(Module.TRADING));
    }

    @Test void controllerRequiresAuthorisationEnableAndModuleToggle() {
        List<AuditEvent> audit = new ArrayList<>();
        AutomationController c = ctl(audit);
        ServerProfiles p = ServerProfiles.defaults();
        c.setProfile(p.resolve(false, "mc.example.org"), 0);
        assertFalse(c.enable(1).allowed());
        c.setProfile(p.resolve(false, "forge.cobblegalaxy.com"), 2);
        assertFalse(c.acquire(Module.HUNTING, 3).allowed(), "not enabled this session");
        assertTrue(c.enable(4).allowed());
        assertFalse(c.acquire(Module.HUNTING, 5).allowed(), "module switched off");
        c.setModuleEnabled(Module.HUNTING, true);
        assertTrue(c.acquire(Module.HUNTING, 6).allowed());
        assertTrue(c.holds(Module.HUNTING));
        assertFalse(c.acquire(Module.TRADING, 7).allowed());
        assertTrue(audit.stream().anyMatch(e -> e.action().equals("enable") && e.outcome().equals("denied")));
    }

    @Test void singleOwnerAndPreemption() {
        AutomationController c = ctl(new ArrayList<>());
        c.setProfile(ServerProfiles.defaults().resolve(true, null), 0);
        c.enable(0);
        c.setModuleEnabled(Module.TRAINING, true);
        c.setModuleEnabled(Module.HUNTING, true);
        c.setModuleEnabled(Module.CAPTURE, true);
        assertTrue(c.acquire(Module.TRAINING, 1).allowed());
        assertTrue(c.acquire(Module.HUNTING, 2).allowed(), "higher priority pre-empts");
        assertFalse(c.holds(Module.TRAINING));
        assertFalse(c.acquire(Module.TRAINING, 3).allowed(), "lower priority must wait");
        c.release(Module.HUNTING, 4, "done");
        assertTrue(c.acquire(Module.TRAINING, 5).allowed());
    }

    @Test void pauseResumeEmergencyStopAndRuntimeLimit() {
        AutomationController.Limits l = new AutomationController.Limits();
        l.maxRunMinutes = 1;
        AutomationController c = new AutomationController(l, null);
        c.setProfile(ServerProfiles.defaults().resolve(true, null), 0);
        c.enable(0);
        c.setModuleEnabled(Module.HUNTING, true);
        c.acquire(Module.HUNTING, 1);
        c.pause(2, "user");
        assertNull(c.leaseHolder());
        assertFalse(c.acquire(Module.HUNTING, 3).allowed());
        assertTrue(c.resume(4).allowed());
        assertTrue(c.acquire(Module.HUNTING, 5).allowed());
        c.emergencyStop(6, "key");
        assertFalse(c.sessionEnabled());
        assertEquals(AutomationController.State.STOPPED, c.state());
        assertFalse(c.acquire(Module.HUNTING, 7).allowed());
        c.enable(10);
        assertFalse(c.mayRun(Module.HUNTING, 10 + 61_000).allowed());
        assertEquals("maximum run time reached", c.stopReason());
    }

    @Test void repeatedFailuresStopInsteadOfRetrying() {
        AutomationController c = ctl(new ArrayList<>());
        c.setProfile(ServerProfiles.defaults().resolve(true, null), 0);
        c.enable(0);
        c.setModuleEnabled(Module.NAVIGATION, true);
        c.acquire(Module.NAVIGATION, 0);
        for (int i = 0; i < 4; i++) c.outcome(Module.NAVIGATION, i, "navigate", "stuck", false);
        assertNotEquals(AutomationController.State.ERROR, c.state());
        c.outcome(Module.NAVIGATION, 5, "navigate", "stuck", false);
        assertEquals(AutomationController.State.ERROR, c.state());
        assertFalse(c.acquire(Module.NAVIGATION, 6).allowed());
        c.outcome(Module.NAVIGATION, 7, "x", "", true);
        assertTrue(c.resume(8).allowed());
    }

    @Test void serverChangeRequiresReEnable() {
        AutomationController c = ctl(new ArrayList<>());
        ServerProfiles p = ServerProfiles.defaults();
        c.setProfile(p.resolve(true, null), 0);
        c.enable(0);
        c.setModuleEnabled(Module.HUNTING, true);
        c.acquire(Module.HUNTING, 1);
        c.setProfile(p.resolve(false, "forge.cobblegalaxy.com"), 2);
        assertNull(c.leaseHolder());
        assertFalse(c.sessionEnabled());
    }

    @Test void tasksAreNeverResumedAfterRestart() {
        TaskStore s = new TaskStore(dir.resolve("tasks.jsonl"), AsyncWriter.inline());
        TaskRecord hunt = s.start(Module.HUNTING, "hunt rares", "cobblegalaxy", false, 1);
        TaskRecord buy = s.start(Module.TRADING, "buy listing", "cobblegalaxy", true, 2);
        TaskRecord done = s.start(Module.TRAINING, "train", "sp", false, 3);
        s.update(done, TaskRecord.Status.DONE, "finished", null, 4);
        TaskStore after = new TaskStore(dir.resolve("tasks.jsonl"), AsyncWriter.inline());
        assertEquals(2, after.interruptedOnLoad().size());
        assertNull(after.active(Module.HUNTING));
        TaskRecord b = after.all().stream().filter(t -> t.id.equals(buy.id)).findFirst().orElseThrow();
        assertEquals(TaskRecord.Status.INTERRUPTED, b.status);
        assertTrue(b.note.contains("irreversible: not resumed"));
        assertEquals(TaskRecord.Status.DONE, after.all().stream().filter(t -> t.id.equals(done.id)).findFirst().orElseThrow().status);
        assertNotNull(hunt);
    }

    @Test void plannerRanksByRiskAdjustedValueAndMarksExecutability() {
        AutomationController c = ctl(new ArrayList<>());
        c.setProfile(ServerProfiles.defaults().resolve(false, "forge.cobblegalaxy.com"), 0);
        c.enable(0);
        c.setModuleEnabled(Module.HUNTING, true);
        var hunting = new ProfitEngine.Option("Rare hunting", Activity.RARE_HUNTING, new Estimate(250_000, 150_000, 350_000, 20, Confidence.MEDIUM, "log"), "x", true);
        var trading = new ProfitEngine.Option("Trading", Activity.TRADING, new Estimate(310_000, 20_000, 600_000, 3, Confidence.LOW, "log"), "x", true);
        var plans = TaskPlanner.plan(List.of(trading, hunting), c, new StrategyEvaluator(), 1);
        assertEquals("Rare hunting", plans.get(0).option().name(), "higher lower-bound wins over higher but uncertain mean");
        assertTrue(plans.get(0).executable());
        assertFalse(plans.get(1).executable());
        assertTrue(plans.get(1).why().startsWith("advice only"));
    }

    @Test void strategyCalibration() {
        StrategyEvaluator e = new StrategyEvaluator();
        assertEquals(1.0, e.calibration("s").factor());
        for (int i = 0; i < 9; i++) e.record(new StrategyEvaluator.Outcome("s", 250_000, 170_000, i));
        var cal = e.calibration("s");
        assertEquals(9, cal.n());
        assertEquals(0.68 * 0.75 + 0.25, cal.factor(), 1e-9);
        assertEquals(32, cal.medianErrorPct(), 1e-9);
        assertEquals(250_000 * cal.factor(), e.calibrated("s", 250_000), 1e-6);
    }

    @Test void resourceCheck() {
        assertFalse(ResourceCheck.forHunting(Map.of("poke_ball", 2), 5, 20, 8).ok());
        var r = ResourceCheck.forHunting(Map.of("poke_ball", 6), 5, 20, 8);
        assertTrue(r.ok());
        assertFalse(r.warnings().isEmpty());
        assertFalse(ResourceCheck.forHunting(Map.of("poke_ball", 60), 5, 4, 8).ok());
    }
}
