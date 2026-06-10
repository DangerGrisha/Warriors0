package org.money.money.kits.timewalker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;
import org.money.money.session.KitResettable;
import org.money.money.session.KitSession;

import java.util.*;

public final class TimeWalkerSlashListener implements Listener, KitResettable {

    // ====== Shared scoreboard tags (interop) ======
    private static final String TAG_SEVER_IFRAME = "TimeWalkerSeverIFrame";
    private static final String TAG_FUTURE_RUN = "TimeWalkerFutureRun";
    private static final String TAG_MOMENTUM = "TimeWalkerMomentum";
    private static final String TAG_MIRAGE = "TimeWalkerMirage";

    // ====== Slash wave movement (WindSword style) ======
    private static final double STEP = 0.65;        // blocks per tick
    private static final double HIT_RADIUS = 1.1;   // hit radius each step

    private static final int PARTICLE_POINTS = 3;       // modest budget
    private static final int PARTICLE_EVERY_TICKS = 2;  // particles every 2 ticks

    private final Plugin plugin;

    private final NamespacedKey KEY_PERFECT_SEVER;

    // ====== Config tunables (read once in constructor) ======
    private final int cooldownSeconds;
    private final long cooldownMs;
    private final int iframeTicks;
    private final double forwardLaunch;
    private final double slashRange;
    private final double baseDamage;
    private final double speedMultiplier;
    private final double minDamage;
    private final double maxDamage;

    // cooldown stores last use time ms
    private final Map<UUID, Long> cooldownMap = new HashMap<>();

    // actionbar timer tasks to avoid stacking multiple timers
    private final Map<UUID, BukkitTask> cdTasks = new HashMap<>();

    // travelling slash-wave tasks, tracked so they can be cancelled on lobby return
    private final Map<UUID, BukkitTask> waveTasks = new HashMap<>();

    // ====== Self-tracked horizontal speed ======
    private final Map<UUID, Location> lastLoc = new HashMap<>();
    private final Map<UUID, Double> horizSpeed = new HashMap<>();

    public TimeWalkerSlashListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_PERFECT_SEVER = new NamespacedKey(plugin, "timewalker_perfect_sever");

        this.cooldownSeconds = plugin.getConfig().getInt("timewalker.slash.cooldown-seconds", 8);
        this.cooldownMs = this.cooldownSeconds * 1000L;
        this.iframeTicks = plugin.getConfig().getInt("timewalker.slash.iframe-ticks", 8);
        this.forwardLaunch = plugin.getConfig().getDouble("timewalker.slash.forward-launch", 0.6);
        this.slashRange = plugin.getConfig().getDouble("timewalker.slash.range", 6.0);
        this.baseDamage = plugin.getConfig().getDouble("timewalker.slash.base-damage", 5.0);
        this.speedMultiplier = plugin.getConfig().getDouble("timewalker.slash.speed-multiplier", 13.5);
        this.minDamage = plugin.getConfig().getDouble("timewalker.slash.min-damage", 5.0);
        this.maxDamage = plugin.getConfig().getDouble("timewalker.slash.max-damage", 20.0);
    }

    /* ================== Item ================== */

    /** Create the Perfect Sever sword item. */
    public ItemStack makePerfectSeverSword() {
        ItemStack it = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Perfect Sever", NamedTextColor.LIGHT_PURPLE));
        im.getPersistentDataContainer().set(KEY_PERFECT_SEVER, PersistentDataType.BYTE, (byte) 1);
        im.setUnbreakable(true);
        im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
        it.setItemMeta(im);
        return it;
    }

    private boolean isPerfectSever(ItemStack it) {
        if (it == null || it.getType() != Material.NETHERITE_SWORD || !it.hasItemMeta()) return false;
        ItemMeta im = it.getItemMeta();
        if (im.getPersistentDataContainer().has(KEY_PERFECT_SEVER, PersistentDataType.BYTE)) return true;
        return Component.text("Perfect Sever", NamedTextColor.LIGHT_PURPLE).equals(im.displayName());
    }

    /* ================== Speed tracking ================== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        Location to = e.getTo();
        if (to == null) return;

        Location prev = lastLoc.get(id);
        lastLoc.put(id, to.clone());

        if (prev == null || prev.getWorld() == null || !prev.getWorld().equals(to.getWorld())) {
            // First sample or world changed -> no speed this tick.
            horizSpeed.put(id, 0.0);
            return;
        }

        double dx = to.getX() - prev.getX();
        double dz = to.getZ() - prev.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        // Light smoothing: keep the max of the previous and current sample so a brief
        // stop right before activation doesn't kill the dash damage.
        double previous = horizSpeed.getOrDefault(id, 0.0);
        double smoothed = Math.max(dist, previous * 0.6);
        horizSpeed.put(id, smoothed);
    }

    /* ================== I-frame damage cancel ================== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (p.getScoreboardTags().contains(TAG_SEVER_IFRAME)) {
            e.setCancelled(true);
        }
    }

    /* ================== Interact ================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;

        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        if (!isPerfectSever(p.getInventory().getItemInMainHand())) return;

        e.setCancelled(true);

        if (!KitSession.isInGame(p)) return;

        UUID id = p.getUniqueId();
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

        // start cooldown
        cooldownMap.put(id, now);
        startCooldownTimer(p, cooldownSeconds);

        // cast ability
        castPerfectSever(p);
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
                    cdTasks.remove(id);
                    cancel();
                    return;
                }

                if (timeLeft <= 0) {
                    player.sendActionBar(Component.text("Ready", NamedTextColor.GREEN));
                    cdTasks.remove(id);
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

    private void castPerfectSever(Player caster) {
        World w = caster.getWorld();
        final UUID casterId = caster.getUniqueId();

        // Enhanced look if any TimeWalker buff is active.
        boolean enhanced = caster.getScoreboardTags().contains(TAG_FUTURE_RUN)
                || caster.getScoreboardTags().contains(TAG_MOMENTUM)
                || caster.getScoreboardTags().contains(TAG_MIRAGE);
        boolean inMirage = caster.getScoreboardTags().contains(TAG_MIRAGE);

        // ===== I-FRAMES =====
        caster.addScoreboardTag(TAG_SEVER_IFRAME);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player online = Bukkit.getPlayer(casterId);
            if (online != null) online.removeScoreboardTag(TAG_SEVER_IFRAME);
        }, Math.max(1L, (long) iframeTicks));

        // ===== Snapshot horizontal speed (blocks/tick) at activation =====
        double speed = horizSpeed.getOrDefault(casterId, 0.0);
        double damage = baseDamage + speed * speedMultiplier;
        damage = Math.max(minDamage, Math.min(maxDamage, damage));
        if (inMirage) {
            // small bonus while inside own mirage, still clamped to max
            damage = Math.min(maxDamage, damage + 1.5);
        }
        final double finalDamage = damage;

        Vector dir = caster.getLocation().getDirection().normalize();
        Vector horizDir = new Vector(dir.getX(), 0, dir.getZ());
        if (horizDir.lengthSquared() < 1e-6) {
            horizDir = new Vector(0, 0, 0);
        } else {
            horizDir.normalize();
        }

        // ===== FORWARD DASH (wall-safe) =====
        Block ahead = caster.getLocation().add(horizDir.clone().multiply(1.0)).getBlock();
        boolean blocked = horizDir.lengthSquared() < 1e-6 || ahead.getType().isSolid();
        Vector launch;
        if (blocked) {
            // Don't shove into a wall: keep only the upward hop.
            launch = new Vector(0, 0.25, 0);
        } else {
            launch = horizDir.clone().multiply(forwardLaunch);
            launch.setY(0.30);
        }
        caster.setVelocity(launch);

        // ===== Sound =====
        w.playSound(caster.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, enhanced ? 0.9f : 1.2f);
        if (enhanced) {
            w.playSound(caster.getLocation(), Sound.ITEM_TRIDENT_RETURN, 1.0f, 1.4f);
        }

        // ===== SLASH WAVE (WindSword style; pass-through, single hit per enemy) =====
        Location start = caster.getEyeLocation().clone()
                .add(caster.getLocation().getDirection().normalize().multiply(1.2));
        Vector waveDir = caster.getLocation().getDirection().normalize();

        final boolean fxEnhanced = enhanced;
        final Set<UUID> hit = new HashSet<>();

        // cancel any in-flight wave for this caster before starting a new one
        BukkitTask prevWave = waveTasks.remove(casterId);
        if (prevWave != null) prevWave.cancel();

        BukkitTask waveTask = new BukkitRunnable() {
            double traveled = 0.0;
            Location pos = start.clone();

            @Override
            public void run() {
                Player online = Bukkit.getPlayer(casterId);
                if (online == null || !online.isOnline() || !KitSession.isInGame(online)) {
                    waveTasks.remove(casterId);
                    cancel();
                    return;
                }

                // move forward
                pos.add(waveDir.clone().multiply(STEP));
                traveled += STEP;

                // stop if hit a solid block
                if (pos.getBlock().getType().isSolid()) {
                    w.playSound(pos, Sound.BLOCK_WOOL_BREAK, 0.6f, 1.6f);
                    waveTasks.remove(casterId);
                    cancel();
                    return;
                }

                // particles
                if (((int) (traveled / STEP)) % PARTICLE_EVERY_TICKS == 0) {
                    spawnSlashParticles(w, pos, waveDir, fxEnhanced);
                }

                // hit detection (pass-through: do NOT cancel on hit)
                for (Entity ent : w.getNearbyEntities(pos, HIT_RADIUS, HIT_RADIUS, HIT_RADIUS)) {
                    if (!(ent instanceof Player target)) continue;
                    if (target.getUniqueId().equals(casterId)) continue;
                    if (!target.isOnline() || target.isDead()) continue;
                    if (hit.contains(target.getUniqueId())) continue;

                    // team rule: never self, never confirmed ally
                    if (isFriendly(online, target)) continue;

                    hit.add(target.getUniqueId());

                    target.damage(finalDamage, online);

                    // knockback forward along slash direction
                    Vector kb = waveDir.clone();
                    kb.setY(0);
                    if (kb.lengthSquared() > 1e-6) kb.normalize();
                    kb.multiply(0.6);
                    kb.setY(0.25);
                    target.setVelocity(target.getVelocity().add(kb));

                    w.playSound(target.getLocation(), Sound.ENTITY_PLAYER_HURT, 0.9f, 1.2f);
                    w.spawnParticle(Particle.CRIT, target.getLocation().add(0, 1.0, 0), 12, 0.25, 0.35, 0.25, 0.08);
                }

                // max distance
                if (traveled >= slashRange) {
                    waveTasks.remove(casterId);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);

        waveTasks.put(casterId, waveTask);
    }

    private void spawnSlashParticles(World w, Location center, Vector dir, boolean enhanced) {
        Vector forward = dir.clone().normalize();
        Vector side = forward.clone().crossProduct(new Vector(0, 1, 0)).normalize();

        int points = enhanced ? PARTICLE_POINTS + 2 : PARTICLE_POINTS;
        Particle.DustOptions dust = new Particle.DustOptions(
                Color.fromRGB(60, 180, 255), 1.0f); // light blue

        for (int i = 0; i < points; i++) {
            double t = (Math.random() * 2.2) - 1.1;   // width
            double y = (Math.random() * 0.6) - 0.1;   // height

            Location p = center.clone()
                    .add(side.clone().multiply(t))
                    .add(0, y, 0);

            w.spawnParticle(Particle.SWEEP_ATTACK, p, 1, 0, 0, 0, 0);
            w.spawnParticle(Particle.CRIT, p, 1, 0.03, 0.03, 0.03, 0.005);
            w.spawnParticle(Particle.DUST, p, 1, 0.05, 0.05, 0.05, 0.0, dust);
        }

        if (enhanced) {
            w.spawnParticle(Particle.FLASH, center, 1, 0, 0, 0, 0);
        }
    }

    /* ================== Team check ================== */

    private boolean isFriendly(Player a, Player b) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team ta = sb.getEntryTeam(a.getName());
        Team tb = sb.getEntryTeam(b.getName());
        if (ta == null || tb == null) return false;
        return ta.getName().equalsIgnoreCase(tb.getName());
    }

    /* ================== Cleanup ================== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        cleanup(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        cleanup(e.getEntity());
    }

    private void cleanup(Player p) {
        UUID id = p.getUniqueId();
        p.removeScoreboardTag(TAG_SEVER_IFRAME);
        BukkitTask t = cdTasks.remove(id);
        if (t != null) t.cancel();
        BukkitTask wave = waveTasks.remove(id);
        if (wave != null) wave.cancel();
        cooldownMap.remove(id);
        lastLoc.remove(id);
        horizSpeed.remove(id);
    }

    /**
     * Idempotent per-player reset so abilities don't leak into the lobby.
     * Removes the i-frame tag, cancels timers and the travelling slash wave,
     * and drops all per-player state.
     */
    @Override
    public void resetPlayer(Player player) {
        if (player == null) return;
        cleanup(player);
    }
}
