package dev.rareradar.core.autohunt;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * AutoHunt V2 state machine:
 * IDLE → SCAN → SELECT_TARGET → PLAN_ROUTE → APPROACH → AIM → THROW → AWAIT_RESULT → VERIFY_CAPTURE →
 * SUCCESS / RETRY / ABORT. Every tick the Minecraft layer supplies {@link Sensors} and executes the returned
 * {@link Command}. Safety checks run before any state logic, so a stop always wins.
 */
public final class AutoHuntMachine {
    public record Transition(long at, HuntState from, HuntState to, String why) {}

    private final SafetyLimits limits;
    private HuntState state = HuntState.IDLE;
    private int stateTicks, runTicks, stuckTicks, approachTicks, ballsThisRun, ballsThisTarget, planFailures;
    private Long target;
    private double targetScore;
    private CaptureOutcome lastOutcome;
    private String stopReason = "";
    private final CaptureClassifier classifier = new CaptureClassifier();
    private final Deque<Transition> history = new ArrayDeque<>();
    private int captures, failures;
    /** Balls thrown per target id this run, so re-selecting a target cannot reset its budget. */
    private final java.util.Map<Long, Integer> ballsByTarget = new java.util.HashMap<>();
    /** Targets that failed (stuck, unreachable, budget used) are skipped until the given time. */
    private final java.util.Map<Long, Long> blacklistUntil = new java.util.HashMap<>();
    private boolean outcomePending;
    public static final long BLACKLIST_MS = 120_000;

    public AutoHuntMachine(SafetyLimits limits) { this.limits = limits; }

    public HuntState state() { return state; }
    public Long target() { return target; }
    public double targetScore() { return targetScore; }
    public CaptureOutcome lastOutcome() { return lastOutcome; }
    public String stopReason() { return stopReason; }
    public int ballsThisRun() { return ballsThisRun; }
    public int captures() { return captures; }
    public int failures() { return failures; }
    public Iterable<Transition> history() { return history; }
    /** True if the scanner should not offer this id as a candidate right now. */
    public boolean blacklisted(long id, long now) {
        Long until = blacklistUntil.get(id);
        return until != null && until > now;
    }

    /** Returns the outcome of the last throw exactly once (null if none since the last call). */
    public CaptureOutcome consumeOutcome() {
        if (!outcomePending) return null;
        outcomePending = false;
        return lastOutcome;
    }

    private void giveUp(long now, String why) {
        failures++;
        if (target != null) blacklistUntil.put(target, now + BLACKLIST_MS);
        target = null;
        go(HuntState.RETRY, now, why);
    }

    public boolean running() { return state != HuntState.IDLE && state != HuntState.ABORT; }

    public void start(long now) {
        runTicks = ballsThisRun = planFailures = captures = failures = 0;
        ballsByTarget.clear();
        blacklistUntil.clear();
        outcomePending = false;
        stopReason = "";
        target = null;
        go(HuntState.SCAN, now, "started");
    }

    /** Emergency stop / toggle off. */
    public Command abort(long now, String why) {
        stopReason = why;
        target = null;
        go(HuntState.ABORT, now, why);
        return Command.stop(why);
    }

    public Command tick(Sensors s) {
        if (!running()) return Command.NONE;
        long now = s.nowMillis;
        runTicks++;
        stateTicks++;
        // --- safety first ---
        if (s.disconnected || !s.worldPresent) return abort(now, "world changed or disconnected");
        if (s.health < limits.minHealth) return abort(now, "low health");
        if (runTicks > limits.maxRunMinutes * 60 * 20) return abort(now, "maximum run time reached");
        if (ballsThisRun >= limits.maxBallsPerRun && state != HuntState.AWAIT_RESULT && state != HuntState.VERIFY_CAPTURE)
            return abort(now, "ball budget for this run used up");
        if (s.screenOpen && state != HuntState.AWAIT_RESULT) return Command.RELEASE; // paused while a menu is open

        switch (state) {
            case SCAN, RETRY -> { go(HuntState.SELECT_TARGET, now, "scan"); return Command.RELEASE; }
            case SUCCESS -> { target = null; go(HuntState.SCAN, now, "next target"); return Command.RELEASE; }
            case SELECT_TARGET -> {
                if (s.bestCandidateId == null) return Command.RELEASE; // idle-wait for a target
                if (blacklisted(s.bestCandidateId, now)) return Command.RELEASE;
                target = s.bestCandidateId;
                ballsThisTarget = ballsByTarget.getOrDefault(target, 0);
                // Stuck and approach timers span re-plans for the same target; only a new selection resets them.
                stuckTicks = 0;
                approachTicks = 0;
                targetScore = s.bestCandidateScore;
                go(HuntState.PLAN_ROUTE, now, "target " + target);
                return new Command(Command.Kind.PLAN_PATH, -1, "");
            }
            case PLAN_ROUTE -> {
                if (!targetValid(s, now)) return Command.RELEASE;
                if (s.targetInThrowRange) { go(HuntState.AIM, now, "already in range"); return new Command(Command.Kind.AIM, -1, ""); }
                if (!s.pathFound) {
                    if (++planFailures >= limits.maxPlanFailures) return abort(now, "no route to targets");
                    giveUp(now, "no path");
                    failures--; // a missing path is counted by planFailures, not as a throw failure
                    return Command.RELEASE;
                }
                planFailures = 0;
                go(HuntState.APPROACH, now, "path found");
                return new Command(Command.Kind.FOLLOW_PATH, -1, "");
            }
            case APPROACH -> {
                if (!targetValid(s, now)) return Command.RELEASE;
                if (s.targetInThrowRange) { go(HuntState.AIM, now, "in range"); return new Command(Command.Kind.AIM, -1, ""); }
                stuckTicks = s.makingProgress ? 0 : stuckTicks + 1;
                approachTicks++;
                if (stuckTicks > limits.stuckTimeoutTicks) { giveUp(now, "stuck"); return Command.RELEASE; }
                if (approachTicks > limits.approachTimeoutTicks) { giveUp(now, "approach timed out"); return Command.RELEASE; }
                if (s.pathFinished || stuckTicks == limits.stuckTimeoutTicks / 2) { go(HuntState.PLAN_ROUTE, now, "repath"); return new Command(Command.Kind.PLAN_PATH, -1, ""); }
                return new Command(Command.Kind.FOLLOW_PATH, -1, "");
            }
            case AIM -> {
                if (!targetValid(s, now)) return Command.RELEASE;
                if (!s.targetInThrowRange) { go(HuntState.PLAN_ROUTE, now, "target moved out of range"); return new Command(Command.Kind.PLAN_PATH, -1, ""); }
                if (s.aimed) { go(HuntState.THROW, now, "aimed"); return new Command(Command.Kind.AIM, -1, ""); }
                if (stateTicks > limits.aimTimeoutTicks) { giveUp(now, "could not aim"); return Command.RELEASE; }
                return new Command(Command.Kind.AIM, -1, "");
            }
            case THROW -> {
                if (!targetValid(s, now)) return Command.RELEASE;
                if (s.ballSlot < 0) { lastOutcome = CaptureOutcome.NO_BALLS; return abort(now, CaptureOutcome.NO_BALLS.label); }
                if (ballsThisTarget >= limits.maxBallsPerTarget) { giveUp(now, "per-target ball limit"); return Command.RELEASE; }
                ballsThisRun++;
                ballsThisTarget++;
                ballsByTarget.put(target, ballsThisTarget);
                classifier.reset();
                go(HuntState.AWAIT_RESULT, now, "thrown");
                return new Command(Command.Kind.THROW, s.ballSlot, "");
            }
            case AWAIT_RESULT -> {
                classifier.observe(s.ownBallState, s.targetPresent, s.targetInBattle);
                CaptureOutcome o = classifier.result(limits.awaitResultTimeoutTicks);
                if (o == null) return Command.RELEASE;
                lastOutcome = o;
                outcomePending = true;
                go(HuntState.VERIFY_CAPTURE, now, o.name());
                return Command.RELEASE;
            }
            case VERIFY_CAPTURE -> {
                if (lastOutcome == CaptureOutcome.CAPTURED) { captures++; go(HuntState.SUCCESS, now, "captured"); }
                else {
                    failures++;
                    boolean retrySame = (lastOutcome == CaptureOutcome.BROKE_FREE || lastOutcome == CaptureOutcome.MISSED) && s.targetPresent && !s.targetInBattle;
                    if (retrySame) go(HuntState.AIM, now, lastOutcome.label);
                    else { if (target != null) blacklistUntil.put(target, now + BLACKLIST_MS); target = null; go(HuntState.RETRY, now, lastOutcome.label); }
                }
                return Command.RELEASE;
            }
            default -> { return Command.NONE; }
        }
    }

    private boolean targetValid(Sensors s, long now) {
        if (target == null) { go(HuntState.SCAN, now, "no target"); return false; }
        if (!s.targetPresent) { target = null; go(HuntState.RETRY, now, "target despawned"); return false; }
        if (s.targetInBattle) { target = null; go(HuntState.RETRY, now, "target in battle"); return false; }
        return true;
    }

    private void go(HuntState to, long now, String why) {
        if (history.size() >= 64) history.removeFirst();
        history.addLast(new Transition(now, state, to, why));
        state = to;
        stateTicks = 0;
    }
}
