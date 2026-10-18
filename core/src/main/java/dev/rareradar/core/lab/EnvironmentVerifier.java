package dev.rareradar.core.lab;

import dev.rareradar.core.automation.EnvironmentHost;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Decides whether the Lab may run here. Allowed: a local single-player world, or a multiplayer host that the user put
 * on the Lab allowlist AND that is a private/loopback address. CobbleGalaxy and any public host are always refused,
 * even if listed.
 */
public final class EnvironmentVerifier {
    public enum Kind { NOT_IN_WORLD, SINGLEPLAYER, MULTIPLAYER }

    public record Env(Kind kind, String address, boolean cobblemonPresent) {
        /** Identity used to detect a changed connection mid-session. */
        public String key() { return kind + ":" + (address == null ? "" : EnvironmentHost.normalize(address)); }
        public boolean inWorld() { return kind != Kind.NOT_IN_WORLD; }
    }

    public record Verdict(boolean allowed, String reason) {}

    /** Hosts the Lab refuses unconditionally. */
    private static final List<String> NEVER = List.of("cobblegalaxy");

    private EnvironmentVerifier() {}

    public static Verdict verify(Env e, Collection<String> allowlist) {
        if (e == null || e.kind() == Kind.NOT_IN_WORLD) return new Verdict(false, "not in a world");
        if (e.kind() == Kind.SINGLEPLAYER) return new Verdict(true, "local single-player world");
        String host = e.address() == null ? "" : EnvironmentHost.normalize(e.address());
        if (host.isEmpty()) return new Verdict(false, "multiplayer server with unknown address");
        for (String n : NEVER) if (host.contains(n)) return new Verdict(false, "Lab automation is never allowed on " + host);
        if (!isPrivateHost(host)) return new Verdict(false, host + " is not a private/local address — public servers are refused");
        if (allowlist != null) for (String a : allowlist)
            if (a != null && EnvironmentHost.normalize(a).equals(host)) return new Verdict(true, "private test server " + host + " (allowlisted)");
        return new Verdict(false, host + " is private but not on the Lab allowlist (lab_config.json → allowedServers)");
    }

    /** Loopback, RFC1918, link-local-ish names. Single-label names and public DNS names are not private. */
    static boolean isPrivateHost(String h) {
        h = h.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        if (h.equals("localhost") || h.equals("::1") || h.endsWith(".local") || h.endsWith(".lan")) return true;
        String[] p = h.split("\\.");
        if (p.length != 4) return false;
        int[] o = new int[4];
        for (int i = 0; i < 4; i++) {
            try { o[i] = Integer.parseInt(p[i]); } catch (NumberFormatException ex) { return false; }
            if (o[i] < 0 || o[i] > 255) return false;
        }
        return o[0] == 127 || o[0] == 10 || (o[0] == 192 && o[1] == 168) || (o[0] == 172 && o[1] >= 16 && o[1] <= 31);
    }
}
