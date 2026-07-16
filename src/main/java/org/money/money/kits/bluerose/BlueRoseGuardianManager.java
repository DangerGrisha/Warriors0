package org.money.money.kits.bluerose;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import org.money.money.kits.blastborn.ExplosionUtil;
import org.money.money.meta.ClassRegistry;
import org.money.money.session.KitResettable;
import org.money.money.session.KitSession;
import org.money.money.util.ItemModels;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Blue Rose Guardian — центральный сервис класса (Defender / Support / Zone Controller).
 *
 * <p>Держит ВСЁ «мировое» состояние класса в одной точке (семена, Heritage-розы, активные зоны
 * Ward/Garden/Trail, root/invuln), фабрики предметов и общие хелперы (команда, флаг, лечение,
 * контроль, knockback). Перехватывает урон для трёх легендарных механик: детонация семени мечом
 * Rose Oath, аварийное раскрытие Heritage Bloom и спасение «First Rose Salvation» в Garden.
 *
 * <p>Реализует {@link KitResettable}: при возврате в лобби / death / quit / spectator / world-change
 * / {@code /warriors reset} всё снимается без orphan-сущностей и зависших тасков. Балансовые числа
 * читаются из {@code classes.json} в момент использования (hot-reload через {@code /warriors reload}).
 */
public final class BlueRoseGuardianManager implements Listener, KitResettable {

    public static final String CLASS_ID = "blue_rose_guardian";
    private static final String TAG = "BlueRoseGuardian";
    private static final String ANCHOR_TAG = "BlueRoseAnchor";

    // Базовые материалы предметов (текстура — через item_model lastwar:blue_rose_guardian_*).
    static final Material MAT_ROSE_OATH = Material.IRON_SWORD;
    static final Material MAT_WARD      = Material.CORNFLOWER;
    static final Material MAT_ROSEBIND  = Material.BLUE_DYE;
    static final Material MAT_PETAL     = Material.FEATHER;
    static final Material MAT_HERITAGE  = Material.LIGHT_BLUE_DYE;
    static final Material MAT_GARDEN    = Material.BLUE_ORCHID;
    static final Material MAT_STORM     = Material.PACKED_ICE;

    private final Plugin plugin;

    private final NamespacedKey KEY_ROSE_OATH;
    private final NamespacedKey KEY_WARD;
    private final NamespacedKey KEY_ROSEBIND;
    private final NamespacedKey KEY_PETAL;
    private final NamespacedKey KEY_HERITAGE;
    private final NamespacedKey KEY_GARDEN;
    private final NamespacedKey KEY_STORM;

    // ===== Конфиг-тогглы (config.yml, читаются один раз) =====
    private final boolean deathSaveEnabled;
    private final boolean allowVoidSave;
    private final boolean selfCastEnabled;
    private final double  selfCastMultiplier;
    private final List<String> flagTags;
    private final List<String> flagModelPrefixes;

    // ===== Состояние =====
    private final Map<UUID, BlueRoseGuardianState> states = new HashMap<>();
    private final RoseSeedService seeds = new RoseSeedService();
    private final Map<UUID, HeritageBloom> heritage = new HashMap<>();        // target -> bloom
    private final Map<UUID, Long> heritageEmergencyCdUntil = new HashMap<>(); // target -> untilMs
    private final Map<UUID, Set<RoseZone>> zonesByOwner = new HashMap<>();
    private final Set<RoseZone> activeGardens = new HashSet<>();
    private final Map<UUID, Long> rootUntilMs = new HashMap<>();
    private final Map<UUID, Long> invulnUntilMs = new HashMap<>();
    private final Map<UUID, Long> gardenFrozenUntilMs = new HashMap<>();      // target -> полный фриз Garden (TTL)
    private final Map<UUID, Long> rosebindDriveUntil = new HashMap<>();       // owner -> окно «вождения» розы
    private final Map<UUID, Boolean> rosebindHuntMode = new HashMap<>();      // owner -> режим розы (true=Поиск)
    private final Map<String, Long> roseOathHitCd = new HashMap<>();          // "attacker|target" -> untilMs

    private BukkitTask globalTicker;
    private BlueRoseFrostStorm frostStorm;

    /** Heritage-роза на союзнике: владелец + окно (для emergency). */
    private static final class HeritageBloom {
        final UUID owner;
        final long expiresAtMs;
        final boolean selfCast;
        HeritageBloom(UUID owner, long expiresAtMs, boolean selfCast) {
            this.owner = owner; this.expiresAtMs = expiresAtMs; this.selfCast = selfCast;
        }
    }

    public BlueRoseGuardianManager(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_ROSE_OATH = new NamespacedKey(plugin, "brg_rose_oath");
        this.KEY_WARD      = new NamespacedKey(plugin, "brg_ward");
        this.KEY_ROSEBIND  = new NamespacedKey(plugin, "brg_rosebind");
        this.KEY_PETAL     = new NamespacedKey(plugin, "brg_petal_step");
        this.KEY_HERITAGE  = new NamespacedKey(plugin, "brg_heritage");
        this.KEY_GARDEN    = new NamespacedKey(plugin, "brg_garden");
        this.KEY_STORM     = new NamespacedKey(plugin, "brg_storm");

        this.deathSaveEnabled   = plugin.getConfig().getBoolean("classes.blue_rose_guardian.death-save-enabled", true);
        this.allowVoidSave      = plugin.getConfig().getBoolean("classes.blue_rose_guardian.allow-void-save", false);
        this.selfCastEnabled    = plugin.getConfig().getBoolean("classes.blue_rose_guardian.self-cast-enabled", true);
        this.selfCastMultiplier = plugin.getConfig().getDouble("classes.blue_rose_guardian.self-cast-multiplier", 0.5);

        List<String> tags = plugin.getConfig().getStringList("classes.blue_rose_guardian.flag-carrier-tags");
        this.flagTags = tags.isEmpty() ? List.of("flagcarrier", "hasflag", "carryingflag", "hasFlag") : tags;
        List<String> prefixes = plugin.getConfig().getStringList("classes.blue_rose_guardian.flag-item-model-prefixes");
        this.flagModelPrefixes = prefixes.isEmpty()
                ? List.of("banner_bow", "banner_infly", "banner_red_dye") : prefixes;

        startGlobalTicker();
        this.frostStorm = new BlueRoseFrostStorm(plugin, this);
    }

    /* ============================================================ */
    /* Реестр чисел (classes.json, читается при использовании)        */
    /* ============================================================ */

    int cdTicks(String key, int def) { return Math.max(0, ClassRegistry.ticks(CLASS_ID, key, def)); }
    double num(String key, String param, double def) { return ClassRegistry.num(CLASS_ID, key, param, def); }
    int numInt(String key, String param, int def) { return ClassRegistry.numInt(CLASS_ID, key, param, def); }

    Plugin plugin() { return plugin; }
    RoseSeedService seeds() { return seeds; }
    boolean selfCastEnabled() { return selfCastEnabled; }
    double selfCastMultiplier() { return selfCastMultiplier; }

    /* ============================================================ */
    /* Членство в классе                                             */
    /* ============================================================ */

    public void markGuardian(Player p) {
        if (p == null) return;
        p.addScoreboardTag(TAG);
        states.computeIfAbsent(p.getUniqueId(), k -> new BlueRoseGuardianState());
    }

    boolean isGuardian(Player p) { return p != null && p.getScoreboardTags().contains(TAG); }

    private BlueRoseGuardianState getState(Player p) {
        return states.computeIfAbsent(p.getUniqueId(), k -> new BlueRoseGuardianState());
    }

    /* ============================================================ */
    /* Кулдауны (actionbar)                                          */
    /* ============================================================ */

    /** true (+ actionbar/звук), если способность на кулдауне. */
    boolean isOnCooldown(Player p, String key, int defTicks, String label) {
        long left = cooldownLeftMs(p, key, defTicks);
        if (left <= 0) return false;
        long sec = (left + 999) / 1000;
        p.sendActionBar(Component.text(label + ": " + sec + "s", NamedTextColor.RED));
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.8f);
        return true;
    }

    private long cooldownLeftMs(Player p, String key, int defTicks) {
        BlueRoseGuardianState s = states.get(p.getUniqueId());
        if (s == null) return 0;
        Long last = s.lastUseMs.get(key);
        if (last == null) return 0;
        long cdMs = cdTicks(key, defTicks) * 50L;
        long passed = System.currentTimeMillis() - last;
        return passed >= cdMs ? 0 : (cdMs - passed);
    }

    /** Пометить способность использованной БЕЗ тикающего отсчёта (тихий гейт, проверяется isOnCooldown). */
    void markUsed(Player p, String key) {
        getState(p).lastUseMs.put(key, System.currentTimeMillis());
    }

    /** Сбросить гейт способности (напр. постановка не удалась — возвращаем право на повтор). */
    void clearCooldown(Player p, String key) {
        BlueRoseGuardianState s = states.get(p.getUniqueId());
        if (s != null) s.lastUseMs.remove(key);
    }

    /** Запустить кулдаун + обратный отсчёт в actionbar. */
    void triggerCooldown(Player p, String key, int defTicks, String label) {
        BlueRoseGuardianState s = getState(p);
        s.lastUseMs.put(key, System.currentTimeMillis());
        int cd = cdTicks(key, defTicks);
        // округляем ВВЕРХ, как isOnCooldown — иначе таймер бы отставал на долю секунды
        int seconds = Math.max(1, (cd + 19) / 20);
        BukkitTask prev = s.cdTasks.remove(key);
        if (prev != null) prev.cancel();
        if (cd <= 0) return;
        final UUID id = p.getUniqueId();
        BukkitTask task = new BukkitRunnable() {
            int timeLeft = seconds;
            @Override public void run() {
                Player online = Bukkit.getPlayer(id);
                if (online == null || !online.isOnline()) { cancel(); return; }
                if (timeLeft <= 0) {
                    online.sendActionBar(Component.text(label + " ready", NamedTextColor.GREEN));
                    cancel();
                    return;
                }
                online.sendActionBar(Component.text(label + ": " + timeLeft + "s", NamedTextColor.AQUA));
                timeLeft--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
        s.cdTasks.put(key, task);
    }

    /* ============================================================ */
    /* Команда / флаг / цели                                         */
    /* ============================================================ */

    boolean isFriendly(Player a, Player b) { return ExplosionUtil.isFriendly(a, b); }

    /** Враг = не сам и не союзник (игроки без команды считаются врагами). */
    boolean isEnemy(Player source, Player target) {
        if (source == null || target == null) return false;
        if (source.getUniqueId().equals(target.getUniqueId())) return false;
        return !ExplosionUtil.isFriendly(source, target);
    }

    /** Союзники источника в радиусе (включая/исключая самого). */
    List<Player> alliesIn(Player source, Location center, double radius, boolean includeSelf) {
        List<Player> out = new ArrayList<>();
        World w = center.getWorld();
        if (w == null) return out;
        for (Entity e : w.getNearbyEntities(center, radius, radius, radius)) {
            if (!(e instanceof Player p) || !p.isOnline() || p.isDead()) continue;
            if (p.getLocation().distanceSquared(center) > radius * radius) continue;
            boolean self = p.getUniqueId().equals(source.getUniqueId());
            if (self) { if (includeSelf) out.add(p); continue; }
            if (ExplosionUtil.isFriendly(source, p)) out.add(p);
        }
        return out;
    }

    /** Враги источника в радиусе. */
    List<Player> enemiesIn(Player source, Location center, double radius) {
        List<Player> out = new ArrayList<>();
        World w = center.getWorld();
        if (w == null) return out;
        for (Entity e : w.getNearbyEntities(center, radius, radius, radius)) {
            if (!(e instanceof Player p) || !p.isOnline() || p.isDead()) continue;
            if (p.getLocation().distanceSquared(center) > radius * radius) continue;
            if (isEnemy(source, p)) out.add(p);
        }
        return out;
    }

    boolean isFlagCarrier(Player p) {
        if (p == null) return false;
        for (String tag : flagTags) if (p.getScoreboardTags().contains(tag)) return true;
        for (ItemStack it : p.getInventory().getContents()) if (itemIsFlag(it)) return true;
        return itemIsFlag(p.getInventory().getItemInOffHand());
    }

    private boolean itemIsFlag(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        ItemMeta m = it.getItemMeta();
        try {
            NamespacedKey k = m.getItemModel();   // null-check ниже покрывает «модель не задана»
            if (k == null || !"lastwar".equalsIgnoreCase(k.getNamespace())) return false;
            String path = k.getKey();
            for (String pre : flagModelPrefixes) if (path.startsWith(pre)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    /** Bloodline Bloom активна, если союзный флагоносец (или сам Guardian с флагом) рядом. */
    boolean bloodlineActive(Player g) {
        if (g == null) return false;
        if (isFlagCarrier(g)) return true;
        double range = num("passive", "flagCarrierRange", 8.0);
        World w = g.getWorld();
        if (w == null) return false;
        for (Entity e : w.getNearbyEntities(g.getLocation(), range, range, range)) {
            if (!(e instanceof Player ally) || !ally.isOnline() || ally.isDead()) continue;
            if (ally.getUniqueId().equals(g.getUniqueId())) continue;
            if (ally.getLocation().distanceSquared(g.getLocation()) > range * range) continue;
            if (ExplosionUtil.isFriendly(g, ally) && isFlagCarrier(ally)) return true;
        }
        return false;
    }

    // Bloodline-множители (применяются только при активной пассивке).
    double bloodlineWardDurationMult() { return num("passive", "wardDurationMultiplier", 1.25); }
    double bloodlineWardRadiusBonus()  { return num("passive", "wardRadiusBonus", 0.5); }
    double bloodlineHealingMult()      { return num("passive", "healingMultiplier", 1.15); }
    double bloodlineShieldMult()       { return num("passive", "shieldMultiplier", 1.15); }
    double bloodlineEmergencyMult()    { return num("passive", "emergencyMultiplier", 1.15); }
    double bloodlineKnockbackBonus()   { return num("passive", "knockbackRadiusBonus", 0.5); }

    /* ============================================================ */
    /* Лечение / щит / эффекты                                       */
    /* ============================================================ */

    private double maxHealth(Player p) {
        var a = p.getAttribute(Attribute.MAX_HEALTH);
        return a != null ? a.getValue() : 20.0;
    }

    void healAlly(Player p, double amount) {
        if (p == null || !p.isOnline() || p.isDead() || amount <= 0) return;
        p.setHealth(Math.min(maxHealth(p), p.getHealth() + amount));
    }

    /** Вернуть предмет-способность игроку через {@code delayTicks} (если он ещё в игре). */
    void giveItemLater(UUID ownerId, ItemStack item, int delayTicks, String readyMsg) {
        if (item == null) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player on = Bukkit.getPlayer(ownerId);
            if (on == null || !on.isOnline() || !KitSession.isInGame(on)) return;
            on.getInventory().addItem(item);
            if (readyMsg != null) on.sendActionBar(Component.text(readyMsg, NamedTextColor.GREEN));
            on.playSound(on.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.4f);
        }, Math.max(1, delayTicks));
    }

    /** Дать абсорбцию не ниже {@code amount} на время; по истечении уменьшить на ту же величину. */
    void grantTempAbsorption(Player p, double amount, int durationTicks) {
        if (p == null || amount <= 0) return;
        double cur = p.getAbsorptionAmount();
        p.setAbsorptionAmount(Math.max(cur, amount));
        final UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player online = Bukkit.getPlayer(id);
            if (online == null || !online.isOnline()) return;
            online.setAbsorptionAmount(Math.max(0.0, online.getAbsorptionAmount() - amount));
        }, Math.max(1, durationTicks));
    }

    void applySlow(Player p, int ticks, int amp) {
        if (p == null || ticks <= 0) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, Math.max(0, amp), false, true, true));
    }

    void applyWeakness(Player p, int ticks, int amp) {
        if (p == null || ticks <= 0 || amp < 0) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, ticks, amp, false, true, true));
    }

    void applyRegen(Player p, int ticks, int amp) {
        if (p == null || ticks <= 0 || amp < 0) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, ticks, amp, false, true, true));
    }

    void applyWither(Player p, int ticks, int amp) {
        if (p == null || ticks <= 0 || amp < 0) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, ticks, amp, false, true, true));
    }

    /** Запрет прыжка: Jump Boost с огромным усилением = отрицательная сила прыжка → прыгнуть нельзя. */
    void applyNoJump(Player p, int ticks) {
        if (p == null || ticks <= 0) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, ticks, 128, false, false, false));
    }

    /** Запустить ульту-бурю Аяки (Kamisato Art: Soumetsu). */
    void castFrostStorm(Player p) {
        if (frostStorm != null) frostStorm.cast(p);
    }

    void applyResistance(Player p, int ticks, int amp) {
        if (p == null || ticks <= 0 || amp < 0) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, ticks, amp, false, false, true));
    }

    void applySpeed(Player p, int ticks, int amp) {
        if (p == null || ticks <= 0 || amp < 0) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, ticks, amp, false, false, true));
    }

    /** Снять у союзника вредные эффекты (мягкая «очистка» в зоне Ward). */
    void cleanseAlly(Player p) {
        if (p == null) return;
        p.removePotionEffect(PotionEffectType.SLOWNESS);
        p.removePotionEffect(PotionEffectType.POISON);
        p.removePotionEffect(PotionEffectType.WEAKNESS);
        if (p.getFireTicks() > 0) p.setFireTicks(0);
        if (p.getFreezeTicks() > 0) p.setFreezeTicks(0);
    }

    /* ============================================================ */
    /* Root / Freeze / Invuln                                        */
    /* ============================================================ */

    /** Корень: блокирует только горизонтальное движение (камера/прыжки живут) + сильный Slowness. */
    void applyRoot(Player p, int ticks) {
        if (p == null || ticks <= 0) return;
        rootUntilMs.put(p.getUniqueId(), System.currentTimeMillis() + ticks * 50L);
        applySlow(p, ticks, 6);
    }

    /** Заморозка = root + очень сильный Slowness (для emergency/death-save по врагам). */
    void applyFreeze(Player p, int ticks, int slowAmp) {
        if (p == null || ticks <= 0) return;
        rootUntilMs.put(p.getUniqueId(), System.currentTimeMillis() + ticks * 50L);
        applySlow(p, ticks, Math.max(5, slowAmp));
        p.getWorld().spawnParticle(org.bukkit.Particle.SNOWFLAKE, p.getLocation().add(0, 1, 0), 10, 0.3, 0.6, 0.3, 0.01);
    }

    private boolean isRooted(Player p) {
        Long until = rootUntilMs.get(p.getUniqueId());
        return until != null && System.currentTimeMillis() < until;
    }

    void grantInvuln(Player p, int ticks) {
        if (p == null || ticks <= 0) return;
        invulnUntilMs.put(p.getUniqueId(), System.currentTimeMillis() + ticks * 50L);
    }

    private boolean isInvuln(Player p) {
        Long until = invulnUntilMs.get(p.getUniqueId());
        return until != null && System.currentTimeMillis() < until;
    }

    /* ============================================================ */
    /* Garden ult: полный фриз игроков в радиусе                      */
    /* ============================================================ */

    /** Заморозить/продлить полный фриз цели (таск сада обновляет каждый тик с TTL). */
    void freezeGarden(UUID id, int ticks) {
        long until = System.currentTimeMillis() + Math.max(1, ticks) * 50L;
        Long cur = gardenFrozenUntilMs.get(id);
        if (cur == null || until > cur) gardenFrozenUntilMs.put(id, until);
    }

    /** true, пока цель под полным фризом сада (движение/действия запрещены). */
    boolean isGardenFrozen(UUID id) {
        Long until = gardenFrozenUntilMs.get(id);
        return until != null && System.currentTimeMillis() < until;
    }

    /* ============================================================ */
    /* Семена: стандартное «раскрытие» (для способностей-роз)        */
    /* ============================================================ */

    /** Раскрыть вражеское семя: slow + ледяной шип + небольшой урон. Без семени — ничего. */
    void genericBloom(Player owner, Player target) {
        RoseSeed s = seeds.consumeEnemySeed(target.getUniqueId());
        if (s == null) return;
        int slowDur = numInt("seeds", "enemyDetonationSlowDurationTicks", 20);
        int slowAmp = numInt("seeds", "enemyDetonationSlowAmplifier", 1);
        double dmg = num("seeds", "enemyDetonationDamage", 1.5);
        applySlow(target, slowDur, slowAmp);
        BlueRoseVisualUtil.iceSpike(target.getLocation());
        BlueRoseVisualUtil.soundBind(target.getLocation());
        if (dmg > 0 && owner != null) safeDamage(target, dmg, owner);
    }

    void safeDamage(Player target, double dmg, Player source) {
        if (target == null || dmg <= 0) return;
        try { target.damage(dmg, source); } catch (Throwable ignored) {}
    }

    /** Урон БЕЗ отбрасывания: наносим и тут же возвращаем прежнюю скорость (гасим ванильный кнокбэк). */
    void damageNoKnockback(Player target, double dmg, Player source) {
        if (target == null || dmg <= 0) return;
        org.bukkit.util.Vector before = target.getVelocity();
        try { target.damage(dmg, source); } catch (Throwable ignored) {}
        try { target.setVelocity(before); } catch (Throwable ignored) {}
    }

    /** Knockback врагов источника от центра (переиспользует ExplosionUtil, союзники не задеты). */
    void knockbackEnemies(Location center, double radius, double strength, Player source) {
        ExplosionUtil.knockbackPlayers(center, radius, strength, 0.45, source, false, true);
    }

    /* ============================================================ */
    /* Heritage                                                      */
    /* ============================================================ */

    /**
     * Повесить Heritage-розу на союзника. Живёт БЕССРОЧНО (нет таймера) — снимается только
     * использованием (аварийным раскрытием при крит.HP), смертью, выходом цели/владельца или
     * их переносом в другой мир. Союзное семя (визуал-маркер + синергия лечения) живёт столько же.
     */
    void putHeritage(Player owner, Player target, boolean selfCast) {
        heritage.put(target.getUniqueId(), new HeritageBloom(owner.getUniqueId(), Long.MAX_VALUE, selfCast));
        seeds.applyAllySeedPermanent(owner, target);
    }

    boolean hasHeritage(UUID target) {
        HeritageBloom b = heritage.get(target);
        if (b == null) return false;
        if (System.currentTimeMillis() >= b.expiresAtMs) { heritage.remove(target); return false; }
        return true;
    }

    /** Снять Heritage-розу при переносе игрока в другой мир — и как цели, и как владельца. */
    private void dropHeritageOnWorldChange(UUID id) {
        if (heritage.remove(id) != null) seeds.removeAllySeed(id);   // его собственная роза-цель
        heritageEmergencyCdUntil.remove(id);
        heritage.entrySet().removeIf(en -> {                         // розы, что он поставил на других
            if (en.getValue().owner.equals(id)) { seeds.removeAllySeed(en.getKey()); return true; }
            return false;
        });
    }

    /* ============================================================ */
    /* Зоны (Ward / Garden / Trail)                                  */
    /* ============================================================ */

    void registerZone(RoseZone z) {
        zonesByOwner.computeIfAbsent(z.owner, k -> new HashSet<>()).add(z);
        if (z.kind == RoseZone.Kind.GARDEN) activeGardens.add(z);
    }

    void unregisterZone(RoseZone z) {
        Set<RoseZone> set = zonesByOwner.get(z.owner);
        if (set != null) {
            set.remove(z);
            if (set.isEmpty()) zonesByOwner.remove(z.owner);
        }
        activeGardens.remove(z);
    }

    /** Снять зону полностью (на естественном истечении из её таска). */
    void endZone(RoseZone z) {
        z.cancel();
        unregisterZone(z);
    }

    int wardCount(UUID owner) {
        Set<RoseZone> set = zonesByOwner.get(owner);
        if (set == null) return 0;
        int c = 0;
        for (RoseZone z : set) if (z.kind == RoseZone.Kind.WARD && !z.isCancelled()) c++;
        return c;
    }

    /** Если у владельца уже {@code max} роз — снять самую старую. */
    void enforceWardLimit(UUID owner, int max) {
        while (wardCount(owner) >= Math.max(1, max)) {
            RoseZone oldest = null;
            Set<RoseZone> set = zonesByOwner.get(owner);
            if (set == null) return;
            for (RoseZone z : set) {
                if (z.kind != RoseZone.Kind.WARD || z.isCancelled()) continue;
                if (oldest == null || z.createdAtMs < oldest.createdAtMs) oldest = z;
            }
            if (oldest == null) return;
            endZone(oldest);
        }
    }

    /** Один активный Garden на Guardian — старый снимается. */
    void enforceSingleGarden(UUID owner) {
        Set<RoseZone> set = zonesByOwner.get(owner);
        if (set == null) return;
        for (RoseZone z : new ArrayList<>(set)) {
            if (z.kind == RoseZone.Kind.GARDEN) endZone(z);
        }
    }

    /** Одна активная Rosebind-роза на Guardian — новая ломает прежнюю. */
    void enforceSingleRosebind(UUID owner) {
        Set<RoseZone> set = zonesByOwner.get(owner);
        if (set == null) return;
        for (RoseZone z : new ArrayList<>(set)) {
            if (z.kind != RoseZone.Kind.ROSEBIND) continue;
            if (z.center != null) BlueRoseVisualUtil.roseBurst(z.center, 0.8f);
            endZone(z);
        }
    }

    /** Активная Rosebind-роза владельца (или null). */
    RoseZone activeRosebind(UUID owner) {
        Set<RoseZone> set = zonesByOwner.get(owner);
        if (set == null) return null;
        for (RoseZone z : set) if (z.kind == RoseZone.Kind.ROSEBIND && !z.isCancelled()) return z;
        return null;
    }

    /** Открыть окно «вождения» розы на {@code windowTicks} (ЛКМ зажат/клик продлевает его). */
    void driveRosebind(UUID owner, int windowTicks) {
        rosebindDriveUntil.put(owner, System.currentTimeMillis() + windowTicks * 50L);
    }

    /** true, пока окно «вождения» розы открыто. */
    boolean isDrivingRosebind(UUID owner) {
        Long until = rosebindDriveUntil.get(owner);
        return until != null && System.currentTimeMillis() < until;
    }

    /** Режим розы: false = «Стоять» (ручное ведение), true = «Поиск» (авто-погоня за врагом). */
    void setRosebindHunt(UUID owner, boolean hunt) { rosebindHuntMode.put(owner, hunt); }
    boolean rosebindHunt(UUID owner) { return Boolean.TRUE.equals(rosebindHuntMode.get(owner)); }

    /**
     * Тик защитной зоны розы (Ward / Rosebind — «один в один»): лечит/чистит союзников
     * (бонус при ally-семени), давит врагов slow/weakness и метит Rose Seed'ом.
     * Балансовые числа берутся по ключу {@code cfgKey} ("ward" или "rosebind").
     */
    void applyWardTickEffects(Player owner, Location center, double radius, double healMult, String cfgKey) {
        double healAmount = num(cfgKey, "allyHealAmount", 1.0) * healMult;
        int resAmp = numInt(cfgKey, "allyResistanceAmplifier", 0);
        double seedBonus = num("seeds", "allyHealBonusMultiplier", 1.25);
        for (Player ally : alliesIn(owner, center, radius, true)) {
            double heal = healAmount;
            if (seeds.hasAllySeed(ally.getUniqueId())) heal *= seedBonus;
            healAlly(ally, heal);
            applyResistance(ally, 30, resAmp);
            cleanseAlly(ally);
            BlueRoseVisualUtil.bodyPetals(ally.getLocation());
        }

        int slowAmp = numInt(cfgKey, "enemySlowAmplifier", 1);
        int weakAmp = numInt(cfgKey, "enemyWeaknessAmplifier", 0);
        int seedDur = numInt("seeds", "enemyDurationTicks", 100);
        int seedCd = numInt(cfgKey, "enemySeedApplicationCooldownTicks", 60);
        for (Player enemy : enemiesIn(owner, center, radius)) {
            applySlow(enemy, 30, slowAmp);
            if (weakAmp >= 0) applyWeakness(enemy, 30, weakAmp);
            if (seeds.tryApplyCooldown(owner.getUniqueId(), enemy.getUniqueId(), seedCd)) {
                seeds.applyEnemySeed(owner, enemy, seedDur);
            }
        }
    }

    /** Невидимый ArmorStand-якорь с «моделью розы» (минимум entity, чистится через RoseZone). */
    ArmorStand spawnRoseAnchor(Location at, String modelPath) {
        World w = at.getWorld();
        if (w == null) return null;
        return w.spawn(at, ArmorStand.class, a -> {
            a.setInvisible(true);
            a.setMarker(true);
            a.setGravity(false);
            a.setInvulnerable(true);
            a.setSilent(true);
            a.setSmall(true);
            a.setPersistent(false);
            a.setCanPickupItems(false);
            a.addScoreboardTag(ANCHOR_TAG);
            try {
                if (a.getEquipment() != null) a.getEquipment().setHelmet(roseHelmet(modelPath));
            } catch (Throwable ignored) {}
        });
    }

    private ItemStack roseHelmet(String modelPath) {
        ItemStack it = new ItemStack(Material.CORNFLOWER);
        // item_model отключён: текстур пока нет — якорь рендерится дефолтным CORNFLOWER (синий цветок).
        // Вернуть: ItemMeta m = it.getItemMeta(); if (m != null) { ItemModels.apply(m, modelPath); it.setItemMeta(m); }
        return it;
    }

    /* ============================================================ */
    /* Фабрики предметов                                             */
    /* ============================================================ */

    public ItemStack makeRoseOath() {
        ItemStack it = new ItemStack(MAT_ROSE_OATH);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Rose Oath", NamedTextColor.AQUA));
        im.setUnbreakable(true);
        im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
        im.getPersistentDataContainer().set(KEY_ROSE_OATH, PersistentDataType.BYTE, (byte) 1);
        // item_model отключён: нет текстур — вернуть: ItemModels.apply(im, "blue_rose_guardian_rose_oath");
        it.setItemMeta(im);
        return it;
    }

    public ItemStack makeWard() {
        ItemStack it = new ItemStack(MAT_WARD);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Blue Rose Ward", NamedTextColor.AQUA));
        im.getPersistentDataContainer().set(KEY_WARD, PersistentDataType.BYTE, (byte) 1);
        // item_model отключён: нет текстур — вернуть: ItemModels.apply(im, "blue_rose_guardian_ward");
        it.setItemMeta(im);
        return it;
    }

    public ItemStack makeRosebind() {
        ItemStack it = new ItemStack(MAT_ROSEBIND);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Rosebind", NamedTextColor.AQUA));
        im.getPersistentDataContainer().set(KEY_ROSEBIND, PersistentDataType.BYTE, (byte) 1);
        // item_model отключён: нет текстур — вернуть: ItemModels.apply(im, "blue_rose_guardian_rosebind");
        it.setItemMeta(im);
        return it;
    }

    public ItemStack makePetalStep() {
        ItemStack it = new ItemStack(MAT_PETAL);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Petal Step", NamedTextColor.AQUA));
        im.getPersistentDataContainer().set(KEY_PETAL, PersistentDataType.BYTE, (byte) 1);
        // item_model отключён: нет текстур — вернуть: ItemModels.apply(im, "blue_rose_guardian_petal_step");
        it.setItemMeta(im);
        return it;
    }

    public ItemStack makeHeritageBloom() {
        ItemStack it = new ItemStack(MAT_HERITAGE);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Heritage Bloom", NamedTextColor.AQUA));
        im.getPersistentDataContainer().set(KEY_HERITAGE, PersistentDataType.BYTE, (byte) 1);
        // item_model отключён: нет текстур — вернуть: ItemModels.apply(im, "blue_rose_guardian_heritage_bloom");
        it.setItemMeta(im);
        return it;
    }

    public ItemStack makeGarden() {
        ItemStack it = new ItemStack(MAT_GARDEN);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Garden of the First Rose", NamedTextColor.AQUA));
        im.getPersistentDataContainer().set(KEY_GARDEN, PersistentDataType.BYTE, (byte) 1);
        // item_model отключён: нет текстур — вернуть: ItemModels.apply(im, "blue_rose_guardian_garden");
        it.setItemMeta(im);
        return it;
    }

    public ItemStack makeStorm() {
        ItemStack it = new ItemStack(MAT_STORM);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Kamisato Art: Soumetsu", NamedTextColor.AQUA));
        im.getPersistentDataContainer().set(KEY_STORM, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(im);
        return it;
    }

    private boolean isTagged(ItemStack it, Material mat, NamespacedKey key, String name) {
        if (it == null || it.getType() != mat || !it.hasItemMeta()) return false;
        ItemMeta im = it.getItemMeta();
        if (im.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) return true;
        return Component.text(name, NamedTextColor.AQUA).equals(im.displayName());
    }

    boolean isRoseOath(ItemStack it) { return isTagged(it, MAT_ROSE_OATH, KEY_ROSE_OATH, "Rose Oath"); }
    boolean isWard(ItemStack it)     { return isTagged(it, MAT_WARD, KEY_WARD, "Blue Rose Ward"); }
    boolean isRosebind(ItemStack it) { return isTagged(it, MAT_ROSEBIND, KEY_ROSEBIND, "Rosebind"); }
    boolean isPetalStep(ItemStack it){ return isTagged(it, MAT_PETAL, KEY_PETAL, "Petal Step"); }
    boolean isHeritage(ItemStack it) { return isTagged(it, MAT_HERITAGE, KEY_HERITAGE, "Heritage Bloom"); }
    boolean isGarden(ItemStack it)   { return isTagged(it, MAT_GARDEN, KEY_GARDEN, "Garden of the First Rose"); }
    boolean isStorm(ItemStack it)    { return isTagged(it, MAT_STORM, KEY_STORM, "Kamisato Art: Soumetsu"); }

    /* ============================================================ */
    /* Глобальный тикер                                              */
    /* ============================================================ */

    private void startGlobalTicker() {
        this.globalTicker = new BukkitRunnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();
                seeds.tick();
                heritage.entrySet().removeIf(e -> now >= e.getValue().expiresAtMs);
                heritageEmergencyCdUntil.values().removeIf(until -> now >= until);
                rootUntilMs.values().removeIf(until -> now >= until);
                invulnUntilMs.values().removeIf(until -> now >= until);
                gardenFrozenUntilMs.values().removeIf(until -> now >= until);
                roseOathHitCd.values().removeIf(until -> now >= until);
            }
        }.runTaskTimer(plugin, 5L, 5L);
    }

    /* ============================================================ */
    /* Перехват урона                                                */
    /* ============================================================ */

    /** Rose Oath: удар по врагу с активным семенем — раскрытие (slow + шип + бонус-урон). */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.NORMAL)
    public void onMeleeHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker)) return;
        if (!(e.getEntity() instanceof Player victim)) return;
        if (!isRoseOath(attacker.getInventory().getItemInMainHand())) return;
        if (!KitSession.isInGame(attacker)) return;
        if (!isEnemy(attacker, victim)) return;
        if (!seeds.hasEnemySeed(victim.getUniqueId())) return;

        int cdTicks = numInt("sword", "internalCooldownPerTargetTicks", 60);
        String key = attacker.getUniqueId() + "|" + victim.getUniqueId();
        long now = System.currentTimeMillis();
        Long until = roseOathHitCd.get(key);
        if (until != null && now < until) return;
        roseOathHitCd.put(key, now + cdTicks * 50L);

        seeds.consumeEnemySeed(victim.getUniqueId());
        int slowDur = numInt("sword", "slowDurationTicks", 20);
        int slowAmp = numInt("sword", "slowAmplifier", 1);
        double bonus = num("sword", "seedDetonationDamage", 1.5);
        applySlow(victim, slowDur, slowAmp);
        BlueRoseVisualUtil.iceSpike(victim.getLocation());
        BlueRoseVisualUtil.soundBind(victim.getLocation());
        if (bonus > 0) e.setDamage(e.getDamage() + bonus);
    }

    /** Invuln + Heritage emergency + Garden death-save. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        if (victim.isDead()) return;

        // (1) активная неуязвимость (emergency/death-save окно)
        if (isInvuln(victim)) { e.setCancelled(true); return; }

        // getFinalDamage() уже учитывает абсорбцию/броню/резист — это итог, который снимется со
        // ЗДОРОВЬЯ. Не вычитаем абсорбцию повторно (иначе летальный удар посчитается «выживаемым»).
        double finalDmg = e.getFinalDamage();
        double predicted = victim.getHealth() - finalDmg;
        boolean lethal = predicted <= 0.0001;
        double max = maxHealth(victim);
        double ratio = max > 0 ? predicted / max : 0;

        // (2) Heritage Bloom — аварийное раскрытие при критическом здоровье
        boolean heritageSavedLethal = false;
        if (hasHeritage(victim.getUniqueId())) {
            int threshold = numInt("heritage", "emergencyHealthThresholdPercent", 30);
            boolean belowThreshold = ratio * 100.0 < threshold;
            long now = System.currentTimeMillis();
            Long cd = heritageEmergencyCdUntil.get(victim.getUniqueId());
            boolean cdOk = (cd == null || now >= cd);
            if ((lethal || belowThreshold) && cdOk) {
                triggerHeritageEmergency(victim, lethal, e);
                // Heritage уже спас летальный удар (отменил событие) — Garden бережём для других.
                heritageSavedLethal = lethal && e.isCancelled();
            }
        }

        // (3) Garden of the First Rose — First Rose Salvation (только летальный урон,
        //     если Heritage его уже не отменил; механики независимы).
        if (deathSaveEnabled && lethal && !heritageSavedLethal && saveableCause(e.getCause())) {
            RoseZone garden = findGardenSaving(victim);
            if (garden != null && garden.deathSavesLeft > 0) {
                triggerGardenSalvation(victim, garden, e);
            }
        }
    }

    private void triggerHeritageEmergency(Player victim, boolean lethal, EntityDamageEvent e) {
        HeritageBloom bloom = heritage.remove(victim.getUniqueId());
        if (bloom == null) return;
        seeds.removeAllySeed(victim.getUniqueId());

        Player owner = Bukkit.getPlayer(bloom.owner);
        boolean bloodline = owner != null && bloodlineActive(owner);
        double mult = (bloom.selfCast ? selfCastMultiplier : 1.0) * (bloodline ? bloodlineEmergencyMult() : 1.0);

        double heal = num("heritage", "emergencyHeal", 3.0) * mult;
        double shield = num("heritage", "emergencyAbsorption", 2.0) * mult;
        int invulnTicks = numInt("heritage", "emergencyInvulnerabilityTicks", 15);
        double kbRadius = num("heritage", "enemyKnockbackRadius", 3.5) + (bloodline ? bloodlineKnockbackBonus() : 0);
        double kbStr = num("heritage", "enemyKnockbackStrength", 1.0);
        int freezeTicks = numInt("heritage", "enemyFreezeDurationTicks", 12);

        Player kbSource = owner != null ? owner : victim;
        if (lethal) {
            e.setCancelled(true);
            victim.setHealth(Math.min(maxHealth(victim), Math.max(1.0, heal)));
            victim.setAbsorptionAmount(Math.max(victim.getAbsorptionAmount(), shield));
        } else {
            final double fHeal = heal, fShield = shield;
            final UUID id = victim.getUniqueId();
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player on = Bukkit.getPlayer(id);
                if (on == null || !on.isOnline() || on.isDead()) return;
                healAlly(on, fHeal);
                on.setAbsorptionAmount(Math.max(on.getAbsorptionAmount(), fShield));
            });
        }
        grantInvuln(victim, invulnTicks);
        Location c = victim.getLocation();
        if (c != null && c.getWorld() != null) {
            knockbackEnemies(c, kbRadius, kbStr, kbSource);
            for (Player en : enemiesIn(kbSource, c, kbRadius)) applyFreeze(en, freezeTicks, 5);
            BlueRoseVisualUtil.roseBurst(c, 1.3f);
            BlueRoseVisualUtil.soundBloom(c);
        }
        victim.sendActionBar(Component.text("Heritage Bloom shields you!", NamedTextColor.AQUA));
        heritageEmergencyCdUntil.put(victim.getUniqueId(),
                System.currentTimeMillis() + numInt("heritage", "perTargetEmergencyCooldownTicks", 120) * 50L);
    }

    private void triggerGardenSalvation(Player victim, RoseZone garden, EntityDamageEvent e) {
        e.setCancelled(true);
        garden.deathSavesLeft--;

        Player owner = Bukkit.getPlayer(garden.owner);
        Player kbSource = owner != null ? owner : victim;

        victim.setHealth(Math.min(maxHealth(victim), 1.0));
        victim.setAbsorptionAmount(0.0);
        int invulnTicks = numInt("garden", "deathSaveInvulnerabilityTicks", 20);
        grantInvuln(victim, invulnTicks);

        double kbRadius = num("garden", "deathSaveKnockbackRadius", 4.5);
        double kbStr = num("garden", "deathSaveKnockbackStrength", 1.4);
        int freezeTicks = numInt("garden", "deathSaveFreezeDurationTicks", 18);
        Location c = victim.getLocation();
        if (c != null && c.getWorld() != null) {
            knockbackEnemies(c, kbRadius, kbStr, kbSource);
            for (Player en : enemiesIn(kbSource, c, kbRadius)) applyFreeze(en, freezeTicks, 6);
            BlueRoseVisualUtil.roseBurst(c, 1.8f);
            BlueRoseVisualUtil.soundSalvation(c);
        }
        victim.sendActionBar(Component.text("First Rose Salvation!", NamedTextColor.AQUA));
    }

    /** Сад, который может спасти эту жертву (член команды владельца внутри радиуса). */
    private RoseZone findGardenSaving(Player victim) {
        for (RoseZone g : activeGardens) {
            if (g.isCancelled() || g.deathSavesLeft <= 0 || g.center == null) continue;
            if (g.center.getWorld() == null || !g.center.getWorld().equals(victim.getWorld())) continue;
            if (victim.getLocation().distanceSquared(g.center) > g.radius * g.radius) continue;
            Player owner = Bukkit.getPlayer(g.owner);
            boolean member = victim.getUniqueId().equals(g.owner)
                    || (owner != null && ExplosionUtil.isFriendly(owner, victim));
            if (member) return g;
        }
        return null;
    }

    private boolean saveableCause(EntityDamageEvent.DamageCause cause) {
        if (cause == EntityDamageEvent.DamageCause.VOID) return allowVoidSave;
        if (cause == EntityDamageEvent.DamageCause.KILL) return false;     // /kill и спец-добивания
        return true;
    }

    /* ============================================================ */
    /* Root move-lock                                                */
    /* ============================================================ */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (isGardenFrozen(p.getUniqueId())) {
            // Полный стоп: фиксируем XYZ (в т.ч. Y → зависание в воздухе, гасит гравитацию/нокбэк),
            // камеру не трогаем (можно осмотреться). Так замораживаются даже летящие/падающие.
            Location ffrom = e.getFrom();
            Location fto = e.getTo();
            if (fto != null && (ffrom.getX() != fto.getX() || ffrom.getY() != fto.getY() || ffrom.getZ() != fto.getZ())) {
                e.setTo(new Location(ffrom.getWorld(), ffrom.getX(), ffrom.getY(), ffrom.getZ(), fto.getYaw(), fto.getPitch()));
            }
            return;
        }
        if (!isRooted(p)) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        if (from.getX() == to.getX() && from.getZ() == to.getZ()) return; // только поворот/вертикаль
        // блокируем горизонталь, оставляя Y (гравитация/прыжок) и поворот камеры
        e.setTo(new Location(from.getWorld(), from.getX(), to.getY(), from.getZ(), to.getYaw(), to.getPitch()));
    }

    /* ====== Полный фриз Garden: запрет действий замороженным ====== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFrozenAttack(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && isGardenFrozen(p.getUniqueId())) e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFrozenShoot(ProjectileLaunchEvent e) {
        if (e.getEntity().getShooter() instanceof Player p && isGardenFrozen(p.getUniqueId())) e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFrozenInteract(PlayerInteractEvent e) {
        if (isGardenFrozen(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFrozenInvClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && isGardenFrozen(p.getUniqueId())) e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFrozenDrop(PlayerDropItemEvent e) {
        if (isGardenFrozen(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFrozenSwap(PlayerSwapHandItemsEvent e) {
        if (isGardenFrozen(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFrozenHeld(PlayerItemHeldEvent e) {
        if (isGardenFrozen(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    /* ============================================================ */
    /* Жизненный цикл / очистка                                       */
    /* ============================================================ */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) { cleanup(e.getPlayer()); }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) { cleanup(e.getEntity()); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        if (e.getNewGameMode() == GameMode.SPECTATOR) cleanup(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (KitSession.isLobbyWorld(p.getWorld())) { cleanup(p); return; }
        // Перенос в другой (не-лобби) мир: полную очистку не делаем, но Heritage-роза смену мира не переживает.
        dropHeritageOnWorldChange(p.getUniqueId());
    }

    @Override
    public void resetPlayer(Player player) { cleanup(player); }

    /**
     * Полная идемпотентная очистка игрока И как Guardian'а (его зоны/семена/кд),
     * И как цели (семена/Heritage/root/invuln на нём).
     */
    private void cleanup(Player player) {
        if (player == null) return;
        UUID id = player.getUniqueId();

        // как Guardian
        cancelZones(id);
        rosebindDriveUntil.remove(id);
        rosebindHuntMode.remove(id);
        seeds.clearOwner(id);
        heritage.values().removeIf(b -> b.owner.equals(id));
        roseOathHitCd.keySet().removeIf(k -> k.startsWith(id + "|"));
        BlueRoseGuardianState s = states.remove(id);
        if (s != null) {
            for (BukkitTask t : s.cdTasks.values()) if (t != null) t.cancel();
            s.cdTasks.clear();
        }

        // как цель
        seeds.clearTarget(id);
        heritage.remove(id);
        heritageEmergencyCdUntil.remove(id);
        rootUntilMs.remove(id);
        invulnUntilMs.remove(id);
        gardenFrozenUntilMs.remove(id);
        roseOathHitCd.keySet().removeIf(k -> k.endsWith("|" + id));
    }

    private void cancelZones(UUID owner) {
        Set<RoseZone> set = zonesByOwner.remove(owner);
        if (set != null) for (RoseZone z : new ArrayList<>(set)) { z.cancel(); activeGardens.remove(z); }
    }

    /** Снять все зоны/таски (Main.onDisable). Per-player очистку делает SessionManager. */
    public void stop() {
        if (globalTicker != null) { globalTicker.cancel(); globalTicker = null; }
        for (Set<RoseZone> set : new ArrayList<>(zonesByOwner.values())) {
            for (RoseZone z : new ArrayList<>(set)) z.cancel();
        }
        zonesByOwner.clear();
        activeGardens.clear();
        for (BlueRoseGuardianState s : states.values()) {
            for (BukkitTask t : s.cdTasks.values()) if (t != null) t.cancel();
        }
        states.clear();
        seeds.clearAll();
        heritage.clear();
        rosebindDriveUntil.clear();
        rosebindHuntMode.clear();
        gardenFrozenUntilMs.clear();
        if (frostStorm != null) frostStorm.shutdown(); // откатить весь наш лёд при выключении
    }
}
