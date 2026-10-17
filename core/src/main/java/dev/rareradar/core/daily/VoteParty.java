package dev.rareradar.core.daily;

import dev.rareradar.core.util.Estimate;
import dev.rareradar.core.util.Stats;

/**
 * A manually started Vote Party window. The wiki reports a 3.0× shiny spawn rate for 30 minutes; both are editable
 * defaults. Efficiency compares shinies seen during the window against the user's normal shiny rate.
 */
public final class VoteParty {
    public long start;
    public double durationMinutes = 30;     // reported default
    public double reportedMultiplier = 3.0; // reported default
    public int encounters;
    public int shinies;

    public boolean active(long now) { return start > 0 && now < start + (long) (durationMinutes * 60_000); }

    public double minutesLeft(long now) { return active(now) ? (start + durationMinutes * 60_000 - now) / 60_000.0 : 0; }

    /** Observed shinies per hour during the event with a 90% interval. */
    public Estimate shinyRate(double observedMinutes) {
        if (observedMinutes <= 0) return Estimate.unknown("event not observed");
        double h = observedMinutes / 60;
        double[] ci = Stats.poissonRate(shinies, h, 0.9);
        return Estimate.of(shinies / h, ci[0], ci[1], shinies, "Vote Party window");
    }

    /** Observed event shiny rate ÷ the user's baseline shiny rate (NaN when baseline unknown or zero). */
    public double observedMultiplier(double observedMinutes, double baselineShinyPerHour) {
        if (!(baselineShinyPerHour > 0) || observedMinutes <= 0) return Double.NaN;
        return (shinies / (observedMinutes / 60)) / baselineShinyPerHour;
    }
}
