package dev.rareradar.core.lab.scenarios;

import dev.rareradar.core.economy.*;
import dev.rareradar.core.lab.*;
import dev.rareradar.core.store.AsyncWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Synthetic-message checks of RareRadar's economy logic. FIXTURE layer: the messages are made up for the test, so a pass
 * says the logic behaves on those inputs and says nothing about a real server's chat formats or prices.
 */
public final class EconomyFixtureScenario implements Scenario {
    public enum Case { BALANCE, TRANSACTIONS, EARNINGS }

    private final Case which;
    public EconomyFixtureScenario(Case which) { this.which = which; }

    public String id() { return "ECON_" + which.name(); }
    public String idPrefix() { return "ECON"; }
    public String category() { return "economy"; }
    public String description() { return "Economy fixtures (" + which.name().toLowerCase() + ") on synthetic messages"; }
    public Layer layer() { return Layer.FIXTURE; }
    public String expected() { return "All fixture assertions hold; real-server formats still need manual verification"; }

    public void begin(TestContext c) {}

    public Step tick(TestContext c) throws Exception {
        List<String> failed = new ArrayList<>();
        int[] n = {0};
        switch (which) {
            case BALANCE -> balance(failed, n);
            case TRANSACTIONS -> transactions(failed, n);
            case EARNINGS -> earnings(failed, n);
        }
        c.metric("assertions", n[0]);
        c.metric("assertions_failed", failed.size());
        for (String f : failed) c.event("assertion_failed", "detail", f);
        if (failed.isEmpty()) return Step.pass(n[0] + " assertions held");
        return Step.fail(FailureClass.ECONOMY_ASSERTION, String.join("; ", failed), failed.size() + " of " + n[0] + " failed");
    }

    private static void check(List<String> failed, int[] n, boolean ok, String what) { n[0]++; if (!ok) failed.add(what); }

    private static void balance(List<String> f, int[] n) {
        BalanceTracker t = new BalanceTracker(null);
        check(f, n, t.parse(List.of("Balance: $11,242")) == 11242, "parse '$11,242' sidebar line");
        check(f, n, Double.isNaN(t.parse(List.of("Players: 3", "Rank: Gold"))), "no money line → NaN");
        check(f, n, Double.isNaN(t.parse(List.of())), "empty sidebar → NaN");
        check(f, n, Double.isNaN(t.parse(List.of("Balance: $100", "Money: $250"))), "two different money lines are ambiguous → NaN");
        BalanceTracker b = new BalanceTracker(null);
        check(f, n, b.observe(List.of("Balance: $1,000"), 1000) == null, "first reading is a baseline, not a change");
        var ch = b.observe(List.of("Balance: $1,750"), 2000);
        check(f, n, ch != null && Math.abs(ch.delta() - 750) < 1e-6, "change of +750 detected");
        check(f, n, b.observe(List.of("Balance: $1,750"), 3000) == null, "unchanged balance reports no change");
        check(f, n, b.observe(List.of("nothing here"), 4000) == null && b.current() == 1750, "unreadable sidebar keeps last balance");
        b.resetSession();
        check(f, n, b.observe(List.of("Balance: $99,999"), 5000) == null, "after reset the next reading is a new baseline (no phantom income)");
    }

    private static void transactions(List<String> f, int[] n) throws Exception {
        Set<String> species = Set.of("pikachu");
        String example = "You sold Pikachu for $1,500 to the server";
        TransactionRule r = TransactionRule.learn(example, TransactionKind.STS_PAYOUT, species::contains, 1L);
        check(f, n, r != null, "rule learned from a labelled example");
        if (r != null) {
            var m = r.match("You sold Pikachu for $2,000 to the server");
            check(f, n, m != null && m.amount() == 2000 && "pikachu".equals(m.subject()), "rule matches same format with a different amount and subject");
            check(f, n, r.match("<Bob> You sold Pikachu for $2,000 to the server") == null, "player chat imitating the format is rejected");
            check(f, n, r.match("You sold Pikachu for $2,000 and $3,000") == null, "message with extra amounts is not matched");
        }
        Path dir = Files.createTempDirectory("rr-lab-econ");
        try {
            TransactionLedger l = new TransactionLedger(dir.resolve("tx.jsonl"), AsyncWriter.inline());
            Transaction t1 = tx(10_000, TransactionKind.STS_PAYOUT, 1500, example);
            Transaction t2 = tx(10_800, TransactionKind.STS_PAYOUT, 1500, example);
            Transaction t3 = tx(20_000, TransactionKind.STS_PAYOUT, 1500, example);
            check(f, n, l.add(t1), "first transaction added");
            check(f, n, !l.add(t2), "identical message within 2 s is a duplicate");
            check(f, n, l.add(t3), "identical message 10 s later is a new event");
            check(f, n, l.all().size() == 2, "ledger holds 2 transactions");
            List<BalanceTracker.Change> changes = List.of(new BalanceTracker.Change(10_300, 5000, 6500));
            check(f, n, BalanceTracker.reconcile(new ArrayList<>(l.all()), changes, 3000) == 1, "balance change confirms exactly one matching payout");
            check(f, n, l.all().get(0).balanceConfirmed && !l.all().get(1).balanceConfirmed, "unmatched payout stays unconfirmed");
        } finally {
            try (var s = Files.walk(dir)) { s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        }
    }

    private static Transaction tx(long ts, TransactionKind k, double amt, String msg) {
        Transaction t = new Transaction();
        t.ts = ts; t.kind = k; t.amount = amt; t.message = msg;
        return t;
    }

    private static void earnings(List<String> f, int[] n) {
        var none = EarningsBreakdown.compute(List.of(1000.0, 2000.0, 3000.0, 1_000_000.0), List.of(), 605_120_000.0);
        check(f, n, none.realisedRevenue() == 0 && !none.hasRealisedData(), "asking prices alone give $0 realised revenue");
        check(f, n, none.askMedian() == 2500.0, "median asking price is computed robustly (outlier does not dominate)");
        check(f, n, none.hypotheticalPerHour() == 605_120_000.0 && none.netProfit() == 0, "hypothetical potential never enters net profit");
        check(f, n, none.headline().contains("$0") && none.notes().stream().anyMatch(s -> s.contains("no confirmed sales")), "headline states $0 realised and explains why");
        var some = EarningsBreakdown.compute(List.of(), List.of(
                tx(1, TransactionKind.STS_PAYOUT, 1500, "a"), tx(2, TransactionKind.GTS_SALE, 4000, "b"),
                tx(3, TransactionKind.GTS_PURCHASE, 2500, "c"), tx(4, TransactionKind.EXPENSE, 500, "d")), Double.NaN);
        check(f, n, some.realisedRevenue() == 5500 && some.confirmedSales() == 2, "payouts and sales add up to realised revenue");
        check(f, n, some.costs() == 3000 && some.netProfit() == 2500, "purchases and expenses are costs; net = revenue − costs");
        check(f, n, Double.isNaN(EarningsBreakdown.netOfFee(1000, Double.NaN)), "unknown fee is NaN, not zero");
        check(f, n, EarningsBreakdown.netOfFee(1000, 0.05) == 950, "5% fee deducted from 1000");
        var empty = EarningsBreakdown.compute(List.of(), List.of(), Double.NaN);
        check(f, n, Double.isNaN(empty.askMedian()) && empty.askCount() == 0, "no data → NaN median, not 0");
    }
}
