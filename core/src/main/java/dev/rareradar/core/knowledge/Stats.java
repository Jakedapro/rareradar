package dev.rareradar.core.knowledge;

/**
 * Six Cobblemon stats as written in species data ({@code baseStats} / {@code evYield}).
 * A value the data file did not contain is {@link #MISSING}; {@link #total()} is then {@link #MISSING} too.
 */
public record Stats(int hp, int attack, int defence, int specialAttack, int specialDefence, int speed, int total) {
    public static final int MISSING = -1;

    public boolean complete() {
        return hp >= 0 && attack >= 0 && defence >= 0 && specialAttack >= 0 && specialDefence >= 0 && speed >= 0;
    }

    /** Value by Cobblemon stat key (hp, attack, defence, special_attack, special_defence, speed); MISSING if unknown key. */
    public int get(String key) {
        return switch (key) {
            case "hp" -> hp;
            case "attack" -> attack;
            case "defence", "defense" -> defence;
            case "special_attack" -> specialAttack;
            case "special_defence", "special_defense" -> specialDefence;
            case "speed" -> speed;
            default -> MISSING;
        };
    }
}
