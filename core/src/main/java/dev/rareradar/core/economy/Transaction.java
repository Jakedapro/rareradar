package dev.rareradar.core.economy;

/** One money event observed from a server message the player has labelled (never inferred or invented). */
public final class Transaction {
    public long ts;
    public TransactionKind kind;
    /** Amount stated in the message, always positive; {@link TransactionKind#sign} gives the direction. */
    public double amount;
    /** Species or item named in the message, if the rule captures one. */
    public String subject;
    public String message;
    public String ruleId;
    public String session;
    /** True once a matching sidebar balance change was observed. */
    public boolean balanceConfirmed;

    public double signed() { return kind == null ? 0 : kind.sign * amount; }
}
