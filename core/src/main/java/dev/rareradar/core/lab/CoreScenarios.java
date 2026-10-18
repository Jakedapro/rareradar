package dev.rareradar.core.lab;

import dev.rareradar.core.lab.scenarios.EconomyFixtureScenario;
import dev.rareradar.core.lab.scenarios.SelfCheckScenario;

/** Registers the scenarios and suites that need no Minecraft. The mod adds its in-game scenarios on top. */
public final class CoreScenarios {
    private CoreScenarios() {}

    public static ScenarioRegistry register(ScenarioRegistry r) {
        r.register("LAB_SELF_CHECK", SelfCheckScenario::new);
        for (EconomyFixtureScenario.Case c : EconomyFixtureScenario.Case.values())
            r.register("ECON_" + c.name(), () -> new EconomyFixtureScenario(c));
        r.suite("smoke", "LAB_SELF_CHECK", "ECON_BALANCE", "ECON_TRANSACTIONS", "ECON_EARNINGS");
        r.suite("economy_fixtures", "ECON_BALANCE", "ECON_TRANSACTIONS", "ECON_EARNINGS");
        return r;
    }
}
