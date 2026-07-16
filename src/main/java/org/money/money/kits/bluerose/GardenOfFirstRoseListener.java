package org.money.money.kits.bluerose;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitRunnable;

import org.money.money.session.KitSession;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Ultimate — Garden of the First Rose / Сад Первой Розы (Ayaka).
 *
 * <p>ПКМ (self-cast) → 3-секундный каст-заклинание с частицами/звуками, затем ВОКРУГ кастера
 * расцветает сад на 10с: замораживает ВСЕХ в радиусе (тиммейтов, врагов и саму Аяку). Тиммейты
 * и сама Аяка — слабый реген; враги — «иссушение» (Wither). Легендарная First Rose Salvation
 * (death-save) сохранена. Один сад на Guardian'а. (Морозная буря Soumetsu — отдельная способность.)
 */
public final class GardenOfFirstRoseListener implements Listener {

    private final BlueRoseGuardianManager m;

    public GardenOfFirstRoseListener(BlueRoseGuardianManager m) { this.m = m; }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!m.isGarden(p.getInventory().getItemInMainHand())) return;
        e.setCancelled(true);
        if (!KitSession.isInGame(p)) return;
        if (m.isOnCooldown(p, "garden", 2000, "Garden")) return;

        m.triggerCooldown(p, "garden", 2000, "Garden");
        startCast(p);
    }

    /* ===================== 3-секундный каст ===================== */

    private void startCast(Player p) {
        final int castTicks = Math.max(10, m.numInt("garden", "castTicks", 60));
        final UUID id = p.getUniqueId();

        BlueRoseVisualUtil.soundGarden(p.getLocation());
        p.sendActionBar(Component.text("Channeling the First Rose…", NamedTextColor.AQUA));

        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                Player owner = Bukkit.getPlayer(id);
                if (owner == null || !owner.isOnline() || owner.isDead() || !KitSession.isInGame(owner)) {
                    cancel();
                    return;
                }
                t++;
                castParticles(owner.getLocation(), t);
                if (t % 12 == 0) BlueRoseVisualUtil.soundBind(owner.getLocation());
                if (t >= castTicks) {
                    cancel();
                    startGardenZone(owner, owner.getLocation().clone().add(0, 0.1, 0));
                }
            }
        }.runTaskTimer(m.plugin(), 1L, 1L);
    }

    private void castParticles(Location loc, int t) {
        BlueRoseVisualUtil.bodyPetals(loc.clone().add(0, 1, 0));
        double r = 3.2 * (1.0 - (double) (t % 20) / 20.0) + 0.5;
        BlueRoseVisualUtil.frostDust(loc, r, 10);
        if (t % 12 == 0) BlueRoseVisualUtil.groundRing(loc, r, 24);
    }

    /* ===================== Зона сада (10с) ===================== */

    private void startGardenZone(Player p, Location center) {
        final int totalTicks = m.numInt("garden", "durationTicks", 200);
        final double radius = m.num("garden", "radius", 7.5);
        final int effectInterval = Math.max(5, m.numInt("garden", "effectIntervalTicks", 20));

        m.enforceSingleGarden(p.getUniqueId());

        final RoseZone zone = new RoseZone(RoseZone.Kind.GARDEN, p.getUniqueId());
        zone.center = center.clone();
        zone.radius = radius;
        zone.deathSavesLeft = Math.max(0, m.numInt("garden", "deathSaveUsesPerGarden", 1));
        zone.setAnchor(m.spawnRoseAnchor(center, "blue_rose_guardian_garden"));

        BlueRoseVisualUtil.soundGarden(center);
        BlueRoseVisualUtil.roseBurst(center, 1.6f);
        p.sendActionBar(Component.text("Garden of the First Rose blooms", NamedTextColor.AQUA));

        final UUID ownerId = p.getUniqueId();
        var task = new BukkitRunnable() {
            int elapsed = 0;
            final Set<UUID> regenApplied = new HashSet<>();
            final Set<UUID> witherApplied = new HashSet<>();

            @Override public void run() {
                Player owner = Bukkit.getPlayer(ownerId);
                if (elapsed >= totalTicks || owner == null || !owner.isOnline() || !KitSession.isInGame(owner)) {
                    BlueRoseVisualUtil.roseBurst(center, 0.8f);
                    m.endZone(zone);
                    return;
                }

                BlueRoseVisualUtil.groundRing(center, radius, 48);
                BlueRoseVisualUtil.groundRing(center, radius * 0.6, 28);
                BlueRoseVisualUtil.roseBloom(center);
                if (elapsed % 10 == 0) BlueRoseVisualUtil.frostDust(center, radius, 14);

                freezeEveryone(owner);
                if (elapsed % effectInterval == 0) {
                    regenAlliesAndSelf(owner);
                    drainEnemies(owner);
                }

                elapsed += 5;
            }

            private void freezeEveryone(Player owner) {
                World w = center.getWorld();
                if (w == null) return;
                int refresh = Math.max(6, m.numInt("garden", "freezeRefreshTicks", 12));
                double r2 = radius * radius;
                for (Entity ent : w.getNearbyEntities(center, radius, radius, radius)) {
                    if (!(ent instanceof Player pl) || !pl.isOnline() || pl.isDead()) continue;
                    if (pl.getLocation().distanceSquared(center) > r2) continue;
                    m.freezeGarden(pl.getUniqueId(), refresh);
                    pl.setFallDistance(0);
                    BlueRoseVisualUtil.bodyPetals(pl.getLocation());
                }
            }

            private void regenAlliesAndSelf(Player owner) {
                int regenAmp = m.numInt("garden", "allyRegenAmplifier", 0);
                int remaining = Math.max(20, totalTicks - elapsed);
                for (Player ally : m.alliesIn(owner, center, radius, true)) {
                    if (regenApplied.add(ally.getUniqueId())) m.applyRegen(ally, remaining, regenAmp);
                    BlueRoseVisualUtil.bodyPetals(ally.getLocation());
                }
            }

            private void drainEnemies(Player owner) {
                int witherAmp = m.numInt("garden", "enemyWitherAmplifier", 0);
                double drainDmg = m.num("garden", "enemyDrainDamage", 0.0);
                int remaining = Math.max(20, totalTicks - elapsed);
                for (Player enemy : m.enemiesIn(owner, center, radius)) {
                    if (witherApplied.add(enemy.getUniqueId())) m.applyWither(enemy, remaining, witherAmp);
                    if (drainDmg > 0) m.safeDamage(enemy, drainDmg, owner);
                    BlueRoseVisualUtil.iceSpike(enemy.getLocation());
                }
            }
        }.runTaskTimer(m.plugin(), 0L, 5L);

        zone.setTask(task);
        m.registerZone(zone);
    }
}
