package org.money.money.kits.ganyu.listeners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;
import org.money.money.combat.ElementalReactions;
import org.money.money.session.KitSession;
import org.money.money.util.ItemModels;

import java.util.*;

public final class GanyuUltListener implements Listener {

    private final Plugin plugin;
    private final ElementalReactions elemental;

    // Ключи
    private final NamespacedKey KEY_ULT_ITEM;    // на Heart of the Sea у игрока
    private final NamespacedKey KEY_SPHERE;      // на главном ArmorStand (IceSphere)
    private final NamespacedKey KEY_HITTER;      // на падающих ArmorStand’ах (IceHitter)
    private final NamespacedKey KEY_OWNER;       // UUID владельца (строкой) на сферах/хиттерах
    private final NamespacedKey KEY_ARMED;       // стрела уже «взводится» (антидубль)

    // Параметры
    private static final int    RING_POINTS          = 96;       // плотность круга
    private static final int    HITTER_CHECK_TICKS   = 2;        // как часто проверять приземление
    private static final String CRYO_TAG             = "Cryo";

    // баланс — читается из ClassRegistry при использовании (def = прежние значения)
    private static int    sphereLifetime()     { return org.money.money.meta.ClassRegistry.numInt("ganyu", "ult", "durationTicks", 1000); }
    private static double sphereRadiusXz()     { return org.money.money.meta.ClassRegistry.num("ganyu", "ult", "fieldRadius", 30.0); }
    private static int    rainIntervalTicks()  { return org.money.money.meta.ClassRegistry.numInt("ganyu", "ult", "rainIntervalTicks", 2); }
    private static double hitterImpactRadius() { return org.money.money.meta.ClassRegistry.num("ganyu", "ult", "impactRadius", 6.0); }
    private static double hitterDamage()       { return org.money.money.meta.ClassRegistry.num("ganyu", "ult", "damage", 4.0); }
    private static int    freezeAddTicks()     { return org.money.money.meta.ClassRegistry.numInt("ganyu", "ult", "freezeAddTicks", 100); }
    private static int    slowTicks()          { return org.money.money.meta.ClassRegistry.numInt("ganyu", "ult", "slowDurationTicks", 80); }
    private static int    slowAmplifier()      { return org.money.money.meta.ClassRegistry.numInt("ganyu", "ult", "slowAmplifier", 1); }
    private static double arrowDamage()        { return org.money.money.meta.ClassRegistry.num("ganyu", "ult", "arrowDirectDamage", 1.0); }
    private static int    arrowFuseTicks()     { return org.money.money.meta.ClassRegistry.numInt("ganyu", "ult", "arrowFuseTicks", 20); }
    private static double arrowSpeed()         { return org.money.money.meta.ClassRegistry.num("ganyu", "ult", "arrowSpeed", 0.6); }

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
        this.KEY_ARMED    = new NamespacedKey(plugin, "ganyu_ult_armed");
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

        // вернём по кулдауну (по UUID — переживает смерть/переподключение; в лобби не выдаём; без дубля)
        final int cooldownTicks = org.money.money.meta.ClassRegistry.ticks("ganyu", "ult", 3000);
        final UUID uid = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player online = Bukkit.getPlayer(uid);
            if (online == null || !online.isOnline() || !KitSession.isInGame(online)) return;
            for (ItemStack it : online.getInventory().getContents()) if (isUltItem(it)) return; // уже есть — не дублируем
            online.getInventory().addItem(makeUltItem());
            online.sendMessage(Component.text("Everfrost Core", NamedTextColor.AQUA)
                    .append(Component.text(" is ready again!", NamedTextColor.GRAY)));
            online.playSound(online.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.45f, 1.7f);
        }, cooldownTicks);

        // центральная сфера на +10 по Y
        Location sphereLoc = p.getLocation().clone().add(0, 10, 0);
        ArmorStand sphere = spawnSphere(sphereLoc, p);

        // проживёт SPHERE_LIFETIME
        startSphereTasks(sphere);

        Bukkit.getScheduler().runTaskLater(plugin, () -> removeSphere(sphere), sphereLifetime());
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
            ItemModels.apply(dyeMeta, "ganyu_smok1");
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
            renderRing(sphere.getWorld(), sphere.getLocation(), sphereRadiusXz(), ice);
        }, 0L, 5L));

        // «ледяной дождь»: рандомные падения
        rainTasks.put(id, Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!sphere.isValid() || sphere.isDead()) { cancel(rainTasks.remove(id)); return; }
            spawnHitterRandom(sphere);
        }, 0L, rainIntervalTicks()));
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
        double r = sphereRadiusXz() * Math.sqrt(Math.random());
        double ang = Math.random() * Math.PI * 2;

        double x = c.getX() + Math.cos(ang) * r;
        double z = c.getZ() + Math.sin(ang) * r;
        Location spawn = new Location(w, x, c.getY(), z);

        String ownerStr = sphere.getPersistentDataContainer().get(KEY_OWNER, PersistentDataType.STRING);

        // НАСТОЯЩАЯ стрела, падающая вниз
        Arrow arrow = w.spawnArrow(spawn, new Vector(0, -1, 0), (float) arrowSpeed(), 0f);
        arrow.setGravity(true);
        arrow.setCritical(false);
        arrow.setPersistent(false);
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED); // нельзя поднять
        arrow.setPierceLevel(127);                                     // проходит СКВОЗЬ игроков
        arrow.setDamage(arrowDamage());                                // минимальный урон при попадании
        var pdc = arrow.getPersistentDataContainer();
        pdc.set(KEY_HITTER, PersistentDataType.BYTE, (byte) 1);
        if (ownerStr != null) {
            pdc.set(KEY_OWNER, PersistentDataType.STRING, ownerStr);
            try {
                var ent = Bukkit.getEntity(UUID.fromString(ownerStr));
                if (ent instanceof Player op) arrow.setShooter(op);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    /** Стрела воткнулась в БЛОК → через секунду ледяной взрыв. Попадания по сущностям игнорируем (стрела пробивная). */
    @EventHandler(ignoreCancelled = false)
    public void onArrowHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof Arrow arrow)) return;
        var pdc = arrow.getPersistentDataContainer();
        if (!pdc.has(KEY_HITTER, PersistentDataType.BYTE)) return;
        if (e.getHitBlock() == null) return;                       // попали в игрока (пробили) — летим дальше
        if (pdc.has(KEY_ARMED, PersistentDataType.BYTE)) return;   // уже взводится
        pdc.set(KEY_ARMED, PersistentDataType.BYTE, (byte) 1);

        final Location impact = arrow.getLocation().clone().add(0, 0.1, 0);
        final String ownerStr = pdc.get(KEY_OWNER, PersistentDataType.STRING);

        chargeArrowFx(arrow, impact);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            doIceImpact(impact, ownerStr);
            if (arrow.isValid()) arrow.remove();
        }, arrowFuseTicks());
    }

    /** «Взвод» стрелы: копится иней ~1с до ледяного взрыва. */
    private void chargeArrowFx(Arrow arrow, Location impact) {
        World w = impact.getWorld();
        try { w.playSound(impact, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.9f); } catch (Throwable ignored) {}
        Particle.DustOptions ice = new Particle.DustOptions(org.bukkit.Color.fromRGB(120, 180, 255), 1.0f);
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (t++ >= arrowFuseTicks() || !arrow.isValid()) { cancel(); return; }
                Location l = arrow.getLocation().clone().add(0, 0.2, 0);
                w.spawnParticle(Particle.SNOWFLAKE, l, 4, 0.15, 0.15, 0.15, 0.01);
                if (t % 4 == 0) w.spawnParticle(Particle.DUST, l, 2, 0.1, 0.1, 0.1, 0.0, ice);
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void doIceImpact(Location loc, String ownerStr) {
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
        if (ownerStr != null) {
            try {
                UUID ou = UUID.fromString(ownerStr);
                var ent = Bukkit.getEntity(ou);
                if (ent instanceof Player p && p.isOnline()) owner = p;
            } catch (IllegalArgumentException ignored) {}
        }

        // АОЕ
        double impactRadius = hitterImpactRadius();
        int freezeAdd = freezeAddTicks();
        for (var e : w.getNearbyEntities(loc, impactRadius, impactRadius, impactRadius)) {
            if (!(e instanceof LivingEntity le) || le.isDead() || !le.isValid()) continue;
            if (owner != null && (le.getUniqueId().equals(owner.getUniqueId()) || isTeammate(owner, le))) continue;

            // Дебафы
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slowTicks(), slowAmplifier(), false, true, true));
            le.setFreezeTicks(Math.min(le.getMaxFreezeTicks(), le.getFreezeTicks() + freezeAdd));

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
                    hitterDamage(),                         // уже суммарный «базовый» урон без реакции
                    ElementalReactions.Element.CRYO,
                    /*newAuraTicks=*/freezeAdd,             // если реакции нет — повесим CRYO
                    /*consumeOnReact=*/true                 // если реакция была — съедаем обе
            );

            Vector kbBefore = le.getVelocity();
            if (owner != null) le.damage(d, owner); else le.damage(d);
            try { le.setVelocity(kbBefore); } catch (Throwable ignored) {} // без отбрасывания от кастера
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
