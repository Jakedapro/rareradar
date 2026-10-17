package dev.rareradar.core.autohunt;

/** What the Minecraft layer should do this tick. */
public record Command(Kind kind, int slot, String reason) {
    public enum Kind { NONE, RELEASE_KEYS, PLAN_PATH, FOLLOW_PATH, AIM, THROW, STOP }

    public static final Command NONE = new Command(Kind.NONE, -1, "");
    public static final Command RELEASE = new Command(Kind.RELEASE_KEYS, -1, "");
    public static Command stop(String why) { return new Command(Kind.STOP, -1, why); }
}
