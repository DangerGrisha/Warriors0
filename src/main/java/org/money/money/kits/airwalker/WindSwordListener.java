package org.money.money.kits.airwalker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;

import java.util.*;

public final class WindSwordListener implements Listener {

    // ====== Tuning ======
    private static double SLASH_RANGE() { return org.money.money.meta.ClassRegistry.num("airwalker", "windsword", "range", 15.0); }      // blocks
    private static final double STEP = 0.65;             // blocks per tick
    private static double HIT_RADIUS() { return org.money.money.meta.ClassRegistry.num("airwalker", "windsword", "hitRadius", 1.1); }        // hit radius each step
    private static double DAMAGE() { return org.money.money.meta.ClassRegistry.num("airwalker", "windsword", "damage", 12.0); }            // 3 hearts

    private static final int PARTICLE_POINTS = 3;     // было 10 (в разы меньше)
    private static final int PARTICLE_EVERY_TICKS = 2; // частицы раз в 2 тика


    private static final int COOLDOWN_SECONDS = 7;
    private static final long COOLDOWN_MS = COOLDOWN_SECONDS * 1000L;

    private final Plugin plugin;

    private final NamespacedKey KEY_WIND_SWORD;

    // cooldown stores last use time ms
    private final Map<UUID, Long> cooldownMap = new HashMap<>();

    // actionbar timer tasks to avoid stacking multiple timers
    private final Map<UUID, BukkitTask> cdTasks = new HashMap<>();

    public WindSwordListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_WIND_SWORD = new NamespacedKey(plugin, "airwalker_wind_sword");
    }

    /* ================== Item ================== */

    /** Create the Wind Sword item. */
    public ItemStack makeWindSword() {
        ItemStack it = new ItemStack(Material.IRON_SWORD);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Wind Sword", NamedTextColor.WHITE));
        im.getPersistentDataContainer().set(KEY_WIND_SWORD, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(im);
        return it;
    }

    private boolean isWindSword(ItemStack it) {
        if (it == null || it.getType() == Material.AIR || !it.hasItemMeta()) return false;
        ItemMeta im = it.getItemMeta();
        if (im.getPersistentDataContainer().has(KEY_WIND_SWORD, PersistentDataType.BYTE)) return true;
        return Component.text("Wind Sword").equals(im.displayName());
    }

    /* ================== Interact ================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;

        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        if (!isWindSword(p.getInventory().getItemInMainHand())) return;

        e.setCancelled(true);

        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();

        if (cooldownMap.containsKey(id)) {
            long last = cooldownMap.get(id);
            long passed = now - last;
            if (passed < COOLDOWN_MS) {
                // still on cooldown
                long secLeft = (COOLDOWN_MS - passed + 999) / 1000;
                p.sendActionBar(Component.text(secLeft + " sec", NamedTextColor.RED));
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.8f);
                return;
            }
        }

        // start cooldown
        cooldownMap.put(id, now);
        startCooldownTimer(p, COOLDOWN_SECONDS);

        // cast ability
        castAirSlash(p);
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

    private void castAirSlash(Player caster) {
        World w = caster.getWorld();
        Location start = caster.getEyeLocation().clone().add(caster.getLocation().getDirection().normalize().multiply(1.2));
        Vector dir = caster.getLocation().getDirection().normalize();

        w.playSound(caster.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.2f);

        // Track players already hit (so it doesn't multi-hit)
        Set<UUID> hit = new HashSet<>();

        new BukkitRunnable() {
            double traveled = 0.0;
            Location pos = start.clone();

            @Override
            public void run() {
                if (!caster.isOnline()) {
                    cancel();
                    return;
                }

                // move forward
                pos.add(dir.clone().multiply(STEP));
                traveled += STEP;

                // stop if hit a solid block
                if (pos.getBlock().getType().isSolid()) {
                    w.playSound(pos, Sound.BLOCK_WOOL_BREAK, 0.6f, 1.6f);
                    cancel();
                    return;
                }

                // particles (air slash)
                if (((int) (traveled / STEP)) % PARTICLE_EVERY_TICKS == 0) {
                    spawnSlashParticles(w, pos, dir);
                }

                // hit detection
                for (Entity ent : w.getNearbyEntities(pos, HIT_RADIUS(), HIT_RADIUS(), HIT_RADIUS())) {
                    if (!(ent instanceof Player target)) continue;
                    if (target.getUniqueId().equals(caster.getUniqueId())) continue;
                    if (!target.isOnline() || target.isDead()) continue;
                    if (hit.contains(target.getUniqueId())) continue;

                    // damage ONLY enemy team (see helper below)
                    if (!isEnemy(caster, target)) continue;

                    hit.add(target.getUniqueId());

                    target.damage(DAMAGE(), caster);
                    w.playSound(target.getLocation(), Sound.ENTITY_PLAYER_HURT, 0.9f, 1.2f);
                    w.spawnParticle(Particle.CRIT, target.getLocation().add(0, 1.0, 0), 12, 0.25, 0.35, 0.25, 0.08);

                    // optional: stop on first hit
                    cancel();
                    return;
                }

                // max distance
                if (traveled >= SLASH_RANGE()) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void spawnSlashParticles(World w, Location center, Vector dir) {
        Vector forward = dir.clone().normalize();
        Vector side = forward.clone().crossProduct(new Vector(0, 1, 0)).normalize();

        // меньше точек, чуть рандом, но тот же "срез"
        for (int i = 0; i < PARTICLE_POINTS; i++) {
            double t = (Math.random() * 2.2) - 1.1;   // ширина
            double y = (Math.random() * 0.6) - 0.1;   // высота

            Location p = center.clone()
                    .add(side.clone().multiply(t))
                    .add(0, y, 0);

            w.spawnParticle(Particle.SWEEP_ATTACK, p, 1, 0, 0, 0, 0);
            w.spawnParticle(Particle.CLOUD, p, 1, 0.03, 0.03, 0.03, 0.005);
        }
    }


    /* ================== Team check ================== */

    /**
     * Enemy check via scoreboard teams.
     * If teams are missing -> returns false (safe: no friendly-fire by accident).
     * Change fallback if you want "damage anyone" when no teams exist.
     */
    private boolean isEnemy(Player a, Player b) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team ta = sb.getEntryTeam(a.getName());
        Team tb = sb.getEntryTeam(b.getName());
        if (ta == null || tb == null) return false;
        return !ta.getName().equalsIgnoreCase(tb.getName());
    }
}
