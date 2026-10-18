package dev.rareradar.core.economy;

import dev.rareradar.core.store.AsyncWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EconomyTest {
    @TempDir Path dir;
    static final Set<String> SPECIES = Set.of("gible", "dratini", "mr-mime");

    @Test void learnedRuleMatchesSameFormatWithOtherValues() {
        var r = TransactionRule.learn("§a[STS] You sold Gible (Lv. 23) for $1,250!", TransactionKind.STS_PAYOUT, SPECIES::contains, 1);
        assertNotNull(r);
        var m = r.match("[STS] You sold Dratini (Lv. 7) for $12,500!");
        assertNotNull(m);
        assertEquals(12_500, m.amount(), 1e-9);
        assertEquals("dratini", m.subject());
        assertNotNull(r.match("[STS] You sold Gible (Lv. 23) for $1,250!"));
    }

    @Test void anchoredSoPlayerChatCannotImitate() {
        var r = TransactionRule.learn("Your listing Gible sold for $5k.", TransactionKind.GTS_SALE, SPECIES::contains, 1);
        assertNotNull(r);
        assertEquals(5_000, r.match("Your listing Gible sold for $5k.").amount(), 1e-9);
        assertNull(r.match("<Griefer> Your listing Gible sold for $5k."));
        assertNull(r.match("Your listing Gible sold for $5k. lol"));
    }

    @Test void rulesWithoutSpeciesOrWithTwoAmountsAreHandled() {
        var r = TransactionRule.learn("Vote reward: +$500", TransactionKind.INCOME, SPECIES::contains, 1);
        assertNotNull(r);
        var m = r.match("Vote reward: +$750");
        assertEquals(750, m.amount(), 1e-9);
        assertNull(m.subject());
        assertNull(TransactionRule.learn("Paid $5 of $10", TransactionKind.EXPENSE, SPECIES::contains, 1), "two amounts are ambiguous");
        assertNull(TransactionRule.learn("Hello there", TransactionKind.EXPENSE, SPECIES::contains, 1));
    }

    @Test void journalKeepsOneExamplePerFormatAndSkipsExcluded() {
        var j = new MoneyMessageJournal(10);
        assertNotNull(j.offer("You received $100", 1, s -> false));
        assertNotNull(j.offer("You received $250", 2, s -> false));
        assertEquals(1, j.recent().size());
        assertEquals(250, j.recent().get(0).amount(), 1e-9);
        assertNull(j.offer("[GTS] X listed Gible for $5", 3, s -> s.contains("GTS")));
        assertNull(j.offer("no money here", 4, s -> false));
    }

    @Test void ledgerDedupesAndTotals() {
        var l = new TransactionLedger(dir.resolve("t.jsonl"), AsyncWriter.inline());
        assertTrue(l.add(tx(1_000, TransactionKind.STS_PAYOUT, 100, "a")));
        assertFalse(l.add(tx(1_500, TransactionKind.STS_PAYOUT, 100, "a")), "same message within 2 s");
        assertTrue(l.add(tx(5_000, TransactionKind.GTS_PURCHASE, 40, "b")));
        assertEquals(60, l.net(0), 1e-9);
        assertEquals(100, l.totals(0).get(TransactionKind.STS_PAYOUT), 1e-9);
        var again = new TransactionLedger(dir.resolve("t.jsonl"), AsyncWriter.inline());
        assertEquals(2, again.all().size());
    }

    @Test void balanceBaselineChangesAndAmbiguity() {
        var b = new BalanceTracker(null);
        assertNull(b.observe(List.of("§6CobbleGalaxy", "Balance: $1,000", "Online: 120"), 1));
        var c = b.observe(List.of("Balance: $1,250"), 2);
        assertEquals(250, c.delta(), 1e-9);
        assertEquals(250, b.sessionNet(), 1e-9);
        assertNull(b.observe(List.of("Balance: $1,250"), 3));
        assertNull(b.observe(List.of("Balance: $5", "Money: $9"), 4), "two balance lines are ambiguous");
        assertEquals(2000, new BalanceTracker(null).parse(List.of("Bal: 2,000")), 1e-9);
        b.resetSession();
        assertNull(b.observe(List.of("Balance: $99,999"), 5), "reconnect is a new baseline, not income");
        assertEquals(0, b.sessionNet(), 1e-9);
    }

    @Test void reconcileConfirmsMatchingChanges() {
        var t = tx(10_000, TransactionKind.STS_PAYOUT, 250, "x");
        var p = tx(20_000, TransactionKind.GTS_PURCHASE, 100, "y");
        int n = BalanceTracker.reconcile(List.of(t, p), List.of(new BalanceTracker.Change(11_000, 0, 250), new BalanceTracker.Change(40_000, 250, 150)), 5_000);
        assertEquals(1, n);
        assertTrue(t.balanceConfirmed);
        assertFalse(p.balanceConfirmed, "change outside the window");
    }

    static Transaction tx(long ts, TransactionKind k, double amount, String msg) {
        Transaction t = new Transaction();
        t.ts = ts; t.kind = k; t.amount = amount; t.message = msg;
        return t;
    }
}
