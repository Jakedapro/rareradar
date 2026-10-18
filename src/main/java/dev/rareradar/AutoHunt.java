package dev.rareradar;

import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.battles.InBattleMove;
import com.cobblemon.mod.common.battles.MoveActionResponse;
import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.battle.ClientBattle;
import com.cobblemon.mod.common.client.battle.SingleActionRequest;
import com.cobblemon.mod.common.entity.pokeball.EmptyPokeBallEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.rareradar.core.autohunt.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Private-world AutoHunt V2 (catching) and AutoTrain (battling), both refused outside single-player and explicitly
 * allowlisted private servers — and always refused on CobbleGalaxy, whose rules ban automation. The catching logic
 * is the tested {@link AutoHuntMachine}; this class only gathers {@link Sensors} and executes {@link Command}s.
 */
public final class AutoHunt {
    public static boolean active = false;
    /** true = train mode (battle wild Pokemon for XP/EVs), false = hunt mode (catch rares). */
    public static boolean train = false;

    private static AutoHuntMachine machine;
    private static final Navigator nav = new Navigator();
    private static PokemonEntity target;
    private static int ballEntityId = -1, previousBallId = -1;
    private static boolean lastPlanOk;
    private static long throwTick, tick;
    private static Vec3d throwEye = Vec3d.ZERO;
    private static String status = "";

    // Train mode state (V1 behaviour, now gated and time-limited)
    private static int battleDelay, trainCooldown, trainTicks;
    private static Set<String> evSpecies = Set.of();

    private AutoHunt() {}

    public static String status() { return status; }
    public static AutoHuntMachine machine() { return machine; }

    private static dev.rareradar.core.automation.TaskRecord task;
    private static int lastCaptures, lastFailures;
    private static boolean rulesNoticeShown;

    private static dev.rareradar.core.automation.Module module() {
        return train ? dev.rareradar.core.automation.Module.TRAINING : dev.rareradar.core.automation.Module.HUNTING;
    }

    public static void toggleTrain(MinecraftClient client) {
        if (active) { stop(client, "toggled off"); return; }
        train = true;
        loadEvSpecies();
        start(client);
    }

    public static void toggle(MinecraftClient client) {
        if (active) { stop(client, "toggled off"); return; }
        train = false;
        start(client);
    }

    private static void start(MinecraftClient client) {
        if (client.player == null) return;
        if (dev.rareradar.lab.LabRuntime.blocksAutoHunt()) {
            client.player.sendMessage(Text.literal("§cAuto Hunt refused: RareRadar Lab is running (stop it first)"), false);
            return;
        }
        Galaxy g = Galaxy.get();
        var ctl = g.controller;
        long now = System.currentTimeMillis();
        String label = train ? "Auto Train" : "Auto Hunt";
        // Pressing the key is the user's per-session enable (profiles require it every session).
        if (!ctl.sessionEnabled()) {
            var e = ctl.enable(now);
            if (!e.allowed()) { refuse(client, label, e.reason()); return; }
        }
        var prof = ctl.profile();
        if (prof != null && !prof.singleplayer && !rulesNoticeShown) {
            rulesNoticeShown = true;
            client.player.sendMessage(Text.literal("§eRareRadar automation on " + prof.displayName + " — authorised by: " + prof.authorizationSource
                    + (prof.notes.isBlank() ? "" : "§7 (" + prof.notes + ")")), false);
        }
        ctl.setModuleEnabled(module(), true);
        if (!train) {
            Map<String, Integer> usable = usableBalls(client.player);
            var rc = dev.rareradar.core.automation.ResourceCheck.forHunting(usable, 3, client.player.getHealth(), g.cfg.safety.minHealth);
            if (!rc.ok()) { refuse(client, label, String.join("; ", rc.problems())); return; }
            for (String w : rc.warnings()) client.player.sendMessage(Text.literal("§6" + w), false);
        }
        var d = ctl.acquire(module(), now);
        if (!d.allowed()) { refuse(client, label, d.reason()); return; }
        task = g.tasks.start(module(), train ? "Train lead Pokémon" : "Hunt and catch qualifying Pokémon", prof == null ? "" : prof.id, false, now);
        lastCaptures = lastFailures = 0;
        active = true;
        trainTicks = 0;
        machine = new AutoHuntMachine(Galaxy.get().cfg.safety);
        machine.start(System.currentTimeMillis());
        target = null;
        ballEntityId = -1;
        RareRadarClient.LOG.info("Auto {} ON (profile {})", train ? "train" : "hunt", prof == null ? "?" : prof.id);
        client.player.sendMessage(Text.literal(label + ": ON — " + (prof == null ? "" : prof.displayName) + " · Backspace = emergency stop"), true);
    }

    private static void refuse(MinecraftClient client, String label, String why) {
        train = false;
        client.player.sendMessage(Text.literal("§c" + label + " refused: " + why), false);
        RareRadarClient.LOG.info("Automation refused: {}", why);
    }

    /** Balls the ball policy would allow (protected balls and reserves excluded). */
    private static Map<String, Integer> usableBalls(ClientPlayerEntity p) {
        Map<String, Integer> counts = new HashMap<>();
        for (ItemStack st : p.getInventory().main) { String id = CobblemonCompat.ballId(st); if (id != null) counts.merge(id, st.getCount(), Integer::sum); }
        var pol = Galaxy.get().cfg.balls;
        Map<String, Integer> usable = new HashMap<>();
        for (var e : counts.entrySet()) {
            if (pol.protectedBalls.contains(e.getKey()) && !Galaxy.get().cfg.allowProtectedBalls) continue;
            usable.put(e.getKey(), Math.max(0, e.getValue() - pol.reserves.getOrDefault(e.getKey(), 0)));
        }
        return usable;
    }

    /** Stops everything and releases keys. Safe to call at any time (disconnect, world change, emergency key). */
    public static void stop(MinecraftClient client, String why) {
        boolean was = active;
        active = false;
        train = false;
        target = null;
        ballEntityId = -1;
        long now = System.currentTimeMillis();
        if (machine != null && machine.running()) machine.abort(now, why);
        Navigator.release(client);
        status = "stopped: " + why;
        Galaxy g = Galaxy.get();
        if (g != null) {
            for (var m : dev.rareradar.core.automation.Module.values()) if (m != dev.rareradar.core.automation.Module.SAFETY) g.controller.release(m, now, why);
            if (task != null) {
                g.tasks.update(task, why.contains("error") || why.contains("refused") ? dev.rareradar.core.automation.TaskRecord.Status.FAILED
                        : dev.rareradar.core.automation.TaskRecord.Status.DONE, status, why, now);
                task = null;
            }
        }
        if (was) {
            RareRadarClient.LOG.info("Auto hunt/train stopped: {}", why);
            if (client.player != null) client.player.sendMessage(Text.literal("Auto Hunt stopped: " + why), true);
        }
    }

    public static void tick(MinecraftClient client) {
        tick++;
        if (!active) return;
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null) { stop(client, "world unloaded"); return; }
        long now = System.currentTimeMillis();
        var ctl = Galaxy.get().controller;
        var may = ctl.mayRun(module(), now);
        if (!may.allowed() || !ctl.holds(module())) { stop(client, may.allowed() ? "control taken by another module" : may.reason()); return; }
        if (train) { tickTrain(client, p); return; }

        Sensors s = sense(client, p);
        Command cmd = machine.tick(s);
        resolveTarget(client); // the machine may have just selected or dropped a target
        status = machine.state() + (target != null ? " → " + target.getPokemon().getSpecies().getName() : "")
                + " · balls " + machine.ballsThisRun() + "/" + Galaxy.get().cfg.safety.maxBallsPerRun
                + " · caught " + machine.captures();
        execute(client, p, cmd);
        onOutcome(client, machine.consumeOutcome()); // exactly once per throw
        ctl.report(module(), switch (machine.state()) {
            case SCAN, SELECT_TARGET, RETRY -> dev.rareradar.core.automation.AutomationController.State.SCANNING;
            case PLAN_ROUTE -> dev.rareradar.core.automation.AutomationController.State.PLANNING;
            case APPROACH -> dev.rareradar.core.automation.AutomationController.State.NAVIGATING;
            case AIM, THROW -> dev.rareradar.core.automation.AutomationController.State.INTERACTING;
            case AWAIT_RESULT, VERIFY_CAPTURE -> dev.rareradar.core.automation.AutomationController.State.VERIFYING;
            case SUCCESS -> dev.rareradar.core.automation.AutomationController.State.TARGET_SELECTED;
            default -> dev.rareradar.core.automation.AutomationController.State.IDLE;
        });
        // Feed successes/failures to the controller: repeated failures stop automation instead of retrying forever.
        if (machine.captures() > lastCaptures) { lastCaptures = machine.captures(); ctl.outcome(dev.rareradar.core.automation.Module.CAPTURE, now, "capture", "verified by ball state", true); }
        if (machine.failures() > lastFailures) {
            lastFailures = machine.failures();
            ctl.outcome(module(), now, "attempt", machine.history().iterator().hasNext() ? lastTransition() : "", false);
        }
        if (!machine.running()) stop(client, machine.stopReason().isEmpty() ? "finished" : machine.stopReason());
    }

    private static Sensors sense(MinecraftClient c, ClientPlayerEntity p) {
        Galaxy g = Galaxy.get();
        Sensors s = new Sensors();
        s.nowMillis = System.currentTimeMillis();
        s.worldPresent = c.world != null;
        // Pause in menus and during the player's own Cobblemon battle (its GUI is an overlay, not a Screen).
        s.screenOpen = c.currentScreen != null || CobblemonCompat.inBattle();
        s.health = p.getHealth();
        for (RadarScanner.Hit h : RadarScanner.hits()) {
            if (h.obs.owned || CobblemonCompat.isBattling(h.entity) || h.entity.isRemoved()) continue;
            if (machine.blacklisted(h.entity.getId(), s.nowMillis)) continue;
            s.bestCandidateId = (long) h.entity.getId();
            s.bestCandidateScore = h.rarity.score();
            break;
        }
        resolveTarget(c);
        if (target != null) {
            s.targetPresent = true;
            s.targetInBattle = CobblemonCompat.isBattling(target);
            double flat = Math.hypot(target.getX() - p.getX(), target.getZ() - p.getZ());
            s.targetInThrowRange = flat <= Settings.S.throwRange && p.canSee(target);
            s.aimed = aimError(p, aimPoint(target)) < 3.0;
        }
        s.pathFinished = nav.finished();
        s.makingProgress = lastProgress;
        s.pathFound = lastPlanOk;
        // Ball choice is for the locked target (not whatever is currently best on the radar).
        s.ballSlot = chooseBall(p, machine.target() != null ? machine.targetScore() : s.bestCandidateScore);
        s.ownBallState = trackBall(c, p);
        return s;
    }

    private static void execute(MinecraftClient c, ClientPlayerEntity p, Command cmd) {
        switch (cmd.kind()) {
            case RELEASE_KEYS, NONE -> Navigator.release(c);
            case PLAN_PATH -> {
                Navigator.release(c);
                lastPlanOk = target != null && nav.plan(c, target.getBlockPos(), 4.0);
                lastProgress = true;
            }
            case FOLLOW_PATH -> {
                if (target != null) {
                    // feed progress back next tick via Sensors.makingProgress
                    lastProgress = nav.follow(c);
                }
            }
            case AIM -> { Navigator.release(c); if (target != null) Navigator.faceToward(p, aimPoint(target), 3.0f); }
            case THROW -> {
                Navigator.release(c);
                p.getInventory().selectedSlot = cmd.slot();
                c.interactionManager.interactItem(p, Hand.MAIN_HAND);
                p.swingHand(Hand.MAIN_HAND);
                throwTick = tick;
                throwEye = p.getEyePos();
                if (ballEntityId >= 0) previousBallId = ballEntityId;
                ballEntityId = -1;
                Galaxy.get().sessions.ballThrown();
                RareRadarClient.LOG.info("Threw {} at {}", ballName(p.getInventory().getStack(cmd.slot())), target == null ? "?" : target.getPokemon().getSpecies().getName());
            }
            case STOP -> Navigator.release(c);
        }
    }

    private static boolean lastProgress = true;

    private static String lastTransition() {
        String last = "";
        for (var t : machine.history()) last = t.to() + ": " + t.why();
        return last;
    }

    /** Last throw outcome AutoHunt classified (read by RareRadar Lab; never used to decide anything here). */
    public static volatile CaptureOutcome lastOutcome;

    private static void onOutcome(MinecraftClient c, CaptureOutcome o) {
        if (o == null) return;
        lastOutcome = o;
        RareRadarClient.LOG.info("Throw outcome: {}", o.label);
        if (o == CaptureOutcome.CAPTURED) Galaxy.get().sessions.caught();
        if (c.player != null) c.player.sendMessage(Text.literal("Auto Hunt: " + o.label), true);
    }

    private static void resolveTarget(MinecraftClient c) {
        Long tid = machine == null ? null : machine.target();
        if (tid == null || c.world == null) { target = null; return; }
        Entity e = c.world.getEntityById(tid.intValue());
        target = e instanceof PokemonEntity pe && !pe.isRemoved() && pe.isAlive() ? pe : null;
    }

    private static Vec3d aimPoint(PokemonEntity e) { return e.getPos().add(0, e.getHeight() * 0.5, 0); }

    private static double aimError(ClientPlayerEntity p, Vec3d aim) {
        Vec3d eye = p.getEyePos();
        double dx = aim.x - eye.x, dy = aim.y - eye.y, dz = aim.z - eye.z;
        float wantYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        return Math.max(Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(wantYaw - p.getYaw())), Math.abs(wantPitch - p.getPitch()));
    }

    private static int chooseBall(ClientPlayerEntity p, double targetScore) {
        String[] hotbar = new String[9];
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < p.getInventory().main.size(); i++) {
            ItemStack st = p.getInventory().main.get(i);
            String id = CobblemonCompat.ballId(st);
            if (id == null) continue;
            counts.merge(id, st.getCount(), Integer::sum);
            if (i < 9) hotbar[i] = id;
        }
        Galaxy g = Galaxy.get();
        return g.cfg.balls.choose(hotbar, counts, targetScore, g.cfg.allowProtectedBalls).slot();
    }

    private static String ballName(ItemStack s) { String id = CobblemonCompat.ballId(s); return id == null ? "ball" : id; }

    /**
     * Finds our thrown ball and returns its synced capture state. Prefers the projectile owner; if the owner was not
     * synced, falls back to a ball that appeared within 3 ticks of our throw within 4 blocks of our eye position.
     */
    private static CaptureClassifier.BallState trackBall(MinecraftClient c, ClientPlayerEntity p) {
        if (machine.state() != HuntState.AWAIT_RESULT) return null;
        if (ballEntityId >= 0) {
            Entity e = c.world.getEntityById(ballEntityId);
            return e instanceof EmptyPokeBallEntity b && !b.isRemoved() ? CobblemonCompat.ballState(b) : null;
        }
        Box box = p.getBoundingBox().expand(48);
        EmptyPokeBallEntity best = null;
        for (EmptyPokeBallEntity b : c.world.getEntitiesByClass(EmptyPokeBallEntity.class, box, x -> !x.isRemoved())) {
            if (b.getId() == previousBallId) continue; // a lingering ball from the previous throw
            if (b.getOwner() == p) { best = b; break; }
            if (tick - throwTick <= 3 && b.age <= 3 && b.getPos().squaredDistanceTo(throwEye) < 16) best = b;
        }
        if (best == null) return null;
        ballEntityId = best.getId();
        return CobblemonCompat.ballState(best);
    }

    /** Progress flag for the machine; read when building the next tick's sensors. */
    static boolean makingProgress() { return lastProgress; }

    // ----------------------------------------------------------------- AutoTrain (V1 logic, gated)

    private static void loadEvSpecies() {
        Path f = FabricLoader.getInstance().getConfigDir().resolve("rareradar-ev.txt");
        try {
            if (Files.exists(f)) {
                Set<String> out = new HashSet<>();
                for (String l : Files.readAllLines(f)) {
                    l = l.trim().toLowerCase(Locale.ROOT);
                    if (!l.isEmpty() && !l.startsWith("#")) out.add(l);
                }
                evSpecies = out;
            } else evSpecies = Set.of();
        } catch (Exception e) {
            RareRadarClient.LOG.warn("Could not read {}: {}", f, e.toString());
            evSpecies = Set.of();
        }
    }

    private static void tickTrain(MinecraftClient client, ClientPlayerEntity p) {
        var lim = Galaxy.get().cfg.safety;
        if (++trainTicks > lim.maxRunMinutes * 60 * 20) { stop(client, "maximum run time reached"); return; }
        if (handleBattle(client)) { Navigator.release(client); return; }
        if (client.currentScreen != null) { Navigator.release(client); return; }
        if (p.getHealth() < lim.minHealth) { stop(client, "low health"); return; }
        if (trainCooldown > 0) trainCooldown--;
        if (!leadOk()) { stop(client, "lead Pokemon is hurt or fainted - heal it"); return; }
        if (target == null || target.isRemoved() || !target.isAlive()) {
            target = bestTrainTarget(client);
            if (target == null) { Navigator.release(client); status = "train: waiting for a target"; return; }
            nav.plan(client, target.getBlockPos(), 3.0);
        }
        if (CobblemonCompat.isBattling(target)) { target = null; return; }
        double flat = Math.hypot(target.getX() - p.getX(), target.getZ() - p.getZ());
        boolean inRange = flat <= Settings.S.trainRange && p.canSee(target);
        status = "train → " + target.getPokemon().getSpecies().getName();
        if (!inRange) {
            if (nav.finished() || tick % 40 == 0) nav.plan(client, target.getBlockPos(), 3.0);
            nav.follow(client);
            return;
        }
        Navigator.release(client);
        Navigator.faceToward(p, aimPoint(target), 3.0f);
        if (trainCooldown == 0) {
            KeyBinding k = sendOutKey(client);
            if (k == null) { stop(client, "could not find Cobblemon's Throw Selected Pokemon key"); return; }
            KeyBinding.onKeyPressed(InputUtil.fromTranslationKey(k.getBoundKeyTranslationKey()));
            trainCooldown = 100;
        }
    }

    private static KeyBinding sendOutKey(MinecraftClient c) {
        for (KeyBinding k : c.options.allKeys) if (k.getTranslationKey().equals("key.cobblemon.throwpartypokemon")) return k;
        return null;
    }

    private static Pokemon lead() {
        for (Pokemon pk : CobblemonCompat.party()) if (!pk.isFainted()) return pk;
        return null;
    }

    private static boolean leadOk() {
        Pokemon pk = lead();
        return pk != null && pk.getCurrentHealth() >= pk.getMaxHealth() * (Settings.S.minLeadHpPercent / 100.0);
    }

    private static PokemonEntity bestTrainTarget(MinecraftClient client) {
        Pokemon pk = lead();
        int lvl = pk == null ? 1 : pk.getLevel();
        PokemonEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity e : client.world.getEntities()) {
            if (!(e instanceof PokemonEntity pe) || pe.getOwnerUuid() != null || CobblemonCompat.isBattling(pe)) continue;
            if (RadarScanner.color(pe.getId()) != -1) continue; // never battle rares, shinies, watchlist or hunt targets
            double dist = pe.distanceTo(client.player);
            if (dist > Settings.S.scanRange) continue;
            int l = pe.getPokemon().getLevel();
            boolean evHit = evSpecies.contains(pe.getPokemon().getSpecies().getName().toLowerCase(Locale.ROOT));
            if (!evHit && Math.abs(l - lvl) > Settings.S.levelWindow) continue;
            double score = dist - (evHit ? 40 : 0);
            if (score < bestScore) { bestScore = score; best = pe; }
        }
        return best;
    }

    /** Picks the strongest usable move each turn, only for battles while AutoTrain is active. */
    private static boolean handleBattle(MinecraftClient client) {
        ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
        if (battle == null || battle.getSpectating()) return false;
        if (battleDelay > 0) { battleDelay--; return true; }
        SingleActionRequest req = battle.getFirstUnansweredRequest();
        if (req == null) return true;
        if (req.getForceSwitch()) { stop(client, "lead fainted in battle"); return true; }
        InBattleMove bestMove = null;
        double bestScore = -1;
        for (InBattleMove m : req.getMoveSet().getMoves()) {
            if (!m.canBeUsed()) continue;
            MoveTemplate t = Moves.getByName(m.getId());
            double power = t == null ? 0 : t.getPower();
            double acc = t == null || t.getAccuracy() <= 0 ? 100 : t.getAccuracy();
            double score = power * acc / 100.0;
            if (bestMove == null || score > bestScore) { bestMove = m; bestScore = score; }
        }
        if (bestMove == null) { stop(client, "no usable moves"); return true; }
        String pnx = req.getActivePokemon().getOppositeOpponent() == null ? null : req.getActivePokemon().getOppositeOpponent().getPNX();
        req.setResponse(new MoveActionResponse(bestMove.getId(), pnx, null));
        battle.checkForFinishedChoosing();
        battleDelay = 25;
        return true;
    }
}
