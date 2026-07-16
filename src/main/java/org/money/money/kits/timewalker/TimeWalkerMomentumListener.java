package org.money.money.kits.timewalker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.money.money.meta.ClassRegistry;
import org.money.money.session.KitResettable;
import org.money.money.session.KitSession;

import java.util.*;

/**
 * TimeWalker — Ability 3: Momentum Drive / Разгон времени (toggle perk).
 *
 * <p>RMB the Momentum item toggles the perk. While ON, moving in a single straight
 * direction ramps up a Speed effect (level 1..maxLevel). Stopping, turning sharply,
 * dying, going spectator or leaving to the lobby resets/disables it.
 *
 * <p>At level &gt;= {@code hulkLevel} (5) she enters the "Hulk" state: crashing into a wall
 * smashes the block(s) ahead and costs 2 levels instead of a full stop. At level
 * &gt;= {@code knockbackImmuneLevel} (8) she gains full knockback resistance until she drops below it.
 */
public final class TimeWalkerMomentumListener implements Listener, KitResettable {

    // ===== Shared scoreboard tag (interop with the other TimeWalker abilities) =====
    private static final String TAG_MOMENTUM = "TimeWalkerMomentum";

    // ===== Global task period (ticks) =====
    private static final long TASK_PERIOD_TICKS = 2L;

    // ===== Speed effect refresh window (ticks) — comfortably longer than the period =====
    private static final int SPEED_REFRESH_TICKS = 40;

    // ===== Black "evil" footprint particle =====
    private static final Particle.DustOptions FOOTSTEP_DUST = new Particle.DustOptions(Color.fromRGB(8, 8, 8), 1.4f);

    private final Plugin plugin;

    // Item marker
    private final NamespacedKey KEY_MOMENTUM_ITEM;

    // Active set (players with the perk toggled ON)
    private final Set<UUID> active = new HashSet<>();

    // Per-player ramp state
    private final Map<UUID, Vector> lockedDirection = new HashMap<>();
    private final Map<UUID, Double> accumulatorTicks = new HashMap<>();
    private final Map<UUID, Integer> currentLevel = new HashMap<>();
    private final Map<UUID, Location> lastLocation = new HashMap<>();
    // Last server tick a hoofbeat sound played (throttles the galloping FX per player).
    private final Map<UUID, Long> lastStepTick = new HashMap<>();
    // Active foot (false=right, true=left) for alternating footprints.
    private final Map<UUID, Boolean> stepFoot = new HashMap<>();
    // Players currently at the max "Hulk" state with full knockback resistance applied.
    private final Set<UUID> knockbackImmune = new HashSet<>();

    // Single global repeating task
    private BukkitTask globalTask;

    public TimeWalkerMomentumListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_MOMENTUM_ITEM = new NamespacedKey(plugin, "timewalker_momentum");

        startGlobalTask();
    }

    /* ===================== Registry reads (use-time, hot-reloadable) ===================== */

    private static int maxLevel() {
        return Math.max(1, ClassRegistry.numInt("timewalker", "momentum", "maxLevel", 8));
    }

    private static double secondsPerLevel() {
        return Math.max(0.05, ClassRegistry.num("timewalker", "momentum", "secondsPerLevel", 0.9));
    }

    /** Level at which the "Hulk" state begins (can smash walls she crashes into). */
    private static int hulkLevel() {
        return Math.max(1, ClassRegistry.numInt("timewalker", "momentum", "hulkLevel", 5));
    }

    /** Level at which she becomes immune to knockback until she drops below it. */
    private static int knockbackImmuneLevel() {
        return Math.max(1, ClassRegistry.numInt("timewalker", "momentum", "knockbackImmuneLevel", 8));
    }

    /* ===================== Item ===================== */

    /** Create the Momentum Drive toggle item. */
    public ItemStack makeMomentumItem() {
        ItemStack it = new ItemStack(Material.SUGAR);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Momentum Drive", NamedTextColor.AQUA));
        im.getPersistentDataContainer().set(KEY_MOMENTUM_ITEM, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(im);
        return it;
    }

    private boolean isMomentumItem(ItemStack it) {
        if (it == null || it.getType() != Material.SUGAR || !it.hasItemMeta()) return false;
        ItemMeta im = it.getItemMeta();
        if (im.getPersistentDataContainer().has(KEY_MOMENTUM_ITEM, PersistentDataType.BYTE)) return true;
        return Component.text("Momentum Drive", NamedTextColor.AQUA).equals(im.displayName());
    }

    /* ===================== Toggle ===================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;

        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        if (!isMomentumItem(p.getInventory().getItemInMainHand())) return;

        e.setCancelled(true);

        UUID id = p.getUniqueId();
        if (active.contains(id)) {
            disable(p, true);
        } else {
            enable(p);
        }
    }

    private void enable(Player p) {
        UUID id = p.getUniqueId();
        active.add(id);
        p.addScoreboardTag(TAG_MOMENTUM);

        // Start tracking fresh
        accumulatorTicks.put(id, 0.0);
        currentLevel.put(id, 0);
        lockedDirection.remove(id);
        lastLocation.put(id, p.getLocation().clone());

        p.playSound(p.getLocation(), Sound.ENTITY_HORSE_GALLOP, 0.9f, 1.0f);
        p.sendMessage(Component.text("Momentum Drive: ON", NamedTextColor.AQUA));
    }

    /** Fully disable the perk for a player and clear all per-player state. Idempotent. */
    private void disable(Player p, boolean announce) {
        if (p == null) return;

        UUID id = p.getUniqueId();
        boolean wasActive = active.remove(id);

        clearState(id);

        p.removeScoreboardTag(TAG_MOMENTUM);
        p.removePotionEffect(PotionEffectType.SPEED);
        setKnockbackImmune(p, false);
        if (wasActive && announce && p.isOnline()) {
            p.playSound(p.getLocation(), Sound.ENTITY_HORSE_STEP, 0.8f, 0.8f);
            p.sendMessage(Component.text("Momentum Drive: OFF", NamedTextColor.GRAY));
        }
    }

    /** Reset the ramp (stopped / sharp turn) but keep the perk toggled ON. */
    private void resetRamp(Player p, boolean playBreakFx) {
        UUID id = p.getUniqueId();
        accumulatorTicks.put(id, 0.0);
        currentLevel.put(id, 0);
        lockedDirection.remove(id);
        p.removePotionEffect(PotionEffectType.SPEED);
        setKnockbackImmune(p, false);

        if (playBreakFx) {
            p.playSound(p.getLocation(), Sound.ENTITY_HORSE_STEP_WOOD, 0.7f, 0.7f);
            p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation().add(0, 0.3, 0), 6, 0.2, 0.1, 0.2, 0.01);
        }
    }

    private void clearState(UUID id) {
        lockedDirection.remove(id);
        accumulatorTicks.remove(id);
        currentLevel.remove(id);
        lastLocation.remove(id);
        lastStepTick.remove(id);
        stepFoot.remove(id);
        knockbackImmune.remove(id);
    }

    /* ===================== Global ramp task ===================== */

    private void startGlobalTask() {
        if (globalTask != null) return;
        globalTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TASK_PERIOD_TICKS, TASK_PERIOD_TICKS);
    }

    /** Cancel the global repeating task (e.g. from Main.onDisable). */
    public void stop() {
        if (globalTask != null) {
            globalTask.cancel();
            globalTask = null;
        }
    }

    private void tick() {
        if (active.isEmpty()) return;

        // Iterate over a snapshot so we can disable players safely while looping.
        for (UUID id : new ArrayList<>(active)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) {
                // Offline -> drop from active, clear state (tag/effect die with the player).
                active.remove(id);
                clearState(id);
                continue;
            }

            // Auto-disable conditions
            if (p.getGameMode() == GameMode.SPECTATOR || !KitSession.isInGame(p)) {
                disable(p, false);
                continue;
            }

            updateMomentum(p);
        }
    }

    private void updateMomentum(Player p) {
        UUID id = p.getUniqueId();
        Location now = p.getLocation();
        Location prev = lastLocation.get(id);
        lastLocation.put(id, now.clone());

        if (prev == null || prev.getWorld() == null || now.getWorld() == null
                || !prev.getWorld().equals(now.getWorld())) {
            // No reliable delta this tick (teleport / world change) -> just reset ramp quietly.
            resetRamp(p, false);
            return;
        }

        double dx = now.getX() - prev.getX();
        double dz = now.getZ() - prev.getZ();
        double horizPerTick = Math.sqrt(dx * dx + dz * dz) / TASK_PERIOD_TICKS;

        // Balance numbers read at use time so /warriors reload applies without restart.
        double minMoveSpeed = ClassRegistry.num("timewalker", "momentum", "minMoveSpeed", 0.04);
        double turnResetDegrees = ClassRegistry.num("timewalker", "momentum", "turnResetDegrees", 60.0);

        if (horizPerTick < minMoveSpeed) {
            int lvl = currentLevel.getOrDefault(id, 0);
            // Hulk state: crashing into a wall smashes it (costs 2 levels) instead of a full stop.
            // lockedDirection still holds the run heading at the impact tick.
            if (lvl >= hulkLevel()) {
                Vector run = lockedDirection.get(id);
                if (run != null && breakWallAhead(p, run)) {
                    hulkSmashFx(p);
                    reduceLevel(p, 2);
                    return; // smashed through — keep the reduced momentum, don't full-reset
                }
            }
            // Stopped -> reset (only play FX if we actually had momentum built up).
            if (lvl >= 1 || lockedDirection.containsKey(id)) {
                resetRamp(p, true);
            }
            return;
        }

        Vector moveDir = new Vector(dx, 0, dz);
        if (moveDir.lengthSquared() < 1e-9) {
            return;
        }
        moveDir.normalize();

        Vector locked = lockedDirection.get(id);
        if (locked == null) {
            lockedDirection.put(id, moveDir.clone());
        } else {
            double dot = Math.max(-1.0, Math.min(1.0, locked.dot(moveDir)));
            double angleDeg = Math.toDegrees(Math.acos(dot));
            if (angleDeg > turnResetDegrees) {
                // Sharp turn (also covers moving backward/sideways) -> reset, then re-lock.
                resetRamp(p, true);
                lockedDirection.put(id, moveDir.clone());
                return;
            }
        }

        // Aligned: accumulate and recompute level.
        double acc = accumulatorTicks.getOrDefault(id, 0.0) + TASK_PERIOD_TICKS;
        accumulatorTicks.put(id, acc);

        double accSeconds = acc / 20.0;
        int level = Math.min(maxLevel(), 1 + (int) Math.floor(accSeconds / secondsPerLevel()));
        int prevLevel = currentLevel.getOrDefault(id, 0);
        currentLevel.put(id, level);

        if (level > prevLevel) momentumMilestone(p, prevLevel, level);

        // Hulk max-state: full knockback immunity at/above the threshold, off below it.
        setKnockbackImmune(p, level >= knockbackImmuneLevel());

        if (level >= 1) {
            int amp = level - 1; // level 1 -> Speed I (amp 0); level 8 -> Speed VIII (amp 7)
            p.addPotionEffect(new PotionEffect(
                    PotionEffectType.SPEED, SPEED_REFRESH_TICKS, amp, false, false, false));

            stepFx(p, id, level);
            p.sendActionBar(Component.text("Momentum: " + roman(level), NamedTextColor.AQUA));
        }
    }

    /**
     * One footstep while ramping: a throttled horse "hoofbeat" (cadence + pitch rise with the
     * level, walk -> gallop) plus a black "evil" footprint stamped on the ground, alternating
     * left/right feet, with a wisp of dark smoke.
     */
    private void stepFx(Player p, UUID id, int level) {
        long nowTick = Bukkit.getCurrentTick();
        long last = lastStepTick.getOrDefault(id, 0L);
        int interval = Math.max(3, 9 - level); // higher level -> faster cadence
        if (nowTick - last < interval) return;
        lastStepTick.put(id, nowTick);

        boolean left = stepFoot.getOrDefault(id, false);
        stepFoot.put(id, !left);

        float pitch = 0.9f + level * 0.04f;
        p.playSound(p.getLocation(), Sound.ENTITY_HORSE_GALLOP, 0.55f, pitch);

        // Direction along the run (fall back to facing).
        Vector dir = lockedDirection.get(id);
        if (dir == null) dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) return;
        dir = dir.clone().normalize();

        // Stamp a black footprint (heel + toe) offset to the active foot, at ground level.
        Vector right = new Vector(-dir.getZ(), 0, dir.getX());
        double side = left ? -0.22 : 0.22;
        World w = p.getWorld();
        Location foot = p.getLocation().clone().add(right.multiply(side)).add(0, 0.05, 0);
        Location heel = foot.clone().subtract(dir.clone().multiply(0.12));
        Location toe = foot.clone().add(dir.clone().multiply(0.12));
        w.spawnParticle(Particle.DUST, heel, 2, 0.04, 0.0, 0.04, 0.0, FOOTSTEP_DUST);
        w.spawnParticle(Particle.DUST, toe, 2, 0.04, 0.0, 0.04, 0.0, FOOTSTEP_DUST);
        w.spawnParticle(Particle.SMOKE, foot.clone().add(0, 0.05, 0),
                Math.min(3, 1 + level / 3), 0.05, 0.02, 0.05, 0.005);
    }

    /**
     * Satisfying "picking up speed" cue when the ramp crosses level 5 and level 8 (max) —
     * like flooring the gas: a rising level-up chime + a wind-burst whoosh.
     */
    private void momentumMilestone(Player p, int prevLevel, int level) {
        if (prevLevel < 5 && level >= 5) {
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
            p.playSound(p.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 0.9f, 1.2f);
        }
        if (prevLevel < 8 && level >= 8) {
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 2.0f);
            p.playSound(p.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 1.0f, 1.7f);
            p.getWorld().spawnParticle(Particle.FLASH, p.getLocation().add(0, 1.0, 0), 1, 0, 0, 0, 0);
        }
    }

    private String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            default -> {
                // Fallback for configs with max-level > 6.
                if (n <= 0) yield "";
                StringBuilder sb = new StringBuilder();
                int[] vals = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
                String[] sym = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
                int v = n;
                for (int i = 0; i < vals.length; i++) {
                    while (v >= vals[i]) { sb.append(sym[i]); v -= vals[i]; }
                }
                yield sb.toString();
            }
        };
    }

    /* ===================== Fall: softer landing -> momentum spike ===================== */

    /**
     * While Momentum Drive is ON: reduce fall damage and convert the impact into momentum —
     * the harder the fall, the bigger the speed spike at the moment of landing.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onFallDamage(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(e.getEntity() instanceof Player p)) return;
        UUID id = p.getUniqueId();
        if (!active.contains(id)) return;

        double original = e.getDamage();
        // Balance numbers read at use time so /warriors reload applies without restart.
        // Soften the landing (x0.5 by default).
        e.setDamage(original * ClassRegistry.num("timewalker", "momentum", "fallDamageMultiplier", 0.5));

        // Bigger fall -> more momentum.
        double acc = accumulatorTicks.getOrDefault(id, 0.0)
                + original * ClassRegistry.num("timewalker", "momentum", "fallMomentumTicksPerDamage", 4.0);
        accumulatorTicks.put(id, acc);

        // Prime the direction lock to current facing so continuing the run keeps the gained speed.
        Vector face = p.getLocation().getDirection().setY(0);
        if (face.lengthSquared() > 1e-6) lockedDirection.put(id, face.normalize());

        // Apply the new level instantly.
        int level = Math.min(maxLevel(), 1 + (int) Math.floor((acc / 20.0) / secondsPerLevel()));
        currentLevel.put(id, level);
        if (level >= 1) {
            p.addPotionEffect(new PotionEffect(
                    PotionEffectType.SPEED, SPEED_REFRESH_TICKS, level - 1, false, false, false));
            // Impact -> speed-boost cue: heavy landing thud + a wind-burst whoosh.
            p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.5f, 1.6f);
            p.playSound(p.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 0.9f, 1.1f);
            p.getWorld().spawnParticle(Particle.SMOKE, p.getLocation().add(0, 0.1, 0), 14, 0.35, 0.05, 0.35, 0.02);
            p.sendActionBar(Component.text("Momentum: " + roman(level), NamedTextColor.AQUA));
        }
    }

    /* ===================== Hulk state (level >= hulkLevel) ===================== */

    /**
     * Toggle full knockback resistance for the max "Hulk" state via the KNOCKBACK_RESISTANCE
     * attribute (1.0 = no knockback from hits, 0.0 = default). Turning OFF always clears the
     * attribute even if our set already forgot the player, so the boosted value can never leak
     * into the player's NBT after stop/turn/death/quit/reset.
     */
    private void setKnockbackImmune(Player p, boolean on) {
        if (p == null) return;
        UUID id = p.getUniqueId();
        AttributeInstance attr = p.getAttribute(Attribute.KNOCKBACK_RESISTANCE);

        if (on) {
            if (attr != null) attr.setBaseValue(1.0);
            if (knockbackImmune.add(id)) {
                p.playSound(p.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.9f, 0.5f);
                p.getWorld().spawnParticle(Particle.CRIT, p.getLocation().add(0, 1, 0), 20, 0.4, 0.6, 0.4, 0.1);
            }
        } else {
            if (attr != null && attr.getBaseValue() != 0.0) attr.setBaseValue(0.0);
            knockbackImmune.remove(id);
        }
    }

    /** Drop the momentum level by {@code amount} (min 0), realign the accumulator, refresh speed + immunity. */
    private void reduceLevel(Player p, int amount) {
        UUID id = p.getUniqueId();
        int newLvl = Math.max(0, currentLevel.getOrDefault(id, 0) - amount);
        currentLevel.put(id, newLvl);

        // Re-seat the accumulator at the start of the new level band so speed must be rebuilt.
        accumulatorTicks.put(id, newLvl <= 0 ? 0.0 : (newLvl - 1) * secondsPerLevel() * 20.0);

        if (newLvl >= 1) {
            p.addPotionEffect(new PotionEffect(
                    PotionEffectType.SPEED, SPEED_REFRESH_TICKS, newLvl - 1, false, false, false));
            p.sendActionBar(Component.text("Momentum: " + roman(newLvl), NamedTextColor.AQUA));
        } else {
            p.removePotionEffect(PotionEffectType.SPEED);
        }
        setKnockbackImmune(p, newLvl >= knockbackImmuneLevel());
    }

    /** FX when the Hulk smashes through a wall. */
    private void hulkSmashFx(Player p) {
        p.playSound(p.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 0.7f, 1.4f);
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.5f, 0.8f);
        p.getWorld().spawnParticle(Particle.EXPLOSION, p.getLocation().add(0, 1, 0), 1, 0, 0, 0, 0);
    }

    /**
     * Break the (up to 2-tall) wall block directly ahead of the player, snapping to the dominant
     * cardinal axis of {@code runDir}. Returns true if any block was destroyed.
     */
    private boolean breakWallAhead(Player p, Vector runDir) {
        double ax = Math.abs(runDir.getX());
        double az = Math.abs(runDir.getZ());
        int dx = 0, dz = 0;
        if (ax >= az) dx = runDir.getX() >= 0 ? 1 : -1;
        else          dz = runDir.getZ() >= 0 ? 1 : -1;
        if (dx == 0 && dz == 0) return false;

        Block feet = p.getLocation().getBlock().getRelative(dx, 0, dz);
        Block head = feet.getRelative(0, 1, 0);
        boolean broke = smashBlock(feet);
        broke |= smashBlock(head);
        return broke;
    }

    /** Destroy one wall block (no drops) if it is solid, breakable and not protected. */
    private boolean smashBlock(Block b) {
        Material t = b.getType();
        if (t.isAir() || b.isLiquid() || !t.isSolid()) return false;
        if (t.getHardness() < 0) return false;      // bedrock / barrier — unbreakable
        if (isProtectedBlock(t)) return false;

        World w = b.getWorld();
        w.spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 20, 0.3, 0.3, 0.3, 0.0, b.getBlockData());
        w.playSound(b.getLocation(), Sound.BLOCK_STONE_BREAK, 1.0f, 0.7f);
        b.setType(Material.AIR, false); // no drops, no physics
        return true;
    }

    private static boolean isProtectedBlock(Material t) {
        if (t == Material.BEDROCK || t == Material.BARRIER) return true;
        if (t == Material.END_PORTAL || t == Material.END_PORTAL_FRAME) return true;
        if (t == Material.REINFORCED_DEEPSLATE) return true;
        String n = t.name();
        return n.endsWith("COMMAND_BLOCK") || n.contains("STRUCTURE") || n.equals("JIGSAW");
    }

    /* ===================== Cleanup / disable triggers ===================== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        disable(e.getPlayer(), false);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        disable(e.getEntity(), false);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onGameModeChange(PlayerGameModeChangeEvent e) {
        if (e.getNewGameMode() == GameMode.SPECTATOR) {
            disable(e.getPlayer(), false);
        }
    }

    /** End of game / entering lobby / /warriors reset — force OFF and clear everything. */
    @Override
    public void resetPlayer(Player player) {
        if (player == null) return;
        disable(player, false);
    }
}
