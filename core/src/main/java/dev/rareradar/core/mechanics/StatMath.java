package dev.rareradar.core.mechanics;

/**
 * Stat and experience formulas of Cobblemon 1.8.2 (tag 1.8.2, commit 643bf9068b51c829316429916373e5aa9864b8b9).
 *
 * <ul>
 *   <li>Stats: {@code pokemon/stat/CobblemonStatProvider.kt} {@code getStatForPokemon}, lines 88-104 (the default
 *       {@code Cobblemon.statProvider}, Cobblemon.kt:181). The IV used is {@code ivs.getEffectiveBattleIV(stat)}
 *       (hyper-trained value if present, IVs.kt:47-49) and the nature is {@code effectiveNature} (mint if present,
 *       Pokemon.kt:504-505).</li>
 *   <li>Natures: {@code api/pokemon/Natures.kt} lines 28-176 and {@code Nature.modifyStat} (Nature.kt:27-33), which uses
 *       {@code Mth.floor(value * 1.1)} / {@code Mth.floor(value * 0.9)} in double precision.</li>
 *   <li>Experience: {@code api/pokemon/experience/ExperienceGroups.kt} lines 95-137, with {@code Int.pow} from
 *       {@code util/math/SimpleMathExtensions.kt:22-24} ({@code toDouble().pow(power).toInt()}) and level lookup by
 *       {@code CachedLevelThresholds} ({@code api/LevelCurve.kt:34-58}).</li>
 * </ul>
 * All integer arithmetic is 32-bit and wraps exactly as Kotlin {@code Int} does.
 */
public final class StatMath {
    private StatMath() {}

    /** Permanent stats ({@code Stats.PERMANENT}, Stats.kt:44). */
    public enum Stat { HP, ATTACK, DEFENCE, SPECIAL_ATTACK, SPECIAL_DEFENCE, SPEED }

    /** The 25 natures of Natures.kt with the stat each raises and lowers (null = neutral). */
    public enum Nature {
        HARDY(null, null),                                  // Natures.kt:29-30
        LONELY(Stat.ATTACK, Stat.DEFENCE),                  // :35-36
        BRAVE(Stat.ATTACK, Stat.SPEED),                     // :41-42
        ADAMANT(Stat.ATTACK, Stat.SPECIAL_ATTACK),          // :47-48
        NAUGHTY(Stat.ATTACK, Stat.SPECIAL_DEFENCE),         // :53-54
        BOLD(Stat.DEFENCE, Stat.ATTACK),                    // :59-60
        DOCILE(null, null),                                 // :65-66
        RELAXED(Stat.DEFENCE, Stat.SPEED),                  // :71-72
        IMPISH(Stat.DEFENCE, Stat.SPECIAL_ATTACK),          // :77-78
        LAX(Stat.DEFENCE, Stat.SPECIAL_DEFENCE),            // :83-84
        TIMID(Stat.SPEED, Stat.ATTACK),                     // :89-90
        HASTY(Stat.SPEED, Stat.DEFENCE),                    // :95-96
        SERIOUS(null, null),                                // :101-102
        JOLLY(Stat.SPEED, Stat.SPECIAL_ATTACK),             // :107-108
        NAIVE(Stat.SPEED, Stat.SPECIAL_DEFENCE),            // :113-114
        MODEST(Stat.SPECIAL_ATTACK, Stat.ATTACK),           // :119-120
        MILD(Stat.SPECIAL_ATTACK, Stat.DEFENCE),            // :125-126
        QUIET(Stat.SPECIAL_ATTACK, Stat.SPEED),             // :131-132
        BASHFUL(null, null),                                // :137-138
        RASH(Stat.SPECIAL_ATTACK, Stat.SPECIAL_DEFENCE),    // :143-144
        CALM(Stat.SPECIAL_DEFENCE, Stat.ATTACK),            // :149-150
        GENTLE(Stat.SPECIAL_DEFENCE, Stat.DEFENCE),         // :155-156
        SASSY(Stat.SPECIAL_DEFENCE, Stat.SPEED),            // :161-162
        CAREFUL(Stat.SPECIAL_DEFENCE, Stat.SPECIAL_ATTACK), // :167-168
        QUIRKY(null, null);                                 // :173-174

        public final Stat increased;
        public final Stat decreased;

        Nature(Stat increased, Stat decreased) {
            this.increased = increased;
            this.decreased = decreased;
        }

        /** Nature.kt:27-33: {@code when (stat) { increasedStat -> floor(v*1.1); decreasedStat -> floor(v*0.9); else -> v }}. */
        public int modifyStat(Stat stat, int value) {
            if (stat == increased) return mthFloor(value * 1.1);
            if (stat == decreased) return mthFloor(value * 0.9);
            return value;
        }

        /** Cobblemon id, e.g. "cobblemon:adamant". */
        public String id() { return "cobblemon:" + name().toLowerCase(java.util.Locale.ROOT); }

        public static Nature fromId(String id) {
            if (id == null) return null;
            String key = id.startsWith("cobblemon:") ? id.substring("cobblemon:".length()) : id;
            for (Nature n : values()) if (n.name().equalsIgnoreCase(key)) return n;
            return null;
        }
    }

    /** {@code net.minecraft.util.Mth.floor(double)}. */
    static int mthFloor(double d) {
        int i = (int) d;
        return d < i ? i - 1 : i;
    }

    /** {@code kotlin.math.truncate(Double)}. */
    private static double truncate(double d) {
        return d < 0 ? Math.ceil(d) : Math.floor(d);
    }

    /**
     * HP (CobblemonStatProvider.kt:93-100):
     * {@code truncate(truncate(2.0 * base + iv + truncate(ev / 4.0) + 100) * level / 100.0 + 10).toInt()};
     * Shedinja ({@code cobblemon:shedinja}) always has 1.
     */
    public static int hp(int base, int iv, int ev, int level, boolean shedinja) {
        if (shedinja) return 1;
        return (int) truncate(truncate(2.0 * base + iv + truncate(ev / 4.0) + 100) * level / 100.0 + 10);
    }

    public static int hp(int base, int iv, int ev, int level) { return hp(base, iv, ev, level, false); }

    /**
     * Non-HP stat (CobblemonStatProvider.kt:102):
     * {@code effectiveNature.modifyStat(stat, ((2 * base + iv + (ev / 4)) * level) / 100 + 5)} in Int arithmetic.
     */
    public static int stat(Stat stat, int base, int iv, int ev, int level, Nature nature) {
        if (stat == Stat.HP) throw new IllegalArgumentException("use hp(...) for HP");
        int raw = ((2 * base + iv + (ev / 4)) * level) / 100 + 5;
        return nature == null ? raw : nature.modifyStat(stat, raw);
    }

    /** Any stat, dispatching to {@link #hp} or {@link #stat}. */
    public static int compute(Stat stat, int base, int iv, int ev, int level, Nature nature, boolean shedinja) {
        return stat == Stat.HP ? hp(base, iv, ev, level, shedinja) : stat(stat, base, iv, ev, level, nature);
    }

    /**
     * Range {min, max} of max HP over IV in [ivMin, ivMax] and EV in [evMin, evMax]. The HP formula is
     * non-decreasing in IV and EV, so the extremes are at the corners.
     */
    public static int[] hpRange(int base, int level, int ivMin, int ivMax, int evMin, int evMax) {
        return new int[]{hp(base, ivMin, evMin, level), hp(base, ivMax, evMax, level)};
    }

    /** {@code Int.pow} of SimpleMathExtensions.kt:22-24. */
    static int ipow(int base, int power) {
        return (int) Math.pow(base, power);
    }

    /** The six experience groups registered by {@code ExperienceGroups.registerDefaults()} (lines 41-48). */
    public enum ExperienceGroup {
        ERRATIC("erratic"), FAST("fast"), MEDIUM_FAST("medium_fast"), MEDIUM_SLOW("medium_slow"), SLOW("slow"),
        FLUCTUATING("fluctuating");

        /** Name used in species data ({@code experienceGroup}). */
        public final String id;

        ExperienceGroup(String id) { this.id = id; }

        public static ExperienceGroup fromId(String id) {
            for (ExperienceGroup g : values()) if (g.id.equalsIgnoreCase(id)) return g;
            return null;
        }

        /** Total experience at which {@code level} is reached (ExperienceGroups.kt:95-137). */
        public int experienceForLevel(int level) {
            switch (this) {
                case ERRATIC: // lines 97-104
                    if (level == 1) return 0;
                    if (level < 50) return ipow(level, 3) * (100 - level) / 50;
                    if (level < 68) return ipow(level, 3) * (150 - level) / 100;
                    if (level < 98) return ipow(level, 3) * (1911 - 10 * level) / 3 / 500;
                    return ipow(level, 3) * (160 - level) / 100;
                case FAST: // line 110
                    return level == 1 ? 0 : 4 * ipow(level, 3) / 5;
                case MEDIUM_FAST: // line 115
                    return level == 1 ? 0 : ipow(level, 3);
                case MEDIUM_SLOW: // line 120
                    return Math.max(0, ipow(level, 3) * 6 / 5 - 15 * ipow(level, 2) + 100 * level - 140);
                case SLOW: // line 125
                    return level == 1 ? 0 : 5 * ipow(level, 3) / 4;
                case FLUCTUATING: // lines 130-136
                    if (level == 1) return 0;
                    if (level < 15) return ipow(level, 3) * ((level + 1) / 3 + 24) / 50;
                    if (level < 36) return ipow(level, 3) * (level + 14) / 50;
                    return ipow(level, 3) * (level / 2 + 32) / 50;
                default:
                    throw new AssertionError(this);
            }
        }

        /**
         * {@code CachedLevelThresholds.getLevel} (LevelCurve.kt:39-57, levelLimit 1000): the first level whose
         * threshold exceeds {@code experience}, minus one; 1 if none below the limit does. Returns 0 for negative
         * experience. Cobblemon additionally caps the result at {@code maxPokemonLevel} where it sets levels
         * (Pokemon.kt:2165).
         */
        public int levelForExperience(int experience) {
            for (int level = 1; level < 1000; level++) {
                if (experience < experienceForLevel(level)) return level - 1;
            }
            return 1;
        }
    }
}
