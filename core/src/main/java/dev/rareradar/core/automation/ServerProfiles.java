package dev.rareradar.core.automation;

import java.util.*;

/**
 * The set of server profiles. Defaults:
 * <ul>
 *   <li><b>singleplayer</b> — automation authorised locally;</li>
 *   <li><b>cobblegalaxy</b> — hosts ending in cobblegalaxy.com (the official modpack lists forge.cobblegalaxy.com);
 *       authorised on the user's statement that staff granted permission (recorded as the source);</li>
 *   <li>any other server — an unauthorised profile is created on first connect; the user must confirm permission.</li>
 * </ul>
 */
public final class ServerProfiles {
    public List<ServerProfile> profiles = new ArrayList<>();

    public static ServerProfiles defaults() {
        ServerProfiles p = new ServerProfiles();
        ServerProfile sp = new ServerProfile();
        sp.id = "singleplayer";
        sp.displayName = "Single-player";
        sp.singleplayer = true;
        sp.automationAuthorized = true;
        sp.authorizationSource = "local world";
        sp.requireUserEnable = true;
        for (Module m : Module.values()) sp.modules.put(m, ModuleStatus.AVAILABLE);
        p.profiles.add(sp);

        ServerProfile cg = new ServerProfile();
        cg.id = "cobblegalaxy";
        cg.displayName = "CobbleGalaxy";
        cg.hostSuffixes.add("cobblegalaxy.com");
        cg.automationAuthorized = true;
        cg.authorizationSource = "User statement (2026-10-17): CobbleGalaxy staff granted automation permission to this player";
        cg.authorizedAt = 1_792_195_200_000L; // 2026-10-17T00:00:00Z
        cg.requireUserEnable = true;
        cg.modules.put(Module.HUNTING, ModuleStatus.AVAILABLE);
        cg.modules.put(Module.CAPTURE, ModuleStatus.AVAILABLE);
        cg.modules.put(Module.NAVIGATION, ModuleStatus.AVAILABLE);
        cg.modules.put(Module.TRAINING, ModuleStatus.AVAILABLE);
        cg.notes = "The publicly indexed CobbleGalaxy rules prohibit macros/automation; this profile relies on the player's "
                + "stated individual permission. Keep proof of that permission. Server list address in the official modpack: forge.cobblegalaxy.com.";
        p.profiles.add(cg);
        p.sanitize();
        return p;
    }

    public void sanitize() {
        if (profiles == null) profiles = new ArrayList<>();
        profiles.removeIf(x -> x == null || x.id == null || x.id.isBlank());
        for (ServerProfile x : profiles) x.sanitize();
        // Ensure the built-in profiles exist even in an old config.
        ServerProfiles d = null;
        for (String id : List.of("singleplayer", "cobblegalaxy")) {
            if (byId(id) == null) {
                if (d == null) d = defaultsRaw();
                profiles.add(d.byId(id));
            }
        }
    }

    private static ServerProfiles defaultsRaw() {
        ServerProfiles p = defaults();
        return p;
    }

    public ServerProfile byId(String id) {
        for (ServerProfile x : profiles) if (x.id.equals(id)) return x;
        return null;
    }

    /** Profile for the current connection; creates an unauthorised profile for an unknown server. */
    public ServerProfile resolve(boolean singleplayer, String address) {
        if (singleplayer) return byId("singleplayer");
        if (address == null || address.isBlank()) return unknown("(unknown address)");
        for (ServerProfile x : profiles) if (!x.singleplayer && x.matches(address)) return x;
        String host = EnvironmentHost.normalize(address);
        ServerProfile created = unknown(host);
        created.id = "server:" + host;
        created.hostSuffixes.add(host);
        profiles.add(created);
        return created;
    }

    private static ServerProfile unknown(String host) {
        ServerProfile u = new ServerProfile();
        u.id = "unknown";
        u.displayName = host;
        u.automationAuthorized = false;
        u.authorizationSource = "";
        u.sanitize();
        return u;
    }

    /** Records the user's explicit confirmation that they have permission to automate on this server. */
    public static void authorize(ServerProfile p, String statement, long now) {
        if (statement == null || statement.isBlank()) throw new IllegalArgumentException("Describe who granted permission");
        p.automationAuthorized = true;
        p.authorizationSource = statement.trim();
        p.authorizedAt = now;
        p.modules.put(Module.HUNTING, ModuleStatus.AVAILABLE);
        p.modules.put(Module.CAPTURE, ModuleStatus.AVAILABLE);
        p.modules.put(Module.NAVIGATION, ModuleStatus.AVAILABLE);
        p.modules.put(Module.TRAINING, ModuleStatus.AVAILABLE);
    }

    public static void revoke(ServerProfile p) {
        p.automationAuthorized = false;
        p.authorizationSource = "";
        p.authorizedAt = 0;
    }
}
