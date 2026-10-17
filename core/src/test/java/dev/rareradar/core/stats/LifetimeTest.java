package dev.rareradar.core.stats;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LifetimeTest {
    static final long H = 3_600_000L, D = 24 * H, NOW = 1_800_000_000_000L;

    static SessionRecord s(long start, double hours, double profit, int balls, int caught) {
        SessionRecord r = new SessionRecord();
        r.start = start; r.end = start + (long) (hours * H);
        r.rewardsEarned = profit; r.ballsThrown = balls; r.caught = caught; r.encountered = 10;
        return r;
    }

    @Test void totalsWindowsAndBest() {
        var l = Reports.lifetime(List.of(s(NOW - D, 2, 2_000, 10, 5), s(NOW - 9 * D, 1, 500, 6, 1), s(NOW - 30 * D, 1, 9_000, 0, 0)), NOW);
        assertEquals(3, l.all().sessions());
        assertEquals(4, l.all().hours(), 1e-9);
        assertEquals(1_000, l.last7().profitPerHour(), 1e-9);
        assertEquals(500, l.prev7().profitPerHour(), 1e-9);
        assertEquals(1.0, l.profitPerHourChange(), 1e-9);
        assertEquals(16 / 6.0, l.ballsPerCatch(), 1e-9);
        assertEquals(9_000, l.best().realisedProfit(), 1e-9);
    }

    @Test void emptyHistoryIsUnknownNotZero() {
        var l = Reports.lifetime(List.of(), NOW);
        assertEquals(0, l.all().sessions());
        assertTrue(Double.isNaN(l.all().profitPerHour()));
        assertTrue(Double.isNaN(l.profitPerHourChange()));
        assertNull(l.best());
    }
}
