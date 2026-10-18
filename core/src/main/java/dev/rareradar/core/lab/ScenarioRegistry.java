package dev.rareradar.core.lab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Scenario factories and named suites (ordered scenario-id lists that are cycled until a limit is hit). */
public final class ScenarioRegistry {
    private final Map<String, Supplier<Scenario>> factories = new LinkedHashMap<>();
    private final Map<String, List<String>> suites = new LinkedHashMap<>();

    public ScenarioRegistry register(String id, Supplier<Scenario> factory) { factories.put(id, factory); return this; }

    public ScenarioRegistry suite(String name, String... scenarioIds) {
        for (String id : scenarioIds) if (!factories.containsKey(id)) throw new IllegalArgumentException("unknown scenario " + id + " in suite " + name);
        suites.put(name, List.of(scenarioIds));
        return this;
    }

    public Scenario create(String id) { return factories.get(id).get(); }
    public boolean hasSuite(String name) { return suites.containsKey(name); }
    public List<String> suiteScenarios(String name) { return suites.getOrDefault(name, List.of()); }
    public List<String> suiteNames() { return new ArrayList<>(suites.keySet()); }
    public List<String> scenarioIds() { return new ArrayList<>(factories.keySet()); }

    /** Scenario ids that exist but are not part of the given suite (reported as "not run"). */
    public List<String> notInSuite(String name) {
        List<String> out = new ArrayList<>(factories.keySet());
        out.removeAll(suiteScenarios(name));
        return out;
    }
}
