package dev.rareradar.core.autohunt;

/**
 * Classifies a throw from what the client can observe: the synced capture state of our own thrown ball
 * (Cobblemon's EmptyPokeBallEntity.CaptureState: NOT, HIT, FALL, CRITICAL, SHAKE, CAPTURED, BROKEN_FREE),
 * whether the ball entity still exists, and the target's presence/battle state.
 * A disappearing Pokémon is never treated as a capture by itself.
 */
public final class CaptureClassifier {
    public enum BallState { NOT, HIT, FALL, CRITICAL, SHAKE, CAPTURED, BROKEN_FREE }

    private boolean ballSeen, ballGone, hit, captured, brokeFree, targetGone, targetBattling;
    private int ticks;

    public void reset() {
        ballSeen = ballGone = hit = captured = brokeFree = targetGone = targetBattling = false;
        ticks = 0;
    }

    /** Feed one tick of observations. {@code ballState} null = our ball entity not present this tick. */
    public void observe(BallState ballState, boolean targetPresent, boolean targetInBattle) {
        ticks++;
        if (ballState != null) {
            ballSeen = true;
            switch (ballState) {
                case HIT, CRITICAL, SHAKE -> hit = true;
                case CAPTURED -> { hit = true; captured = true; }
                case BROKEN_FREE -> { hit = true; brokeFree = true; }
                default -> {}
            }
        } else if (ballSeen) ballGone = true;
        if (!targetPresent) targetGone = true;
        if (targetInBattle) targetBattling = true;
    }

    /** Final outcome once resolved; null while still undetermined and not timed out. */
    public CaptureOutcome result(int timeoutTicks) {
        if (captured) return CaptureOutcome.CAPTURED;
        if (brokeFree) return CaptureOutcome.BROKE_FREE;
        if (targetBattling) return CaptureOutcome.TARGET_IN_BATTLE;
        if (ballGone && !hit) return targetGone ? CaptureOutcome.TARGET_DESPAWNED : CaptureOutcome.MISSED;
        if (ballGone) return CaptureOutcome.UNKNOWN;          // ball vanished mid-capture without a final state
        if (!ballSeen && targetGone) return CaptureOutcome.TARGET_DESPAWNED;
        if (ticks >= timeoutTicks) return CaptureOutcome.UNKNOWN;
        return null;
    }
}
