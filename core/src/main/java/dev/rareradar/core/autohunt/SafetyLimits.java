package dev.rareradar.core.autohunt;

/** Hard limits for a private-world automation run. All counts in client ticks (20/s) unless stated. */
public final class SafetyLimits {
    public int maxRunMinutes = 30;
    public int maxBallsPerRun = 64;
    public int maxBallsPerTarget = 8;
    public int maxPlanFailures = 5;
    public int approachTimeoutTicks = 20 * 60;   // give up on reaching one target after 60 s
    public int stuckTimeoutTicks = 20 * 8;       // abort after 8 s without progress (repath before that)
    public int awaitResultTimeoutTicks = 20 * 15;
    public int aimTimeoutTicks = 20 * 3;
    public float minHealth = 8.0f;

    public void sanitize() {
        maxRunMinutes = clamp(maxRunMinutes, 1, 240);
        maxBallsPerRun = clamp(maxBallsPerRun, 1, 1000);
        maxBallsPerTarget = clamp(maxBallsPerTarget, 1, 64);
        maxPlanFailures = clamp(maxPlanFailures, 1, 50);
        approachTimeoutTicks = clamp(approachTimeoutTicks, 100, 20 * 600);
        stuckTimeoutTicks = clamp(stuckTimeoutTicks, 40, 20 * 60);
        awaitResultTimeoutTicks = clamp(awaitResultTimeoutTicks, 100, 20 * 60);
        aimTimeoutTicks = clamp(aimTimeoutTicks, 10, 20 * 10);
        if (!(minHealth >= 1) || minHealth > 20) minHealth = 8;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
