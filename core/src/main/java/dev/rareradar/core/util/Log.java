package dev.rareradar.core.util;

/** Logging sink for core code. The mod points this at SLF4J; tests leave the stderr default. */
public final class Log {
    public interface Sink { void log(String level, String msg, Throwable t); }

    private static volatile Sink sink = (level, msg, t) -> {
        System.err.println("[RareRadar/" + level + "] " + msg);
        if (t != null) t.printStackTrace();
    };

    private Log() {}

    public static void setSink(Sink s) { if (s != null) sink = s; }
    public static void info(String msg) { sink.log("INFO", msg, null); }
    public static void warn(String msg) { sink.log("WARN", msg, null); }
    public static void warn(String msg, Throwable t) { sink.log("WARN", msg, t); }
    public static void error(String msg, Throwable t) { sink.log("ERROR", msg, t); }
}
