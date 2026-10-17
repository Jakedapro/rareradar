package dev.rareradar.core.profit;

/** Economic activities the profit engine compares. */
public enum Activity {
    RARE_HUNTING("Rare hunting"),
    HUNTS("Completing /hunt objectives"),
    BREEDING("Breeding"),
    SELLING("Selling (GTS/STS)"),
    TRADING("Trading / flipping"),
    POKEDEX("Pokédex completion"),
    DAILY_REWARDS("Daily rewards / calendar / vote"),
    MEGA_STONES("Boss battles / Mega Stones"),
    RAIDS("Raids"),
    BATTLE_FACTORY("Battle Factory"),
    OTHER("Other");

    public final String label;

    Activity(String label) { this.label = label; }
}
