package dev.rareradar.core.autohunt;

/** One tick of facts gathered by the Minecraft layer. Plain data so the state machine is testable. */
public final class Sensors {
    public boolean worldPresent = true;
    public boolean screenOpen;
    public boolean disconnected;
    public float health = 20;
    public long nowMillis;

    /** Current target candidate chosen by the scanner (null if none qualifies). */
    public Long bestCandidateId;
    public double bestCandidateScore;

    // Facts about the locked target (only meaningful when the machine has one)
    public boolean targetPresent;
    public boolean targetInBattle;
    public boolean targetInThrowRange;   // distance ≤ throw range and line of sight
    public boolean aimed;                // crosshair within tolerance of the target

    // Navigation
    public boolean pathFound;
    public boolean pathFinished;
    public boolean makingProgress = true;

    // Throwing
    public int ballSlot = -1;            // chosen by BallPolicy; -1 = none allowed
    public CaptureClassifier.BallState ownBallState; // null = our ball not present
}
