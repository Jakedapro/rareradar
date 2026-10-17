package dev.rareradar.core.automation;

import java.util.*;

/**
 * Per-server automation policy. Matched against the actual connection address. Authorisation always records who
 * granted it and when; it is never inferred.
 */
public final class ServerProfile {
    public String id;
    public String displayName;
    /** Host suffixes this profile applies to (lower-case, without port), e.g. "cobblegalaxy.com". */
    public List<String> hostSuffixes = new ArrayList<>();
    public boolean singleplayer;
    public boolean automationAuthorized;
    public String authorizationSource = "";
    public long authorizedAt;
    /** Automation must still be switched on by the user every session. */
    public boolean requireUserEnable = true;
    public boolean radarEnabled = true;
    public boolean marketCaptureEnabled = true;
    public Map<Module, ModuleStatus> modules = new EnumMap<>(Module.class);
    public String notes = "";

    public ModuleStatus status(Module m) {
        ModuleStatus s = modules == null ? null : modules.get(m);
        return s == null ? ModuleStatus.DISABLED : s;
    }

    public boolean matches(String host) {
        if (host == null) return false;
        String h = EnvironmentHost.normalize(host);
        for (String suf : hostSuffixes) {
            String s = suf.toLowerCase(Locale.ROOT);
            if (h.equals(s) || h.endsWith("." + s)) return true;
        }
        return false;
    }

    public void sanitize() {
        if (hostSuffixes == null) hostSuffixes = new ArrayList<>();
        if (modules == null) modules = new EnumMap<>(Module.class);
        if (authorizationSource == null) authorizationSource = "";
        if (notes == null) notes = "";
        // Trading has no verified purchase interface; it cannot be switched on by configuration.
        modules.put(Module.TRADING, ModuleStatus.UNSUPPORTED);
        modules.putIfAbsent(Module.SAFETY, ModuleStatus.AVAILABLE);
    }
}
