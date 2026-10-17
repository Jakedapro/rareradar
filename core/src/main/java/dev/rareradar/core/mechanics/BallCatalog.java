package dev.rareradar.core.mechanics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Every Poké Ball registered by Cobblemon 1.8.2 ({@code PokeBalls.kt} init block, lines 250-297) with its catch-rate
 * modifier transcribed from {@code api/pokeball/catching/modifiers/CatchRateModifiers.kt} (cited as CRM.kt) and the
 * modifier classes. Balls created without a modifier get {@code MultiplierModifier(1F) { _, _ -> true }}
 * (PokeBalls.kt:334). Every modifier's behaviour is MULTIPLY.
 *
 * <p>{@link Ball#clientEvaluable()} is true only when every fact the condition/value reads is data a Cobblemon 1.8.2
 * client is sent (see {@link Ball#clientNote()}); none of these were verified in-game.
 */
public final class BallCatalog {
    private BallCatalog() {}

    /** Kind of {@code CatchRateModifier} the ball uses. */
    public enum ModifierKind {
        /** {@code MultiplierModifier(1F)} default: always applies, x1. */
        DEFAULT_X1,
        /** {@code MultiplierModifier(v)} without condition. */
        MULTIPLIER,
        /** {@code MultiplierModifier(v) { condition }}. */
        CONDITIONAL_MULTIPLIER,
        /** {@code DynamicMultiplierModifier(value, condition)}. */
        DYNAMIC_MULTIPLIER,
        /** {@code BaseStatModifier}. */
        BASE_STAT,
        /** {@code BattleModifier}: always valid; value 1F unless the thrower is in a battle. */
        BATTLE,
        /** {@code WorldStateModifier}: always valid; value from the target entity / world. */
        WORLD_STATE,
        /** {@code GuaranteedModifier}. */
        GUARANTEED
    }

    /**
     * @param id              ball id without namespace (namespace is {@code cobblemon})
     * @param kind            modifier kind
     * @param fixedValue      the value when it does not depend on context, else null
     * @param condition       the condition and value rule, in words, transcribed from source
     * @param source          source citation
     * @param clientEvaluable whether a client can evaluate the condition from data it is sent
     * @param clientNote      what the client would need and whether it has it
     * @param ancient         {@code ancient = true} (only changes the shake animation, EmptyPokeBallEntity.kt:528-530)
     */
    public record Ball(String id, ModifierKind kind, Float fixedValue, String condition, String source,
                       boolean clientEvaluable, String clientNote, boolean ancient,
                       Function<BallContext, BallEffect> evaluator) {
        /** The ball's effect for the given context; unknown parts are null. */
        public BallEffect effect(BallContext ctx) { return evaluator.apply(ctx); }

        public boolean guaranteed() { return kind == ModifierKind.GUARANTEED; }
    }

    private static final Map<String, Ball> BALLS = new LinkedHashMap<>();

    private static final String PB = "PokeBalls.kt:";
    private static final String NO_CONDITION = "no condition; nothing to evaluate";

    private static void add(String id, ModifierKind kind, Float fixed, String condition, String source,
                            boolean clientEvaluable, String note, boolean ancient, Function<BallContext, BallEffect> f) {
        BALLS.put(id, new Ball(id, kind, fixed, condition, source, clientEvaluable, note, ancient, f));
    }

    private static void plain(String id, int line, boolean ancient) {
        add(id, ModifierKind.DEFAULT_X1, 1F, "always applies, x1", PB + line + " (default modifier, PokeBalls.kt:334)",
                true, NO_CONDITION, ancient, ctx -> BallEffect.plain());
    }

    private static void flat(String id, float v, int line, boolean ancient) {
        add(id, ModifierKind.MULTIPLIER, v, "always applies, x" + v, PB + line + " MultiplierModifier(" + v + "F)",
                true, NO_CONDITION, ancient, ctx -> BallEffect.multiplier(v, true));
    }

    private static void guaranteed(String id, int line, boolean ancient) {
        add(id, ModifierKind.GUARANTEED, 255F, "isGuaranteed() = true: capture always succeeds",
                PB + line + " GuaranteedModifier(); CobblemonCaptureCalculator.kt:57-59", true, NO_CONDITION, ancient,
                ctx -> BallEffect.guaranteedCapture());
    }

    /** Value of a modifier that is always valid, computed from a nullable fact. */
    private static BallEffect valid(Float value) { return new BallEffect(false, value, true); }

    static {
        plain("poke_ball", 250, false);
        plain("slate_ball", 251, false);
        plain("azure_ball", 252, false);
        plain("verdant_ball", 253, false);
        plain("roseate_ball", 254, false);
        plain("citrine_ball", 255, false);
        flat("great_ball", 1.5F, 256, false);
        flat("ultra_ball", 2F, 257, false);
        guaranteed("master_ball", 258, false);

        // CRM.kt:111 val SAFARI = MultiplierModifier(1.5f) { _, poke -> !poke.entity?.isBattling!! }
        add("safari_ball", ModifierKind.CONDITIONAL_MULTIPLIER, 1.5F, "x1.5 when the target is not battling",
                PB + "259; CRM.kt:111", true, "battle state is synced (PokemonEntity BATTLE_ID)", false,
                ctx -> new BallEffect(false, 1.5F, ctx.inBattle() == null ? null : !ctx.inBattle()));

        // BaseStatModifier(Stats.SPEED, { it >= 100 }, 4F); valid when form base Speed >= 100
        add("fast_ball", ModifierKind.BASE_STAT, 4F, "x4 when the form's base Speed >= 100",
                PB + "260; BaseStatModifier.kt:41-48", true, "base stats come from species data the client has", false,
                ctx -> new BallEffect(false, 4F, ctx.baseSpeed() == null ? null : ctx.baseSpeed() >= 100));

        // CRM.kt:33-41 LEVEL; BattleModifier.value returns 1F when the player is in no battle (BattleModifier.kt:33-40)
        add("level_ball", ModifierKind.BATTLE, null,
                "in battle: highest active player level > 4x target level -> x4, > 2x -> x3, > target level -> x2, else x1; out of battle x1",
                PB + "261; CRM.kt:33-41; BattleModifier.kt:33-40", true,
                "own active Pokémon levels and target level are known to the client in battle", false,
                ctx -> valid(levelBallValue(ctx)));

        // CRM.kt:130-135 LURE
        add("lure_ball", ModifierKind.WORLD_STATE, null, "x4 when the target entity has the \"fished\" aspect, else x1",
                PB + "262; CRM.kt:130-135", true, "entity aspects are synced (PokemonEntity ASPECTS); not verified in-game", false,
                ctx -> valid(ctx.fishedAspect() == null ? null : (ctx.fishedAspect() ? 4F : 1F)));

        // CRM.kt:141-149 WEIGHT_BASED
        add("heavy_ball", ModifierKind.DYNAMIC_MULTIPLIER, null,
                "applies when form weight >= 1000 hg; >= 3000 -> x4, in 2000..2999 -> x2.5, in 1000..1999 -> x1.5, else (gaps) x1",
                PB + "263; CRM.kt:141-149", true, "weight comes from species data the client has", false,
                BallCatalog::heavyBall);

        // CRM.kt:60-72 LOVE
        add("love_ball", ModifierKind.BATTLE, null,
                "in battle: target genderless -> x1; an active player Pokémon of opposite gender and same species -> x8; any opposite gender -> x2.5; else x1; out of battle x1",
                PB + "264; CRM.kt:60-72", false,
                "needs the wild target's gender, which is not a dedicated synced field (only visible if exposed as an aspect)", false,
                ctx -> valid(loveBallValue(ctx)));

        plain("friend_ball", 265, false);

        // CRM.kt:81-90 MOON_PHASES
        add("moon_ball", ModifierKind.WORLD_STATE, null,
                "dayTime % 24000 < 12000 -> x1; otherwise moon phase 0 -> x4, 1 or 7 -> x2.5, 2 or 6 -> x1.5, else x1",
                PB + "266; CRM.kt:81-90", true, "world time is sent to the client; moon phase derives from it", false,
                ctx -> valid(moonBallValue(ctx)));

        flat("sport_ball", 1.5F, 267, false);

        // CRM.kt:174 typeBoosting(3F, BUG, WATER) -> MultiplierModifier with condition
        add("net_ball", ModifierKind.CONDITIONAL_MULTIPLIER, 3F, "x3 when the target has type Bug or Water",
                PB + "268; CRM.kt:174", true, "types come from species/form data the client has", false,
                ctx -> new BallEffect(false, 3F, ctx.types() == null ? null : (ctx.types().contains("bug") || ctx.types().contains("water"))));

        // CRM.kt:47 SUBMERGED_IN_WATER
        add("dive_ball", ModifierKind.WORLD_STATE, null, "x3.5 when the target entity isUnderWater, else x1",
                PB + "269; CRM.kt:47", true, "client computes entity fluid state itself; may differ briefly from the server", false,
                ctx -> valid(ctx.underWater() == null ? null : (ctx.underWater() ? 3.5F : 1F)));

        // CRM.kt:53 NEST: value (41 - level) / 10F, condition level < 30
        add("nest_ball", ModifierKind.DYNAMIC_MULTIPLIER, null, "applies when level < 30: x(41 - level) / 10",
                PB + "270; CRM.kt:53", true, "level is synced (PokemonEntity LABEL_LEVEL)", false,
                ctx -> ctx.level() == null ? new BallEffect(false, null, null)
                        : new BallEffect(false, (41 - ctx.level()) / 10F, ctx.level() < 30));

        // CRM.kt:156-164 REPEAT
        add("repeat_ball", ModifierKind.WORLD_STATE, null, "x3.5 when the thrower's Pokédex knowledge of the species is OWNED, else x1",
                PB + "271; CRM.kt:156-164", true, "the player's own Pokédex is synced to the client; not verified in-game", false,
                ctx -> valid(ctx.speciesOwned() == null ? null : (ctx.speciesOwned() ? 3.5F : 1F)));

        // PokeBalls.kt:272 turnBased { turn -> (1F + turn * (1229F / 4096F)).coerceAtMost(4F) }; CRM.kt:193-196
        add("timer_ball", ModifierKind.BATTLE, null, "in battle: min(1 + turn * 1229/4096, 4); out of battle x1",
                PB + "272; CRM.kt:193-196", false,
                "needs battle.turn; the client is not sent the turn number as data (TurnInstruction.kt only broadcasts a chat line)", false,
                ctx -> valid(turnValue(ctx, turn -> Math.min(1F + turn * (1229F / 4096F), 4F))));

        plain("luxury_ball", 273, false);
        plain("premier_ball", 274, false);

        // CRM.kt:99-105 LIGHT_LEVEL
        add("dusk_ball", ModifierKind.WORLD_STATE, null, "max local raw brightness 0 -> x3.5, 1..7 -> x3, else x1",
                PB + "275; CRM.kt:99-105", true, "client has light data for loaded chunks; assumed equal to the server's", false,
                ctx -> valid(duskBallValue(ctx.maxLocalRawBrightness())));

        plain("heal_ball", 276, false);

        // PokeBalls.kt:277 turnBased { turn -> if (turn == 1) 5F else 1F }
        add("quick_ball", ModifierKind.BATTLE, null, "in battle: turn == 1 -> x5, else x1; out of battle x1",
                PB + "277; CRM.kt:193-196", false,
                "needs battle.turn (see timer_ball); out of battle it is always x1", false,
                ctx -> valid(turnValue(ctx, turn -> turn == 1 ? 5F : 1F)));

        plain("cherish_ball", 278, false);

        // CRM.kt:118-123 PARK
        add("park_ball", ModifierKind.WORLD_STATE, null, "x2.5 when the biome at the target is in tag cobblemon:is_temperate, else x1",
                PB + "279; CRM.kt:118-123", true, "biome and its tags are known to the client; not verified in-game", false,
                ctx -> valid(ctx.temperateBiome() == null ? null : (ctx.temperateBiome() ? 2.5F : 1F)));

        // CRM.kt:184 statusBoosting(4F, Statuses.SLEEP)
        add("dream_ball", ModifierKind.CONDITIONAL_MULTIPLIER, 4F, "x4 when the target's status is sleep",
                PB + "280; CRM.kt:184", false,
                "status of a wild Pokémon is only shown in battle; out of battle it is not synced", false,
                ctx -> new BallEffect(false, 4F, ctx.status() == null ? null : ctx.status() == PersistentStatus.SLEEP));

        // PokeBalls.kt:281 DynamicMultiplierModifier({ if (hasLabels(ULTRA_BEAST)) 5F else 0.1F }) { true }
        add("beast_ball", ModifierKind.DYNAMIC_MULTIPLIER, null, "always applies: Ultra Beast label -> x5, otherwise x0.1",
                PB + "281", true, "labels come from species data the client has", false,
                ctx -> valid(ctx.ultraBeast() == null ? null : (ctx.ultraBeast() ? 5F : 0.1F)));

        plain("ancient_poke_ball", 282, true);
        plain("ancient_citrine_ball", 283, true);
        plain("ancient_verdant_ball", 284, true);
        plain("ancient_azure_ball", 285, true);
        plain("ancient_roseate_ball", 286, true);
        plain("ancient_slate_ball", 287, true);
        plain("ancient_ivory_ball", 288, true);
        flat("ancient_great_ball", 1.5F, 289, true);
        flat("ancient_ultra_ball", 2F, 290, true);
        plain("ancient_heavy_ball", 291, true);
        flat("ancient_leaden_ball", 1.5F, 292, true);
        flat("ancient_gigaton_ball", 2F, 293, true);
        plain("ancient_feather_ball", 294, true);
        flat("ancient_wing_ball", 1.5F, 295, true);
        flat("ancient_jet_ball", 2F, 296, true);
        guaranteed("ancient_origin_ball", 297, true);
    }

    public static List<Ball> all() { return List.copyOf(BALLS.values()); }

    public static Map<String, Ball> byId() { return Collections.unmodifiableMap(BALLS); }

    /** Accepts "great_ball" or "cobblemon:great_ball". */
    public static Optional<Ball> get(String id) {
        if (id == null) return Optional.empty();
        String key = id.startsWith("cobblemon:") ? id.substring("cobblemon:".length()) : id;
        return Optional.ofNullable(BALLS.get(key));
    }

    // ---------------------------------------------------------------- value rules

    static Float levelBallValue(BallContext ctx) {
        if (ctx.inBattle() == null) return null;
        if (!ctx.inBattle()) return 1F;
        if (ctx.level() == null || ctx.playerActive() == null || ctx.playerActive().isEmpty()) return null; // maxOf on empty throws
        int highest = ctx.playerActive().stream().mapToInt(BallContext.ActiveMon::level).max().getAsInt();
        int level = ctx.level();
        if (highest > level * 4) return 4F;
        if (highest > level * 2) return 3F;
        if (highest > level) return 2F;
        return 1F;
    }

    static Float loveBallValue(BallContext ctx) {
        if (ctx.inBattle() == null) return null;
        if (!ctx.inBattle()) return 1F;
        if (ctx.targetGender() == null) return null;
        if (ctx.targetGender().equals("GENDERLESS")) return 1F;
        if (ctx.playerActive() == null || ctx.targetSpeciesId() == null) return null;
        List<BallContext.ActiveMon> opposite = ctx.playerActive().stream()
                .filter(m -> !"GENDERLESS".equals(m.gender()) && !ctx.targetGender().equals(m.gender())).toList();
        if (opposite.stream().anyMatch(m -> ctx.targetSpeciesId().equals(m.speciesId()))) return 8F;
        if (!opposite.isEmpty()) return 2.5F;
        return 1F;
    }

    static Float moonBallValue(BallContext ctx) {
        if (ctx.dayTime() == null) return null;
        if (ctx.dayTime() % 24000 < 12000) return 1F;
        if (ctx.moonPhase() == null) return null;
        return switch (ctx.moonPhase()) {
            case 2, 6 -> 1.5F;
            case 1, 7 -> 2.5F;
            case 0 -> 4F;
            default -> 1F;
        };
    }

    static Float duskBallValue(Integer brightness) {
        if (brightness == null) return null;
        if (brightness == 0) return 3.5F;
        if (brightness >= 1 && brightness <= 7) return 3F;
        return 1F;
    }

    static BallEffect heavyBall(BallContext ctx) {
        Float w = ctx.weightHectograms();
        if (w == null) return new BallEffect(false, null, null);
        float value;
        if (w >= 3000F) value = 4F;
        else if (w >= 2000F && w <= 2999F) value = 2.5F;
        else if (w >= 1000F && w <= 1999F) value = 1.5F;
        else value = 1F;
        return new BallEffect(false, value, w >= 1000F);
    }

    static Float turnValue(BallContext ctx, Function<Integer, Float> rule) {
        if (ctx.inBattle() == null) return null;
        if (!ctx.inBattle()) return 1F;
        if (ctx.battleTurn() == null) return null;
        return rule.apply(ctx.battleTurn());
    }

    /** Ball ids whose condition needs state a client is not sent. */
    public static Set<String> notClientEvaluable() {
        return BALLS.values().stream().filter(b -> !b.clientEvaluable()).map(Ball::id)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
