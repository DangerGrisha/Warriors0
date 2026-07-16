package org.money.money.kits.bluerose;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import org.money.money.kits.blastborn.ExplosionUtil;
import org.money.money.session.KitSession;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Ability 3 — Petal Step / Лепестковый Шаг.
 *
 * <p>(Shift+)ПКМ — короткий элегантный рывок вперёд (не сквозь стены), оставляющий дорожку
 * лепестков: союзникам Speed (и маленький heal при первом входе), врагам Slowness + Rose Seed.
 * Не мобильность ассасина — инструмент создания безопасного маршрута для команды.
 */
public final class PetalStepListener implements Listener {

    private final BlueRoseGuardianManager m;

    public PetalStepListener(BlueRoseGuardianManager m) { this.m = m; }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!m.isPetalStep(p.getInventory().getItemInMainHand())) return;
        e.setCancelled(true);
        if (!KitSession.isInGame(p)) return;
        if (m.isOnCooldown(p, "petalstep", 1300, "Petal Step")) return;

        m.triggerCooldown(p, "petalstep", 1300, "Petal Step");

        final double dashDistance = m.num("petalstep", "dashDistance", 5.5);
        final int dashTicks = Math.max(1, m.numInt("petalstep", "dashDurationTicks", 6));
        final int trailDuration = m.numInt("petalstep", "trailDurationTicks", 1200);

        Vector dir = p.getEyeLocation().getDirection();
        dir.setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 0, 1); else dir.normalize();

        // Стена впереди — укоротить рывок (не клипаемся).
        double clamp = dashDistance;
        RayTraceResult ray = ExplosionUtil.rayTrace(p, dashDistance + 0.6);
        if (ray != null && ray.getHitPosition() != null) {
            double d = p.getEyeLocation().toVector().distance(ray.getHitPosition());
            clamp = Math.max(0.0, Math.min(dashDistance, d - 0.8));
        }

        final Location start = p.getLocation().clone();
        final Location end = start.clone().add(dir.clone().multiply(clamp));

        // Рывок: импульс по горизонтали (без вертикального абуза).
        double speed = clamp / dashTicks * 1.05;
        Vector vel = dir.clone().multiply(speed);
        vel.setY(0.12);
        p.setVelocity(vel);

        BlueRoseVisualUtil.soundDash(start);
        BlueRoseVisualUtil.sakuraLine(start, end, 0.5);

        // Точки дорожки (фиксированные на момент рывка).
        final List<Location> points = new ArrayList<>();
        double total = start.distance(end);
        if (total < 0.5) points.add(start.clone());
        else for (double d = 0; d <= total; d += 1.0) {
            points.add(start.clone().add(dir.clone().multiply(d)).add(0, 0.1, 0));
        }

        startTrail(p.getUniqueId(), points, trailDuration);
    }

    private void startTrail(UUID ownerId, List<Location> points, int trailDuration) {
        final RoseZone zone = new RoseZone(RoseZone.Kind.TRAIL, ownerId);
        final int allySpeedAmp = m.numInt("petalstep", "allySpeedAmplifier", 1);
        final int allySpeedDur = m.numInt("petalstep", "allySpeedDurationTicks", 30);
        final int enemySlowAmp = m.numInt("petalstep", "enemySlowAmplifier", 1);
        final int enemySlowDur = m.numInt("petalstep", "enemySlowDurationTicks", 30);
        final int seedCd = m.numInt("petalstep", "enemySeedApplicationCooldownTicks", 50);
        final int seedDur = m.numInt("seeds", "enemyDurationTicks", 100);
        final Set<UUID> healedOnce = new HashSet<>();
        final double reach = 1.4;

        var task = new BukkitRunnable() {
            int elapsed = 0;
            @Override public void run() {
                Player owner = org.bukkit.Bukkit.getPlayer(ownerId);
                if (elapsed >= trailDuration || owner == null || !owner.isOnline() || !KitSession.isInGame(owner)) {
                    m.endZone(zone);
                    return;
                }
                // Визуал дорожки (сакура на уровне ног).
                for (Location pt : points) BlueRoseVisualUtil.sakuraTrailTile(pt);

                if (elapsed % 10 == 0) {
                    org.bukkit.World w = points.get(0).getWorld();
                    if (w == null) { m.endZone(zone); return; }
                    for (Player pl : w.getPlayers()) {
                        if (!pl.isOnline() || pl.isDead()) continue;
                        if (!onTrail(pl.getLocation())) continue;
                        if (pl.getUniqueId().equals(ownerId) || m.isFriendly(owner, pl)) {
                            m.applySpeed(pl, allySpeedDur, allySpeedAmp);
                            if (healedOnce.add(pl.getUniqueId())) m.healAlly(pl, 1.0);
                        } else if (m.isEnemy(owner, pl)) {
                            m.applySlow(pl, enemySlowDur, enemySlowAmp);
                            if (m.seeds().tryApplyCooldown(ownerId, pl.getUniqueId(), seedCd)) {
                                m.seeds().applyEnemySeed(owner, pl, seedDur);
                            }
                        }
                    }
                }
                elapsed += 5;
            }

            private boolean onTrail(Location loc) {
                for (Location pt : points) {
                    if (pt.getWorld() == null || !pt.getWorld().equals(loc.getWorld())) continue;
                    double dx = loc.getX() - pt.getX();
                    double dz = loc.getZ() - pt.getZ();
                    double dy = Math.abs(loc.getY() - pt.getY());
                    if (dy <= 1.0 && (dx * dx + dz * dz) <= reach * reach) return true;   // линия в один блок высотой
                }
                return false;
            }
        }.runTaskTimer(m.plugin(), 0L, 5L);

        zone.setTask(task);
        m.registerZone(zone);
    }
}
