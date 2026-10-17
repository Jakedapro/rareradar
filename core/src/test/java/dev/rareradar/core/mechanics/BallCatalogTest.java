package dev.rareradar.core.mechanics;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Values transcribed from PokeBalls.kt:250-297 and CatchRateModifiers.kt (Cobblemon 1.8.2). */
class BallCatalogTest {

    private static BallEffect effect(String id, BallContext ctx) {
        return BallCatalog.get(id).orElseThrow().effect(ctx);
    }

    @Test void containsEveryRegisteredBallOnce() {
        // PokeBalls.kt init block has 48 createDefault calls (lines 250-297).
        List<BallCatalog.Ball> all = BallCatalog.all();
        assertEquals(48, all.size());
        Set<String> ids = new HashSet<>();
        for (BallCatalog.Ball b : all) assertTrue(ids.add(b.id()), b.id());
        assertEquals(16, all.stream().filter(BallCatalog.Ball::ancient).count());
        assertEquals(Set.of("master_ball", "ancient_origin_ball"),
                new HashSet<>(all.stream().filter(BallCatalog.Ball::guaranteed).map(BallCatalog.Ball::id).toList()));
    }

    @Test void flatMultipliers() {
        BallContext none = BallContext.builder().build();
        assertEquals(new BallEffect(false, 1F, true), effect("poke_ball", none));
        assertEquals(new BallEffect(false, 1.5F, true), effect("great_ball", none));
        assertEquals(new BallEffect(false, 2F, true), effect("ultra_ball", none));
        assertEquals(new BallEffect(false, 1.5F, true), effect("sport_ball", none));
        assertEquals(new BallEffect(false, 2F, true), effect("ancient_jet_ball", none));
        assertEquals(new BallEffect(false, 1F, true), effect("ancient_heavy_ball", none)); // throwPower only
        assertEquals(new BallEffect(false, 1F, true), effect("friend_ball", none)); // capture effect only
    }

    @Test void conditionalBalls() {
        assertEquals(Boolean.TRUE, effect("net_ball", BallContext.builder().types(Set.of("water", "flying")).build()).applies());
        assertEquals(Boolean.FALSE, effect("net_ball", BallContext.builder().types(Set.of("fire")).build()).applies());
        assertEquals(Boolean.TRUE, effect("fast_ball", BallContext.builder().baseSpeed(100).build()).applies());
        assertEquals(Boolean.FALSE, effect("fast_ball", BallContext.builder().baseSpeed(99).build()).applies());
        assertEquals(Boolean.TRUE, effect("safari_ball", BallContext.builder().inBattle(false).build()).applies());
        assertEquals(Boolean.FALSE, effect("safari_ball", BallContext.builder().inBattle(true).build()).applies());
        assertEquals(Boolean.TRUE, effect("dream_ball", BallContext.builder().status(PersistentStatus.SLEEP).build()).applies());
        assertEquals(Boolean.FALSE, effect("dream_ball", BallContext.builder().status(PersistentStatus.NONE).build()).applies());
    }

    @Test void nestBall() {
        // (41 - level) / 10F, applies when level < 30
        BallEffect l10 = effect("nest_ball", BallContext.builder().level(10).build());
        assertEquals(3.1F, l10.value());
        assertTrue(l10.applies());
        assertEquals(1.2F, effect("nest_ball", BallContext.builder().level(29).build()).value());
        assertFalse(effect("nest_ball", BallContext.builder().level(30).build()).applies());
    }

    @Test void heavyBallBucketsIncludingGaps() {
        assertEquals(new BallEffect(false, 1F, false), effect("heavy_ball", BallContext.builder().weightHectograms(999F).build()));
        assertEquals(new BallEffect(false, 1.5F, true), effect("heavy_ball", BallContext.builder().weightHectograms(1000F).build()));
        // 1999 < w < 2000 falls in neither closed range -> 1F while still valid
        assertEquals(new BallEffect(false, 1F, true), effect("heavy_ball", BallContext.builder().weightHectograms(1999.5F).build()));
        assertEquals(new BallEffect(false, 2.5F, true), effect("heavy_ball", BallContext.builder().weightHectograms(2999F).build()));
        assertEquals(new BallEffect(false, 1F, true), effect("heavy_ball", BallContext.builder().weightHectograms(2999.5F).build()));
        assertEquals(new BallEffect(false, 4F, true), effect("heavy_ball", BallContext.builder().weightHectograms(3000F).build()));
    }

    @Test void turnBasedBalls() {
        // Timer: min(1 + turn * 1229/4096, 4); 1229/4096 = 0.300048828125
        assertEquals(1F + 5 * (1229F / 4096F), effect("timer_ball", BallContext.builder().inBattle(true).battleTurn(5).build()).value());
        assertEquals(2.500244140625F, effect("timer_ball", BallContext.builder().inBattle(true).battleTurn(5).build()).value());
        assertEquals(4F, effect("timer_ball", BallContext.builder().inBattle(true).battleTurn(10).build()).value()); // 4.0005 capped
        assertEquals(1F, effect("timer_ball", BallContext.builder().inBattle(false).build()).value());
        assertEquals(5F, effect("quick_ball", BallContext.builder().inBattle(true).battleTurn(1).build()).value());
        assertEquals(1F, effect("quick_ball", BallContext.builder().inBattle(true).battleTurn(2).build()).value());
        // BattleModifier: out of battle the value is 1F, so a Quick Ball does nothing on an out-of-battle throw
        assertEquals(1F, effect("quick_ball", BallContext.builder().inBattle(false).build()).value());
        assertNull(effect("quick_ball", BallContext.builder().inBattle(true).build()).value());
    }

    @Test void worldStateBalls() {
        assertEquals(1F, effect("moon_ball", BallContext.builder().dayTime(6000L).build()).value());
        assertNull(effect("moon_ball", BallContext.builder().dayTime(18000L).build()).value()); // night, phase unknown
        assertEquals(4F, effect("moon_ball", BallContext.builder().dayTime(18000L).moonPhase(0).build()).value());
        assertEquals(2.5F, effect("moon_ball", BallContext.builder().dayTime(42000L).moonPhase(7).build()).value());
        assertEquals(1.5F, effect("moon_ball", BallContext.builder().dayTime(18000L).moonPhase(2).build()).value());
        assertEquals(1F, effect("moon_ball", BallContext.builder().dayTime(18000L).moonPhase(4).build()).value());
        assertEquals(3.5F, effect("dusk_ball", BallContext.builder().maxLocalRawBrightness(0).build()).value());
        assertEquals(3F, effect("dusk_ball", BallContext.builder().maxLocalRawBrightness(7).build()).value());
        assertEquals(1F, effect("dusk_ball", BallContext.builder().maxLocalRawBrightness(8).build()).value());
        assertEquals(3.5F, effect("dive_ball", BallContext.builder().underWater(true).build()).value());
        assertEquals(4F, effect("lure_ball", BallContext.builder().fishedAspect(true).build()).value());
        assertEquals(2.5F, effect("park_ball", BallContext.builder().temperateBiome(true).build()).value());
        assertEquals(3.5F, effect("repeat_ball", BallContext.builder().speciesOwned(true).build()).value());
        assertEquals(1F, effect("repeat_ball", BallContext.builder().speciesOwned(false).build()).value());
        assertEquals(5F, effect("beast_ball", BallContext.builder().ultraBeast(true).build()).value());
        assertEquals(0.1F, effect("beast_ball", BallContext.builder().ultraBeast(false).build()).value());
    }

    @Test void battleBalls() {
        List<BallContext.ActiveMon> mine = List.of(new BallContext.ActiveMon(50, "MALE", "cobblemon:eevee"));
        BallContext.Builder b = BallContext.builder().inBattle(true).playerActive(mine);
        assertEquals(4F, effect("level_ball", b.level(12).build()).value()); // 50 > 48
        assertEquals(3F, effect("level_ball", b.level(13).build()).value()); // 50 > 26
        assertEquals(2F, effect("level_ball", b.level(25).build()).value()); // 50 > 25 but not > 50
        assertEquals(1F, effect("level_ball", b.level(50).build()).value());
        assertEquals(8F, effect("love_ball", b.targetGender("FEMALE").targetSpeciesId("cobblemon:eevee").build()).value());
        assertEquals(2.5F, effect("love_ball", b.targetGender("FEMALE").targetSpeciesId("cobblemon:pikachu").build()).value());
        assertEquals(1F, effect("love_ball", b.targetGender("MALE").build()).value());
        assertEquals(1F, effect("love_ball", b.targetGender("GENDERLESS").build()).value());
        assertNull(effect("love_ball", BallContext.builder().inBattle(true).build()).value());
    }

    @Test void unknownContextGivesUnknownEffect() {
        BallContext none = BallContext.builder().build();
        assertNull(effect("net_ball", none).applies());
        assertNull(effect("dusk_ball", none).value());
        assertNull(effect("nest_ball", none).effectiveBonus());
        CaptureResult r = CaptureMath.evaluate(CaptureInputs.builder().catchRate(45).hp(10, 10).level(10)
                .status(PersistentStatus.NONE).inBattle(false).ball(effect("dusk_ball", none)).caughtCount(0).build());
        assertFalse(r.known());
        assertEquals(List.of("ball.value"), r.missing());
    }

    @Test void clientEvaluabilityFlags() {
        assertEquals(Set.of("love_ball", "timer_ball", "quick_ball", "dream_ball"), BallCatalog.notClientEvaluable());
        for (BallCatalog.Ball b : BallCatalog.all()) {
            assertTrue(b.source().startsWith("PokeBalls.kt:"), b.id());
            assertFalse(b.condition().isBlank());
        }
    }
}
