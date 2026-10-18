package dev.rareradar.core.economy;

import dev.rareradar.core.market.GtsTextParser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Predicate;

/**
 * Recent server messages that mention exactly one money amount but match no labelled rule. These are the raw
 * evidence the player labels on the dashboard ("this is an STS payout"); nothing here is ever counted as money.
 */
public final class MoneyMessageJournal {
    public record Entry(long ts, String message, double amount) {}

    private final int capacity;
    private final Deque<Entry> recent = new ArrayDeque<>();

    public MoneyMessageJournal(int capacity) { this.capacity = capacity; }

    /** Returns the entry if the message is a candidate (one amount, not a duplicate of the last few), else null. */
    public synchronized Entry offer(String rawMessage, long now, Predicate<String> exclude) {
        String msg = GtsTextParser.clean(rawMessage);
        if (msg.isEmpty() || msg.length() > 300 || exclude.test(msg)) return null;
        double a = GtsTextParser.singlePrice(msg);
        if (!(a > 0)) return null;
        // Collapse repeats of the same format: keep the newest example of a message identical up to digits.
        String shape = msg.replaceAll("\\d", "#");
        recent.removeIf(e -> e.message().replaceAll("\\d", "#").equals(shape));
        Entry e = new Entry(now, msg, a);
        recent.addFirst(e);
        while (recent.size() > capacity) recent.removeLast();
        return e;
    }

    public synchronized List<Entry> recent() { return new ArrayList<>(recent); }

    public synchronized void remove(Entry e) { recent.remove(e); }
}
