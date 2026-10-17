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
    private static int ballEntityId = -1;
    private static long throwTick, tick;
    private static Vec3d throwEye = Vec3d.ZERO;
    private static String status = "";

    // Train mode state (V1 behaviour, now gated and time-limited)
    private static int battleDelay, trainCooldown, trainTicks;
    private static Set<String> evSpecies = Set.of();

    private AutoHunt() {}

    public static String status() { return status; }
    public static AutoHuntMachine machine() { return machine; }

    private static EnvironmentGate.Decision gate(MinecraftClient c) {
        boolean single = c.isInSingleplayer();
        String addr = c.getCurrentServerEntry() == null ? null : c.getCurrentServerEntry().address;
        return EnvironmentGate.check(single, addr, Galaxy.get().cfg.privateServerAllowlist);
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
        EnvironmentGate.Decision d = gate(client);
        if (!d.allowed()) {
            train = false;
            client.player.sendMessage(Text.literal("§cAuto " + (train ? "Train" : "Hunt") + " refused: " + d.reason()), false);
            RareRadarClient.LOG.info("Automation refused: {}", d.reason());
            return;
        }
        active = true;
        trainTicks = 0;
        machine = new AutoHuntMachine(Galaxy.get().cfg.safety);
        machine.start(System.currentTimeMillis());
        target = null;
        ballEntityId = -1;
        RareRadarClient.LOG.info("Auto {} ON ({})", train ? "train" : "hunt", d.reason());
        client.player.sendMessage(Text.literal((train ? "Auto Train" : "Auto Hunt") + ": ON — " + d.reason()), true);
    }

    /** Stops everything and releases keys. Safe to call at any time (disconnect, world change, emergency key). */
    public static void stop(MinecraftClient client, String why) {
        boolean was = active;
        active = false;
        train = false;
        target = null;
        ballEntityId = -1;
        if (machine != null && machine.running()) machine.abort(System.currentTimeMillis(), why);
        Navigator.release(client);
        status = "stopped: " + why;
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
        if (train) { tickTrain(client, p); return; }

        Sensors s = sense(client, p);
        Command cmd = machine.tick(s);
        status = machine.state() + (target != null ? " → " + target.getPokemon().getSpecies().getName() : "")
                + " · balls " + machine.ballsThisRun() + "/" + Galaxy.get().cfg.safety.maxBallsPerRun
                + " · caught " + machine.captures();
        execute(client, p, cmd);
        if (machine.state() == HuntState.VERIFY_CAPTURE) onOutcome(client, machine.lastOutcome());
        if (!machine.running()) stop(client, machine.stopReason().isEmpty() ? "finished" : machine.stopReason());
    }

    private static Sensors sense(MinecraftClient c, ClientPlayerEntity p) {
        Galaxy g = Galaxy.get();
        Sensors s = new Sensors();
        s.nowMillis = System.currentTimeMillis();
        s.worldPresent = c.world != null;
        s.screenOpen = c.currentScreen != null;
        s.health = p.getHealth();
        for (RadarScanner.Hit h : RadarScanner.hits()) {
            if (h.obs.owned || CobblemonCompat.isBattling(h.entity) || h.entity.isRemoved()) continue;
            s.bestCandidateId = (long) h.entity.getId();
            s.bestCandidateScore = h.rarity.score();
            break;
        }
        Long tid = machine.target();
        if (tid != null) {
            Entity e = c.world.getEntityById(tid.intValue());
            target = e instanceof PokemonEntity pe && !pe.isRemoved() && pe.isAlive() ? pe : null;
        } else target = null;
        if (target != null) {
            s.targetPresent = true;
            s.targetInBattle = CobblemonCompat.isBattling(target);
            double flat = Math.hypot(target.getX() - p.getX(), target.getZ() - p.getZ());
            s.targetInThrowRange = flat <= Settings.S.throwRange && p.canSee(target);
            s.aimed = aimError(p, aimPoint(target)) < 3.0;
        }
        s.pathFinished = nav.finished();
        s.makingProgress = lastProgress;
        s.ballSlot = chooseBall(p, s.bestCandidateScore);
        s.ownBallState = trackBall(c, p);
        return s;
    }

    private static void execute(MinecraftClient c, ClientPlayerEntity p, Command cmd) {
        switch (cmd.kind()) {
            case RELEASE_KEYS, NONE -> Navigator.release(c);
            case PLAN_PATH -> {
                Navigator.release(c);
                if (target != null) nav.plan(c, target.getBlockPos(), 4.0);
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
                ballEntityId = -1;
                Galaxy.get().sessions.ballThrown();
                RareRadarClient.LOG.info("Threw {} at {}", ballName(p.getInventory().getStack(cmd.slot())), target == null ? "?" : target.getPokemon().getSpecies().getName());
            }
            case STOP -> Navigator.release(c);
        }
    }

    private static boolean lastProgress = true;

    private static void onOutcome(MinecraftClient c, CaptureOutcome o) {
        if (o == null) return;
        RareRadarClient.LOG.info("Throw outcome: {}", o.label);
        if (o == CaptureOutcome.CAPTURED) Galaxy.get().sessions.caught();
        if (c.player != null) c.player.sendMessage(Text.literal("Auto Hunt: " + o.label), true);
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
