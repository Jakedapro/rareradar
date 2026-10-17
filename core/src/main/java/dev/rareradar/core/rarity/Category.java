package dev.rareradar.core.rarity;

/** Classification tags. Order of the special tiers mirrors the original RareRadar precedence. */
public enum Category {
    MYTHICAL("Mythical", 0xFF4FD8),
    LEGENDARY("Legendary", 0xB44CFF),
    ULTRA_BEAST("Ultra Beast", 0x33E0FF),
    PARADOX("Paradox", 0xFF6A2B),
    SHINY("Shiny", 0xFFD700),
    WATCHLIST("Watchlist", 0x7CFC00),
    COMPETITIVE("Competitive", 0x4FC3F7),
    DEX_MISSING("Dex missing", 0xFFFFFF);

    public final String label;
    public final int color;

    Category(String label, int color) { this.label = label; this.color = color; }

    public boolean special() { return this == MYTHICAL || this == LEGENDARY || this == ULTRA_BEAST || this == PARADOX; }
}
