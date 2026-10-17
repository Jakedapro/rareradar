package dev.rareradar.core.mechanics;

import java.util.ArrayList;
import java.util.List;

/**
 * Capture recommendations for a species from its catch rate, base HP and a level, under explicitly named scenarios.
 * A wild Pokémon's current HP, status and the player's Pokédex count are not observable client-side, so every row
 * states its assumptions instead of claiming a single "real" chance. Pokédex criticals are ignored (caught ≤ 30),
 * which only makes the numbers conservative.
 */
public final class CaptureAdvisor {
    private CaptureAdvisor() {}

    public enum Scenario {
        THROWN_FULL_HP("thrown in the overworld, full HP, no status", false, 1.0, PersistentStatus.NONE),
        BATTLE_FULL_HP("in battle, full HP, no status", true, 1.0, PersistentStatus.NONE),
        BATTLE_LOW_HP_ASLEEP("in battle, 1 HP, asleep", true, 0.0, PersistentStatus.SLEEP);

        public final String label; final boolean inBattle; final double hpFraction; final PersistentStatus status;
        Scenario(String label, boolean inBattle, double hpFraction, PersistentStatus status) {
            this.label = label; this.inBattle = inBattle; this.hpFraction = hpFraction; this.status = status;
        }
    }

    public record Row(Scenario scenario, String ball, double probability, double expectedBalls) {}

    /** Poké, Great and Ultra Balls (unconditional multipliers 1, 1.5, 2 in PokeBalls.kt). */
    public static final List<String> STANDARD_BALLS = List.of("poke_ball", "great_ball", "ultra_ball");

    public static List<Row> advise(int catchRate, int baseHp, int level) {
        if (catchRate <= 0 || baseHp <= 0 || level < 1) return List.of();
        // IV 0 / EV 0 → smallest max HP: at 1 HP that gives the lower bound of the (3max−2cur)/3max term.
        int maxHp = StatMath.hp(baseHp, 0, 0, level);
        List<Row> out = new ArrayList<>();
        for (Scenario sc : Scenario.values()) {
            int cur = Math.max(1, (int) Math.round(sc.hpFraction * maxHp));
            for (String id : STANDARD_BALLS) {
                var ball = BallCatalog.get(id).orElseThrow();
                var ctx = BallContext.builder().inBattle(sc.inBattle).build();
                CaptureResult r = CaptureMath.evaluate(CaptureInputs.builder().catchRate(catchRate).hp(cur, maxHp).level(level)
                        .status(sc.status).inBattle(sc.inBattle).ball(ball.effect(ctx)).caughtCount(0).build());
                if (r.known()) out.add(new Row(sc, id, r.captureProbability(), r.expectedBalls()));
            }
        }
        return out;
    }

    /** The cheapest standard ball whose expected balls per catch is at most {@code maxBalls}, or null. */
    public static Row cheapestWithin(List<Row> rows, Scenario sc, double maxBalls) {
        for (String id : STANDARD_BALLS)
            for (Row r : rows) if (r.scenario() == sc && r.ball().equals(id) && r.expectedBalls() <= maxBalls) return r;
        return null;
    }

    /** One owned ball's chance for an overworld throw at a full-HP, status-free target; NaN with {@code missing} when unknown. */
    public record OwnedBall(String ball, double probability, double expectedBalls, boolean guaranteed, List<String> missing) {}

    /**
     * Ranks the balls the player holds for an overworld throw at a wild target at full HP with no status (how wild
     * Pokémon spawn). Pokédex criticals are ignored. Balls whose condition depends on something the context leaves
     * null are returned last with what is missing, never with a guessed bonus.
     */
    public static List<OwnedBall> rankOwned(int catchRate, int baseHp, int level, BallContext ctx, java.util.Collection<String> owned) {
        List<OwnedBall> out = new ArrayList<>();
        if (catchRate <= 0 || baseHp <= 0 || level < 1) return out;
        int maxHp = StatMath.hp(baseHp, 0, 0, level);
        for (String id : new java.util.LinkedHashSet<>(owned)) {
            var ball = BallCatalog.get(id);
            if (ball.isEmpty()) { out.add(new OwnedBall(id, Double.NaN, Double.NaN, false, List.of("unknown ball"))); continue; }
            CaptureResult r = CaptureMath.evaluate(CaptureInputs.builder().catchRate(catchRate).hp(maxHp, maxHp).level(level)
                    .status(PersistentStatus.NONE).inBattle(false).ball(ball.get().effect(ctx)).caughtCount(0).build());
            out.add(r.known() ? new OwnedBall(id, r.captureProbability(), r.expectedBalls(), r.guaranteed(), List.of())
                    : new OwnedBall(id, Double.NaN, Double.NaN, false, r.missing()));
        }
        out.sort(java.util.Comparator.comparing((OwnedBall b) -> Double.isNaN(b.probability()))
                .thenComparing(java.util.Comparator.comparingDouble(OwnedBall::probability).reversed()));
        return out;
    }
}
