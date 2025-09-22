package org.money.money.kits.ganyu.listeners;

import org.bukkit.*;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.money.money.combat.ElementalReactions;

import java.util.*;

public final class GanyuBowListener implements Listener {
    private final Plugin plugin;
    private final NamespacedKey KEY_GANYU_BOW;
    private final NamespacedKey KEY_ICY_ARROW;
    private final ElementalReactions elemental;

    private static final double FREEZE_RADIUS = 3.0;      // радиус взрыва
    private static final int    SLOW_TICKS    = 50;       // 3 сек (20 тиков = 1 сек)
    private static final int    SLOW_LEVEL    = 1;        // Slowness II
    private static final int    FREEZE_ADD    = 80;       // +4 сек к текущему фризу
    private static final int    DIRECT_HIT_BONUS_FREEZE = 40; // +2 сек дополнительно при прямом попадании


    // когда начал тянуть
    private final Map<UUID, Long> drawingSince = new HashMap<>();
    // уже показали флэш на этом натяжении
    private final Set<UUID> flashed = new HashSet<>();
    // периодический «маячок» у лука, когда заряжено
    private final Map<UUID, BukkitTask> glowTask = new HashMap<>();

    // следим, чтобы снять тэг Cryo после разморозки
    private final Map<UUID, BukkitTask> cryoWatch = new HashMap<>();


    private static final boolean DEBUG = false;
    private void dbg(Player p, String msg) {
        if (!DEBUG) return;
        plugin.getLogger().info("[GANYU] " + p.getName() + " :: " + msg);
        p.sendActionBar(net.kyori.adventure.text.Component.text("[Ganyu] " + msg));
    }

    public GanyuBowListener(Plugin plugin, ElementalReactions elemental) {
        this.plugin = plugin;
        this.elemental = elemental;
        this.KEY_GANYU_BOW = new NamespacedKey(plugin, "ganyu_bow");
        this.KEY_ICY_ARROW = new NamespacedKey(plugin, "ganyu_icy_arrow");
    }

    /* ==== старт натяжения ==== */
    @EventHandler(ignoreCancelled = false, priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        if (!isGanyuBow(p.getInventory().getItemInMainHand())) return;

        long now = System.currentTimeMillis();
        drawingSince.put(p.getUniqueId(), now);
        flashed.remove(p.getUniqueId());
        cancelGlow(p);
        dbg(p, "start draw");

        // таймер на «заряжено»
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            UUID id = p.getUniqueId();
            Long start = drawingSince.get(id);
            if (start == null) return;                                   // уже отпустил/выстрелил/сброшено
            if (!p.isOnline() || p.isDead() || !p.isValid()) return;
            if (!isGanyuBow(p.getInventory().getItemInMainHand())) return;
            if (!p.isHandRaised()) return;                                // ВАЖНО: всё ещё тянет
            if (System.currentTimeMillis() - start < 2950) return;        // небольшой допуск
            if (!flashed.add(id)) return;

            dbg(p, "FLASH!");
            icyFaceBurst(p);
            p.getWorld().playSound(p.getEyeLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 2.0f);
            startChargedGlow(p);                                          // включаем маячок у лука
        }, 60L); // 3 сек
    }

    /* ==== выстрел ==== */
    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!isGanyuBow(e.getBow())) return;

        UUID id = p.getUniqueId();
        Long start = drawingSince.remove(id);
        boolean flashedNow = flashed.remove(id);
        cancelGlow(p);

        long elapsed = (start == null) ? 0L : System.currentTimeMillis() - start;
        boolean charged = flashedNow || elapsed >= 2950;

        dbg(p, "shoot: elapsed=" + elapsed + "ms, flashed=" + flashedNow + ", charged=" + charged);

        if (charged && e.getProjectile() instanceof org.bukkit.entity.AbstractArrow arrow) {
            arrow.getPersistentDataContainer().set(KEY_ICY_ARROW, PersistentDataType.BYTE, (byte) 1);
            p.getWorld().playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.9f);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.AbstractArrow arrow)) return;
        Byte tag = arrow.getPersistentDataContainer().get(KEY_ICY_ARROW, PersistentDataType.BYTE);
        if (tag == null || tag == 0) return;

        Location loc = arrow.getLocation();
        World w = loc.getWorld();

        // визуал/звук
        icyExplosion(loc);
        w.playSound(loc, Sound.BLOCK_GLASS_BREAK, 0.9f, 1.6f);
        w.playSound(loc, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.6f, 1.8f);

        // кто стрелял (для атрибуции урона)
        LivingEntity shooter = (arrow.getShooter() instanceof LivingEntity le) ? le : null;

        // прямое попадание — +1 урона и усиленная заморозка
        LivingEntity exclude = null;
        if (e.getHitEntity() instanceof LivingEntity hit) {
            exclude = hit;

            double d = elemental.applyOnTotalDamage(
                    hit,
                    1.0, // бонус за прямое попадание
                    ElementalReactions.Element.CRYO,
                    /*newAuraTicks=*/FREEZE_ADD + DIRECT_HIT_BONUS_FREEZE,
                    /*consumeOnReact=*/true
            );
            if (shooter != null) hit.damage(d, shooter);
            else hit.damage(d);

            applyFreezeAndSlow(hit, FREEZE_ADD + DIRECT_HIT_BONUS_FREEZE);
        }
        // AOE по радиусу (урон 2.0 = 1 сердце) + заморозка
        freezeNearby(loc, FREEZE_RADIUS, shooter, exclude);

        arrow.remove();
    }



    /* ==== сброс/очистка состояний ==== */
    @EventHandler public void onSwap(PlayerItemHeldEvent e)          { reset(e.getPlayer()); }
    @EventHandler public void onSwapHands(PlayerSwapHandItemsEvent e){ reset(e.getPlayer()); }
    @EventHandler public void onDrop(PlayerDropItemEvent e)          { reset(e.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent e)              { reset(e.getPlayer()); }

    private void reset(Player p) {
        drawingSince.remove(p.getUniqueId());
        flashed.remove(p.getUniqueId());
        cancelGlow(p);
    }


    private void freezeNearby(Location center, double radius, LivingEntity source, LivingEntity exclude) {
        World w = center.getWorld();
        for (var ent : w.getNearbyEntities(center, radius, radius, radius)) {
            if (!(ent instanceof LivingEntity le) || !le.isValid() || le.isDead()) continue;
            if (exclude != null && le.equals(exclude)) continue;

            double d = elemental.applyOnTotalDamage(
                    le,
                    6.0, // 2 сердце
                    ElementalReactions.Element.CRYO,
                    /*newAuraTicks=*/FREEZE_ADD,
                    /*consumeOnReact=*/true
            );
            if (source != null) le.damage(d, source); else le.damage(d);

            applyFreezeAndSlow(le, FREEZE_ADD);
        }
    }



    private void applyFreezeAndSlow(LivingEntity le, int addFreezeTicks) {
        le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, SLOW_TICKS, SLOW_LEVEL, false, true, true));
        int cur = le.getFreezeTicks();
        int max = le.getMaxFreezeTicks();
        le.setFreezeTicks(Math.min(max, cur + addFreezeTicks));

        if (le instanceof Player pl) {
            pl.playSound(pl.getEyeLocation(), Sound.BLOCK_POWDER_SNOW_STEP, 0.7f, 1.2f);
        } else {
            le.getWorld().playSound(le.getLocation(), Sound.BLOCK_POWDER_SNOW_STEP, 0.7f, 1.0f);
        }

    }




    /* ==== utils ==== */

    private boolean isGanyuBow(ItemStack it) {
        if (it == null || it.getType() != Material.BOW || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(KEY_GANYU_BOW, PersistentDataType.BYTE);
    }

    // разовый «пшик» у лица
    private void icyFaceBurst(Player p) {
        World w = p.getWorld();
        Location eye = p.getEyeLocation().add(p.getLocation().getDirection().normalize().multiply(0.5));
        Particle.DustOptions ice = new Particle.DustOptions(org.bukkit.Color.fromRGB(120, 180, 255), 1.2f);

        for (int i = 0; i < 20; i++) {
            Vector rand = new Vector((Math.random() - 0.5) * 0.4, (Math.random() - 0.2) * 0.4, (Math.random() - 0.5) * 0.4);
            w.spawnParticle(Particle.DUST, eye.clone().add(rand), 1, 0, 0, 0, 0, ice);
        }
        w.spawnParticle(Particle.SNOWFLAKE, eye, 8, 0.15, 0.15, 0.15, 0.0);
        w.playSound(eye, Sound.BLOCK_SNOW_PLACE, 0.7f, 1.4f);
    }

    // постоянный «маячок» у лука, пока заряжено и игрок держит натянутым
    private void startChargedGlow(Player p) {
        cancelGlow(p);
        UUID id = p.getUniqueId();
        Particle.DustOptions ice = new Particle.DustOptions(org.bukkit.Color.fromRGB(120, 180, 255), 1.0f);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // если перестал тянуть/сменил предмет — выключаем и сбрасываем состояние
            if (!p.isOnline() || p.isDead() || !isGanyuBow(p.getInventory().getItemInMainHand()) || !p.isHandRaised() || !flashed.contains(id)) {
                drawingSince.remove(id);
                flashed.remove(id);
                cancelGlow(p);
                return;
            }
            Location spot = bowSpot(p);
            p.getWorld().spawnParticle(Particle.DUST, spot, 1, 0, 0, 0, 0, ice);
        }, 0L, 3L); // раз в 3 тика

        glowTask.put(id, task);
    }

    private void cancelGlow(Player p) {
        BukkitTask t = glowTask.remove(p.getUniqueId());
        if (t != null) t.cancel();
    }

    // примерная позиция лука: правее, чуть вперёд и вниз от глаз
    private Location bowSpot(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Vector right = dir.clone().crossProduct(new Vector(0, 1, 0)).normalize(); // вправо
        return eye.clone()
                .add(right.multiply(0.35))   // вправо
                .add(dir.multiply(0.35))     // вперёд
                .add(0, -0.25, 0);           // немного вниз
    }

    private void icyExplosion(Location loc) {
        World w = loc.getWorld();
        Particle.DustOptions ice = new Particle.DustOptions(org.bukkit.Color.fromRGB(120, 180, 255), 1.4f);

        for (double r = 0.4; r <= 3; r += 0.5) {      // радиус от центра
            for (int i = 0; i < 24; i++) {            // азимут вокруг оси
                double theta = 2 * Math.PI * i / 24.0;

                for (int j = 0; j < 12; j++) {        // угол вверх-вниз
                    double phi = Math.PI * j / 12.0;  // 0..π

                    double x = r * Math.sin(phi) * Math.cos(theta);
                    double y = r * Math.cos(phi);
                    double z = r * Math.sin(phi) * Math.sin(theta);

                    Location p = loc.clone().add(x, y, z);
                    w.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, ice);
                }
            }
        }

        w.spawnParticle(Particle.SNOWFLAKE, loc, 60, 0.8, 0.6, 0.8, 0.01);
        w.spawnParticle(Particle.CLOUD, loc, 12, 0.4, 0.2, 0.4, 0.01);
    }
}
