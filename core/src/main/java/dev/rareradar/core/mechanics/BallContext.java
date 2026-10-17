package dev.rareradar.core.mechanics;

import java.util.List;
import java.util.Set;

/**
 * Facts a ball's catch-rate condition may read. Every field is nullable: {@code null} means "not known", and a ball
 * whose condition needs it evaluates to an unknown {@link BallEffect} part instead of a guess.
 *
 * @param inBattle         thrower is in a battle with the target (for BattleModifier balls this is
 *                         {@code BattleRegistry.getBattleByParticipatingPlayer(player) != null}; Cobblemon refuses an
 *                         out-of-battle throw while the player is in a battle, EmptyPokeBallEntity.kt:256-259)
 * @param level            target level
 * @param types            target form's elemental type ids, lower case (e.g. "bug", "water")
 * @param baseSpeed        target form's base Speed
 * @param weightHectograms target form's weight in hectograms ({@code form.weight})
 * @param ultraBeast       target has the {@code ultra_beast} label
 * @param status           target persistent status
 * @param fishedAspect     target entity aspects contain {@code "fished"} (FishingSpawnCause.FISHED_ASPECT)
 * @param underWater       target entity {@code isUnderWater}
 * @param maxLocalRawBrightness {@code level.getMaxLocalRawBrightness(entity.blockPosition())}
 * @param dayTime          {@code level.dayTime}
 * @param moonPhase        {@code level.moonPhase} (0..7)
 * @param temperateBiome   biome at the target's block is in tag {@code cobblemon:is_temperate}
 * @param speciesOwned     thrower's Pokédex knowledge for the target species is OWNED
 * @param battleTurn       {@code battle.turn}
 * @param targetGender     "MALE", "FEMALE" or "GENDERLESS"
 * @param targetSpeciesId  e.g. "cobblemon:eevee"
 * @param playerActive     the thrower's active battle Pokémon ({@code originalPokemon} level, gender, species)
 */
public record BallContext(
        Boolean inBattle,
        Integer level,
        Set<String> types,
        Integer baseSpeed,
        Float weightHectograms,
        Boolean ultraBeast,
        PersistentStatus status,
        Boolean fishedAspect,
        Boolean underWater,
        Integer maxLocalRawBrightness,
        Long dayTime,
        Integer moonPhase,
        Boolean temperateBiome,
        Boolean speciesOwned,
        Integer battleTurn,
        String targetGender,
        String targetSpeciesId,
        List<ActiveMon> playerActive) {

    /** One of the thrower's active battle Pokémon. */
    public record ActiveMon(int level, String gender, String speciesId) {}

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Boolean inBattle, ultraBeast, fishedAspect, underWater, temperateBiome, speciesOwned;
        private Integer level, baseSpeed, maxLocalRawBrightness, moonPhase, battleTurn;
        private Set<String> types;
        private Float weightHectograms;
        private PersistentStatus status;
        private Long dayTime;
        private String targetGender, targetSpeciesId;
        private List<ActiveMon> playerActive;

        public Builder inBattle(Boolean v) { inBattle = v; return this; }
        public Builder level(Integer v) { level = v; return this; }
        public Builder types(Set<String> v) { types = v; return this; }
        public Builder baseSpeed(Integer v) { baseSpeed = v; return this; }
        public Builder weightHectograms(Float v) { weightHectograms = v; return this; }
        public Builder ultraBeast(Boolean v) { ultraBeast = v; return this; }
        public Builder status(PersistentStatus v) { status = v; return this; }
        public Builder fishedAspect(Boolean v) { fishedAspect = v; return this; }
        public Builder underWater(Boolean v) { underWater = v; return this; }
        public Builder maxLocalRawBrightness(Integer v) { maxLocalRawBrightness = v; return this; }
        public Builder dayTime(Long v) { dayTime = v; return this; }
        public Builder moonPhase(Integer v) { moonPhase = v; return this; }
        public Builder temperateBiome(Boolean v) { temperateBiome = v; return this; }
        public Builder speciesOwned(Boolean v) { speciesOwned = v; return this; }
        public Builder battleTurn(Integer v) { battleTurn = v; return this; }
        public Builder targetGender(String v) { targetGender = v; return this; }
        public Builder targetSpeciesId(String v) { targetSpeciesId = v; return this; }
        public Builder playerActive(List<ActiveMon> v) { playerActive = v; return this; }

        public BallContext build() {
            return new BallContext(inBattle, level, types, baseSpeed, weightHectograms, ultraBeast, status, fishedAspect,
                    underWater, maxLocalRawBrightness, dayTime, moonPhase, temperateBiome, speciesOwned, battleTurn,
                    targetGender, targetSpeciesId, playerActive);
        }
    }
}
