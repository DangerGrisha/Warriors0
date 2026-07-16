package org.money.money.kits.bluerose;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
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
import org.bukkit.scheduler.BukkitTask;

import org.money.money.session.KitSession;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ability 1 — Blue Rose Ward / Голубая Роза-Хранитель (роза-ловушка).
 *
 * <p>ПКМ по блоку — на земле «сажается» скрытая морозная роза: кольцо-зона НЕ показывается
 * (невидимый круг, радиус чуть больше). Пока внутрь не зашёл враг — роза спит, лишь потихоньку
 * лечит тиммейтов в 2 блоках от цветка. Как только враг входит в круг (цилиндр высотой ≤3) —
 * роза «распускается» волной частиц от центра к краю и ловит его: сильное замедление (двигаться
 * едва можно, прыгать нельзя) + периодический урон, пока он в круге. Вышел — круг сворачивается
 * обратно к центру и роза снова спит.
 */
public final class BlueRoseWardListener implements Listener {

    private final BlueRoseGuardianManager m;

    public BlueRoseWardListener(BlueRoseGuardianManager m) { this.m = m; }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!m.isWard(p.getInventory().getItemInMainHand())) return;
        e.setCancelled(true);
        if (!KitSession.isInGame(p)) return;
        if (e.getClickedBlock() == null) return;

        final Location support = e.getClickedBlock().getLocation();
        final Location center = support.clone().add(0.5, 1.0, 0.5);
        if (center.getBlock().getType().isSolid()) {
            p.sendActionBar(Component.text("No room for the rose here", NamedTextColor.RED));
            return;
        }

        consumeOne(p);
        m.giveItemLater(p.getUniqueId(), m.makeWard(),
                m.numInt("ward", "returnDelayTicks", 1200), "Blue Rose Ward returned");

        final boolean bloodline = m.bloodlineActive(p);
        final int totalTicks = bloodline
                ? (int) Math.round(m.numInt("ward", "durationTicks", 700) * m.bloodlineWardDurationMult())
                : m.numInt("ward", "durationTicks", 700);
        final double radius = m.num("ward", "radius", 8.5) + (bloodline ? m.bloodlineWardRadiusBonus() : 0);
        final double height = m.num("ward", "heightLimit", 3.0);
        final int healInterval = Math.max(5, m.numInt("ward", "allyHealIntervalTicks", 20));

        final RoseZone zone = new RoseZone(RoseZone.Kind.WARD, p.getUniqueId());
        zone.center = center.clone();
        zone.radius = radius;

        m.enforceWardLimit(p.getUniqueId(), m.numInt("ward", "maxActiveWards", 2));
        zone.setAnchor(m.spawnRoseAnchor(center, "blue_rose_guardian_ward"));

        BlueRoseVisualUtil.soundPlace(center);
        BlueRoseVisualUtil.roseBloom(center);

        final UUID ownerId = p.getUniqueId();
        BukkitTask task = new BukkitRunnable() {
            int elapsed = 0;
            boolean active = false;

            @Override public void run() {
                Player owner = Bukkit.getPlayer(ownerId);
                if (elapsed >= totalTicks
                        || owner == null || !owner.isOnline() || !KitSession.isInGame(owner)
                        || !support.getBlock().getType().isSolid()) {
                    if (active) waveAnimation(center, radius, false); // сворачиваем при завершении
                    BlueRoseVisualUtil.roseBurst(center, 0.6f);
                    m.endZone(zone);
                    return;
                }

                // Пассив: цветок потихоньку лечит тиммейтов в 2 блоках (спит он или активен).
                if (elapsed % healInterval == 0) regenAlliesNear(owner);

                List<Player> trapped = enemiesInCylinder(owner, center, radius, height);
                boolean anyEnemy = !trapped.isEmpty();

                // Переходы состояния: враг вошёл — распускаемся; вышел — сворачиваемся.
                if (anyEnemy && !active) {
                    active = true;
                    BlueRoseVisualUtil.soundBind(center);
                    waveAnimation(center, radius, true);
                } else if (!anyEnemy && active) {
                    active = false;
                    waveAnimation(center, radius, false);
                }

                if (active) {
                    // Полное кольцо + верхний обод (цилиндр по высоте) + ловушка на врагов.
                    // force=true — кольцо видно ВСЕМ игрокам (в т.ч. с урезанными настройками частиц).
                    BlueRoseVisualUtil.groundRing(center, radius, 40, true);
                    BlueRoseVisualUtil.groundRing(center.clone().add(0, height, 0), radius, 40, true);
                    if (elapsed % 10 == 0) BlueRoseVisualUtil.frostDust(center, radius, 12, true);
                    trapEnemies(owner, trapped);
                } else {
                    // Спит: НИКАКОГО кольца, лишь скромный цветок в центре.
                    if (elapsed % 20 == 0) BlueRoseVisualUtil.roseBloom(center);
                }

                elapsed += 5;
            }

            /** Сильное замедление + запрет прыжка каждый цикл, урон — раз в dotInterval. */
            private void trapEnemies(Player owner, List<Player> trapped) {
                int slowAmp = m.numInt("ward", "trapSlowAmplifier", 4);
                boolean dotTick = elapsed % Math.max(5, m.numInt("ward", "dotIntervalTicks", 20)) == 0;
                double dot = m.num("ward", "trapDamagePerSecond", 1.0);
                for (Player enemy : trapped) {
                    m.applySlow(enemy, 12, slowAmp);   // "чучуть двигаться" — очень медленно, но можно
                    m.applyNoJump(enemy, 12);          // прыгать нельзя
                    BlueRoseVisualUtil.bodyPetals(enemy.getLocation());
                    if (dotTick && dot > 0) {
                        m.damageNoKnockback(enemy, dot, owner);
                        BlueRoseVisualUtil.iceSpike(enemy.getLocation());
                    }
                }
            }

            /** Тиммейты (и сама Аяка) в пределах allyRegenRadius от цветка — маленький реген. */
            private void regenAlliesNear(Player owner) {
                double allyR = m.num("ward", "allyRegenRadius", 2.0);
                double heal = m.num("ward", "allyRegenHealAmount", 0.5);
                int amp = m.numInt("ward", "allyRegenAmplifier", 0);
                for (Player ally : m.alliesIn(owner, center, allyR, true)) {
                    m.healAlly(ally, heal);           // фактическое лечение (без «сброса» эффекта)
                    m.applyRegen(ally, 30, amp);      // визуал реген-частиц
                    BlueRoseVisualUtil.bodyPetals(ally.getLocation());
                }
            }
        }.runTaskTimer(m.plugin(), 0L, 5L);

        zone.setTask(task);
        m.registerZone(zone);
    }

    /** Волна частиц: expand=true — от центра к краю (роспуск), false — от края к центру (сворачивание). */
    private void waveAnimation(Location center, double maxR, boolean expand) {
        final int steps = Math.max(4, m.numInt("ward", "waveTicks", 12));
        new BukkitRunnable() {
            int step = 0;
            @Override public void run() {
                if (step > steps) { cancel(); return; }
                double frac = (double) step / steps;
                double r = expand ? maxR * frac : maxR * (1.0 - frac);
                BlueRoseVisualUtil.groundRing(center, Math.max(0.2, r), 32, true);
                BlueRoseVisualUtil.frostDust(center, Math.max(0.3, r), 8, true);
                step++;
            }
        }.runTaskTimer(m.plugin(), 0L, 1L);
    }

    /** Враги внутри цилиндра: в радиусе по горизонтали и не выше {@code height} по вертикали. */
    private List<Player> enemiesInCylinder(Player owner, Location center, double radius, double height) {
        List<Player> out = new ArrayList<>();
        World w = center.getWorld();
        if (w == null) return out;
        double r2 = radius * radius;
        for (Entity ent : w.getNearbyEntities(center, radius, height, radius)) {
            if (!(ent instanceof Player pl) || !pl.isOnline() || pl.isDead()) continue;
            if (pl.getUniqueId().equals(owner.getUniqueId())) continue;
            if (pl.getGameMode() == GameMode.SPECTATOR || pl.getGameMode() == GameMode.CREATIVE) continue;
            if (m.isFriendly(owner, pl)) continue;
            Location l = pl.getLocation();
            double dx = l.getX() - center.getX(), dz = l.getZ() - center.getZ();
            if (dx * dx + dz * dz > r2) continue;                  // горизонталь: в радиусе круга
            if (Math.abs(l.getY() - center.getY()) > height) continue; // высота: не выше height
            out.add(pl);
        }
        return out;
    }

    /** Снять один предмет-способность из главной руки. */
    private void consumeOne(Player p) {
        var hand = p.getInventory().getItemInMainHand();
        int amt = hand.getAmount();
        if (amt <= 1) p.getInventory().setItemInMainHand(null);
        else hand.setAmount(amt - 1);
    }
}
