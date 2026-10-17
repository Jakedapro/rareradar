package dev.rareradar.core.mechanics;

import java.util.EnumSet;
import java.util.Set;

/**
 * Ranges of possible inputs for {@link CaptureMath#bounds}. Each field describes everything that could be true; the
 * bounds are the exact minimum and maximum of the per-throw capture probability over that set.
 *
 * <p>Current HP is given either as an absolute range ({@code currentHpMin..currentHpMax}) or, when that is
 * {@code null}, as a fraction of max HP ({@code hpFractionMin..hpFractionMax}); the fraction is widened outward
 * to whole HP ({@code floor(fMin*max) .. ceil(fMax*max)}) and clipped to {@code 1..max}.
 *
 * @param catchRate            the catch rate; {@code null} makes the bounds unknown (no meaningful range exists)
 * @param maxHpMin             smallest possible max HP (see {@link StatMath#hpRange})
 * @param maxHpMax             largest possible max HP
 * @param currentHpMin         smallest possible current HP, or null to use the fraction
 * @param currentHpMax         largest possible current HP, or null to use the fraction
 * @param hpFractionMin        smallest possible current/max HP fraction (0..1)
 * @param hpFractionMax        largest possible current/max HP fraction (0..1)
 * @param levelMin             smallest possible level
 * @param levelMax             largest possible level
 * @param statuses             possible statuses
 * @param inBattle             possible values of "target is in battle"
 * @param ballGuaranteed       the ball's modifier guarantees capture
 * @param ballBonusMin         smallest possible effective ball bonus (1 if the condition might not apply)
 * @param ballBonusMax         largest possible effective ball bonus
 * @param caughtCountMin       smallest possible Pokédex CaughtCount
 * @param caughtCountMax       largest possible Pokédex CaughtCount
 * @param penaltyReferenceLevel see {@link CaptureInputs#penaltyReferenceLevel()}
 * @param maxPokemonLevel      config value
 */
public record CaptureRanges(
        Integer catchRate,
        int maxHpMin, int maxHpMax,
        Integer currentHpMin, Integer currentHpMax,
        double hpFractionMin, double hpFractionMax,
        int levelMin, int levelMax,
        Set<PersistentStatus> statuses,
        Set<Boolean> inBattle,
        boolean ballGuaranteed,
        float ballBonusMin, float ballBonusMax,
        int caughtCountMin, int caughtCountMax,
        Integer penaltyReferenceLevel,
        int maxPokemonLevel) {

    public CaptureRanges {
        statuses = statuses.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(statuses));
        inBattle = Set.copyOf(inBattle);
    }

    public static Builder builder() { return new Builder(); }

    /** Defaults describe total ignorance except for the fields that must be supplied (catch rate, max HP, level). */
    public static final class Builder {
        private Integer catchRate;
        private int maxHpMin = -1, maxHpMax = -1;
        private Integer currentHpMin, currentHpMax;
        private double hpFractionMin = 0, hpFractionMax = 1;
        private int levelMin = -1, levelMax = -1;
        private Set<PersistentStatus> statuses = EnumSet.allOf(PersistentStatus.class);
        private Set<Boolean> inBattle = Set.of(true, false);
        private boolean ballGuaranteed;
        private float ballBonusMin = 1F, ballBonusMax = 1F;
        private int caughtCountMin = 0, caughtCountMax = Integer.MAX_VALUE;
        private Integer penaltyReferenceLevel;
        private int maxPokemonLevel = CaptureMath.DEFAULT_MAX_POKEMON_LEVEL;

        public Builder catchRate(Integer v) { catchRate = v; return this; }
        public Builder maxHp(int min, int max) { maxHpMin = min; maxHpMax = max; return this; }
        public Builder currentHp(Integer min, Integer max) { currentHpMin = min; currentHpMax = max; return this; }
        public Builder hpFraction(double min, double max) { hpFractionMin = min; hpFractionMax = max; currentHpMin = null; currentHpMax = null; return this; }
        public Builder level(int min, int max) { levelMin = min; levelMax = max; return this; }
        public Builder statuses(Set<PersistentStatus> v) { statuses = v; return this; }
        public Builder inBattle(Set<Boolean> v) { inBattle = v; return this; }
        public Builder ballGuaranteed(boolean v) { ballGuaranteed = v; return this; }
        public Builder ballBonus(float min, float max) { ballBonusMin = min; ballBonusMax = max; return this; }
        /** Bonus range of a ball whose value is known but whose condition may or may not apply: {1, value}. */
        public Builder ballMaybeApplies(float value) { ballBonusMin = Math.min(1F, value); ballBonusMax = Math.max(1F, value); return this; }
        public Builder caughtCount(int min, int max) { caughtCountMin = min; caughtCountMax = max; return this; }
        public Builder penaltyReferenceLevel(Integer v) { penaltyReferenceLevel = v; return this; }
        public Builder maxPokemonLevel(int v) { maxPokemonLevel = v; return this; }

        public CaptureRanges build() {
            return new CaptureRanges(catchRate, maxHpMin, maxHpMax, currentHpMin, currentHpMax, hpFractionMin,
                    hpFractionMax, levelMin, levelMax, statuses, inBattle, ballGuaranteed, ballBonusMin, ballBonusMax,
                    caughtCountMin, caughtCountMax, penaltyReferenceLevel, maxPokemonLevel);
        }
    }
}
