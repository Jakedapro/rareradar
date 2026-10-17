package dev.rareradar.core.mechanics;

import dev.rareradar.core.mechanics.StatMath.ExperienceGroup;
import dev.rareradar.core.mechanics.StatMath.Nature;
import dev.rareradar.core.mechanics.StatMath.Stat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Hand-computed from CobblemonStatProvider.kt:88-104, Nature.kt:27-33 and ExperienceGroups.kt:95-137 (Cobblemon 1.8.2). */
class StatMathTest {

    @Test void hpWorkedExample() {
        // base 108, IV 24, EV 74, level 78:
        // trunc(2*108 + 24 + trunc(74/4.0 = 18.5) = 18 + 100) = 358; 358 * 78 / 100.0 = 279.24; + 10 = 289.24 -> 289
        assertEquals(289, StatMath.hp(108, 24, 74, 78));
        // base 50, level 20, IV 0, EV 0: 200 * 20 / 100 + 10 = 50; IV 31: 231 * 20 / 100 = 46.2 + 10 -> 56
        assertEquals(50, StatMath.hp(50, 0, 0, 20));
        assertEquals(56, StatMath.hp(50, 31, 0, 20));
        assertArrayEquals(new int[]{50, 56}, StatMath.hpRange(50, 20, 0, 31, 0, 0));
        // level 100, base 255 (Blissey), IV 31, EV 252: 510 + 31 + 63 + 100 = 704; 704 * 100 / 100 + 10 = 714
        assertEquals(714, StatMath.hp(255, 31, 252, 100));
        assertEquals(1, StatMath.hp(1, 31, 252, 100, true)); // Shedinja
    }

    @Test void otherStatWorkedExample() {
        // base 130, IV 12, EV 195, level 78, Adamant (+Atk):
        // 195/4 = 48 (Int); (260 + 12 + 48) * 78 = 24960; / 100 = 249; + 5 = 254; floor(254 * 1.1 = 279.4) = 279
        assertEquals(279, StatMath.stat(Stat.ATTACK, 130, 12, 195, 78, Nature.ADAMANT));
        // same numbers, neutral nature: 254
        assertEquals(254, StatMath.stat(Stat.ATTACK, 130, 12, 195, 78, Nature.HARDY));
        // Adamant lowers Sp. Atk: base 80, IV 16, EV 48, level 78: (160+16+12)*78 = 14664; /100 = 146; +5 = 151; floor(151*0.9 = 135.9) = 135
        assertEquals(135, StatMath.stat(Stat.SPECIAL_ATTACK, 80, 16, 48, 78, Nature.ADAMANT));
        assertThrows(IllegalArgumentException.class, () -> StatMath.stat(Stat.HP, 1, 1, 1, 1, Nature.HARDY));
    }

    @Test void natureRoundingUsesDoubleFloor() {
        // 10 * 1.1 = 11.000000000000002 -> 11; 11 * 0.9 = 9.9 -> 9; 100 * 0.9 = 90.0 -> 90
        assertEquals(11, Nature.LONELY.modifyStat(Stat.ATTACK, 10));
        assertEquals(9, Nature.LONELY.modifyStat(Stat.DEFENCE, 11));
        assertEquals(90, Nature.LONELY.modifyStat(Stat.DEFENCE, 100));
        assertEquals(100, Nature.LONELY.modifyStat(Stat.SPEED, 100));
    }

    @Test void natureTable() {
        assertEquals(25, Nature.values().length);
        int neutral = 0;
        for (Nature n : Nature.values()) {
            if (n.increased == null) { assertNull(n.decreased); neutral++; }
            else assertNotEquals(n.increased, n.decreased);
        }
        assertEquals(5, neutral); // hardy, docile, serious, bashful, quirky
        assertEquals(Stat.SPEED, Nature.TIMID.increased);
        assertEquals(Stat.ATTACK, Nature.TIMID.decreased);
        assertEquals(Stat.SPECIAL_DEFENCE, Nature.CAREFUL.increased);
        assertEquals(Stat.SPECIAL_ATTACK, Nature.CAREFUL.decreased);
        assertEquals(Nature.MODEST, Nature.fromId("cobblemon:modest"));
    }

    @Test void experienceAtLevel100() {
        assertEquals(600_000, ExperienceGroup.ERRATIC.experienceForLevel(100));      // 100^3 * (160-100) / 100
        assertEquals(800_000, ExperienceGroup.FAST.experienceForLevel(100));         // 4 * 100^3 / 5
        assertEquals(1_000_000, ExperienceGroup.MEDIUM_FAST.experienceForLevel(100));
        assertEquals(1_059_860, ExperienceGroup.MEDIUM_SLOW.experienceForLevel(100)); // 1.2e6 - 150000 + 10000 - 140
        assertEquals(1_250_000, ExperienceGroup.SLOW.experienceForLevel(100));       // 5 * 100^3 / 4
        assertEquals(1_640_000, ExperienceGroup.FLUCTUATING.experienceForLevel(100)); // 100^3 * (50 + 32) / 50
    }

    @Test void experienceBranchesAndIntegerDivision() {
        for (ExperienceGroup g : ExperienceGroup.values()) assertEquals(0, g.experienceForLevel(1), g.name());
        // Medium Slow L2: 8*6/5 = 9 (Int), - 15*4 + 200 - 140 -> 9
        assertEquals(9, ExperienceGroup.MEDIUM_SLOW.experienceForLevel(2));
        // Erratic L50 (second branch): 125000 * (150 - 50) / 100 = 125000
        assertEquals(125_000, ExperienceGroup.ERRATIC.experienceForLevel(50));
        // Erratic L68 (third branch): 314432 * (1911 - 680) = 387065792; / 3 = 129021930; / 500 = 258043
        assertEquals(258_043, ExperienceGroup.ERRATIC.experienceForLevel(68));
        // Fluctuating L14: 2744 * ((15)/3 + 24 = 29) / 50 = 79576 / 50 = 1591; L15: 3375 * 29 / 50 = 1957
        assertEquals(1591, ExperienceGroup.FLUCTUATING.experienceForLevel(14));
        assertEquals(1957, ExperienceGroup.FLUCTUATING.experienceForLevel(15));
        // Fast L2: 4 * 8 / 5 = 6
        assertEquals(6, ExperienceGroup.FAST.experienceForLevel(2));
    }

    @Test void levelForExperience() {
        ExperienceGroup g = ExperienceGroup.MEDIUM_FAST;
        assertEquals(0, g.levelForExperience(-1));
        assertEquals(1, g.levelForExperience(0));
        assertEquals(1, g.levelForExperience(7));
        assertEquals(2, g.levelForExperience(8));
        assertEquals(99, g.levelForExperience(999_999));
        assertEquals(100, g.levelForExperience(1_000_000));
        for (ExperienceGroup group : ExperienceGroup.values())
            for (int level = 2; level <= 100; level++) {
                int xp = group.experienceForLevel(level);
                assertEquals(level, group.levelForExperience(xp), group + " " + level);
                assertEquals(level - 1, group.levelForExperience(xp - 1), group + " " + level);
            }
    }
}
