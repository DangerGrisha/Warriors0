package org.money.money.kits.ganyu.listeners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;
import org.money.money.combat.ElementalReactions;

import java.util.*;

public final class GanyuUltListener implements Listener {

    private final Plugin plugin;
    private final ElementalReactions elemental;

    // Ключи
    private final NamespacedKey KEY_ULT_ITEM;    // на Heart of the Sea у игрока
    private final NamespacedKey KEY_SPHERE;      // на главном ArmorStand (IceSphere)
    private final NamespacedKey KEY_HITTER;      // на падающих ArmorStand’ах (IceHitter)
    private final NamespacedKey KEY_OWNER;       // UUID владельца (строкой) на сферах/хиттерах

    // Параметры
    private static final int    COOLDOWN_TICKS       = 20 * 150; // 2.5 мин
    private static final int    SPHERE_LIFETIME      = 20 * 50;  // длительность «ульты» ~50c
    private static final double SPHERE_RADIUS_XZ     = 30.0;     // радиус круга в небе
    private static final int    RING_POINTS          = 96;       // плотность круга

    private static final int    RAIN_INTERVAL_TICKS  = 2;        // каждые 0.4с спавним «ледяную болванку»
    private static final int    HITTER_CHECK_TICKS   = 2;        // как часто проверять приземление
    private static final double HITTER_IMPACT_RADIUS = 6.0;      // АОЕ при ударе
    private static final double HITTER_DAMAGE        = 4;      // 2 ❤ урона

    private static final int    FREEZE_ADD_TICKS     = 100;      // +5с инея
    private static final int    SLOW_TICKS           = 80;       // 4с
    private static final int    SLOW_LEVEL           = 1;        // Slowness II
    private static final String CRYO_TAG             = "Cryo";

    // Таски на центральную сферу
    private final Map<UUID, BukkitTask> spinTasks  = new HashMap<>();
    private final Map<UUID, BukkitTask> ringTasks  = new HashMap<>();
    private final Map<UUID, BukkitTask> rainTasks  = new HashMap<>();

    // Мониторы падения «хиттеров»
    private final Map<UUID, BukkitTask> hitterMonitors = new HashMap<>();

    public GanyuUltListener(Plugin plugin, ElementalReactions elemental) {
        this.plugin = plugin;
        this.elemental = elemental;
        this.KEY_ULT_ITEM = new NamespacedKey(plugin, "ganyu_ult_item");
        this.KEY_SPHERE   = new NamespacedKey(plugin, "ganyu_ult_sphere");
        this.KEY_HITTER   = new NamespacedKey(plugin, "ganyu_ult_hitter");
        this.KEY_OWNER    = new NamespacedKey(plugin, "ganyu_ult_owner");
    }

    /* ======================= ПРЕДМЕТ ======================= */

    /** Выдать игроку «ульт»-предмет (в своём giver/команде дергай этот метод). */
    public ItemStack makeUltItem() {
        ItemStack it = new ItemStack(Material.HEART_OF_THE_SEA);
        ItemMeta im = it.getItemMeta();
        im.setDisplayName("§bEverfrost Core");
        im.setLore(List.of("§7Right-click to cast §bIceSphere§7."));
        im.getPersistentDataContainer().set(KEY_ULT_ITEM, PersistentDataType.BYTE, (byte)1);
        im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(im);
        return it;
    }

    private boolean isUltItem(ItemStack it) {
        return it != null
                && it.getType() == Material.HEART_OF_THE_SEA
                && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(KEY_ULT_ITEM, PersistentDataType.BYTE);
    }

    /* ======================= АКТИВАЦИЯ ======================= */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onUseUlt(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!isUltItem(hand)) return;

        // не даём «ванилле» что-то сделать
        e.setUseItemInHand(Event.Result.DENY);
        e.setCancelled(true);

        // забираем один предмет и ставим на кд
        if (hand.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
        else hand.setAmount(hand.getAmount() - 1);
        p.playSound(p.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 0.7f, 1.6f);

        // вернём через 2.5 минуты
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            p.getInventory().addItem(makeUltItem());
            p.sendMessage(
                    Component.text("Everfrost Core", NamedTextColor.AQUA)
                            .append(Component.text(" is ready again!", NamedTextColor.GRAY))
            );
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.45f, 1.7f);
        }, COOLDOWN_TICKS);

        // центральная сфера на +10 по Y
        Location sphereLoc = p.getLocation().clone().add(0, 10, 0);
        ArmorStand sphere = spawnSphere(sphereLoc, p);

        // проживёт SPHERE_LIFETIME
        startSphereTasks(sphere);

        Bukkit.getScheduler().runTaskLater(plugin, () -> removeSphere(sphere), SPHERE_LIFETIME);
    }

    /* ======================= СФЕРА ======================= */

    private ArmorStand spawnSphere(Location loc, Player owner) {
        return loc.getWorld().spawn(loc, ArmorStand.class, s -> {
            s.setInvisible(true);
            s.setMarker(true);
            s.setInvulnerable(true);
            s.setGravity(false);
            s.setCustomNameVisible(true);
            s.setCustomName("§bIceSphere");

            // ► Руки включаем и ставим строго вниз
            s.setArms(true);
            EulerAngle down = new EulerAngle(0, 0, 0); // X,Y,Z в радианах
            s.setRightArmPose(down);
            s.setLeftArmPose(down);
            s.setBasePlate(false); // опционально, убрать «пяточку»

            ItemStack redDye = new ItemStack(Material.HEART_OF_THE_SEA);
            ItemMeta dyeMeta = redDye.getItemMeta();
            dyeMeta.displayName(Component.text("freezeSmoke"));
            redDye.setItemMeta(dyeMeta);
            s.getEquipment().setItemInMainHand(redDye);

            var pdc = s.getPersistentDataContainer();
            pdc.set(KEY_SPHERE, PersistentDataType.BYTE, (byte)1);
            pdc.set(KEY_OWNER,  PersistentDataType.STRING, owner.getUniqueId().toString());
        });
    }

    private void startSphereTasks(ArmorStand sphere) {
        UUID id = sphere.getUniqueId();

        // вращение
        spinTasks.put(id, Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!sphere.isValid() || sphere.isDead()) { cancel(spinTasks.remove(id)); return; }
            Location l = sphere.getLocation();
            l.setYaw(l.getYaw() + 6f);
            sphere.teleport(l);
        }, 0L, 2L));

        // рисуем круг партиклами в небе
        Particle.DustOptions ice = new Particle.DustOptions(org.bukkit.Color.fromRGB(120,180,255), 1.15f);
        ringTasks.put(id, Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!sphere.isValid() || sphere.isDead()) { cancel(ringTasks.remove(id)); return; }
            renderRing(sphere.getWorld(), sphere.getLocation(), SPHERE_RADIUS_XZ, ice);
        }, 0L, 5L));

        // «ледяной дождь»: рандомные падения
        rainTasks.put(id, Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!sphere.isValid() || sphere.isDead()) { cancel(rainTasks.remove(id)); return; }
            spawnHitterRandom(sphere);
        }, 0L, RAIN_INTERVAL_TICKS));
    }

    private void removeSphere(ArmorStand sphere) {
        UUID id = sphere.getUniqueId();
        cancel(spinTasks.remove(id));
        cancel(ringTasks.remove(id));
        cancel(rainTasks.remove(id));
        if (sphere.isValid()) sphere.remove();
    }

    private void renderRing(World w, Location center, double radius, Particle.DustOptions ice) {
        int pts = RING_POINTS;
        for (int i = 0; i < pts; i++) {
            double ang = (Math.PI * 2) * i / pts;
            double x = Math.cos(ang) * radius;
            double z = Math.sin(ang) * radius;
            w.spawnParticle(Particle.DUST, center.clone().add(x, 0, z), 1, 0,0,0, 0, ice);
        }
        w.spawnParticle(Particle.SNOWFLAKE, center, 12, 1.2, 0.2, 1.2, 0.01);
    }

    /* ======================= ПАДАЮЩИЕ «ХИТТЕРЫ» ======================= */

    private void spawnHitterRandom(ArmorStand sphere) {
        World w = sphere.getWorld();
        Location c = sphere.getLocation();

        // равномерный по диску радиус 0..R
        double r = SPHERE_RADIUS_XZ * Math.sqrt(Math.random());
        // равномерный угол 0..2π
        double ang = Math.random() * Math.PI * 2;

        double x = c.getX() + Math.cos(ang) * r;
        double z = c.getZ() + Math.sin(ang) * r;
        Location spawn = new Location(w, x, c.getY(), z);

        // спавним «груз» — ArmorStand с гравитацией
        ArmorStand as = w.spawn(spawn, ArmorStand.class, s -> {
            s.setInvisible(true);
            s.setMarker(false);
            s.setInvulnerable(true);
            s.setGravity(true);
            s.setCustomNameVisible(false);

            ItemStack core = new ItemStack(Material.HEART_OF_THE_SEA);
            ItemMeta cm = core.getItemMeta(); cm.setDisplayName("§bIceHitter"); core.setItemMeta(cm);
            s.getEquipment().setItemInMainHand(core);

            s.getPersistentDataContainer().set(KEY_HITTER, PersistentDataType.BYTE, (byte)1);
            // пробуем подтянуть владельца из сферы
            String owner = sphere.getPersistentDataContainer().get(KEY_OWNER, PersistentDataType.STRING);
            if (owner != null) s.getPersistentDataContainer().set(KEY_OWNER, PersistentDataType.STRING, owner);
        });

        monitorHitter(as);
    }

    private void monitorHitter(ArmorStand hitter) {
        UUID id = hitter.getUniqueId();
        hitterMonitors.put(id, Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!hitter.isValid() || hitter.isDead()) { cancel(hitterMonitors.remove(id)); return; }

            // приземлился? (на практике — или onGround, или снизу не воздух)
            boolean onGround = hitter.isOnGround();
            if (!onGround) {
                Material below = hitter.getLocation().clone().add(0, -0.2, 0).getBlock().getType();
                onGround = below.isSolid();
            }

            if (onGround) {
                Location impact = hitter.getLocation().clone().add(0, 0.1, 0);
                doIceImpact(impact, hitter);
                cancel(hitterMonitors.remove(id));
                hitter.remove();
            }
        }, 0L, HITTER_CHECK_TICKS));
    }

    private void doIceImpact(Location loc, ArmorStand source) {
        World w = loc.getWorld();

        // Визуал как у лука
        Particle.DustOptions ice = new Particle.DustOptions(org.bukkit.Color.fromRGB(120,180,255), 1.35f);
        for (double r = 0.4; r <= 1.8; r += 0.25) {
            for (int i = 0; i < 28; i++) {
                double ang = (Math.PI * 2) * i / 28.0;
                Location p = loc.clone().add(Math.cos(ang) * r, 0.12, Math.sin(ang) * r);
                w.spawnParticle(Particle.DUST, p, 1, 0,0,0, 0, ice);
            }
        }
        w.spawnParticle(Particle.SNOWFLAKE, loc, 80, 1.0, 0.8, 1.0, 0.01);
        w.spawnParticle(Particle.CLOUD,     loc, 16, 0.5, 0.2, 0.5, 0.01);
        w.playSound(loc, Sound.BLOCK_GLASS_BREAK, 0.9f, 1.55f);
        w.playSound(loc, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.6f, 1.85f);

        // Владелец для дружеского огня
        Player owner = null;
        String ownerStr = source.getPersistentDataContainer().get(KEY_OWNER, PersistentDataType.STRING);
        if (ownerStr != null) {
            try {
                UUID ou = UUID.fromString(ownerStr);
                var ent = Bukkit.getEntity(ou);
                if (ent instanceof Player p && p.isOnline()) owner = p;
            } catch (IllegalArgumentException ignored) {}
        }

        // АОЕ
        for (var e : w.getNearbyEntities(loc, HITTER_IMPACT_RADIUS, HITTER_IMPACT_RADIUS, HITTER_IMPACT_RADIUS)) {
            if (!(e instanceof LivingEntity le) || le.isDead() || !le.isValid()) continue;
            if (owner != null && (le.getUniqueId().equals(owner.getUniqueId()) || isTeammate(owner, le))) continue;

            // Дебафы
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, SLOW_TICKS, SLOW_LEVEL, false, true, true));
            le.setFreezeTicks(Math.min(le.getMaxFreezeTicks(), le.getFreezeTicks() + FREEZE_ADD_TICKS));

            // БЫЛО:
            // double d = elemental.computeDamageWithReaction(
            //         le,
            //         HITTER_DAMAGE,
            //         ElementalReactions.Element.CRYO,
            //         /*newAuraTicks=*/FREEZE_ADD_TICKS,
            //         /*consumeOnReact=*/true
            // );

            // СТАЛО:
            double d = elemental.applyOnTotalDamage(
                    le,
                    HITTER_DAMAGE,                          // уже суммарный «базовый» урон без реакции
                    ElementalReactions.Element.CRYO,
                    /*newAuraTicks=*/FREEZE_ADD_TICKS,      // если реакции нет — повесим CRYO
                    /*consumeOnReact=*/true                 // если реакция была — съедаем обе
            );

            if (owner != null) le.damage(d, owner); else le.damage(d);
        }
    }

    /* ======================= УТИЛИТЫ ======================= */

    private boolean isTeammate(Player owner, LivingEntity target) {
        if (!(target instanceof Player other)) return false;
        Team tOwner = teamOf(owner, owner.getScoreboard());
        if (tOwner == null) tOwner = teamOf(owner, Bukkit.getScoreboardManager().getMainScoreboard());
        Team tOther = teamOf(other, other.getScoreboard());
        if (tOther == null) tOther = teamOf(other, Bukkit.getScoreboardManager().getMainScoreboard());
        return tOwner != null && tOwner.equals(tOther);
    }

    private Team teamOf(Player p, Scoreboard sb) {
        if (sb == null) return null;
        return sb.getEntryTeam(p.getName());
    }

    private static void cancel(BukkitTask t) { if (t != null) t.cancel(); }
}
