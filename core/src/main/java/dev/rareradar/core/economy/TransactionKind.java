package dev.rareradar.core.economy;

/** What a server money message means for the player. Only the player can assign this to a message format. */
public enum TransactionKind {
    /** Server paid the player for selling to the server (STS). */
    STS_PAYOUT(+1, "STS payout"),
    /** One of the player's own GTS listings was bought by someone (a confirmed sale). */
    GTS_SALE(+1, "my GTS sale"),
    /** The player bought something on the GTS. */
    GTS_PURCHASE(-1, "my GTS purchase"),
    /** Other income (rewards, quests, votes). */
    INCOME(+1, "other income"),
    /** Other spending (shops, fees). */
    EXPENSE(-1, "other spending");

    public final int sign;
    public final String label;
    TransactionKind(int sign, String label) { this.sign = sign; this.label = label; }
}
