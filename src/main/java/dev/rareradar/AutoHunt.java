package dev.rareradar;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.item.PokeBallItem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import net.minecraft.util.math.BlockPos;

import com.cobblemon.mod.common.battles.InBattleMove;
import com.cobblemon.mod.common.battles.MoveActionResponse;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.battle.ClientBattle;
import com.cobblemon.mod.common.client.battle.SingleActionRequest;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.List;

/** Walks toward the nearest rare Pokemon and throws balls at it. Toggle with the hunt key. */
public final class AutoHunt {
    public static boolean active = false;
    /** true = train mode (battle wild Pokemon for XP/EVs), false = hunt mode (catch rares). */
    public static boolean train = false;
    private static int battleDelay = 0;
    private static Set<String> evSpecies = Set.of();
    private static final int THROW_COOLDOWN = 50; // ticks between throws
    private static final float MAX_TURN = 7.0f;   // degrees per tick, keeps turning smooth
    private static final List<String> BALL_PREFERENCE =
            List.of("master_ball", "ultra_ball", "great_ball", "premier_ball", "poke_ball");

    private static PokemonEntity target;
    private static int cooldown = 0;
    private static int stuckTicks = 0;
    private static Vec3d lastPos = Vec3d.ZERO;
    private static List<BlockPos> path = List.of();
    private static int pathIdx = 0;
    private static int repathIn = 0;
    private static int noProgress = 0;
    private static BlockPos pathGoal = BlockPos.ORIGIN;

    private AutoHunt() {}

    public static void toggleTrain(MinecraftClient client) {
        if (!active) { train = true; loadEvSpecies(); } else if (!train) { stop(client, "switched mode"); train = true; loadEvSpecies(); }
        toggle(client);
    }

    private static void loadEvSpecies() {
        Path f = FabricLoader.getInstance().getConfigDir().resolve("rareradar-ev.txt");
        try {
            if (Files.exists(f)) {
                Set<String> out = new HashSet<>();
                for (String l : Files.readAllLines(f)) {
                    l = l.trim().toLowerCase();
                    if (!l.isEmpty() && !l.startsWith("#")) out.add(l);
                }
                evSpecies = out;
            } else {
                evSpecies = Set.of();
            }
        } catch (Exception e) {
            RareRadarClient.LOG.warn("Could not read {}: {}", f, e.toString());
            evSpecies = Set.of();
        }
        RareRadarClient.LOG.info("EV target species: {}", evSpecies.isEmpty() ? "(none, training any wild near your level)" : evSpecies);
    }

    public static void toggle(MinecraftClient client) {
        active = !active;
        if (!active && train) train = false;
        RareRadarClient.LOG.info("Auto {} {}", train ? "train" : "hunt", active ? "ON" : "OFF");
        if (!active) releaseKeys(client);
        target = null;
        if (client.player != null) {
            client.player.sendMessage(net.minecraft.text.Text.literal((train ? "Auto Train: " : "Auto Hunt: ") + (active ? "ON" : "OFF")), true);
        }
    }

    public static void stop(MinecraftClient client, String why) {
        active = false;
        train = false;
        RareRadarClient.LOG.info("Auto hunt/train stopped: {}", why);
        releaseKeys(client);
        target = null;
        if (client.player != null) {
            client.player.sendMessage(net.minecraft.text.Text.literal("Auto Hunt stopped: " + why), true);
        }
    }

    private static void releaseKeys(MinecraftClient c) {
        c.options.forwardKey.setPressed(false);
        c.options.jumpKey.setPressed(false);
        c.options.sprintKey.setPressed(false);
    }

    public static void tick(MinecraftClient client) {
        if (!active) return;
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null) { active = false; return; }
        if (train && handleBattle(client)) { releaseKeys(client); return; }
        if (client.currentScreen != null) { releaseKeys(client); return; } // pause in menus
        if (p.getHealth() < 8.0f) { stop(client, "low health"); return; }
        if (cooldown > 0) cooldown--;

        if (train && !leadOk()) { stop(client, "lead Pokemon is hurt or fainted - heal it"); return; }
        if (target == null || target.isRemoved() || !target.isAlive() || (!train && Rarity.of(target) == null)) {
            target = train ? bestTrainTarget(client) : nearestRare(client);
            if (target == null) { releaseKeys(client); return; } // idle, keep looking
            RareRadarClient.LOG.info("Target: {} ({}) at {} dist {}", target.getPokemon().getSpecies().getName(), train ? "lv" + target.getPokemon().getLevel() : Rarity.of(target).label, target.getBlockPos().toShortString(), (int) target.distanceTo(p));
        }
        if (target.isBattling()) { releaseKeys(client); return; } // someone else is fighting it

        Vec3d eye = p.getEyePos();
        Vec3d aim = target.getPos().add(0, target.getHeight() * 0.5, 0);
        double flat = Math.hypot(aim.x - p.getX(), aim.z - p.getZ());

        boolean inRange = flat <= (train ? Settings.S.trainRange : Settings.S.throwRange) && p.canSee(target);
        if (inRange) {
            releaseKeys(client);
            faceToward(p, eye, aim);
        } else if (!follow(client, p)) {
            return;
        }

        if (inRange && cooldown == 0 && train) {
            KeyBinding k = sendOutKey(client);
            if (k == null) { stop(client, "could not find Cobblemon's Throw Selected Pokemon key"); return; }
            RareRadarClient.LOG.info("Sending out lead at {}", target.getPokemon().getSpecies().getName());
            KeyBinding.onKeyPressed(InputUtil.fromTranslationKey(k.getBoundKeyTranslationKey()));
            cooldown = 100 + client.world.random.nextInt(40);
        } else if (inRange && cooldown == 0) {
            if (selectBall(client, p)) {
                RareRadarClient.LOG.info("Throwing ball at {}", target.getPokemon().getSpecies().getName());
                client.interactionManager.interactItem(p, Hand.MAIN_HAND);
                p.swingHand(Hand.MAIN_HAND);
                cooldown = THROW_COOLDOWN + client.world.random.nextInt(20);
            } else {
                stop(client, "no Poke Balls in hotbar");
            }
        }
    }

    private static PokemonEntity nearestRare(MinecraftClient client) {
        PokemonEntity best = null;
        double bestD = (double) Settings.S.scanRange * Settings.S.scanRange;
        for (Entity e : client.world.getEntities()) {
            if (e instanceof PokemonEntity pe && Rarity.of(pe) != null && !pe.isBattling()) {
                double d = pe.squaredDistanceTo(client.player);
                if (d < bestD) { bestD = d; best = pe; }
            }
        }
        return best;
    }

    private static void faceToward(ClientPlayerEntity p, Vec3d eye, Vec3d aim) {
        double dx = aim.x - eye.x, dy = aim.y - eye.y, dz = aim.z - eye.z;
        float wantYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        p.setYaw(p.getYaw() + MathHelper.clamp(MathHelper.wrapDegrees(wantYaw - p.getYaw()), -MAX_TURN, MAX_TURN));
        p.setPitch(p.getPitch() + MathHelper.clamp(wantPitch - p.getPitch(), -MAX_TURN, MAX_TURN));
    }

    private static KeyBinding sendOutKey(MinecraftClient c) {
        for (KeyBinding k : c.options.allKeys) if (k.getTranslationKey().equals("key.cobblemon.throwpartypokemon")) return k;
        return null;
    }

    private static Pokemon lead() {
        var party = CobblemonClient.INSTANCE.getStorage().getParty();
        for (Pokemon pk : party.getSlots()) if (pk != null && !pk.isFainted()) return pk;
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
            if (!(e instanceof PokemonEntity pe) || pe.getOwnerUuid() != null || pe.isBattling()) continue;
            int l = pe.getPokemon().getLevel();
            boolean evHit = evSpecies.contains(pe.getPokemon().getSpecies().getName().toLowerCase());
            if (!evSpecies.isEmpty() && !evHit && Math.abs(l - lvl) > Settings.S.levelWindow) continue;
            if (evSpecies.isEmpty() && Math.abs(l - lvl) > Settings.S.levelWindow) continue;
            double score = pe.distanceTo(client.player) - (evHit ? 40 : 0);
            if (pe.distanceTo(client.player) > Settings.S.scanRange) continue;
            if (score < bestScore) { bestScore = score; best = pe; }
        }
        return best;
    }

    /** Picks the strongest usable move each turn. Returns true while a battle is in progress. */
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
            MoveTemplate t = Moves.INSTANCE.getByName(m.getId());
            double power = t == null ? 0 : t.getPower();
            double acc = t == null || t.getAccuracy() <= 0 ? 100 : t.getAccuracy();
            double score = power * acc / 100.0;
            if (bestMove == null || score > bestScore) { bestMove = m; bestScore = score; }
        }
        if (bestMove == null) { stop(client, "no usable moves"); return true; }
        String pnx = req.getActivePokemon().getOppositeOpponent() == null ? null : req.getActivePokemon().getOppositeOpponent().getPNX();
        RareRadarClient.LOG.info("Battle: using {} (score {})", bestMove.getId(), (int) bestScore);
        req.setResponse(new MoveActionResponse(bestMove.getId(), pnx, null));
        battle.checkForFinishedChoosing();
        battleDelay = 25 + client.world.random.nextInt(25);
        return true;
    }

    /** Follow an A* path toward the target. Returns false if the hunt was aborted. */
    private static boolean follow(MinecraftClient client, ClientPlayerEntity p) {
        BlockPos goal = target.getBlockPos();
        if (--repathIn <= 0 || path.isEmpty() || goal.getSquaredDistance(pathGoal) > 16 || pathIdx >= path.size()) {
            path = Pathfinder.find(client.world, p.getBlockPos(), goal, 4.0);
            pathGoal = goal;
            pathIdx = Math.min(1, Math.max(0, path.size() - 1));
            RareRadarClient.LOG.info("Replanned path: {} nodes", path.size());
            repathIn = 40;
        }
        if (path.size() < 2) {
            releaseKeys(client);
            if (++noProgress > 200) { stop(client, "no route to the Pokemon"); return false; }
            return true;
        }
        BlockPos node = path.get(pathIdx);
        Vec3d c = Vec3d.ofBottomCenter(node);
        double dxz = Math.hypot(c.x - p.getX(), c.z - p.getZ());
        if (dxz < 0.45 && Math.abs(c.y - p.getY()) < 1.3) {
            if (pathIdx < path.size() - 1) pathIdx++;
            node = path.get(pathIdx);
            c = Vec3d.ofBottomCenter(node);
        }
        // look where we're walking (yaw only, level pitch)
        float wantYaw = (float) Math.toDegrees(Math.atan2(-(c.x - p.getX()), c.z - p.getZ()));
        p.setYaw(p.getYaw() + MathHelper.clamp(MathHelper.wrapDegrees(wantYaw - p.getYaw()), -MAX_TURN * 1.5f, MAX_TURN * 1.5f));
        p.setPitch(p.getPitch() + MathHelper.clamp(-p.getPitch(), -MAX_TURN, MAX_TURN));

        boolean facing = Math.abs(MathHelper.wrapDegrees(wantYaw - p.getYaw())) < 35;
        client.options.forwardKey.setPressed(facing);
        client.options.sprintKey.setPressed(facing && dxz > 1.5);

        Vec3d now = p.getPos();
        stuckTicks = now.squaredDistanceTo(lastPos) < 0.0004 ? stuckTicks + 1 : 0;
        lastPos = now;
        boolean needUp = c.y > p.getY() + 0.1 && dxz < 1.6;
        client.options.jumpKey.setPressed((needUp && p.isOnGround()) || p.isTouchingWater() || (p.horizontalCollision && p.isOnGround()));
        if (stuckTicks > 30) { repathIn = 0; }
        if (stuckTicks > 160) { stop(client, "got stuck"); return false; }
        return true;
    }

    private static boolean selectBall(MinecraftClient client, ClientPlayerEntity p) {
        int bestSlot = -1, bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.getItem() instanceof PokeBallItem ball) {
                int rank = BALL_PREFERENCE.indexOf(ball.getPokeBall().getName().getPath());
                if (rank < 0) rank = BALL_PREFERENCE.size();
                if (rank < bestRank) { bestRank = rank; bestSlot = i; }
            }
        }
        if (bestSlot < 0) return false;
        p.getInventory().selectedSlot = bestSlot;
        return true;
    }
}
