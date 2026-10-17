package dev.rareradar.core.automation;

import java.util.*;

/**
 * The single owner of automated control. Modules must hold the lease before moving the player or interacting with
 * the world; only one module holds it at a time. The controller enforces the server profile, the user's per-session
 * enable, per-module toggles, a run-time limit, a consecutive-failure limit, pause/resume and the emergency stop, and
 * writes every decision to the audit trail. It never performs game actions itself.
 */
public final class AutomationController {
    public enum State { IDLE, SCANNING, PLANNING, TARGET_SELECTED, NAVIGATING, INTERACTING, VERIFYING, RECOVERING, PAUSED, ERROR, STOPPED }

    public record Decision(boolean allowed, String reason) {
        static Decision yes(String r) { return new Decision(true, r); }
        static Decision no(String r) { return new Decision(false, r); }
    }

    public interface AuditSink { void record(AuditEvent e); }

    public static final class Limits {
        public int maxRunMinutes = 60;
        public int maxConsecutiveFailures = 5;
    }

    private final Limits limits;
    private final AuditSink audit;
    private final Deque<AuditEvent> recent = new ArrayDeque<>();
    private final EnumSet<Module> userEnabled = EnumSet.noneOf(Module.class);
    private ServerProfile profile;
    private boolean sessionEnabled;
    private State state = State.STOPPED;
    private Module leaseHolder;
    private long runStartedAt;
    private int consecutiveFailures;
    private String stopReason = "not started";

    public AutomationController(Limits limits, AuditSink audit) {
        this.limits = limits;
        this.audit = audit == null ? e -> {} : audit;
    }

    public State state() { return state; }
    public Module leaseHolder() { return leaseHolder; }
    public ServerProfile profile() { return profile; }
    public boolean sessionEnabled() { return sessionEnabled; }
    public String stopReason() { return stopReason; }
    public List<AuditEvent> recent() { return List.copyOf(recent); }
    public boolean moduleEnabled(Module m) { return userEnabled.contains(m); }

    /** Called on join/world change; drops any lease and requires a fresh enable (profiles may require it). */
    public void setProfile(ServerProfile p, long now) {
        if (leaseHolder != null) release(leaseHolder, now, "server changed");
        this.profile = p;
        if (p == null || p.requireUserEnable) sessionEnabled = false;
        state = State.STOPPED;
        stopReason = "server changed";
        log(now, Module.SAFETY, "profile", p == null ? "none" : p.id + (p.automationAuthorized ? " (authorised: " + p.authorizationSource + ")" : " (not authorised)"), "ok");
    }

    public void setModuleEnabled(Module m, boolean on) { if (on) userEnabled.add(m); else userEnabled.remove(m); }

    /** The user switches automation on for this session. Fails (with a reason) if the profile does not allow it. */
    public Decision enable(long now) {
        if (profile == null) return deny(now, Module.SAFETY, "enable", "no server profile");
        if (!profile.automationAuthorized) return deny(now, Module.SAFETY, "enable", "automation is not authorised for " + profile.displayName + " — confirm permission on the Automation page first");
        sessionEnabled = true;
        consecutiveFailures = 0;
        runStartedAt = now;
        state = State.IDLE;
        stopReason = "";
        log(now, Module.SAFETY, "enable", profile.displayName, "ok");
        return Decision.yes("enabled");
    }

    public void pause(long now, String why) {
        if (state == State.STOPPED) return;
        if (leaseHolder != null) release(leaseHolder, now, "paused");
        state = State.PAUSED;
        log(now, Module.SAFETY, "pause", why, "ok");
    }

    public Decision resume(long now) {
        if (!sessionEnabled) return deny(now, Module.SAFETY, "resume", "automation not enabled");
        if (state != State.PAUSED && state != State.ERROR) return Decision.yes("not paused");
        consecutiveFailures = 0;
        state = State.IDLE;
        log(now, Module.SAFETY, "resume", "", "ok");
        return Decision.yes("resumed");
    }

    /** Emergency stop: immediately revokes the lease and the session enable. */
    public void emergencyStop(long now, String why) {
        if (leaseHolder != null) release(leaseHolder, now, "emergency stop");
        sessionEnabled = false;
        state = State.STOPPED;
        stopReason = why;
        log(now, Module.SAFETY, "emergency_stop", why, "ok");
    }

    /** Can this module act right now? Checked before every lease request and on every tick by the holder. */
    public Decision mayRun(Module m, long now) {
        if (profile == null) return Decision.no("no server profile");
        if (!profile.automationAuthorized) return Decision.no("not authorised on " + profile.displayName);
        ModuleStatus st = profile.status(m);
        if (st == ModuleStatus.UNSUPPORTED) return Decision.no(m.label + " automation is unsupported (no verified interface)");
        if (st != ModuleStatus.AVAILABLE) return Decision.no(m.label + " is disabled for " + profile.displayName);
        if (!sessionEnabled) return Decision.no("automation not enabled this session");
        if (!userEnabled.contains(m)) return Decision.no(m.label + " module is switched off");
        if (state == State.PAUSED) return Decision.no("paused");
        if (state == State.ERROR) return Decision.no("stopped after errors: " + stopReason);
        if (state == State.STOPPED) return Decision.no("stopped: " + stopReason);
        if (runStartedAt > 0 && now - runStartedAt > limits.maxRunMinutes * 60_000L) {
            emergencyStop(now, "maximum run time reached");
            return Decision.no("maximum run time reached");
        }
        return Decision.yes("ok");
    }

    /** Requests exclusive control. A higher-priority module (lower ordinal) may take the lease from a lower one. */
    public Decision acquire(Module m, long now) {
        Decision d = mayRun(m, now);
        if (!d.allowed()) return d;
        if (leaseHolder == m) return Decision.yes("already held");
        if (leaseHolder != null) {
            if (m.ordinal() >= leaseHolder.ordinal()) return Decision.no("control held by " + leaseHolder.label);
            release(leaseHolder, now, "pre-empted by " + m.label);
        }
        leaseHolder = m;
        state = State.SCANNING;
        log(now, m, "acquire", "", "ok");
        return Decision.yes("acquired");
    }

    public boolean holds(Module m) { return leaseHolder == m; }

    public void release(Module m, long now, String why) {
        if (leaseHolder != m) return;
        leaseHolder = null;
        if (state != State.PAUSED && state != State.STOPPED && state != State.ERROR) state = State.IDLE;
        log(now, m, "release", why, "ok");
    }

    /** The lease holder reports its state; other modules are ignored. */
    public void report(Module m, State s) {
        if (leaseHolder == m && state != State.PAUSED && state != State.STOPPED && state != State.ERROR) state = s;
    }

    /** Records an action result. Repeated failures stop automation instead of retrying forever. */
    public void outcome(Module m, long now, String action, String detail, boolean success) {
        log(now, m, action, detail, success ? "success" : "failure");
        if (success) { consecutiveFailures = 0; return; }
        if (++consecutiveFailures >= limits.maxConsecutiveFailures) {
            if (leaseHolder != null) release(leaseHolder, now, "too many failures");
            state = State.ERROR;
            stopReason = consecutiveFailures + " consecutive failures (last: " + action + " " + detail + ")";
            log(now, Module.SAFETY, "error_stop", stopReason, "ok");
        }
    }

    private Decision deny(long now, Module m, String action, String why) {
        log(now, m, action, why, "denied");
        return Decision.no(why);
    }

    private void log(long now, Module m, String action, String detail, String outcome) {
        AuditEvent e = new AuditEvent(now, profile == null ? "" : profile.id, m, action, detail, outcome);
        if (recent.size() >= 200) recent.removeFirst();
        recent.addLast(e);
        audit.record(e);
    }
}
