package dev.rareradar.core.inventory;

import dev.rareradar.core.util.Money;

import java.util.*;

/**
 * Tracks counts and replacement values of valuable resources. Counts come from the player's inventory scan;
 * unit values only from manually recorded market prices (missing = unknown).
 */
public final class ResourceBook {
    public static final class Resource {
        public String id;                 // item id, e.g. "cobblemon:master_ball"
        public String label;
        public int count;
        public double unitValue = Double.NaN;
        public int reserve;               // warn below this
        public boolean protectedItem;     // never spent by automation without explicit permission
    }

    public Map<String, Resource> resources = new LinkedHashMap<>();

    public static ResourceBook defaults() {
        ResourceBook b = new ResourceBook();
        b.track("cobblemon:poke_ball", "Poké Ball", 0, false);
        b.track("cobblemon:great_ball", "Great Ball", 0, false);
        b.track("cobblemon:ultra_ball", "Ultra Ball", 0, false);
        b.track("cobblemon:master_ball", "Master Ball", 0, true);
        b.track("cobblemon:rare_candy", "Rare Candy", 0, true);
        b.track("cobblemon:destiny_knot", "Destiny Knot", 0, true);
        b.track("cobblemon:everstone", "Everstone", 0, false);
        return b;
    }

    public Resource track(String id, String label, int reserve, boolean prot) {
        Resource r = resources.computeIfAbsent(id, k -> new Resource());
        r.id = id; r.label = label; r.reserve = reserve; r.protectedItem = prot;
        return r;
    }

    public void setCount(String id, int count) {
        Resource r = resources.get(id);
        if (r != null) r.count = Math.max(0, count);
    }

    /** Total replacement value of items with known prices, plus how many tracked items have no price. */
    public double[] totalValue() {
        double v = 0; int unknown = 0;
        for (Resource r : resources.values()) {
            if (r.count == 0) continue;
            if (Double.isFinite(r.unitValue)) v += r.count * r.unitValue; else unknown++;
        }
        return new double[]{v, unknown};
    }

    public List<String> warnings() {
        List<String> w = new ArrayList<>();
        for (Resource r : resources.values())
            if (r.reserve > 0 && r.count < r.reserve) w.add(r.label + " low: " + r.count + " (reserve " + r.reserve + ")");
        return w;
    }

    /** Warning for a planned activity whose expected cost exceeds its expected revenue or a share of resources. */
    public static String activityWarning(String activity, double expectedCost, double expectedRevenue) {
        if (!Double.isFinite(expectedCost) || !Double.isFinite(expectedRevenue)) return null;
        if (expectedCost > expectedRevenue)
            return activity + " is expected to cost " + Money.fmt(expectedCost) + " for about " + Money.fmt(expectedRevenue) + " back.";
        return null;
    }

    public void sanitize() {
        if (resources == null) resources = new LinkedHashMap<>();
        resources.values().removeIf(r -> r == null || r.id == null);
        for (Resource r : resources.values()) {
            r.count = Math.max(0, r.count); r.reserve = Math.max(0, r.reserve);
            if (r.label == null) r.label = r.id;
            if (!Double.isNaN(r.unitValue) && (!Double.isFinite(r.unitValue) || r.unitValue < 0)) r.unitValue = Double.NaN;
        }
    }
}
