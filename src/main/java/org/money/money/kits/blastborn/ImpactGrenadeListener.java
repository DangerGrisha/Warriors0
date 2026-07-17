package org.money.money.kits.blastborn;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
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
import org.money.money.meta.ClassRegistry;
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

    // ===== Config (read once; non-balance toggles only — balance numbers come from ClassRegistry at use time) =====
    private final boolean friendlyFire;
    private final boolean allowGrenadeDuringUltimate;

    // ===== Per-player state =====
    private final Map<UUID, Long> cooldownMap = new HashMap<>();
    private final Set<UUID> priming = new HashSet<>();
    private final Map<UUID, BukkitTask> primeTasks = new HashMap<>();

    // ===== Projectile tracking (bouncing, fused) =====
    private final Map<UUID, Grenade> grenades = new HashMap<>(); // current snowball UUID -> grenade

    /** A thrown grenade: bounces once, detonates on a 1s fuse independent of the snowball's own life. */
    private static final class Grenade {
        UUID sbId;              // current snowball entity (re-keyed on the single bounce)
        final UUID shooterId;
        Location lastLoc;       // updated each tick by the trail; the detonation point
        boolean bounced;        // the one real-life-ish hop already happened
        BukkitTask fuseTask;    // scheduled on first contact; fires the detonation
        BukkitTask trailTask;   // smoke/flame while a live snowball exists
        Grenade(UUID sbId, UUID shooterId, Location lastLoc) {
            this.sbId = sbId; this.shooterId = shooterId; this.lastLoc = lastLoc;
        }
    }

    public ImpactGrenadeListener(Plugin plugin, BlastbornManager manager) {
        this.plugin = Objects.requireNonNull(plugin);
        this.manager = Objects.requireNonNull(manager);

        this.KEY_GRENADE = new NamespacedKey(plugin, "blastborn_grenade");
        this.KEY_PROJECTILE = new NamespacedKey(plugin, "blastborn_grenade_projectile");

        this.friendlyFire = plugin.getConfig().getBoolean("classes.blastborn.grenade.friendlyFire", false);
        this.allowGrenadeDuringUltimate = plugin.getConfig().getBoolean("classes.blastborn.ultimate.allowGrenadeDuringUltimate", false);
    }

    /** Pin-pull charge-up window (ticks), read at use time so /warriors reload applies. */
    private static int primeTicks() {
        return Math.max(0, ClassRegistry.numInt("blastborn", "grenade", "primeTicks", 20));
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
        // Cooldown read at use time so /warriors reload applies without restart.
        long cooldownMs = Math.max(0, ClassRegistry.ticks("blastborn", "grenade", 600)) * 50L;
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

        final int primeTicks = primeTicks();

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
        // Balance numbers read at use time so /warriors reload applies without restart.
        double projectileSpeed = ClassRegistry.num("blastborn", "grenade", "projectileSpeed", 1.9);
        Vector velocity = p.getEyeLocation().getDirection().multiply(projectileSpeed);
        Snowball sb = p.launchProjectile(Snowball.class, velocity);
        sb.setShooter(p);
        sb.getPersistentDataContainer().set(KEY_PROJECTILE, PersistentDataType.BYTE, (byte) 1);

        Grenade g = new Grenade(sb.getUniqueId(), p.getUniqueId(), sb.getLocation());
        grenades.put(sb.getUniqueId(), g);
        startTrail(g, sb);

        World w = p.getWorld();
        w.playSound(p.getLocation(), Sound.ENTITY_SNOWBALL_THROW, 1.0f, 0.8f);
        w.playSound(p.getLocation(), Sound.ENTITY_TNT_PRIMED, 0.8f, 1.6f);

        // Start the cooldown now.
        cooldownMap.put(p.getUniqueId(), System.currentTimeMillis());

        // Optional self-destruction meter gain on throw.
        int selfDestructionGain = ClassRegistry.numInt("blastborn", "grenade", "selfDestructionGain", 0);
        if (selfDestructionGain > 0) {
            manager.addPoints(p, selfDestructionGain);
        }
    }

    /* ================== Impact ================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onProjectileHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof Snowball sb)) return;
        UUID sbId = sb.getUniqueId();
        Grenade g = grenades.get(sbId);
        if (g == null) {
            // stray grenade snowball (e.g. left over across a reload) — remove quietly
            if (sb.getPersistentDataContainer().has(KEY_PROJECTILE, PersistentDataType.BYTE)) sb.remove();
            return;
        }

        Player shooter = (sb.getShooter() instanceof Player ps && ps.isOnline()) ? ps : null;
        Location hitLoc = e.getHitBlock() != null
                ? e.getHitBlock().getLocation().add(0.5, 0.5, 0.5)
                : sb.getLocation();
        g.lastLoc = hitLoc;

        // Never fuse/detonate in the lobby / after the game ended.
        if (shooter == null || !KitSession.isInGame(shooter)) {
            finalizeGrenade(g);
            return;
        }

        // First contact starts the 1s fuse — it no longer detonates the instant it lands.
        if (g.fuseTask == null) {
            final int fuseTicks = Math.max(1, ClassRegistry.numInt("blastborn", "grenade", "fuseTicks", 20));
            World w = sb.getWorld();
            w.playSound(hitLoc, Sound.BLOCK_LEVER_CLICK, 0.9f, 0.6f);
            w.playSound(hitLoc, Sound.ENTITY_CREEPER_PRIMED, 0.7f, 1.4f);
            g.fuseTask = new BukkitRunnable() {
                int left = fuseTicks;
                @Override
                public void run() {
                    Player owner = plugin.getServer().getPlayer(g.shooterId);
                    if (owner == null || !owner.isOnline() || !KitSession.isInGame(owner)) {
                        cancel();
                        finalizeGrenade(g); // owner left mid-fuse -> no boom
                        return;
                    }
                    if (--left <= 0) { cancel(); detonate(g); return; }
                    // "cooking" telegraph at the grenade's resting / last spot
                    if (g.lastLoc != null && g.lastLoc.getWorld() != null) {
                        Location tl = g.lastLoc.clone().add(0, 0.25, 0);
                        tl.getWorld().spawnParticle(Particle.SMOKE, tl, 3, 0.08, 0.08, 0.08, 0.01);
                        if (left % 4 == 0) tl.getWorld().spawnParticle(Particle.CRIT, tl, 5, 0.12, 0.12, 0.12, 0.02);
                    }
                }
            }.runTaskTimer(plugin, 1L, 1L);
        }

        var face = e.getHitBlockFace();
        if (!g.bounced && face != null) {
            // One real-life-ish hop off the surface, then it settles; the fuse keeps ticking.
            g.bounced = true;
            double damping = ClassRegistry.num("blastborn", "grenade", "bounceDamping", 0.45);
            Vector v = sb.getVelocity();
            Vector n = new Vector(face.getModX(), face.getModY(), face.getModZ());
            Vector reflected = v.subtract(n.clone().multiply(2 * v.dot(n))).multiply(Math.max(0.0, damping));
            if (face.getModY() > 0 && reflected.getY() < 0.15) reflected.setY(0.15); // small pop off the floor
            final Player owner = shooter;
            Snowball nb = sb.getWorld().spawn(hitLoc.clone().add(n.clone().multiply(0.25)), Snowball.class, s -> {
                s.setShooter(owner);
                s.getPersistentDataContainer().set(KEY_PROJECTILE, PersistentDataType.BYTE, (byte) 1);
            });
            nb.setVelocity(reflected);
            // re-key the grenade onto the new snowball, keep the fuse ticking
            grenades.remove(sbId);
            g.sbId = nb.getUniqueId();
            grenades.put(nb.getUniqueId(), g);
            sb.remove();
            startTrail(g, nb);
            nb.getWorld().playSound(hitLoc, Sound.ENTITY_SLIME_JUMP, 0.7f, 1.3f);
        } else {
            // Already bounced, or hit an entity (no face): let this snowball die. The fuse (which holds
            // g directly) still detonates at g.lastLoc; g stays mapped under its now-dead id for cleanup.
            if (g.trailTask != null) { g.trailTask.cancel(); g.trailTask = null; }
            sb.remove();
        }
    }

    /** Smoke/flame trail that also feeds the grenade's detonation point (g.lastLoc). */
    private void startTrail(Grenade g, Snowball sb) {
        if (g.trailTask != null) g.trailTask.cancel();
        g.trailTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!sb.isValid() || sb.isDead()) { cancel(); return; }
                g.lastLoc = sb.getLocation();
                sb.getWorld().spawnParticle(Particle.SMOKE, g.lastLoc, 4, 0.05, 0.05, 0.05, 0.01);
                sb.getWorld().spawnParticle(Particle.FLAME, g.lastLoc, 2, 0.05, 0.05, 0.05, 0.01);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Fuse expired: blow up at the grenade's last position (unless the owner left the game). */
    private void detonate(Grenade g) {
        Player shooter = plugin.getServer().getPlayer(g.shooterId);
        Location at = g.lastLoc;
        boolean boom = at != null && shooter != null && shooter.isOnline() && KitSession.isInGame(shooter);
        finalizeGrenade(g);
        if (boom) explode(at, shooter);
    }

    /** Manual explosion — never a real world explosion. Balance read at use time (/warriors reload). */
    private void explode(Location at, Player shooter) {
        final double explosionRadius = ClassRegistry.num("blastborn", "grenade", "explosionRadius", 4.0);
        final double blockBreakRadius = ClassRegistry.num("blastborn", "grenade", "blockBreakRadius", 3.0);
        final double damage = ClassRegistry.num("blastborn", "grenade", "damage", 12.0);
        final double knockback = ClassRegistry.num("blastborn", "grenade", "knockback", 1.5);
        ExplosionUtil.visualExplosion(at, 4f, true);
        ExplosionUtil.knockbackPlayers(at, explosionRadius, knockback, 0.5, shooter, true, false);
        ExplosionUtil.damagePlayers(at, explosionRadius, damage, shooter, false, 1.0, friendlyFire);
        ExplosionUtil.breakBlocksSafely(at, blockBreakRadius);
    }

    /** Cancel a grenade's fuse + trail, drop it from tracking, and remove any live snowball. */
    private void finalizeGrenade(Grenade g) {
        if (g.fuseTask != null) { g.fuseTask.cancel(); g.fuseTask = null; }
        if (g.trailTask != null) { g.trailTask.cancel(); g.trailTask = null; }
        grenades.remove(g.sbId);
        Entity ent = plugin.getServer().getEntity(g.sbId);
        if (ent instanceof Snowball s && s.isValid()) s.remove();
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
        if (primeTicks() > 0) p.removePotionEffect(PotionEffectType.SLOWNESS);
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
        if (priming.remove(id) && primeTicks() > 0) {
            p.removePotionEffect(PotionEffectType.SLOWNESS);
        }
        cooldownMap.remove(id);
        // Drop any in-flight grenades this player owns (no boom in the lobby / after death).
        grenades.values().removeIf(g -> {
            if (!id.equals(g.shooterId)) return false;
            if (g.fuseTask != null) g.fuseTask.cancel();
            if (g.trailTask != null) g.trailTask.cancel();
            Entity ent = plugin.getServer().getEntity(g.sbId);
            if (ent instanceof Snowball s && s.isValid()) s.remove();
            return true;
        });
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
        for (Grenade g : grenades.values()) {
            if (g.fuseTask != null) g.fuseTask.cancel();
            if (g.trailTask != null) g.trailTask.cancel();
            Entity ent = plugin.getServer().getEntity(g.sbId);
            if (ent instanceof Snowball s && s.isValid()) s.remove();
        }
        grenades.clear();
        for (BukkitTask t : primeTasks.values()) {
            if (t != null) t.cancel();
        }
        primeTasks.clear();
        priming.clear();
    }
}
