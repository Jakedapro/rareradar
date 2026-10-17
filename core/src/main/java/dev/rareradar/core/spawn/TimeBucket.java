package dev.rareradar.core.spawn;

/** RareRadar's own coarse time-of-day buckets over Minecraft day ticks (0..23999). */
public enum TimeBucket {
    DAWN, DAY, DUSK, NIGHT, UNKNOWN;

    public static TimeBucket of(Long ticks) {
        if (ticks == null) return UNKNOWN;
        long t = Math.floorMod(ticks, 24000L);
        if (t >= 1000 && t < 12000) return DAY;
        if (t >= 12000 && t < 13800) return DUSK;
        if (t >= 13800 && t < 22200) return NIGHT;
        return DAWN;
    }
}
