package dev.rareradar.core.economy;

import dev.rareradar.core.store.AsyncWriter;
import dev.rareradar.core.store.JsonlStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Persistent record of labelled money messages, with totals per kind over a time window. */
public final class TransactionLedger {
    /** The same message text within this window is one event (servers sometimes send a line twice). */
    public static final long DUPLICATE_WINDOW_MS = 2_000;

    private final JsonlStore<Transaction> store;
    private final List<Transaction> all = new ArrayList<>();

    public TransactionLedger(Path file, AsyncWriter writer) {
        store = new JsonlStore<>(file, Transaction.class, writer);
        for (Transaction t : store.load()) if (t.kind != null && t.amount > 0 && t.ts > 0) all.add(t);
    }

    /** Adds the transaction unless it duplicates the previous identical message; returns whether it was added. */
    public boolean add(Transaction t) {
        for (int i = all.size() - 1; i >= 0; i--) {
            Transaction p = all.get(i);
            if (t.ts - p.ts > DUPLICATE_WINDOW_MS) break;
            if (p.message != null && p.message.equals(t.message)) return false;
        }
        all.add(t);
        store.append(t);
        return true;
    }

    public List<Transaction> all() { return Collections.unmodifiableList(all); }

    /** Rewrites the file (after marking balance confirmations). */
    public void save() { store.rewrite(all); }

    public List<Transaction> since(long ts) {
        List<Transaction> out = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && all.get(i).ts >= ts; i--) out.add(all.get(i));
        Collections.reverse(out);
        return out;
    }

    /** Sum of amounts per kind for transactions at or after {@code ts}. */
    public Map<TransactionKind, Double> totals(long ts) {
        Map<TransactionKind, Double> m = new EnumMap<>(TransactionKind.class);
        for (Transaction t : since(ts)) m.merge(t.kind, t.amount, Double::sum);
        return m;
    }

    /** Net signed money since {@code ts}. */
    public double net(long ts) {
        double n = 0;
        for (Transaction t : since(ts)) n += t.signed();
        return n;
    }
}
