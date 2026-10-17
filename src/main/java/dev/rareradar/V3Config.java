package dev.rareradar;

import dev.rareradar.core.autohunt.SafetyLimits;
import dev.rareradar.core.daily.DailyChecklist;
import dev.rareradar.core.hunt.HuntPointGoal;
import dev.rareradar.core.inventory.BallPolicy;
import dev.rareradar.core.inventory.ResourceBook;
import dev.rareradar.core.market.ValuationConfig;
import dev.rareradar.core.mega.MegaPlanner;
import dev.rareradar.core.rarity.RarityConfig;

import java.util.LinkedHashSet;
import java.util.Set;

/** V3 settings, stored in config/rareradar/v3.json. Every field is validated by {@link #sanitize()} after loading. */
public final class V3Config {
    public int version = 3;

    // Radar / performance
    public int scanIntervalTicks = 5;      // full entity scan every N ticks (was every tick in V1)
    public boolean recordEncounters = true;
    public boolean showValues = true;      // show estimated value next to radar entries
    public boolean showHuntMarkers = true;
    public double distanceHalfLife = 64;   // blocks at which a target's priority halves

    // HUD
    public int hudX = 6, hudY = 6;
    public float hudScale = 1.0f;
    public int hudOpacity = 96;            // background alpha 0..255
    public boolean dashboardOverlay = false;

    // CobbleGalaxy read-only bridge
    public boolean cobbleGalaxyBridge = true;
    public boolean captureGtsListings = true;
    /** HISTORY cards whose status text matches this are recorded as confirmed sales (hypothesis — verify in-game). */
    public String gtsSoldPattern = "(?i)\\b(sold|purchased|bought)\\b";

    // Economics inputs (user-entered; NaN = unknown)
    public double huntPointValue = Double.NaN;
    public double tokenValue = Double.NaN;
    public double travelMinutes = 0;

    // Automation (private worlds only)
    public Set<String> privateServerAllowlist = new LinkedHashSet<>();
    public boolean allowProtectedBalls = false;

    public RarityConfig rarity = new RarityConfig();
    public ValuationConfig valuation = new ValuationConfig();
    public BallPolicy balls = new BallPolicy();
    public SafetyLimits safety = new SafetyLimits();
    public HuntPointGoal huntGoal = new HuntPointGoal();
    public DailyChecklist daily = new DailyChecklist();
    public ResourceBook resources = ResourceBook.defaults();
    public MegaPlanner mega = new MegaPlanner();

    public void sanitize() {
        scanIntervalTicks = Math.max(1, Math.min(40, scanIntervalTicks));
        if (!(distanceHalfLife > 0) || distanceHalfLife > 10_000) distanceHalfLife = 64;
        hudX = Math.max(0, Math.min(4000, hudX));
        hudY = Math.max(0, Math.min(4000, hudY));
        if (!(hudScale >= 0.5f && hudScale <= 3f)) hudScale = 1.0f;
        hudOpacity = Math.max(0, Math.min(255, hudOpacity));
        if (gtsSoldPattern == null || gtsSoldPattern.isBlank()) gtsSoldPattern = "(?i)\\b(sold|purchased|bought)\\b";
        try { java.util.regex.Pattern.compile(gtsSoldPattern); } catch (RuntimeException e) { gtsSoldPattern = "(?i)\\b(sold|purchased|bought)\\b"; }
        if (!Double.isNaN(huntPointValue) && (!Double.isFinite(huntPointValue) || huntPointValue < 0)) huntPointValue = Double.NaN;
        if (!Double.isNaN(tokenValue) && (!Double.isFinite(tokenValue) || tokenValue < 0)) tokenValue = Double.NaN;
        if (!(travelMinutes >= 0) || travelMinutes > 600) travelMinutes = 0;
        if (privateServerAllowlist == null) privateServerAllowlist = new LinkedHashSet<>();
        if (rarity == null) rarity = new RarityConfig();
        if (valuation == null) valuation = new ValuationConfig();
        if (balls == null) balls = new BallPolicy();
        if (safety == null) safety = new SafetyLimits();
        if (huntGoal == null) huntGoal = new HuntPointGoal();
        if (daily == null) daily = new DailyChecklist();
        if (resources == null) resources = ResourceBook.defaults();
        if (mega == null) mega = new MegaPlanner();
        rarity.sanitize();
        valuation.sanitize();
        safety.sanitize();
        daily.sanitize();
        resources.sanitize();
        if (balls.order == null || balls.order.isEmpty()) balls = new BallPolicy();
        if (balls.protectedBalls == null) balls.protectedBalls = new BallPolicy().protectedBalls;
        if (balls.reserves == null) balls.reserves = new java.util.HashMap<>();
        if (!(huntGoal.cost > 0)) huntGoal.cost = 150;
        if (!(huntGoal.currentPoints >= 0)) huntGoal.currentPoints = 0;
        if (mega.ownedStones == null) mega.ownedStones = new java.util.TreeSet<>();
        if (mega.utility == null) mega.utility = new java.util.HashMap<>();
    }
}
