package dev.rareradar.core.automation;

import java.util.Locale;

final class EnvironmentHost {
    private EnvironmentHost() {}

    /** Lower-case host without port or trailing dot. */
    static String normalize(String a) {
        String s = a.trim().toLowerCase(Locale.ROOT);
        int c = s.lastIndexOf(':');
        if (c > 0 && s.indexOf(':') == c) s = s.substring(0, c); // strip port (not IPv6)
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
