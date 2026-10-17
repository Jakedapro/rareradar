package dev.rareradar.core.mechanics;

/**
 * Inputs of one throw for Cobblemon 1.8.2's default capture calculator ({@code CobblemonCaptureCalculator}).
 * Boxed fields are {@code null} when RareRadar does not know them; {@link CaptureMath#evaluate} then reports them as
 * missing instead of assuming a value.
 *
 * @param catchRate             {@code pokemon.form.catchRate} after the {@code POKEMON_CATCH_RATE} event
 *                              (CaptureCalculator.kt:48-59). Vanilla Cobblemon has no listener that changes it, but
 *                              data-pack callbacks and server plugins can.
 * @param maxHp                 {@code pokemon.maxHealth} (the HP stat).
 * @param currentHp             {@code pokemon.currentHealth}.
 * @param level                 {@code pokemon.level}.
 * @param status                persistent status ({@link PersistentStatus#NONE} when it has none).
 * @param inBattle              {@code target.battleId != null} (CobblemonCaptureCalculator.kt:62).
 * @param ball                  the thrown ball's modifier.
 * @param caughtCount           thrower's global Pokédex {@code CaughtCount} (species with knowledge OWNED), used by the
 *                              critical capture roll (CriticalCaptureProvider.kt:34).
 * @param penaltyReferenceLevel the level {@code findHighestThrowerLevel} returns (CobblemonCaptureCalculator.kt:102-112),
 *                              or {@code null} when it returns null. See docs/MECHANICS.md: for a wild target the
 *                              lookup requires the target to be among the thrower's own active Pokémon, so it returns
 *                              null; {@code null} is therefore the expected value, not "unknown".
 * @param maxPokemonLevel       {@code Cobblemon.config.maxPokemonLevel} (default 100, CobblemonConfig.kt:44).
 */
public record CaptureInputs(
        Integer catchRate,
        Integer maxHp,
        Integer currentHp,
        Integer level,
        PersistentStatus status,
        Boolean inBattle,
        BallEffect ball,
        Integer caughtCount,
        Integer penaltyReferenceLevel,
        int maxPokemonLevel) {

    public static Builder builder() { return new Builder(); }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.catchRate = catchRate; b.maxHp = maxHp; b.currentHp = currentHp; b.level = level; b.status = status;
        b.inBattle = inBattle; b.ball = ball; b.caughtCount = caughtCount; b.penaltyReferenceLevel = penaltyReferenceLevel;
        b.maxPokemonLevel = maxPokemonLevel;
        return b;
    }

    public static final class Builder {
        private Integer catchRate, maxHp, currentHp, level, caughtCount, penaltyReferenceLevel;
        private PersistentStatus status;
        private Boolean inBattle;
        private BallEffect ball = BallEffect.unknown();
        private int maxPokemonLevel = CaptureMath.DEFAULT_MAX_POKEMON_LEVEL;

        public Builder catchRate(Integer v) { catchRate = v; return this; }
        public Builder hp(Integer current, Integer max) { currentHp = current; maxHp = max; return this; }
        public Builder level(Integer v) { level = v; return this; }
        public Builder status(PersistentStatus v) { status = v; return this; }
        public Builder inBattle(Boolean v) { inBattle = v; return this; }
        public Builder ball(BallEffect v) { ball = v == null ? BallEffect.unknown() : v; return this; }
        public Builder caughtCount(Integer v) { caughtCount = v; return this; }
        public Builder penaltyReferenceLevel(Integer v) { penaltyReferenceLevel = v; return this; }
        public Builder maxPokemonLevel(int v) { maxPokemonLevel = v; return this; }

        public CaptureInputs build() {
            return new CaptureInputs(catchRate, maxHp, currentHp, level, status, inBattle, ball, caughtCount,
                    penaltyReferenceLevel, maxPokemonLevel);
        }
    }
}
