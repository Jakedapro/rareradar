package dev.rareradar.core.autohunt;

import dev.rareradar.core.autohunt.CaptureClassifier.BallState;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;

class AutoHuntTest {
    @Test void classifierNeverInfersCaptureFromDisappearance() {
        CaptureClassifier c = new CaptureClassifier();
        c.observe(BallState.NOT, true, false);
        c.observe(BallState.HIT, true, false);
        c.observe(BallState.SHAKE, false, false); // pokemon hidden inside the ball
        c.observe(null, false, false);            // ball gone, no final state seen
        assertEquals(CaptureOutcome.UNKNOWN, c.result(100));

        c.reset();
        c.observe(BallState.SHAKE, false, false);
        c.observe(BallState.CAPTURED, false, false);
        assertEquals(CaptureOutcome.CAPTURED, c.result(100));

        c.reset();
        c.observe(BallState.SHAKE, false, false);
        c.observe(BallState.BROKEN_FREE, true, false);
        assertEquals(CaptureOutcome.BROKE_FREE, c.result(100));

        c.reset();
        c.observe(BallState.NOT, true, false);
        c.observe(BallState.FALL, true, false);
        c.observe(null, true, false);
        assertEquals(CaptureOutcome.MISSED, c.result(100));

        c.reset();
        c.observe(null, false, false);
        assertEquals(CaptureOutcome.TARGET_DESPAWNED, c.result(100));

        c.reset();
        c.observe(BallState.HIT, true, true);
        assertEquals(CaptureOutcome.TARGET_IN_BATTLE, c.result(100));

        c.reset();
        for (int i = 0; i < 5; i++) c.observe(BallState.NOT, true, false);
        assertNull(c.result(100));
        assertEquals(CaptureOutcome.UNKNOWN, c.result(5));
    }

    static Sensors base() {
        Sensors s = new Sensors();
        s.bestCandidateId = 7L; s.bestCandidateScore = 5;
        s.targetPresent = true; s.pathFound = true; s.ballSlot = 2;
        return s;
    }

    @Test void happyPath() {
        AutoHuntMachine m = new AutoHuntMachine(new SafetyLimits());
        m.start(0);
        Sensors s = base();
        assertEquals(Command.Kind.RELEASE_KEYS, m.tick(s).kind());          // SCAN → SELECT
        assertEquals(Command.Kind.PLAN_PATH, m.tick(s).kind());             // SELECT → PLAN
        assertEquals(Command.Kind.FOLLOW_PATH, m.tick(s).kind());           // PLAN → APPROACH
        s.targetInThrowRange = true;
        assertEquals(Command.Kind.AIM, m.tick(s).kind());                   // APPROACH → AIM
        s.aimed = true;
        m.tick(s);                                                          // AIM → THROW
        Command t = m.tick(s);                                              // THROW
        assertEquals(Command.Kind.THROW, t.kind());
        assertEquals(2, t.slot());
        s.ownBallState = BallState.SHAKE; m.tick(s);
        s.ownBallState = BallState.CAPTURED; m.tick(s);
        assertEquals(HuntState.VERIFY_CAPTURE, m.state());
        m.tick(s);
        assertEquals(HuntState.SUCCESS, m.state());
        assertEquals(1, m.captures());
        assertEquals(CaptureOutcome.CAPTURED, m.lastOutcome());
    }

    @Test void safetyStops() {
        AutoHuntMachine m = new AutoHuntMachine(new SafetyLimits());
        m.start(0);
        Sensors s = base();
        s.health = 3;
        assertEquals(Command.Kind.STOP, m.tick(s).kind());
        assertEquals("low health", m.stopReason());

        m.start(0);
        s = base(); s.worldPresent = false;
        assertEquals(Command.Kind.STOP, m.tick(s).kind());
        assertFalse(m.running());
    }

    @Test void noBallsAbortsAndBudgetsHold() {
        SafetyLimits l = new SafetyLimits();
        l.maxBallsPerTarget = 2;
        AutoHuntMachine m = new AutoHuntMachine(l);
        m.start(0);
        Sensors s = base();
        s.targetInThrowRange = true; s.aimed = true;
        int thrown = 0;
        for (int i = 0; i < 200 && m.running(); i++) {
            s.ownBallState = null;
            Command c = m.tick(s);
            if (c.kind() == Command.Kind.THROW) { thrown++; s.ownBallState = BallState.BROKEN_FREE; m.tick(s); }
        }
        assertTrue(thrown >= 2);
        assertTrue(m.ballsThisRun() <= new SafetyLimits().maxBallsPerRun);

        AutoHuntMachine m2 = new AutoHuntMachine(new SafetyLimits());
        m2.start(0);
        Sensors n = base();
        n.targetInThrowRange = true; n.aimed = true; n.ballSlot = -1;
        for (int i = 0; i < 10 && m2.running(); i++) m2.tick(n);
        assertEquals(HuntState.ABORT, m2.state());
        assertEquals(CaptureOutcome.NO_BALLS, m2.lastOutcome());
    }

    @Test void stuckLeadsToRetryThenRepeatedPlanFailuresAbort() {
        SafetyLimits l = new SafetyLimits();
        AutoHuntMachine m = new AutoHuntMachine(l);
        m.start(0);
        Sensors s = base();
        s.makingProgress = false;
        boolean sawRetry = false;
        for (int i = 0; i < l.stuckTimeoutTicks + 20; i++) { m.tick(s); if (m.state() == HuntState.RETRY) sawRetry = true; }
        assertTrue(sawRetry);
        // Approach timeout also survives repeated re-plans (V1 bug: it reset on every repath).
        SafetyLimits l2 = new SafetyLimits();
        AutoHuntMachine slow = new AutoHuntMachine(l2);
        slow.start(0);
        Sensors crawl = base();
        crawl.pathFinished = true; // forces a re-plan every approach tick
        boolean timedOut = false;
        for (int i = 0; i < l2.approachTimeoutTicks * 3 && !timedOut; i++) {
            slow.tick(crawl);
            for (var t : slow.history()) if (t.why().equals("approach timed out")) timedOut = true;
        }
        assertTrue(timedOut);
        AutoHuntMachine m2 = new AutoHuntMachine(l);
        m2.start(0);
        Sensors np = base(); np.pathFound = false;
        // Each unreachable target is blacklisted; the run aborts once several different targets in a row have no route.
        for (int i = 0; i < 100 && m2.running(); i++) { np.bestCandidateId = 100L + i; m2.tick(np); }
        assertEquals("no route to targets", m2.stopReason());
    }

    @Test void despawnedTargetIsNotACapture() {
        AutoHuntMachine m = new AutoHuntMachine(new SafetyLimits());
        m.start(0);
        Sensors s = base();
        s.targetInThrowRange = true; s.aimed = true;
        for (int i = 0; i < 5; i++) m.tick(s);
        assertEquals(HuntState.AWAIT_RESULT, m.state());
        s.targetPresent = false; s.ownBallState = null;
        m.tick(s);
        m.tick(s);
        assertEquals(0, m.captures());
        assertNotEquals(CaptureOutcome.CAPTURED, m.lastOutcome());
    }

    @Test void outcomeReportedOnceEvenWhilePaused() {
        AutoHuntMachine m = new AutoHuntMachine(new SafetyLimits());
        m.start(0);
        Sensors s = base();
        s.targetInThrowRange = true; s.aimed = true;
        for (int i = 0; i < 5; i++) m.tick(s);
        s.ownBallState = BallState.CAPTURED;
        m.tick(s);
        assertEquals(HuntState.VERIFY_CAPTURE, m.state());
        s.screenOpen = true;
        int reported = 0;
        for (int i = 0; i < 10; i++) { m.tick(s); if (m.consumeOutcome() != null) reported++; }
        assertEquals(1, reported);
    }

    @Test void perTargetBudgetSurvivesReselectionAndFailedTargetsAreSkipped() {
        SafetyLimits l = new SafetyLimits();
        l.maxBallsPerTarget = 2;
        AutoHuntMachine m = new AutoHuntMachine(l);
        m.start(0);
        Sensors s = base();
        s.targetInThrowRange = true; s.aimed = true;
        int thrownAt7 = 0;
        for (int i = 0; i < 400 && m.running(); i++) {
            s.nowMillis = i * 50L;
            s.ownBallState = null;
            Command c = m.tick(s);
            if (c.kind() == Command.Kind.THROW) { thrownAt7++; s.ownBallState = BallState.BROKEN_FREE; s.nowMillis += 50; m.tick(s); }
        }
        assertEquals(2, thrownAt7, "the same target must not get a fresh ball budget when re-selected");
        assertTrue(m.blacklisted(7L, 1000));
        assertFalse(m.blacklisted(7L, AutoHuntMachine.BLACKLIST_MS + 400 * 50L));
    }

    @Test void screenOpenPauses() {
        AutoHuntMachine m = new AutoHuntMachine(new SafetyLimits());
        m.start(0);
        Sensors s = base();
        s.screenOpen = true;
        assertEquals(Command.Kind.RELEASE_KEYS, m.tick(s).kind());
        assertEquals(HuntState.SCAN, m.state());
    }
}
