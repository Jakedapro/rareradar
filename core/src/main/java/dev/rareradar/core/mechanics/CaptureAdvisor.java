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
}
