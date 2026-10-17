package dev.rareradar.core.breeding;

/**
 * Inheritance rules. Defaults follow the CobbleGalaxy wiki where documented (Destiny Knot passes 5 IVs; a Power
 * item locks a specific IV) and mainline Gen 6+ behaviour elsewhere (3 IVs without a knot; Everstone passes nature;
 * with two Power items one is chosen at random). The undocumented parts are flagged as assumptions in the UI and
 * can be changed here once verified in-game.
 */
public final class BreedingRules {
    public int ivsInheritedDefault = 3;
    public int ivsInheritedDestinyKnot = 5;
    public boolean everstonePassesNature = true;
    public int natureCount = 25;
    public String source = "Destiny Knot / Power items: CobbleGalaxy wiki (reported). 3 IVs default, Everstone: mainline Gen 6+ (assumed).";
}
