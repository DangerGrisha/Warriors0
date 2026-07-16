package org.money.money.kits.blastborn;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.money.money.meta.ClassRegistry;
import org.money.money.session.KitResettable;
import org.money.money.session.KitSession;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Blastborn — Sweat Machine Gun (barrage).
 *
 * <p>RMB fires a burst of ~20 grenade "bullets" that fly STRAIGHT like fireballs (gravity off)
 * with a little sideways spread. Each detonates on impact for roughly half a normal grenade's
 * damage and a weaker blast. Bullets self-expire after a short lifetime, and — like the grenade —
 * they never trail or detonate in the lobby (they self-clean once their owner is out of game).
 */
public final class SweatMachineGunListener implements Listener, KitResettable {

    private final Plugin plugin;
    private final BlastbornManager manager;

    private final NamespacedKey KEY_GUN;
    private final NamespacedKey KEY_BULLET;

    // ===== Config (read once; non-balance toggles only — balance numbers come from ClassRegistry at use time) =====
    private final boolean breakBlocks;
    private final boolean allowDuringUltimate;

    // ===== State =====
    private final Map<UUID, Long> cooldownMap = new HashMap<>();
    private final Map<UUID, BukkitTask> burstTasks = new HashMap<>();
    private final Set<UUID> liveBullets = new HashSet<>();
    private final Map<UUID, BukkitTask> reapTasks = new HashMap<>();

    public SweatMachineGunListener(Plugin plugin, BlastbornManager manager) {
        this.plugin = Objects.requireNonNull(plugin);
        this.manager = Objects.requireNonNull(manager);
        this.KEY_GUN = new NamespacedKey(plugin, "blastborn_machinegun");
        this.KEY_BULLET = new NamespacedKey(plugin, "blastborn_mg_bullet");

        this.breakBlocks = plugin.getConfig().getBoolean("classes.blastborn.machineGun.breakBlocks", false);
        this.allowDuringUltimate = plugin.getConfig().getBoolean("classes.blastborn.machineGun.allowDuringUltimate", true);
    }

    /* ================== Item ================== */

    public ItemStack makeSweatMachineGun() {
        ItemStack it = new ItemStack(Material.BLAZE_POWDER);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Sweat Machine Gun", NamedTextColor.GOLD));
        im.getPersistentDataContainer().set(KEY_GUN, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(im);
        return it;
    }

    public boolean isSweatMachineGun(ItemStack it) {
        if (it == null || it.getType() != Material.BLAZE_POWDER || !it.hasItemMeta()) return false;
        ItemMeta im = it.getItemMeta();
        if (im.getPersistentDataContainer().has(KEY_GUN, PersistentDataType.BYTE)) return true;
        return Component.text("Sweat Machine Gun", NamedTextColor.GOLD).equals(im.displayName());
    }

    /* ================== Fire ================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        if (!isSweatMachineGun(p.getInventory().getItemInMainHand())) return;

        e.setCancelled(true);
        if (!KitSession.isInGame(p)) return;

        UUID id = p.getUniqueId();
        if (burstTasks.containsKey(id)) return; // already firing a burst

        if (manager.isUltActive(p) && !allowDuringUltimate) {
            p.sendActionBar(Component.text("Disabled during Ultimate", NamedTextColor.RED));
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.8f);
            return;
        }

        long now = System.currentTimeMillis();
        // Cooldown read at use time so /warriors reload applies without restart.
        long cooldownMs = Math.max(0, ClassRegistry.ticks("blastborn", "machinegun", 1000)) * 50L;
        if (cooldownMap.containsKey(id)) {
            long passed = now - cooldownMap.get(id);
            if (passed < cooldownMs) {
                long secLeft = (cooldownMs - passed + 999) / 1000;
                p.sendActionBar(Component.text(secLeft + " sec", NamedTextColor.RED));
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.8f);
                return;
            }
        }

        cooldownMap.put(id, now);
        startBurst(p);
    }

    private void startBurst(Player p) {
        final UUID id = p.getUniqueId();
        BukkitTask prev = burstTasks.remove(id);
        if (prev != null) prev.cancel();

        // Balance numbers read at use time (per burst) so /warriors reload applies without restart.
        final int count = Math.max(1, ClassRegistry.numInt("blastborn", "machinegun", "projectileCount", 20));
        final int fireIntervalTicks = Math.max(1, ClassRegistry.numInt("blastborn", "machinegun", "fireIntervalTicks", 2));

        BukkitTask task = new BukkitRunnable() {
            int fired = 0;

            @Override
            public void run() {
                Player online = plugin.getServer().getPlayer(id);
                if (online == null || !online.isOnline() || online.isDead()
                        || !KitSession.isInGame(online) || fired >= count) {
                    burstTasks.remove(id);
                    cancel();
                    return;
                }
                fireBullet(online);
                fired++;
            }
        }.runTaskTimer(plugin, 0L, fireIntervalTicks);
        burstTasks.put(id, task);
    }

    private void fireBullet(Player p) {
        // Balance numbers read at use time so /warriors reload applies without restart.
        final double spread = ClassRegistry.num("blastborn", "machinegun", "spread", 0.12);
        final double projectileSpeed = ClassRegistry.num("blastborn", "machinegun", "projectileSpeed", 2.2);

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Vector dir = p.getEyeLocation().getDirection().normalize();
        dir.add(new Vector((rnd.nextDouble() - 0.5) * spread,
                (rnd.nextDouble() - 0.5) * spread,
                (rnd.nextDouble() - 0.5) * spread));
        if (dir.lengthSquared() < 1e-6) dir = p.getEyeLocation().getDirection();
        Vector velocity = dir.normalize().multiply(projectileSpeed);

        Snowball sb = p.launchProjectile(Snowball.class, velocity);
        sb.setShooter(p);
        sb.setGravity(false); // straight-line, fireball-like flight
        sb.getPersistentDataContainer().set(KEY_BULLET, PersistentDataType.BYTE, (byte) 1);

        final UUID bid = sb.getUniqueId();
        liveBullets.add(bid);

        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 0.7f, 1.6f);
        sb.getWorld().spawnParticle(Particle.FLAME, sb.getLocation(), 3, 0.02, 0.02, 0.02, 0.01);

        // Lifetime reaper: kills the bullet on max-life, or once its owner leaves the game
        // (so it can't trail/detonate in the lobby after a round transition).
        BukkitTask reaper = new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                int maxLifeTicks = Math.max(5, ClassRegistry.numInt("blastborn", "machinegun", "maxLifeTicks", 40));
                Player owner = (sb.getShooter() instanceof Player ps && ps.isOnline()) ? ps : null;
                if (!sb.isValid() || sb.isDead() || !liveBullets.contains(bid)
                        || owner == null || !KitSession.isInGame(owner) || t >= maxLifeTicks) {
                    liveBullets.remove(bid);
                    reapTasks.remove(bid);
                    if (sb.isValid()) sb.remove();
                    cancel();
                    return;
                }
                sb.getWorld().spawnParticle(Particle.SMOKE, sb.getLocation(), 1, 0.0, 0.0, 0.0, 0.0);
                t++;
            }
        }.runTaskTimer(plugin, 1L, 1L);
        reapTasks.put(bid, reaper);

        int selfDestructionGain = ClassRegistry.numInt("blastborn", "machinegun", "selfDestructionGain", 0);
        if (selfDestructionGain > 0) manager.addPoints(p, selfDestructionGain);
    }

    /* ================== Impact ================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onProjectileHit(ProjectileHitEvent e) {
        Projectile projectile = e.getEntity();
        if (!(projectile instanceof Snowball sb)) return;

        UUID bid = sb.getUniqueId();
        boolean tracked = liveBullets.contains(bid)
                || sb.getPersistentDataContainer().has(KEY_BULLET, PersistentDataType.BYTE);
        if (!tracked) return;

        liveBullets.remove(bid);
        BukkitTask reaper = reapTasks.remove(bid);
        if (reaper != null) reaper.cancel();

        Location at = e.getHitBlock() != null
                ? e.getHitBlock().getLocation().add(0.5, 0.5, 0.5)
                : sb.getLocation();

        Player shooter = (sb.getShooter() instanceof Player ps && ps.isOnline()) ? ps : null;
        sb.remove();

        // Never detonate in the lobby / after the game ended.
        if (shooter == null || !KitSession.isInGame(shooter)) return;

        // Balance numbers read at use time so /warriors reload applies without restart.
        final double explosionRadius = ClassRegistry.num("blastborn", "machinegun", "explosionRadius", 2.5);
        final double damage = ClassRegistry.num("blastborn", "machinegun", "damage", 6.0);
        final double knockback = ClassRegistry.num("blastborn", "machinegun", "knockback", 0.9);

        ExplosionUtil.visualExplosion(at, 1.6f, true);
        ExplosionUtil.knockbackPlayers(at, explosionRadius, knockback, 0.3, shooter, true, false);
        ExplosionUtil.damagePlayers(at, explosionRadius, damage, shooter, false, 1.0, false);
        if (breakBlocks) ExplosionUtil.breakBlocksSafely(at, explosionRadius * 0.6);
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

    /** Cancel a player's active burst + cooldown. In-flight bullets self-clean via their reaper. */
    private void cleanup(Player p) {
        UUID id = p.getUniqueId();
        BukkitTask burst = burstTasks.remove(id);
        if (burst != null) burst.cancel();
        cooldownMap.remove(id);
    }

    @Override
    public void resetPlayer(Player player) {
        if (player == null) return;
        cleanup(player);
    }

    /** Cancel every burst + bullet reaper and remove live bullets (Main.onDisable). */
    public void stop() {
        for (BukkitTask t : burstTasks.values()) {
            if (t != null) t.cancel();
        }
        burstTasks.clear();
        for (BukkitTask t : reapTasks.values()) {
            if (t != null) t.cancel();
        }
        reapTasks.clear();
        liveBullets.clear();
    }
}
