package dev.rareradar.core.breeding;

/** Breeding-relevant held items. Power items lock one stat (index 0..5 = HP, Atk, Def, SpA, SpD, Spe). */
public enum HeldItem {
    NONE(-1), DESTINY_KNOT(-1), EVERSTONE(-1),
    POWER_WEIGHT(0), POWER_BRACER(1), POWER_BELT(2), POWER_LENS(3), POWER_BAND(4), POWER_ANKLET(5);

    public final int stat;

    HeldItem(int stat) { this.stat = stat; }

    public boolean isPower() { return stat >= 0; }
}
