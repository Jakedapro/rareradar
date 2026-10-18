package dev.rareradar.core.lab;

import java.util.regex.Pattern;

/** Scrubs credentials and the home directory from free text before it reaches a log file. */
public final class Redact {
    private static final Pattern SECRET = Pattern.compile("(?i)(access[_-]?token|token|password|passwd|secret|session[_-]?id|authorization|bearer)(\\s*[=:]\\s*|\\s+)[^\\s,;\"']+");
    private Redact() {}

    public static String clean(String s) {
        if (s == null) return null;
        String out = SECRET.matcher(s).replaceAll("$1=<redacted>");
        String home = System.getProperty("user.home");
        if (home != null && home.length() > 3) out = out.replace(home, "~");
        return out;
    }
}
