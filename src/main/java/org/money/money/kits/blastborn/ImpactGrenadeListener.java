package org.money.money.kits.blastborn;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Blastborn — Impact Grenade.
 *
 * <p>RMB the Impact Grenade item to pull the pin: a short priming window plays a
 * creeper-prime cue and (optionally) slows the thrower, then the grenade is launched
 * as a {@link Snowball}. On impact it detonates manually through {@link ExplosionUtil}
 * (knockback + damage + safe block breaking + visuals) — it never spawns a real
 * world explosion.
 *
 * <p>Priming is cancelled if the player switches the item away, dies, quits, goes
 * spectator or the game ends. All per-player tasks (prime + per-snowball smoke trails)
 * are tracked and cancelled on cleanup so nothing leaks into the lobby.
 */
public final class ImpactGrenadeListener implements Listener, KitResettable {

    private final Plugin plugin;
    private final BlastbornManager manager;

    private final NamespacedKey KEY_GRENADE;
    private final NamespacedKey KEY_PROJECTILE;

    // ===== Config (read once) =====
    private final long cooldownMs;
    private final int primeTicks;
    private final double projectileSpeed;
    private final double explosionRadius;
    private final double blockBreakRadius;
    private final double damage;
    private final double knockback;
    private final boolean friendlyFire;
    private final int selfDestructionGain;
    private final boolean allowGrenadeDuringUltimate;

    // ===== Per-player state =====
    private final Map<UUID, Long> cooldownMap = new HashMap<>();
    private final Set<UUID> priming = new HashSet<>();
    private final Map<UUID, BukkitTask> primeTasks = new HashMap<>();

    // ===== Projectile tracking =====
    private final Set<UUID> liveGrenades = new HashSet<>();
    private final Map<UUID, BukkitTask> trailTasks = new HashMap<>();

    public ImpactGrenadeListener(Plugin plugin, BlastbornManager manager) {
        this.plugin = Objects.requireNonNull(plugin);
        this.manager = Objects.requireNonNull(manager);

        this.KEY_GRENADE = new NamespacedKey(plugin, "blastborn_grenade");
        this.KEY_PROJECTILE = new NamespacedKey(plugin, "blastborn_grenade_projectile");

        int cooldownTicks = Math.max(0, plugin.getConfig().getInt("classes.blastborn.grenade.cooldownTicks", 600));
        this.cooldownMs = cooldownTicks * 50L;
        this.primeTicks = Math.max(0, plugin.getConfig().getInt("classes.blastborn.grenade.primeTicks", 20));
        this.projectileSpeed = plugin.getConfig().getDouble("classes.blastborn.grenade.projectileSpeed", 1.9);
        this.explosionRadius = plugin.getConfig().getDouble("classes.blastborn.grenade.explosionRadius", 4.0);
        this.blockBreakRadius = plugin.getConfig().getDouble("classes.blastborn.grenade.blockBreakRadius", 3.0);
        this.damage = plugin.getConfig().getDouble("classes.blastborn.grenade.damage", 12.0);
        this.knockback = plugin.getConfig().getDouble("classes.blastborn.grenade.knockback", 1.5);
        this.friendlyFire = plugin.getConfig().getBoolean("classes.blastborn.grenade.friendlyFire", false);
        this.selfDestructionGain = plugin.getConfig().getInt("classes.blastborn.grenade.selfDestructionGain", 0);
        this.allowGrenadeDuringUltimate = plugin.getConfig().getBoolean("classes.blastborn.ultimate.allowGrenadeDuringUltimate", false);
    }

    /* ================== Item ================== */

    /** Create the Impact Grenade item. */
    public ItemStack makeGrenade() {
        ItemStack it = new ItemStack(Material.FIRE_CHARGE);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Impact Grenade", NamedTextColor.RED));
        im.getPersistentDataContainer().set(KEY_GRENADE, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(im);
        return it;
    }

    public boolean isGrenade(ItemStack it) {
        if (it == null || it.getType() != Material.FIRE_CHARGE || !it.hasItemMeta()) return false;
        ItemMeta im = it.getItemMeta();
        if (im.getPersistentDataContainer().has(KEY_GRENADE, PersistentDataType.BYTE)) return true;
        return Component.text("Impact Grenade", NamedTextColor.RED).equals(im.displayName());
    }

    /* ================== Interact / prime ================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;

        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        if (!isGrenade(p.getInventory().getItemInMainHand())) return;

        e.setCancelled(true);

        if (!KitSession.isInGame(p)) return;

        // Disabled during the ultimate (unless config allows it).
        if (manager.isUltActive(p) && !allowGrenadeDuringUltimate) {
            p.sendActionBar(Component.text("Disabled during Ultimate", NamedTextColor.RED));
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.8f);
            return;
        }

        UUID id = p.getUniqueId();

        // Already pulling the pin -> ignore re-clicks.
        if (priming.contains(id)) return;

        long now = System.currentTimeMillis();
        if (cooldownMap.containsKey(id)) {
            long last = cooldownMap.get(id);
            long passed = now - last;
            if (passed < cooldownMs) {
                long secLeft = (cooldownMs - passed + 999) / 1000;
                p.sendActionBar(Component.text(secLeft + " sec", NamedTextColor.RED));
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.8f);
                return;
            }
        }

        startPriming(p);
    }

    private void startPriming(Player p) {
        final UUID id = p.getUniqueId();

        // Defensive: cancel any stale prime task before starting a fresh one.
        BukkitTask prev = primeTasks.remove(id);
        if (prev != null) prev.cancel();

        priming.add(id);

        // Pin-pull cue + smoke puff.
        World w = p.getWorld();
        w.playSound(p.getLocation(), Sound.BLOCK_LEVER_CLICK, 1.0f, 1.2f);
        w.playSound(p.getLocation(), Sound.ENTITY_CREEPER_PRIMED, 1.0f, 1.2f);
        w.spawnParticle(Particle.LARGE_SMOKE, p.getLocation().add(0, 1.0, 0), 10, 0.25, 0.4, 0.25, 0.02);

        // Slow the thrower for the prime window (clamped to >= 1 tick when there is a window).
        if (primeTicks > 0) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, primeTicks, 1, false, false, false));
        }

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                primeTasks.remove(id);
                Player online = plugin.getServer().getPlayer(id);

                boolean stillPriming = priming.remove(id);

                if (online == null || !online.isOnline() || online.isDead()
                        || !KitSession.isInGame(online) || !stillPriming) {
                    return;
                }
                // Re-check the ult gate in case the ult started during the prime window.
                if (manager.isUltActive(online) && !allowGrenadeDuringUltimate) {
                    online.sendActionBar(Component.text("Disabled during Ultimate", NamedTextColor.RED));
                    return;
                }

                throwGrenade(online);
            }
        }.runTaskLater(plugin, Math.max(1L, (long) primeTicks));

        primeTasks.put(id, task);
    }

    /* ================== Throw ================== */

    private void throwGrenade(Player p) {
        Vector velocity = p.getEyeLocation().getDirection().multiply(projectileSpeed);
        Snowball sb = p.launchProjectile(Snowball.class, velocity);
        sb.setShooter(p);
        sb.getPersistentDataContainer().set(KEY_PROJECTILE, PersistentDataType.BYTE, (byte) 1);

        final UUID sbId = sb.getUniqueId();
        liveGrenades.add(sbId);

        World w = p.getWorld();
        w.playSound(p.getLocation(), Sound.ENTITY_SNOWBALL_THROW, 1.0f, 0.8f);
        w.playSound(p.getLocation(), Sound.ENTITY_TNT_PRIMED, 0.8f, 1.6f);

        // Smoke trail following the snowball until it dies.
        BukkitTask trail = new BukkitRunnable() {
            @Override
            public void run() {
                // Terminate (and clean up the snowball) when it dies OR when its owner is no
                // longer in-game — so an in-flight grenade can't trail/detonate in the lobby
                // after a round transition.
                Player owner = (sb.getShooter() instanceof Player ps && ps.isOnline()) ? ps : null;
                if (!sb.isValid() || sb.isDead() || !liveGrenades.contains(sbId)
                        || owner == null || !KitSession.isInGame(owner)) {
                    liveGrenades.remove(sbId);
                    trailTasks.remove(sbId);
                    if (sb.isValid()) sb.remove();
                    cancel();
                    return;
                }
                Location loc = sb.getLocation();
                sb.getWorld().spawnParticle(Particle.SMOKE, loc, 4, 0.05, 0.05, 0.05, 0.01);
                sb.getWorld().spawnParticle(Particle.FLAME, loc, 2, 0.05, 0.05, 0.05, 0.01);
            }
        }.runTaskTimer(plugin, 0L, 1L);

        trailTasks.put(sbId, trail);

        // Start the cooldown now.
        cooldownMap.put(p.getUniqueId(), System.currentTimeMillis());

        // Optional self-destruction meter gain on throw.
        if (selfDestructionGain > 0) {
            manager.addPoints(p, selfDestructionGain);
        }
    }

    /* ================== Impact ================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onProjectileHit(ProjectileHitEvent e) {
        Projectile projectile = e.getEntity();
        if (!(projectile instanceof Snowball sb)) return;

        UUID sbId = sb.getUniqueId();
        boolean tracked = liveGrenades.contains(sbId)
                || sb.getPersistentDataContainer().has(KEY_PROJECTILE, PersistentDataType.BYTE);
        if (!tracked) return;

        // Stop tracking + cancel the trail.
        liveGrenades.remove(sbId);
        BukkitTask trail = trailTasks.remove(sbId);
        if (trail != null) trail.cancel();

        Location at = e.getHitBlock() != null
                ? e.getHitBlock().getLocation().add(0.5, 0.5, 0.5)
                : sb.getLocation();

        // Resolve the shooter (may be null/offline).
        Player shooter = null;
        if (sb.getShooter() instanceof Player ps && ps.isOnline()) {
            shooter = ps;
        }

        // Never detonate in the lobby / after the game ended.
        if (shooter == null || !KitSession.isInGame(shooter)) {
            sb.remove();
            return;
        }

        // Manual explosion — never a real world explosion.
        ExplosionUtil.visualExplosion(at, 4f, true);
        ExplosionUtil.knockbackPlayers(at, explosionRadius, knockback, 0.5, shooter, true, false);
        ExplosionUtil.damagePlayers(at, explosionRadius, damage, shooter, false, 1.0, friendlyFire);
        ExplosionUtil.breakBlocksSafely(at, blockBreakRadius);

        sb.remove();
    }

    /* ================== Prime-cancel on item swap ================== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onItemHeld(PlayerItemHeldEvent e) {
        cancelPriming(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        cancelPriming(e.getPlayer());
    }

    /** Cancel an in-progress pin-pull (e.g. the player switched the item away). */
    private void cancelPriming(Player p) {
        UUID id = p.getUniqueId();
        if (!priming.remove(id)) {
            // Nothing priming; still drop any orphan task defensively.
            BukkitTask t = primeTasks.remove(id);
            if (t != null) t.cancel();
            return;
        }
        BukkitTask t = primeTasks.remove(id);
        if (t != null) t.cancel();
        if (primeTicks > 0) p.removePotionEffect(PotionEffectType.SLOWNESS);
    }

    /* ================== Cleanup / lifecycle ================== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        cleanup(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        cleanup(e.getEntity());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onGameModeChange(PlayerGameModeChangeEvent e) {
        if (e.getNewGameMode() == org.bukkit.GameMode.SPECTATOR) {
            cleanup(e.getPlayer());
        }
    }

    /** Cancel a player's prime task + slowness, drop them from priming, clear cooldown. */
    private void cleanup(Player p) {
        UUID id = p.getUniqueId();
        BukkitTask t = primeTasks.remove(id);
        if (t != null) t.cancel();
        if (priming.remove(id) && primeTicks > 0) {
            p.removePotionEffect(PotionEffectType.SLOWNESS);
        }
        cooldownMap.remove(id);
    }

    /**
     * Idempotent per-player reset so the grenade can't leak into the lobby: cancel the
     * prime task, drop from the priming set, clear cooldowns. In-flight snowballs are
     * short-lived; their trail tasks are stopped here too if any remain stale.
     */
    @Override
    public void resetPlayer(Player player) {
        if (player == null) return;
        cleanup(player);
    }

    /** Cancel every in-flight grenade trail task (Main.onDisable). */
    public void stop() {
        for (BukkitTask t : trailTasks.values()) {
            if (t != null) t.cancel();
        }
        trailTasks.clear();
        liveGrenades.clear();
        for (BukkitTask t : primeTasks.values()) {
            if (t != null) t.cancel();
        }
        primeTasks.clear();
        priming.clear();
    }
}
