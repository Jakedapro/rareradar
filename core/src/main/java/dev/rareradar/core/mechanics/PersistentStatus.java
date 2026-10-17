package dev.rareradar.core.mechanics;

/**
 * Persistent (non-volatile) status of a Pokémon, as read by Cobblemon 1.8.2's default capture calculator through
 * {@code pokemon.status?.status} ({@code Pokemon.status} is a {@code PersistentStatusContainer?}, Pokemon.kt:374).
 * Volatile statuses (attract, confuse) are not part of {@code pokemon.status} and do not affect capture.
 *
 * <p>The capture bonus is transcribed from
 * {@code common/src/main/kotlin/com/cobblemon/mod/common/pokeball/catching/calculators/CobblemonCaptureCalculator.kt}
 * lines 65-69 (Cobblemon tag 1.8.2, commit 643bf9068b51c829316429916373e5aa9864b8b9):
 * <pre>
 *   is SleepStatus, is FrozenStatus -> 2.5F
 *   is ParalysisStatus, is BurnStatus, is PoisonStatus, is PoisonBadlyStatus -> 1.5F
 *   else -> 1F
 * </pre>
 */
public enum PersistentStatus {
    NONE("none", 1F),
    SLEEP("cobblemon:sleep", 2.5F),
    FROZEN("cobblemon:frozen", 2.5F),
    PARALYSIS("cobblemon:paralysis", 1.5F),
    BURN("cobblemon:burn", 1.5F),
    POISON("cobblemon:poison", 1.5F),
    POISON_BADLY("cobblemon:poisonbadly", 1.5F);

    /** Cobblemon status identifier ({@code cobblemonResource(...)} in the status class), or "none". */
    public final String id;
    /** {@code bonusStatus} in CobblemonCaptureCalculator.kt:65-69. */
    public final float captureBonus;

    PersistentStatus(String id, float captureBonus) {
        this.id = id;
        this.captureBonus = captureBonus;
    }
}
