package dev.rareradar.core.stats;

import java.util.TreeSet;
import java.util.UUID;

/** One play session's totals. Money fields are realised Pokédollars the user logged; estimates are not mixed in. */
public final class SessionRecord {
    public String id = UUID.randomUUID().toString();
    public long start, end;
    public String world = "";
    public int encountered, caught, rareEncounters, shinyEncounters, huntCompletions;
    public double rewardsEarned, tradingProfit, resourceSpend;
    public int ballsThrown;
    /** Balance change read from the server's sidebar over this session (observed, not logged); NaN if never read. */
    public double balanceNet = Double.NaN;
    public TreeSet<String> biomes = new TreeSet<>();

    public double minutes() { return Math.max(0, end - start) / 60_000.0; }
    public double realisedProfit() { return rewardsEarned + tradingProfit - resourceSpend; }
}
