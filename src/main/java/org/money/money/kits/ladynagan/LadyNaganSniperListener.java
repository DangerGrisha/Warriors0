package org.money.money.kits.ladynagan;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.money.money.session.KitResettable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * LadyNagan — снайперка + ульта (полностью переписано на профессиональный уровень).
 *
 * <h2>Геймплей</h2>
 * <ul>
 *   <li>Базовый предмет — палка «T-742K Mori» (опущенная модель). ПКМ — поднять прицел:
 *       надевается тыква-шлем (прицел в ресурспаке), в руке заряженный арбалет «T-742K Mori»,
 *       накладывается замедление. Повторный ПКМ (без редиректа) — опустить.</li>
 *   <li>ЛКМ в прицеле — выстрел медленной невидимой пулей (урон {@value #DAMAGE_NORMAL}).</li>
 *   <li>Пока ВАША пуля летит и рядом с ней враг/не-союзник — ПКМ доворачивает пулю на врага
 *       (один раз на пулю).</li>
 *   <li>Ульта — палка «Ultra Bullet»: ПКМ запускает каст ~2с, затем авто-выстрел пулей,
 *       которая ваншотает; ПКМ во время каста отменяет (без кулдауна). После выстрела —
 *       кулдаун {@value #ULT_COOLDOWN_SEC}с, по истечении кнопка возвращается.</li>
 * </ul>
 *
 * <h2>Что починено относительно старой версии</h2>
 * <ul>
 *   <li><b>Состояние per-player</b> ({@link SniperState} в {@code Map<UUID,...>}) и
 *       <b>per-bullet</b> ({@link Bullet}) — больше нет общих полей листенера, поэтому
 *       несколько игроков за класс работают независимо.</li>
 *   <li><b>Идентификация предметов через PDC</b> (ключ {@code lady_sniper}); display-name,
 *       материал и заряженность арбалета сохранены байт-в-байт ради моделей ресурспака.</li>
 *   <li><b>Арбалет — болванка</b>: отменяются ванильный shoot/взвод, арбалет всегда заряжен.</li>
 *   <li><b>Замедление самолечащееся</b> (короткое, переприменяется тиково) + сохранение/возврат
 *       настоящего шлема вместо вечного эффекта.</li>
 *   <li><b>Полная очистка</b> на смене слота/дропе/смерти/респауне/выходе/смене мира.</li>
 *   <li><b>Урон только врагам</b> (vanilla scoreboard team), союзники/спектаторы прозрачны.</li>
 *   <li>Для использования <b>не нужны кастомные теги</b> — достаточно держать предмет.</li>
 * </ul>
 */
public class LadyNaganSniperListener implements Listener, KitResettable {

    /* ===================== Имена моделей (НЕ менять — завязаны на ресурспак) ===================== */
    private static final String SNIPER_RIFLE_NAME          = "T-742K Mori";    // палка (опущена) + арбалет (поднят)
    private static final String SNIPER_RIFLE_NAME_MODIFIED = "T-742K Mori+";   // арбалет после выстрела (отдача)
    private static final String SNIPER_RIFLE_NAME_ULTRA    = "T-742K Destroy"; // арбалет ульты (поднят)
    private static final String NAME_OF_ULTRA_BUTTON       = "Ultra Bullet";   // палка-кнопка ульты

    /* ===================== PDC-маркеры (логика; на рендер не влияют) ===================== */
    private static final String KIND_STICK   = "stick";   // базовая палка снайперки
    private static final String KIND_RAISED  = "raised";  // арбалет в прицеле
    private static final String KIND_FIRED   = "fired";   // арбалет после выстрела
    private static final String KIND_DESTROY = "destroy"; // арбалет ульты
    private static final String KIND_ULT     = "ult";     // палка-кнопка ульты

    /* ===================== Баланс ===================== */
    private static final double DAMAGE_NORMAL        = 8.0;
    private static final double DAMAGE_ULTA          = 1000.0; // фактически ваншот (через damage-события)
    private static final double NORMAL_SPEED         = 0.6;    // блоков/тик — «медленная пуля»
    private static final double ULT_SPEED            = 0.7;
    private static final double BULLET_RADIUS        = 0.35;   // допуск попадания по хитбоксу
    private static final double DISTANCE_DETECT      = 10.0;   // радиус доворота на врага
    private static final long   REMOVE_BULLET_AFTER  = 120L;   // тиков жизни пули
    private static final long   REFIRE_MS            = 1500L;  // задержка между выстрелами
    private static final long   ULT_CAST_TICKS       = 40L;    // ~2с каста ульты
    private static final int    ULT_COOLDOWN_SEC     = 105;
    private static final String ULT_COOLDOWN_ID      = "lady_ult";

    private final Plugin plugin;
    private final LadyCooldownManager cooldownManager;

    private final NamespacedKey keyKind;     // тип предмета снайперки
    private final NamespacedKey keyPumpkin;  // метка нашего тыквы-шлема

    private final Map<UUID, SniperState> states = new HashMap<>();
    private final Map<UUID, List<Bullet>> bullets = new HashMap<>();

    public LadyNaganSniperListener(Plugin plugin, LadyCooldownManager cooldownManager) {
        this.plugin = plugin;
        this.cooldownManager = cooldownManager;
        this.keyKind = new NamespacedKey(plugin, "lady_sniper");
        this.keyPumpkin = new NamespacedKey(plugin, "lady_sniper_scope");
    }

    /* ===================== Состояние игрока ===================== */

    private enum Phase { HOLSTERED, AIMING, ULT_CASTING }

    private static final class SniperState {
        Phase phase = Phase.HOLSTERED;
        int aimSlot = -1;
        ItemStack savedHelmet;     // настоящий шлем игрока, спрятанный под тыкву
        BukkitTask slowTask;
        BukkitTask castTask;
        long refireReadyAtMs = 0L;
    }

    private SniperState state(Player p) {
        return states.computeIfAbsent(p.getUniqueId(), k -> new SniperState());
    }

    /* ===================== Выдача (вызывается из KitGiveCommand) ===================== */

    /** Базовая снайперка — палка «T-742K Mori». /kitgive LadyNagan sniper */
    public ItemStack makeSniperStick() {
        return stick(SNIPER_RIFLE_NAME, KIND_STICK);
    }

    /** Кнопка ульты — палка «Ultra Bullet». /kitgive LadyNagan ult */
    public ItemStack makeUltraButton() {
        return stick(NAME_OF_ULTRA_BUTTON, KIND_ULT);
    }

    private ItemStack stick(String name, String kind) {
        ItemStack item = new ItemStack(Material.STICK, 1);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        meta.setUnbreakable(true);
        meta.setLore(List.of("something"));
        meta.getPersistentDataContainer().set(keyKind, PersistentDataType.STRING, kind);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack crossbow(String name, String kind) {
        ItemStack cb = new ItemStack(Material.CROSSBOW);
        CrossbowMeta meta = (CrossbowMeta) cb.getItemMeta();
        meta.displayName(Component.text(name));
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(keyKind, PersistentDataType.STRING, kind);
        meta.addChargedProjectile(new ItemStack(Material.ARROW)); // держим «заряженной» ради модели
        cb.setItemMeta(meta);
        return cb;
    }

    private ItemStack createT742KMoriCrossbow()         { return crossbow(SNIPER_RIFLE_NAME, KIND_RAISED); }
    private ItemStack createT742KMoriCrossbowModified() { return crossbow(SNIPER_RIFLE_NAME_MODIFIED, KIND_FIRED); }
    private ItemStack createT742KMoriCrossbowUltra()    { return crossbow(SNIPER_RIFLE_NAME_ULTRA, KIND_DESTROY); }

    private String kindOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(keyKind, PersistentDataType.STRING);
    }

    private boolean isOurCrossbow(ItemStack it) {
        String k = kindOf(it);
        return KIND_RAISED.equals(k) || KIND_FIRED.equals(k) || KIND_DESTROY.equals(k);
    }

    /* ===================== Главный обработчик кликов ===================== */

    @EventHandler(ignoreCancelled = false)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return; // защита от двойного срабатывания (main+off)

        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        String kind = kindOf(hand);
        if (kind == null) return;

        boolean right = event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK;
        boolean left  = event.getAction() == Action.LEFT_CLICK_AIR  || event.getAction() == Action.LEFT_CLICK_BLOCK;
        SniperState st = state(player);

        switch (kind) {
            case KIND_STICK -> {
                if (right) { event.setCancelled(true); raiseRifle(player); }
            }
            case KIND_ULT -> {
                if (right) { event.setCancelled(true); startUlt(player); }
            }
            case KIND_RAISED, KIND_FIRED -> {
                event.setCancelled(true); // арбалет — болванка, гасим ваниль всегда
                if (right) {
                    Bullet redirectable = findRedirectableBullet(player);
                    if (redirectable != null) redirect(redirectable, player);
                    else lowerRifle(player);
                } else if (left) {
                    tryFire(player, st);
                }
            }
            case KIND_DESTROY -> {
                event.setCancelled(true);
                if (right && st.phase == Phase.ULT_CASTING) cancelUlt(player, true);
            }
        }
    }

    /* ===================== Подъём/опускание прицела ===================== */

    private void raiseRifle(Player player) {
        SniperState st = state(player);
        if (st.phase != Phase.HOLSTERED) return;

        st.aimSlot = player.getInventory().getHeldItemSlot();
        st.phase = Phase.AIMING;
        st.refireReadyAtMs = 0L;
        wearScope(player, st);
        player.getInventory().setItem(st.aimSlot, createT742KMoriCrossbow());
    }

    private void lowerRifle(Player player) {
        SniperState st = states.get(player.getUniqueId());
        if (st == null || st.phase != Phase.AIMING) return;
        int slot = st.aimSlot;
        cleanupScope(player, st, true);
        if (slot >= 0) player.getInventory().setItem(slot, makeSniperStick());
        else player.getInventory().setItemInMainHand(makeSniperStick());
    }

    /* ===================== Ульта ===================== */

    private void startUlt(Player player) {
        SniperState st = state(player);
        if (st.phase != Phase.HOLSTERED) return;
        if (!cooldownManager.isCooldownComplete(player, ULT_COOLDOWN_ID)) {
            long left = cooldownManager.getRemainingCooldown(player, ULT_COOLDOWN_ID);
            player.sendActionBar(Component.text("§cUltra Bullet: §f" + Math.max(1, left) + "s"));
            return;
        }

        st.aimSlot = player.getInventory().getHeldItemSlot();
        st.phase = Phase.ULT_CASTING;
        wearScope(player, st);
        try { player.getWorld().playSound(player.getLocation(), "ladynagan.ultaln", 1.0f, 1.0f); } catch (Throwable ignored) {}
        // палка ульты «превращается» в арбалет Destroy (стик при этом расходуется — без отдельного хранилища)
        player.getInventory().setItem(st.aimSlot, createT742KMoriCrossbowUltra());

        st.castTask = new BukkitRunnable() {
            @Override public void run() {
                SniperState s = states.get(player.getUniqueId());
                if (s == null || s.phase != Phase.ULT_CASTING) return; // отменили/прервали
                s.castTask = null;
                int slot = s.aimSlot;
                if (player.isOnline()) fireBullet(player, true);
                cleanupScope(player, s, true);
                if (slot >= 0) player.getInventory().setItem(slot, null); // расход; вернётся по кулдауну
                cooldownManager.startCooldownAndReturn(player, ULT_COOLDOWN_ID, ULT_COOLDOWN_SEC,
                        makeUltraButton(), false, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.25f);
            }
        }.runTaskLater(plugin, ULT_CAST_TICKS);
    }

    private void cancelUlt(Player player, boolean refund) {
        SniperState st = states.get(player.getUniqueId());
        if (st == null || st.phase != Phase.ULT_CASTING) return;
        if (st.castTask != null) { st.castTask.cancel(); st.castTask = null; }
        int slot = st.aimSlot;
        cleanupScope(player, st, true);
        if (refund && slot >= 0) {
            player.getInventory().setItem(slot, makeUltraButton());
            try { player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.8f); } catch (Throwable ignored) {}
        }
    }

    /* ===================== Стрельба ===================== */

    private void tryFire(Player player, SniperState st) {
        if (st.phase != Phase.AIMING) return;
        long now = System.currentTimeMillis();
        if (now < st.refireReadyAtMs) return;
        st.refireReadyAtMs = now + REFIRE_MS;

        fireBullet(player, false);

        final int slot = st.aimSlot;
        if (slot >= 0) player.getInventory().setItem(slot, createT742KMoriCrossbowModified()); // модель отдачи

        // вернуть модель «готов к выстрелу» после задержки
        new BukkitRunnable() {
            @Override public void run() {
                SniperState s = states.get(player.getUniqueId());
                if (s == null || s.phase != Phase.AIMING || slot < 0) return;
                if (KIND_FIRED.equals(kindOf(player.getInventory().getItem(slot)))) {
                    player.getInventory().setItem(slot, createT742KMoriCrossbow());
                }
            }
        }.runTaskLater(plugin, REFIRE_MS / 50L);
    }

    private void fireBullet(Player player, boolean ult) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Location spawn = eye.clone().add(dir.clone().multiply(0.8));

        ArmorStand stand = spawnBulletStand(spawn, dir);
        Bullet bullet = new Bullet(player.getUniqueId(), stand, dir, ult);
        bullets.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>()).add(bullet);
        bullet.start();

        try { player.getWorld().playSound(player.getLocation(), "ladynagan.shoot", 1.0f, 1.0f); } catch (Throwable ignored) {}
    }

    private ArmorStand spawnBulletStand(Location spawn, Vector dir) {
        ArmorStand as = spawn.getWorld().spawn(spawn, ArmorStand.class, a -> {
            a.setVisible(false);
            a.setGravity(false);
            a.setSmall(true);
            a.setInvulnerable(true);
            a.setCollidable(false);
            a.setBasePlate(false);
            a.setArms(false);
            ItemStack bulletDye = new ItemStack(Material.RED_DYE);
            ItemMeta m = bulletDye.getItemMeta();
            m.displayName(Component.text("Bullet"));
            bulletDye.setItemMeta(m);
            a.getEquipment().setItemInMainHand(bulletDye); // визуал пули через ресурспак
            a.addScoreboardTag("bullet");
        });
        updateArmorStandRotation(as, dir);
        return as;
    }

    private void updateArmorStandRotation(ArmorStand armorStand, Vector direction) {
        double x = direction.getX();
        double z = direction.getZ();
        double theta = Math.atan2(-x, z);
        theta += Math.PI / 2;
        theta *= -180 / Math.PI;
        Location loc = armorStand.getLocation();
        loc.setYaw((float) theta);
        armorStand.teleport(loc);
    }

    /* ===================== Доворот пули ===================== */

    private Bullet findRedirectableBullet(Player owner) {
        List<Bullet> list = bullets.get(owner.getUniqueId());
        if (list == null) return null;
        for (Bullet b : list) {
            if (b.redirected || b.stand == null || b.stand.isDead()) continue;
            if (nearestEnemy(b.stand.getLocation(), owner) != null) return b;
        }
        return null;
    }

    private void redirect(Bullet bullet, Player owner) {
        if (bullet.stand == null || bullet.stand.isDead()) return;
        Player target = nearestEnemy(bullet.stand.getLocation(), owner);
        if (target == null) return;
        Vector nd = target.getEyeLocation().toVector().subtract(bullet.stand.getLocation().toVector());
        if (nd.lengthSquared() < 1.0e-6) return;
        bullet.dir = nd.normalize();
        bullet.redirected = true;
        updateArmorStandRotation(bullet.stand, bullet.dir);
        try { owner.playSound(owner.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 0.5f, 1.7f); } catch (Throwable ignored) {}
    }

    private Player nearestEnemy(Location location, Player owner) {
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player p : location.getWorld().getPlayers()) {
            if (p.equals(owner) || isAlly(p, owner)) continue;
            if (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) continue;
            double d = p.getLocation().distanceSquared(location);
            if (d <= DISTANCE_DETECT * DISTANCE_DETECT && d < bestDist) { best = p; bestDist = d; }
        }
        return best;
    }

    /* ===================== Тыква-прицел + замедление ===================== */

    private void wearScope(Player player, SniperState st) {
        ItemStack current = player.getInventory().getHelmet();
        if (!isOurPumpkin(current)) st.savedHelmet = current == null ? null : current.clone();

        ItemStack pumpkin = new ItemStack(Material.CARVED_PUMPKIN);
        ItemMeta pm = pumpkin.getItemMeta();
        pm.getPersistentDataContainer().set(keyPumpkin, PersistentDataType.BYTE, (byte) 1);
        pumpkin.setItemMeta(pm);
        player.getInventory().setHelmet(pumpkin);

        startSlowTask(player, st);
    }

    private void startSlowTask(Player player, SniperState st) {
        if (st.slowTask != null) st.slowTask.cancel();
        st.slowTask = new BukkitRunnable() {
            @Override public void run() {
                SniperState s = states.get(player.getUniqueId());
                if (s == null || s.phase == Phase.HOLSTERED || !player.isOnline()) { cancel(); return; }
                int amp = player.isSneaking() ? 5 : 3;
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, amp, false, false, false));
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }

    /** Снимает тыкву/замедление/таски и возвращает игрока в HOLSTERED. Инвентарь не трогает. */
    private void cleanupScope(Player player, SniperState st, boolean restoreHelmet) {
        if (st.slowTask != null) { st.slowTask.cancel(); st.slowTask = null; }
        player.removePotionEffect(PotionEffectType.SLOWNESS);

        if (isOurPumpkin(player.getInventory().getHelmet())) {
            player.getInventory().setHelmet(restoreHelmet ? st.savedHelmet : null);
        }
        st.savedHelmet = null;
        st.phase = Phase.HOLSTERED;
    }

    private boolean isOurPumpkin(ItemStack it) {
        return it != null && it.getType() == Material.CARVED_PUMPKIN && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(keyPumpkin, PersistentDataType.BYTE);
    }

    /* ===================== Жизненный цикл / очистка ===================== */

    @EventHandler
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        SniperState st = states.get(player.getUniqueId());
        if (st == null || st.phase == Phase.HOLSTERED) return;
        if (event.getNewSlot() == st.aimSlot) return; // всё ещё держим активный предмет
        if (st.phase == Phase.AIMING) lowerRifle(player);
        else if (st.phase == Phase.ULT_CASTING) cancelUlt(player, true);
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        ItemStack dropped = event.getItemDrop().getItemStack();
        if (!isOurCrossbow(dropped)) return; // палки можно дропать спокойно
        event.setCancelled(true); // живой арбалет-болванка не должен валяться в мире
        SniperState st = states.get(player.getUniqueId());
        if (st == null) return;
        if (st.phase == Phase.AIMING) lowerRifle(player);
        else if (st.phase == Phase.ULT_CASTING) cancelUlt(player, true);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        SniperState st = states.get(player.getUniqueId());
        if (st != null) {
            if (st.castTask != null) { st.castTask.cancel(); st.castTask = null; }
            cleanupScope(player, st, false);
        }
        // тыкву-прицел и пулю-дай не дропаем как лут
        event.getDrops().removeIf(it -> isOurPumpkin(it) || "Bullet".equals(plainName(it)));
        removeBullets(player.getUniqueId());
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        // защитная зачистка на случай рассинхрона
        new BukkitRunnable() {
            @Override public void run() {
                if (!player.isOnline()) return;
                if (isOurPumpkin(player.getInventory().getHelmet())) player.getInventory().setHelmet(null);
                player.removePotionEffect(PotionEffectType.SLOWNESS);
            }
        }.runTaskLater(plugin, 1L);
        SniperState st = states.get(player.getUniqueId());
        if (st != null) { st.phase = Phase.HOLSTERED; st.savedHelmet = null; }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        SniperState st = states.remove(player.getUniqueId());
        if (st != null) {
            if (st.slowTask != null) st.slowTask.cancel();
            if (st.castTask != null) st.castTask.cancel();
            if (isOurPumpkin(player.getInventory().getHelmet())) {
                player.getInventory().setHelmet(st.savedHelmet);
            }
            player.removePotionEffect(PotionEffectType.SLOWNESS);
        }
        removeBullets(player.getUniqueId());
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        SniperState st = states.get(player.getUniqueId());
        if (st != null && st.phase != Phase.HOLSTERED) {
            if (st.phase == Phase.AIMING) lowerRifle(player);
            else cancelUlt(player, true);
        }
        removeBullets(player.getUniqueId()); // пули остались в старом мире
    }

    /** Нейтрализация арбалета-болванки: настоящий выстрел невозможен, заряд сохраняется. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCrossbowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        if (!isOurCrossbow(event.getBow())) return;
        event.setCancelled(true);
        event.setConsumeItem(false);
        if (event.getProjectile() != null && event.getProjectile().isValid()) {
            event.getProjectile().remove();
        }
    }

    /** Вызывать из Main.onDisable() — снять прицелы/эффекты/пули при выключении плагина. */
    public void shutdown() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            SniperState st = states.get(p.getUniqueId());
            if (st != null) {
                if (st.slowTask != null) st.slowTask.cancel();
                if (st.castTask != null) st.castTask.cancel();
                if (isOurPumpkin(p.getInventory().getHelmet())) p.getInventory().setHelmet(st.savedHelmet);
            }
            p.removePotionEffect(PotionEffectType.SLOWNESS);
        }
        states.clear();
        for (List<Bullet> list : bullets.values()) {
            for (Bullet b : list) b.destroy();
        }
        bullets.clear();
    }

    private void removeBullets(UUID owner) {
        List<Bullet> list = bullets.remove(owner);
        if (list == null) return;
        // копия, т.к. destroy() мутирует исходный список
        for (Bullet b : new ArrayList<>(list)) b.destroy();
    }

    /** Полная очистка игрока (конец игры / вход в лобби): тыква, замедление, таски, пули. */
    @Override
    public void resetPlayer(Player player) {
        SniperState st = states.remove(player.getUniqueId());
        if (st != null) {
            if (st.slowTask != null) st.slowTask.cancel();
            if (st.castTask != null) st.castTask.cancel();
            if (isOurPumpkin(player.getInventory().getHelmet())) {
                player.getInventory().setHelmet(st.savedHelmet);
            }
        } else if (isOurPumpkin(player.getInventory().getHelmet())) {
            player.getInventory().setHelmet(null);
        }
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        removeBullets(player.getUniqueId());
    }

    /* ===================== Команды/союзники ===================== */

    private boolean isAlly(Player a, Player b) {
        if (a.getGameMode() == GameMode.SPECTATOR || b.getGameMode() == GameMode.SPECTATOR) return true;
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team ta = sb.getEntryTeam(a.getName());
        Team tb = sb.getEntryTeam(b.getName());
        return ta != null && tb != null && ta.equals(tb);
        // Без команды игроки считаются врагами (FFA) — урон проходит, кастомный тег не требуется.
    }

    private static String plainName(ItemStack it) {
        if (it == null || !it.hasItemMeta() || !it.getItemMeta().hasDisplayName()) return null;
        return PlainTextComponentSerializer.plainText().serialize(it.getItemMeta().displayName());
    }

    /* ===================== Пуля ===================== */

    private final class Bullet {
        final UUID owner;
        final boolean ult;
        ArmorStand stand;
        Vector dir;
        boolean redirected = false;
        long age = 0L;
        BukkitTask task;

        Bullet(UUID owner, ArmorStand stand, Vector dir, boolean ult) {
            this.owner = owner;
            this.stand = stand;
            this.dir = dir.clone().normalize();
            this.ult = ult;
        }

        void start() {
            task = new BukkitRunnable() {
                @Override public void run() { tick(); }
            }.runTaskTimer(plugin, 0L, 1L);
        }

        private void tick() {
            if (stand == null || stand.isDead()) { destroy(); return; }
            if (++age > REMOVE_BULLET_AFTER) { destroy(); return; }

            Player shooter = Bukkit.getPlayer(owner);
            if (shooter == null) { destroy(); return; } // владелец оффлайн — пуля гасится

            World w = stand.getWorld();
            Location cur = stand.getLocation();
            double step = ult ? ULT_SPEED : NORMAL_SPEED;

            // блок на пути (свип лучом за один шаг — против туннелинга)
            RayTraceResult hit = w.rayTraceBlocks(cur, dir, step + 0.1, FluidCollisionMode.NEVER, true);
            if (hit != null && hit.getHitBlock() != null) {
                w.playSound(cur, Sound.BLOCK_GLASS_BREAK, 1.0f, 1.0f);
                destroy();
                return;
            }

            Location next = cur.clone().add(dir.clone().multiply(step));

            for (Player target : w.getPlayers()) {
                if (target.equals(shooter)) continue;
                if (isAlly(target, shooter)) continue;                 // союзники/спектаторы прозрачны
                if (target.getGameMode() == GameMode.CREATIVE) continue;
                if (target.isInvulnerable()) continue;
                if (target.getBoundingBox().clone().expand(BULLET_RADIUS)
                        .contains(next.getX(), next.getY(), next.getZ())) {
                    double dmg = ult ? DAMAGE_ULTA : DAMAGE_NORMAL;
                    target.damage(dmg, shooter);
                    target.getWorld().playSound(target.getLocation(), Sound.ENTITY_ARROW_HIT_PLAYER, 1.0f, 1.0f);
                    destroy();
                    return;
                }
            }

            stand.teleport(next);
        }

        void destroy() {
            if (task != null) { task.cancel(); task = null; }
            if (stand != null && !stand.isDead()) stand.remove();
            stand = null;
            List<Bullet> list = bullets.get(owner);
            if (list != null) {
                Iterator<Bullet> it = list.iterator();
                while (it.hasNext()) if (it.next() == this) { it.remove(); break; }
                if (list.isEmpty()) bullets.remove(owner);
            }
        }
    }
}
