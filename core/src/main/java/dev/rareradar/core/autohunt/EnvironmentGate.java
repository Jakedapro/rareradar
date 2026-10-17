package dev.rareradar.core.autohunt;

import java.util.Locale;
import java.util.Set;

/**
 * Decides whether automation (AutoHunt / AutoTrain) may run. Allowed in single-player (including an integrated
 * server opened to LAN) and on servers the user explicitly allowlisted as their own private test server.
 * CobbleGalaxy is always refused: its rules prohibit macros and "any other automation".
 */
public final class EnvironmentGate {
    public record Decision(boolean allowed, String reason) {}

    private static final String[] ALWAYS_BLOCKED = {"cobblegalaxy"};

    private EnvironmentGate() {}

    public static Decision check(boolean singleplayer, String serverAddress, Set<String> allowlist) {
        if (singleplayer) return new Decision(true, "single-player world");
        if (serverAddress == null || serverAddress.isBlank()) return new Decision(false, "unknown server — automation disabled");
        String addr = normalize(serverAddress);
        for (String b : ALWAYS_BLOCKED)
            if (addr.contains(b)) return new Decision(false, "automation is not allowed on CobbleGalaxy (server rules ban macros/automation)");
        if (allowlist != null) for (String a : allowlist)
            if (a != null && !a.isBlank() && normalize(a).equals(addr))
                return new Decision(true, "allowlisted private server " + addr);
        return new Decision(false, "public multiplayer — add this server to the private allowlist only if you own it and automation is permitted");
    }

    /** Lower-cases and strips the default port so "Example.org:25565" equals "example.org". */
    public static String normalize(String a) {
        String s = a.trim().toLowerCase(Locale.ROOT);
        if (s.endsWith(":25565")) s = s.substring(0, s.length() - 6);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
