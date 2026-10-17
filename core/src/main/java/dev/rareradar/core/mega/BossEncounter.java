package dev.rareradar.core.mega;

/** One Boss Pokémon battle the user logged. */
public final class BossEncounter {
    public enum Outcome { WON, LOST, FLED, UNKNOWN }

    public long ts;
    public String species;
    public Integer level;
    public String biome;
    public int x, y, z;
    public Outcome outcome = Outcome.UNKNOWN;
    public boolean megaStoneDropped;
    public String megaStone;          // which stone, if any
    public String otherRewards = "";
}
