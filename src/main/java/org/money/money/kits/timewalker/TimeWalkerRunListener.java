package org.money.money.kits.timewalker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.money.money.session.KitResettable;
import org.money.money.session.KitSession;

import java.util.*;

/**
 * TimeWalker ABILITY 1 — Future Run / Пробег по будущему.
 *
 * <p>RMB the Future Run clock: dash forward at high speed for ~1s, then continue moving
 * with a mild speed boost; at the end of the window the player is rewound (teleported)
 * back to the saved start location. Lots of safety guards so nobody clips into walls,
 * teleports across worlds, or gets pulled back after dying / entering the lobby.
 */
public final class TimeWalkerRunListener implements Listener, KitResettable {

    private static final String TAG_FUTURE_RUN = "TimeWalkerFutureRun";
    // External-game flag: while running in the future she cannot pick up the flag. Added on
    // activation, removed on rewind AND on every other end-path (death/quit/lobby/reset/abort).
    private static final String TAG_CANT_PICKUP_FLAG = "cantpickupflag";

    private final Plugin plugin;

    private final NamespacedKey KEY_FUTURE_RUN;

    // ===== Config tunables (read once in constructor) =====
    private final int cooldownSeconds;
    private final int dashTicks;
    private final int totalTicks;
    private final int dashSpeedLevel;

    // cooldown stores last use time ms
    private final Map<UUID, Long> lastUse = new HashMap<>();

    // actionbar timer tasks to avoid stacking multiple timers
    private final Map<UUID, BukkitTask> cdTasks = new HashMap<>();

    // ===== Per-player ability state =====
    private final Map<UUID, Location> startLoc = new HashMap<>();
    private final Map<UUID, UUID> startWorld = new HashMap<>();
    private final Map<UUID, BukkitTask> dashTasks = new HashMap<>();
    private final Map<UUID, BukkitTask> warnTasks = new HashMap<>();
    private final Map<UUID, BukkitTask> returnTasks = new HashMap<>();

    public TimeWalkerRunListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_FUTURE_RUN = new NamespacedKey(plugin, "timewalker_future_run");

        this.cooldownSeconds = plugin.getConfig().getInt("timewalker.run.cooldown-seconds", 45);
        this.dashTicks = plugin.getConfig().getInt("timewalker.run.dash-ticks", 20);
        this.totalTicks = plugin.getConfig().getInt("timewalker.run.total-ticks", 160);
        this.dashSpeedLevel = Math.max(1, plugin.getConfig().getInt("timewalker.run.dash-speed-level", 60));
    }

    /* ================== Item ================== */

    /** Create the Future Run item. */
    public ItemStack makeFutureRunItem() {
        ItemStack it = new ItemStack(Material.CLOCK);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Future Run", NamedTextColor.AQUA));
        im.getPersistentDataContainer().set(KEY_FUTURE_RUN, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(im);
        return it;
    }

    private boolean isFutureRunItem(ItemStack it) {
        if (it == null || it.getType() != Material.CLOCK || !it.hasItemMeta()) return false;
        ItemMeta im = it.getItemMeta();
        if (im.getPersistentDataContainer().has(KEY_FUTURE_RUN, PersistentDataType.BYTE)) return true;
        return Component.text("Future Run", NamedTextColor.AQUA).equals(im.displayName());
    }

    /* ================== Interact ================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;

        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        if (!isFutureRunItem(p.getInventory().getItemInMainHand())) return;

        e.setCancelled(true);

        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        long cooldownMs = cooldownSeconds * 1000L;

        if (lastUse.containsKey(id)) {
            long last = lastUse.get(id);
            long passed = now - last;
            if (passed < cooldownMs) {
                long secLeft = (cooldownMs - passed + 999) / 1000;
                p.sendActionBar(Component.text(secLeft + " sec", NamedTextColor.RED));
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.8f);
                return;
            }
        }

        // already running -> ignore (don't stack windows)
        if (startLoc.containsKey(id)) {
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.7f);
            return;
        }

        // start cooldown
        lastUse.put(id, now);
        startCooldownTimer(p, cooldownSeconds);

        // activate ability
        activateFutureRun(p);
    }

    private void startCooldownTimer(Player player, int seconds) {
        UUID id = player.getUniqueId();

        // cancel previous timer if exists
        BukkitTask prev = cdTasks.remove(id);
        if (prev != null) prev.cancel();

        BukkitTask task = new BukkitRunnable() {
            int timeLeft = seconds;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }

                if (timeLeft <= 0) {
                    player.sendActionBar(Component.text("Ready", NamedTextColor.GREEN));
                    cancel();
                    return;
                }

                player.sendActionBar(Component.text(timeLeft + " sec", NamedTextColor.YELLOW));
                timeLeft--;
            }
        }.runTaskTimer(plugin, 0L, 20L);

        cdTasks.put(id, task);
    }

    /* ================== Ability ================== */

    private void activateFutureRun(Player caster) {
        UUID id = caster.getUniqueId();
        World w = caster.getWorld();

        // Snapshot start state (preserve yaw/pitch). Do NOT snapshot health.
        Location snapshot = caster.getLocation().clone();
        startLoc.put(id, snapshot);
        startWorld.put(id, w.getUID());
        caster.addScoreboardTag(TAG_FUTURE_RUN);
        caster.addScoreboardTag(TAG_CANT_PICKUP_FLAG);

        w.playSound(caster.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.9f, 1.6f);
        caster.sendActionBar(Component.text("Future Run", NamedTextColor.AQUA));

        // "Рывок" = очень большая скорость на ~1с (Speed ~25 = почти телепорт). Игрок бежит сам —
        // без velocity-толчка, поэтому в стены не вклинивается. После рывка эффект просто спадает.
        caster.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, dashTicks, dashSpeedLevel - 1, false, false, false));

        final Particle.DustOptions trailDust = new Particle.DustOptions(Color.fromRGB(60, 180, 255), 1.0f);

        // ===== Dash phase: afterimage trail only (movement is the player's own). =====
        BukkitTask dash = new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                Player online = Bukkit.getPlayer(id);
                if (online == null || !online.isOnline() || online.isDead()
                        || !startLoc.containsKey(id) || ticks >= dashTicks) {
                    cancel();
                    dashTasks.remove(id);
                    return;
                }

                Vector dir = online.getEyeLocation().getDirection();
                dir.setY(0);
                if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 0, 1); else dir.normalize();

                Location trail = online.getLocation().add(0, 0.9, 0).subtract(dir.clone().multiply(0.6));
                online.getWorld().spawnParticle(Particle.DUST, trail, 4, 0.18, 0.25, 0.18, 0.0, trailDust);
                online.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, trail, 4, 0.18, 0.25, 0.18, 0.01);

                ticks++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
        dashTasks.put(id, dash);

        // ===== Warning effect ~0.5s before return =====
        long warnAt = Math.max(0L, totalTicks - 10L);
        BukkitTask warn = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            warnTasks.remove(id);
            Player online = Bukkit.getPlayer(id);
            if (online == null || !online.isOnline() || online.isDead()) return;
            if (!startLoc.containsKey(id)) return;

            online.sendActionBar(Component.text("Rewinding...", NamedTextColor.LIGHT_PURPLE));
            online.playSound(online.getLocation(), Sound.BLOCK_BEACON_AMBIENT, 0.8f, 0.6f);
            spawnRing(online.getLocation().add(0, 1.0, 0), 1.2, 14);
        }, warnAt);
        warnTasks.put(id, warn);

        // ===== Return phase at window end =====
        BukkitTask ret = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            returnTasks.remove(id);
            doReturn(id);
        }, totalTicks);
        returnTasks.put(id, ret);
    }

    /** Perform the rewind teleport with all the safety guards, then clear state. */
    private void doReturn(UUID id) {
        Location saved = startLoc.get(id);
        UUID savedWorldId = startWorld.get(id);

        Player p = Bukkit.getPlayer(id);
        // Offline -> nothing to teleport, just clear.
        if (p == null || !p.isOnline()) {
            clearState(id);
            return;
        }
        // Spectator -> don't yank them around.
        if (p.getGameMode() == GameMode.SPECTATOR) {
            clearState(id);
            return;
        }
        // Game end / lobby -> cancel return.
        if (!KitSession.isInGame(p)) {
            clearState(id);
            return;
        }
        // No saved location / world (shouldn't happen) -> bail.
        if (saved == null || savedWorldId == null) {
            clearState(id);
            return;
        }
        // Cross-world: current world differs from saved world -> cancel.
        if (!p.getWorld().getUID().equals(savedWorldId)) {
            clearState(id);
            return;
        }
        World w = Bukkit.getWorld(savedWorldId);
        if (w == null) {
            clearState(id);
            return;
        }

        // Stuck-in-block: ensure destination is safe.
        Location dest = makeSafe(saved.clone());
        // No safe destination found (even the highest-block fallback is unsafe) -> abort the
        // teleport rather than dumping the player on a roof; just clear state in place.
        if (dest == null) {
            clearState(id);
            return;
        }

        // "Pulled back through time" cue at the player's CURRENT spot, so the rewind is clearly
        // heard at the moment it happens (the dest sounds alone play where they haven't landed yet).
        Location from = p.getLocation();
        from.getWorld().playSound(from, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.2f, 0.8f);
        from.getWorld().playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.7f);
        from.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, from.clone().add(0, 1.0, 0), 24, 0.3, 0.6, 0.3, 0.04);

        // Rewind visuals/sounds at the destination.
        w.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.2f);
        w.playSound(dest, Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 1.4f);
        w.spawnParticle(Particle.SOUL_FIRE_FLAME, dest.clone().add(0, 1.0, 0), 30, 0.3, 0.6, 0.3, 0.05);
        w.spawnParticle(Particle.FLASH, dest.clone().add(0, 1.0, 0), 1, 0.0, 0.0, 0.0, 0.0);
        spawnRing(dest.clone().add(0, 1.0, 0), 1.2, 18);

        p.teleport(dest);
        // Arrival cue the player hears directly after landing.
        p.playSound(dest, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 1.2f);

        clearState(id);
    }

    /**
     * If {@code loc} is unsafe (feet/head block solid or suffocating), find the nearest safe spot:
     * scan a few blocks up for 2 air blocks with a solid floor, else fall back to highest block.
     * Returns {@code null} if even the highest-block fallback is unsafe (caller should abort).
     */
    private Location makeSafe(Location loc) {
        World w = loc.getWorld();
        if (w == null) return loc;

        if (isSafe(loc)) return loc;

        // Scan upward a few blocks for 2 air blocks above a solid floor.
        for (int dy = 1; dy <= 6; dy++) {
            Location candidate = loc.clone().add(0, dy, 0);
            if (isSafe(candidate)) return candidate;
        }

        // Fallback: highest block at this x/z.
        int hy = w.getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ());
        Location top = new Location(w, loc.getX(), hy + 1.0, loc.getZ(), loc.getYaw(), loc.getPitch());
        // Verify the fallback is genuinely safe; if not (e.g. would land on a roof / in a pocket),
        // signal abort by returning null so the caller can skip the teleport entirely.
        if (!isSafe(top)) return null;
        return top;
    }

    /** Feet + head must be passable, floor (block below feet) must be present. */
    private boolean isSafe(Location loc) {
        World w = loc.getWorld();
        if (w == null) return false;
        Block feet = loc.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block floor = feet.getRelative(0, -1, 0);
        if (feet.getType().isSolid()) return false;
        if (head.getType().isSolid()) return false;
        // require something to stand on (avoid teleporting into a void pocket)
        return floor.getType().isSolid();
    }

    /** Small particle ring for warn/return effects. */
    private void spawnRing(Location center, double radius, int points) {
        World w = center.getWorld();
        if (w == null) return;
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(60, 180, 255), 1.0f);
        for (int i = 0; i < points; i++) {
            double ang = (2 * Math.PI * i) / points;
            double x = Math.cos(ang) * radius;
            double z = Math.sin(ang) * radius;
            w.spawnParticle(Particle.DUST, center.clone().add(x, 0, z), 1, 0.0, 0.0, 0.0, 0.0, dust);
        }
    }

    /* ================== State / cleanup ================== */

    /** Cancel tasks, remove tag + speed effects, clear maps. Idempotent. */
    private void clearState(UUID id) {
        BukkitTask dash = dashTasks.remove(id);
        if (dash != null) dash.cancel();
        BukkitTask warn = warnTasks.remove(id);
        if (warn != null) warn.cancel();
        BukkitTask ret = returnTasks.remove(id);
        if (ret != null) ret.cancel();

        startLoc.remove(id);
        startWorld.remove(id);

        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            p.removeScoreboardTag(TAG_FUTURE_RUN);
            p.removeScoreboardTag(TAG_CANT_PICKUP_FLAG);
            p.removePotionEffect(PotionEffectType.SPEED);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        clearState(id);
        BukkitTask cd = cdTasks.remove(id);
        if (cd != null) cd.cancel();
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        // Never teleport a dead player; just clear state/tags/effects.
        clearState(e.getEntity().getUniqueId());
    }

    @Override
    public void resetPlayer(Player player) {
        if (player == null) return;
        UUID id = player.getUniqueId();
        clearState(id);
        BukkitTask cd = cdTasks.remove(id);
        if (cd != null) cd.cancel();
    }
}
