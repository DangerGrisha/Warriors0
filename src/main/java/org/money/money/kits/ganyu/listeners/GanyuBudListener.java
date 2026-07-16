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
import org.money.money.session.KitSession;
import org.money.money.util.ItemModels;

import java.util.*;

public final class GanyuBudListener implements Listener {
    private final Plugin plugin;
    private final ElementalReactions elemental;

    private final NamespacedKey KEY_BUD_ITEM;      // на CYAN_DYE
    private final NamespacedKey KEY_VIEW_ITEM;     // на ENDER_EYE («View Changer»)
    private final NamespacedKey KEY_BUD_STAND;     // на ArmorStand (метка-это bud)
    private final NamespacedKey KEY_BUD_OWNER;     // <<< NEW: владелец bud (UUID строкой)
    private final NamespacedKey KEY_VIEW_BUD;      // <<< NEW: в ENDER_EYE — UUID связанного bud


    // тайминги / радиусы
    private static final double RING_THICKNESS   = 1.25;  // было 0.8
    private static final double RING_HALF_HEIGHT = 1.25;  // было 0.75
    private static final double RING_RADIUS_FUZZ = 0.30;  // новый: небольшой люфт по радиусу

    private static final double RING_Y_TOP_OFFSET  = +1.0;     // +1y от бутона
    private static final double RING_Y_BOT_OFFSET  = -1.0;     // -1y от бутона

    // баланс — читается из ClassRegistry при использовании (def = прежние значения)
    private static int    budLifetimeTicks() { return org.money.money.meta.ClassRegistry.numInt("ganyu", "bud", "durationTicks", 400); }
    private static int    budPulsePeriod()   { return org.money.money.meta.ClassRegistry.numInt("ganyu", "bud", "pulsePeriodTicks", 60); }
    private static double ringRadius()       { return org.money.money.meta.ClassRegistry.num("ganyu", "bud", "radius", 10.0); }
    private static int    freezeAddTicks()   { return org.money.money.meta.ClassRegistry.numInt("ganyu", "bud", "freezeAddTicks", 80); }
    private static int    slowTicks()        { return org.money.money.meta.ClassRegistry.numInt("ganyu", "bud", "slowDurationTicks", 60); }
    private static int    slowAmplifier()    { return org.money.money.meta.ClassRegistry.numInt("ganyu", "bud", "slowAmplifier", 1); }
    private static double budDamage()        { return org.money.money.meta.ClassRegistry.num("ganyu", "bud", "damage", 4.0); }

    private final Map<UUID, BukkitTask> spinTask  = new HashMap<>();
    private final Map<UUID, BukkitTask> pulseTask = new HashMap<>();

    public GanyuBudListener(Plugin plugin, ElementalReactions elemental) {
        this.plugin = plugin;
        this.elemental = elemental;
        this.KEY_BUD_ITEM  = new NamespacedKey(plugin, "ganyu_bud");
        this.KEY_VIEW_ITEM = new NamespacedKey(plugin, "ganyu_view_changer");
        this.KEY_BUD_STAND = new NamespacedKey(plugin, "ganyu_bud_stand");
        this.KEY_BUD_OWNER = new NamespacedKey(plugin, "ganyu_bud_owner"); // <<< NEW
        this.KEY_VIEW_BUD  = new NamespacedKey(plugin, "ganyu_view_bud");  // <<< NEW
    }

    /* ================== USE: Frostbud (CYAN_DYE) ================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onUseBud(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!isBudItem(hand)) return;

        // запретим дефолтное поведение (на всякий)
        e.setUseItemInHand(Event.Result.DENY);
        e.setCancelled(true);

        Location base = p.getLocation().clone();
        ArmorStand as = spawnBudStand(base, p);              // <<< owner сохраняем

        replaceDyeWithViewChanger(p, as);                    // <<< глаз привязываем к bud

        startSpin(as);
        startPulses(as, p);

        applyPlaceRecoil(p); // установка бутона чуть-чуть отбрасывает Ганью назад

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            cleanupStand(as);
            removeViewChangerIfPresent(p);
            startCooldownReturnDye(p);
        }, budLifetimeTicks());
    }

    /* ================== USE: View Changer (ENDER_EYE) ================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onUseViewChanger(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!isViewChanger(hand)) return;

        // важное: не дать ванилле «кинуть» око края
        e.setUseItemInHand(Event.Result.DENY);
        e.setCancelled(true);

        // достаём UUID bud из предмета
        String budIdStr = hand.getItemMeta().getPersistentDataContainer().get(KEY_VIEW_BUD, PersistentDataType.STRING);
        if (budIdStr == null) { pingFail(p, "No active bud linked."); return; }

        UUID budId;
        try { budId = UUID.fromString(budIdStr); } catch (IllegalArgumentException ex) { pingFail(p, "Broken link."); return; }

        var entity = Bukkit.getEntity(budId);
        if (!(entity instanceof ArmorStand as) || !as.isValid()) { pingFail(p, "Bud is gone."); return; }

        // проверяем, что bud — наш и он отмечен как bud
        var pdc = as.getPersistentDataContainer();
        if (!pdc.has(KEY_BUD_STAND, PersistentDataType.BYTE)) { pingFail(p, "Not a bud."); return; }
        String ownerStr = pdc.get(KEY_BUD_OWNER, PersistentDataType.STRING);
        if (ownerStr == null || !ownerStr.equals(p.getUniqueId().toString())) { pingFail(p, "Not your bud."); return; }

        // поворачиваем всех противников в радиусе RING_RADIUS посмотреть на bud
        snapEnemiesLookToBud(as.getLocation().clone().add(0, 0.5, 0), p);

        // тратим один глаз
        consumeOneViewChanger(p);

        // лёгкий звук
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.35f, 1.8f);
    }

    /* ---------- предметы ---------- */

    private boolean isBudItem(ItemStack it) {
        if (it == null || it.getType() != Material.CYAN_DYE || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(KEY_BUD_ITEM, PersistentDataType.BYTE);
    }

    private boolean isViewChanger(ItemStack it) {
        if (it == null || it.getType() != Material.ENDER_EYE || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(KEY_VIEW_ITEM, PersistentDataType.BYTE);
    }

    public ItemStack makeFrostbudDye() {
        ItemStack it = new ItemStack(Material.CYAN_DYE);
        ItemMeta im = it.getItemMeta();
        im.setDisplayName("§bFrostbud");
        im.setLore(java.util.List.of("§7Right-click to plant a cryo bud"));
        im.getPersistentDataContainer().set(KEY_BUD_ITEM, PersistentDataType.BYTE, (byte)1);
        im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(im);
        return it;
    }

    private ItemStack makeViewChangerItem(UUID budId) { // <<< NEW: пишем UUID bud внутрь
        ItemStack it = new ItemStack(Material.ENDER_EYE);
        ItemMeta im = it.getItemMeta();
        im.setDisplayName("§fView Changer");
        var pdc = im.getPersistentDataContainer();
        pdc.set(KEY_VIEW_ITEM, PersistentDataType.BYTE, (byte)1);
        pdc.set(KEY_VIEW_BUD,  PersistentDataType.STRING, budId.toString());
        im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(im);
        return it;
    }

    private void replaceDyeWithViewChanger(Player p, ArmorStand as) { // <<< bud → eye с привязкой
        ItemStack hand = p.getInventory().getItemInMainHand();
        ItemStack eye = makeViewChangerItem(as.getUniqueId());
        if (hand.getAmount() <= 1) {
            p.getInventory().setItemInMainHand(eye);
        } else {
            hand.setAmount(hand.getAmount() - 1);
            var leftover = p.getInventory().addItem(eye);
            leftover.values().forEach(it -> p.getWorld().dropItemNaturally(p.getLocation(), it));
        }
        p.playSound(p.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 0.6f, 1.6f);
    }

    private void consumeOneViewChanger(Player p) { // <<< NEW
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!isViewChanger(hand)) return;
        if (hand.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
        else hand.setAmount(hand.getAmount() - 1);
    }

    private void removeViewChangerIfPresent(Player p) {
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (!isViewChanger(it)) continue;
            if (it.getAmount() <= 1) p.getInventory().setItem(i, null);
            else it.setAmount(it.getAmount() - 1);
            break;
        }
    }

    private void startCooldownReturnDye(Player p) {
        int cooldownTicks = org.money.money.meta.ClassRegistry.ticks("ganyu", "bud", 1600);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || !KitSession.isInGame(p)) return;
            p.getInventory().addItem(makeFrostbudDye());
            p.sendMessage(
                    Component.text("Frostbud", NamedTextColor.AQUA)
                            .append(Component.text(" is ready again!", NamedTextColor.GRAY))
            );

            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.6f);
        }, cooldownTicks);
    }

    /* ---------- ArmorStand ---------- */

    private ArmorStand spawnBudStand(Location base, Player owner) { // <<< owner
        return base.getWorld().spawn(base, ArmorStand.class, s -> {
            s.setInvisible(true);
            s.setMarker(true);
            s.setInvulnerable(true);
            s.setGravity(false);

            // ► Руки включаем и ставим строго вниз
            s.setArms(true);
            EulerAngle down = new EulerAngle(0, 0, 0); // X,Y,Z в радианах
            s.setRightArmPose(down);
            s.setLeftArmPose(down);
            s.setBasePlate(false); // опционально, убрать «пяточку»


            s.setCustomNameVisible(false);
            //s.setCustomName("§bFrostbud");
            ItemStack flower = new ItemStack(Material.CORNFLOWER);
            ItemMeta fm = flower.getItemMeta(); fm.displayName(Component.text(("FrostBud"))); ItemModels.apply(fm, "ganyu_meow_bud"); flower.setItemMeta(fm);
            s.getEquipment().setItemInMainHand(flower);
            var pdc = s.getPersistentDataContainer();
            pdc.set(KEY_BUD_STAND, PersistentDataType.BYTE, (byte)1);
            pdc.set(KEY_BUD_OWNER, PersistentDataType.STRING, owner.getUniqueId().toString()); // <<< NEW
        });
    }

    private void startSpin(ArmorStand as) {
        UUID id = as.getUniqueId();
        BukkitTask spin = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!as.isValid() || as.isDead()) { stopSpin(id); return; }
            Location l = as.getLocation();
            l.setYaw(l.getYaw() + 10f);
            as.teleport(l);
        }, 0L, 2L);
        spinTask.put(id, spin);
    }
    private void stopSpin(UUID id) { Optional.ofNullable(spinTask.remove(id)).ifPresent(BukkitTask::cancel); }

    private void startPulses(ArmorStand as, Player owner) {
        UUID id = as.getUniqueId();
        BukkitTask pulse = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!as.isValid() || as.isDead()) { stopPulse(id); return; }
            Location center = as.getLocation().clone().add(0, 0.5, 0);

            ringsExplosion(center);
            applyCryoRings(center, owner);
            center.getWorld().playSound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.7f, 1.9f);
        }, 0L, budPulsePeriod());
        pulseTask.put(id, pulse);
    }
    private void stopPulse(UUID id) { Optional.ofNullable(pulseTask.remove(id)).ifPresent(BukkitTask::cancel); }

    private void cleanupStand(ArmorStand as) {
        stopSpin(as.getUniqueId());
        stopPulse(as.getUniqueId());
        if (as.isValid()) as.remove();
    }

    /** Отдача: установка бутона отбрасывает Ганью чуть-чуть назад. */
    private void applyPlaceRecoil(Player p) {
        double recoil = org.money.money.meta.ClassRegistry.num("ganyu", "bud", "placeRecoil", 0.5);
        if (recoil <= 0) return;
        Vector dir = p.getLocation().getDirection();
        dir.setY(0);
        if (dir.lengthSquared() < 1.0e-6) dir = new Vector(0, 0, 1); // смотрит вертикально — толкаем условно
        Vector back = dir.normalize().multiply(-recoil);
        back.setY(0.2); // маленький «подскок»
        p.setVelocity(back);
        p.playSound(p.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 0.5f, 1.3f);
    }

    /* ---------- SNAP LOOK ---------- */

    private void snapEnemiesLookToBud(Location bud, Player owner) {
        World w = bud.getWorld();
        double r = ringRadius(); // тот же радиус, что и у умения

        for (var e : w.getNearbyEntities(bud, r, r, r)) {
            if (!(e instanceof LivingEntity le) || le.isDead() || !le.isValid()) continue;
            if (owner != null && (le.getUniqueId().equals(owner.getUniqueId()) || isTeammate(owner, le))) continue;

            // поворачиваем взгляд на bud: направление из глаз
            Location eye = le.getEyeLocation();
            Vector dir = bud.clone().subtract(eye).toVector().normalize();
            Location newLoc = le.getLocation().clone();
            newLoc.setDirection(dir);
            le.teleport(newLoc); // мгновенный «рывок» вида
        }
    }

    /* ---------- эффекты / урон по двум кольцам ---------- */

    private void ringsExplosion(Location center) {
        World w = center.getWorld();
        var ice = new Particle.DustOptions(org.bukkit.Color.fromRGB(120,180,255), 1.1f);
        double radius = ringRadius();
        renderRing(w, center, RING_Y_TOP_OFFSET, radius, ice);
        renderRing(w, center, RING_Y_BOT_OFFSET, radius, ice);
        w.spawnParticle(Particle.SNOWFLAKE, center.clone().add(0, RING_Y_TOP_OFFSET, 0),
                30, 0.6, 0.2, 0.6, 0.01);
        w.spawnParticle(Particle.SNOWFLAKE, center.clone().add(0, RING_Y_BOT_OFFSET, 0),
                30, 0.6, 0.2, 0.6, 0.01);
        w.playSound(center, Sound.BLOCK_GLASS_BREAK, 0.6f, 1.55f);
    }

    private void renderRing(World w, Location center, double yOffset, double radius, Particle.DustOptions ice) {
        Location base = center.clone().add(0, yOffset, 0);
        int points = 72;
        for (int i = 0; i < points; i++) {
            double ang = (Math.PI * 2) * i / points;
            double x = Math.cos(ang) * radius;
            double z = Math.sin(ang) * radius;
            w.spawnParticle(Particle.DUST, base.clone().add(x, 0, z), 1, 0,0,0, 0, ice);
        }
    }

    private void applyCryoRings(Location center, Player owner) {
        World w = center.getWorld();

        // раньше было: double range = RING_RADIUS + RING_THICKNESS + 1.0;
        // var nearby = w.getNearbyEntities(center, range, 2.5, range);

        int freezeAdd = freezeAddTicks();
        double sx = ringRadius() + 2.0;         // захват по XZ чуть больше радиуса
        double sy = RING_HALF_HEIGHT + 2.0;     // захват по высоте с запасом
        var nearby = w.getNearbyEntities(center, sx, sy, sx);

        for (var e : nearby) {
            if (!(e instanceof LivingEntity le) || le.isDead() || !le.isValid()) continue;
            if (owner != null && (le.getUniqueId().equals(owner.getUniqueId()) || isTeammate(owner, le))) continue;

            // == геометрия хита ==
            if (!isInsideDiscLayer(center, le, RING_Y_TOP_OFFSET) &&
                    !isInsideDiscLayer(center, le, RING_Y_BOT_OFFSET)) continue;

            // Бутон теперь ТОЛЬКО замедляет — урона не наносит вообще.
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slowTicks(), slowAmplifier(), false, true, true));
            // Иней (визуал) держим НИЖЕ порога урона от заморозки (max-20), чтобы бутон точно не бил уроном.
            int frost = Math.min(le.getMaxFreezeTicks() - 20, le.getFreezeTicks() + freezeAdd);
            if (frost > le.getFreezeTicks()) le.setFreezeTicks(frost);
        }
    }


    // диск радиуса R, в слое (center.y + yOffset ± halfHeight)
    private boolean isInsideDiscLayer(Location center, LivingEntity le, double yOffset) {
        double targetY = le.getBoundingBox().getCenter().getY();
        if (Math.abs(targetY - (center.getY() + yOffset)) > RING_HALF_HEIGHT) return false;

        double dx = le.getLocation().getX() - center.getX();
        double dz = le.getLocation().getZ() - center.getZ();
        double radius = ringRadius();
        return dx*dx + dz*dz <= radius * radius;
    }



    /* ---------- утилиты ---------- */

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

    private void pingFail(Player p, String msg) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.8f);
        p.sendMessage("7" + msg);
    }
}
