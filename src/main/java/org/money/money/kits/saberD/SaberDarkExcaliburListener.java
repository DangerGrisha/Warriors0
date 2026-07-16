package org.money.money.kits.saberD;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.money.money.util.ItemModels;

import javax.swing.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class SaberDarkExcaliburListener implements Listener {

    /* =========================
       CONFIG (easy to tune)
       ========================= */

    // Base values similar to diamond sword feel
    private static double baseDamage() { return org.money.money.meta.ClassRegistry.num("saberdark", "excaliburd", "baseDamage", 7.0); }
    private static double baseAttackSpeed() { return org.money.money.meta.ClassRegistry.num("saberdark", "excaliburd", "baseAttackSpeed", 1.6); }

    // Souls scaling
    private static double damagePerSoul() { return org.money.money.meta.ClassRegistry.num("saberdark", "excaliburd", "damagePerSoul", 0.5); }
    private static double attackSpeedPerSoul() { return org.money.money.meta.ClassRegistry.num("saberdark", "excaliburd", "attackSpeedPerSoul", 0.1); }
    private static final double MIN_ATTACK_SPEED = 0.4;     // Safety clamp

    // Heaviness: each soul weighs the blade down -> slower movement while held.
    private static double movementPenaltyPerSoul() { return org.money.money.meta.ClassRegistry.num("saberdark", "excaliburd", "movementPenaltyPerSoul", 0.0015); }
    private static final double PLAYER_BASE_MOVE_SPEED = 0.1; // vanilla player MOVEMENT_SPEED base
    private static final double MIN_MOVEMENT_SPEED = 0.045;   // floor so high souls never fully immobilize

    // Guard break system
    private static double guardBreakThreshold() { return org.money.money.meta.ClassRegistry.num("saberdark", "excaliburd", "guardBreakThreshold", 12.0); }
    private static long guardBreakDurationTicks() { return org.money.money.meta.ClassRegistry.numInt("saberdark", "excaliburd", "guardBreakDurationTicks", 100); }
    private static final long   GUARD_REGEN_PERIOD_TICKS = 20L * 2; // every 2 sec
    private static final double GUARD_REGEN_AMOUNT = 1.0;         // -1 blocked damage

    // How much shield blocks (custom % for your system)
    private static double blockPercent() { return org.money.money.meta.ClassRegistry.num("saberdark", "excaliburd", "blockPercent", 0.80); }

    // Direction check for frontal blocking
    private static final double FRONT_BLOCK_DOT = 0.15; // >0 means attack roughly from front hemisphere

    private final Plugin plugin;

    /* =========================
       PDC KEYS
       ========================= */
    private final NamespacedKey KEY_EXCALIBUR;
    private final NamespacedKey KEY_SOULS;

    /* =========================
       Runtime state
       ========================= */

    // Player -> current blocked damage accumulated for shield break
    private final Map<UUID, Double> blockedDamage = new ConcurrentHashMap<>();

    // Player -> timestamp millis until shield is stunned
    private final Map<UUID, Long> shieldStunUntil = new ConcurrentHashMap<>();

    // Player -> regen task
    private final Map<UUID, BukkitTask> regenTasks = new ConcurrentHashMap<>();

    // Victim -> last attacker who hit with Excalibur (for totem pop / kill)
    private final Map<UUID, UUID> lastExcaliburDamager = new ConcurrentHashMap<>();

    // Small window for validating "recently hit by Excalibur"
    private final Map<UUID, Long> lastExcaliburHitAt = new ConcurrentHashMap<>();
    private static final long LAST_HIT_WINDOW_MS = 4000L;

    // Player -> банк душ, переживающий смерть→респавн (души не теряются — копятся в мече).
    private final Map<UUID, Integer> soulBank = new ConcurrentHashMap<>();

    public SaberDarkExcaliburListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);

        this.KEY_EXCALIBUR = new NamespacedKey(plugin, "saberd_excalibur");
        this.KEY_SOULS     = new NamespacedKey(plugin, "saberd_excalibur_souls");

        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /* =========================
       PUBLIC: item factory
       ========================= */

    public ItemStack makeExcalibur() {
        ItemStack it = new ItemStack(Material.SHIELD);
        ItemMeta meta = it.getItemMeta();

        // Mark as our custom Excalibur
        meta.getPersistentDataContainer().set(KEY_EXCALIBUR, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(KEY_SOULS, PersistentDataType.INTEGER, 0);

        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
        meta.setUnbreakable(true);

        ItemModels.apply(meta, "saber_dexcalibur");
        it.setItemMeta(meta);

        // Apply display + attributes based on souls = 0
        rebuildExcaliburMeta(it, 0);

        return it;
    }

    public boolean isExcalibur(ItemStack it) {
        if (it == null || it.getType() != Material.SHIELD || !it.hasItemMeta()) return false;
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(KEY_EXCALIBUR, PersistentDataType.BYTE);
    }
    public void updateExcaliburSouls(ItemStack it, int souls) {
        souls = Math.max(0, souls);
        rebuildExcaliburMeta(it, souls);
    }

    public int getSouls(ItemStack it) {
        if (!isExcalibur(it)) return 0;
        ItemMeta meta = it.getItemMeta();
        Integer souls = meta.getPersistentDataContainer().get(KEY_SOULS, PersistentDataType.INTEGER);
        return souls == null ? 0 : Math.max(0, souls);
    }

    /* =========================
       Combat events
       ========================= */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onExcaliburHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker)) return;
        if (!(e.getEntity() instanceof LivingEntity victim)) return;
        if (victim instanceof Player pVictim && isTeammate(attacker, pVictim)) return;

        ItemStack main = attacker.getInventory().getItemInMainHand();
        if (!isExcalibur(main)) return;

        // Track last Excalibur hit for totem pop / death
        lastExcaliburDamager.put(victim.getUniqueId(), attacker.getUniqueId());
        lastExcaliburHitAt.put(victim.getUniqueId(), System.currentTimeMillis());

        // Excalibur custom damage scaling (optional but recommended)
        // We override damage to match our soul scaling.
        int souls = getSouls(main);
        double damage = baseDamage() + (souls * damagePerSoul());
        e.setDamage(damage);

        // Excalibur "heavy" feel via attack speed is handled in attributes on the item itself
        // (rebuildExcaliburMeta updates item attributes whenever souls change)
    }

    /**
     * Totem pop detection:
     * EntityResurrectEvent fires when a totem saves an entity.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onTotemPop(EntityResurrectEvent e) {
        Entity entity = e.getEntity();
        if (!(entity instanceof LivingEntity victim)) return;

        UUID victimId = victim.getUniqueId();
        UUID attackerId = lastExcaliburDamager.get(victimId);
        Long hitAt = lastExcaliburHitAt.get(victimId);

        if (attackerId == null || hitAt == null) return;
        if (System.currentTimeMillis() - hitAt > LAST_HIT_WINDOW_MS) return;

        Player attacker = Bukkit.getPlayer(attackerId);
        if (attacker == null || !attacker.isOnline()) return;

        // +1 soul for totem pop caused by Excalibur
        addSoul(attacker, "Totem Broken");
    }

    /**
     * Kill detection.
     * This catches player deaths. If you also want mobs, can add EntityDeathEvent.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();

        UUID victimId = victim.getUniqueId();
        UUID attackerId = lastExcaliburDamager.get(victimId);
        Long hitAt = lastExcaliburHitAt.get(victimId);

        // Clear victim guard UI/state on death (optional)
        blockedDamage.remove(victimId);
        shieldStunUntil.remove(victimId);
        hideGuardBar(victim);

        // Души жертвы не теряются после смерти — снимок + меч с душами не ронять.
        bankSoulsOnDeath(victim, e);

        if (attackerId == null || hitAt == null) return;
        if (System.currentTimeMillis() - hitAt > LAST_HIT_WINDOW_MS) return;

        Player attacker = Bukkit.getPlayer(attackerId);
        if (attacker == null || !attacker.isOnline()) return;

        addSoul(attacker, "Kill");
    }

    /* =========================
       Souls persist across death (накопление в мече)
       ========================= */

    /** Снимок душ жертвы перед дропом + меч с душами не ронять (вернём на респавне). */
    private void bankSoulsOnDeath(Player victim, PlayerDeathEvent e) {
        int souls = 0;
        for (ItemStack it : victim.getInventory().getContents()) if (isExcalibur(it)) souls = Math.max(souls, getSouls(it));
        ItemStack off = victim.getInventory().getItemInOffHand();
        if (isExcalibur(off)) souls = Math.max(souls, getSouls(off));
        for (ItemStack it : e.getDrops()) if (isExcalibur(it)) souls = Math.max(souls, getSouls(it));
        if (souls > 0) soulBank.put(victim.getUniqueId(), souls);
        e.getDrops().removeIf(this::isExcalibur);
    }

    /** На респавне восстановить накопленные души на (заново выданный) меч. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRespawnRestoreSouls(PlayerRespawnEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        Integer banked = soulBank.get(id);
        if (banked == null || banked <= 0) return;
        scheduleSoulRestore(id, banked, 0);
    }

    /** Подождать выдачу Excalibur после респавна и проставить накопленные души (ретраи ~10с). */
    private void scheduleSoulRestore(UUID id, int souls, int attempt) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) { soulBank.remove(id); return; }
            if (restoreSoulsTo(p, souls)) soulBank.remove(id);
            else if (attempt < 40) scheduleSoulRestore(id, souls, attempt + 1);
            else soulBank.remove(id);
        }, 5L);
    }

    /** Проставить души на Excalibur игрока, где бы он ни лежал. true — нашли и обновили. */
    private boolean restoreSoulsTo(Player p, int souls) {
        ItemStack[] store = p.getInventory().getStorageContents();
        for (int i = 0; i < store.length; i++) {
            if (isExcalibur(store[i])) {
                if (getSouls(store[i]) < souls) { updateExcaliburSouls(store[i], souls); p.getInventory().setItem(i, store[i]); }
                return true;
            }
        }
        ItemStack off = p.getInventory().getItemInOffHand();
        if (isExcalibur(off)) {
            if (getSouls(off) < souls) { updateExcaliburSouls(off, souls); p.getInventory().setItemInOffHand(off); }
            return true;
        }
        return false;
    }

    /* =========================
       Custom shield blocking / guard break
       ========================= */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onDefenderBlock(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player defender)) return;
        if (!(e.getDamager() instanceof LivingEntity attacker)) return;

        if (!isSaberDark(defender)) return;

        // Need Excalibur in main hand for this custom mechanic
        ItemStack main = defender.getInventory().getItemInMainHand();
        if (!isExcalibur(main)) return;

        // If shield currently stunned -> cannot block
        if (isShieldStunned(defender)) {
            // Shield is stunned: no custom block allowed
            // Force full damage through (in case vanilla shield is visually raised)
            e.setDamage(e.getDamage()); // keep full incoming damage
            showGuardBar(defender);



            return;
        }

        // Defender must be "using" the shield to block
        // In vanilla: holding right click with shield in main hand should set isBlocking = true
        if (!defender.isBlocking()) return;

        // Optional: only block from front
        if (!isFrontBlock(defender, attacker)) return;

        // Raw incoming damage from event (before final armor calculations).
        // For your custom system this is OK and easier to balance.
        double incoming = e.getDamage();
        if (incoming <= 0.0) return;

        double blocked = incoming * blockPercent();
        double passed = Math.max(0.0, incoming - blocked);

        // Apply reduced damage
        e.setDamage(passed);

        // Accumulate blocked damage toward guard break
        UUID id = defender.getUniqueId();
        double now = blockedDamage.getOrDefault(id, 0.0) + blocked;
        blockedDamage.put(id, now);

        // Start regen loop if not running
        ensureGuardRegen(defender);

        // UI update
        showGuardBar(defender);

        // Break check
        if (now >= guardBreakThreshold()) {
            triggerGuardBreak(defender);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onShieldUseWhileStunned(org.bukkit.event.player.PlayerInteractEvent e) {
        Player p = e.getPlayer();

        if (!isSaberDark(p)) return;
        if (!isShieldStunned(p)) return;

        // Only care when using Excalibur shield in main hand
        ItemStack main = p.getInventory().getItemInMainHand();
        if (!isExcalibur(main)) return;

        Action action = e.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        // Cancel raising shield
        e.setCancelled(true);


        // Feedback
        showGuardBar(p);
    }

    /* =========================
       Guard break helpers
       ========================= */

    private void triggerGuardBreak(Player p) {
        UUID id = p.getUniqueId();

        shieldStunUntil.put(id, System.currentTimeMillis() + (guardBreakDurationTicks() * 50L));
        blockedDamage.put(id, guardBreakThreshold()); // clamp for display before reset

        // Apply vanilla item cooldown visual on shield (optional, nice UX)
        p.setCooldown(Material.SHIELD, (int) guardBreakDurationTicks());

        try {
            p.clearActiveItem(); // Paper API, if available in your version
        } catch (Throwable ignored) {
            // If method unavailable, ignore
        }

        // Feedback
        p.sendActionBar(Component.text("§cGUARD BROKEN! §7(5s)"));
        p.playSound(p.getLocation(), Sound.ITEM_SHIELD_BREAK, 0.8f, 1.0f);
        p.getWorld().spawnParticle(Particle.CRIT, p.getLocation().add(0, 1.0, 0), 12, 0.3, 0.4, 0.3, 0.01);

        // After stun ends -> reset blocked damage to 0 and update UI
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;

            blockedDamage.put(id, 0.0);
            shieldStunUntil.remove(id);

            showGuardBar(p); // should show 0/20
            p.sendActionBar(Component.text("§aShield restored §7(0/20)"));
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 1.4f);
        }, guardBreakDurationTicks());
    }

    private boolean isShieldStunned(Player p) {
        Long until = shieldStunUntil.get(p.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    private void ensureGuardRegen(Player p) {
        UUID id = p.getUniqueId();
        if (regenTasks.containsKey(id)) return;

        BukkitTask t = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline()) {
                cancelGuardRegen(id);
                return;
            }

            // During stun we do not regen blocked counter (you asked full reset after 5 sec)
            if (isShieldStunned(p)) {
                showGuardBar(p);
                return;
            }

            double current = blockedDamage.getOrDefault(id, 0.0);
            if (current <= 0.0) {
                blockedDamage.put(id, 0.0);
                showGuardBar(p);
                return;
            }

            current = Math.max(0.0, current - GUARD_REGEN_AMOUNT);
            blockedDamage.put(id, current);
            showGuardBar(p);

        }, GUARD_REGEN_PERIOD_TICKS, GUARD_REGEN_PERIOD_TICKS);

        regenTasks.put(id, t);
    }

    private void cancelGuardRegen(UUID id) {
        BukkitTask t = regenTasks.remove(id);
        if (t != null) t.cancel();
    }

    private void showGuardBar(Player p) {
        if (!isSaberDark(p)) return;

        double value = blockedDamage.getOrDefault(p.getUniqueId(), 0.0);
        String text = "§b" + format1(value) + "§7/§f" + format1(guardBreakThreshold());

        // EXP BAR / LEVEL DISABLED (as requested)
        // p.setLevel(...)
        // p.setExp(...)

        if (isShieldStunned(p)) {
            p.sendActionBar(Component.text("§cShield Stunned §7| " + text));
        } else {
            p.sendActionBar(Component.text("§7Guard: " + text));
        }
    }

    private void hideGuardBar(Player p) {
        if (!isSaberDark(p)) return;

        // We no longer use EXP bar/level for guard UI.
        // Optional: clear action bar with a short blank message
        p.sendActionBar(Component.text(" "));
    }

    /* =========================
       Soul system
       ========================= */

    private void addSoul(Player attacker, String reason) {
        ItemStack main = attacker.getInventory().getItemInMainHand();
        if (!isExcalibur(main)) return;

        int souls = getSouls(main) + 1;
        rebuildExcaliburMeta(main, souls);

        attacker.getInventory().setItemInMainHand(main);

        attacker.playSound(attacker.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
        attacker.sendActionBar(Component.text("§4ExcaliburD Soul +1 §7(" + souls + ") §8[" + reason + "]"));
    }

    private void rebuildExcaliburMeta(ItemStack it, int souls) {
        if (it == null || it.getType() != Material.SHIELD) return;

        ItemMeta meta = it.getItemMeta();
        if (meta == null) return;

        // Persist souls
        meta.getPersistentDataContainer().set(KEY_EXCALIBUR, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(KEY_SOULS, PersistentDataType.INTEGER, souls);

        // Name: Excalibur - X
        meta.displayName(Component.text("ExcaliburD"));

        // Lore (optional but useful)
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("§7Souls: §6" + souls));
        lore.add(Component.text("§7Damage: §c" + format1(baseDamage() + souls * damagePerSoul())));
        lore.add(Component.text("§7Attack Speed: §b" + format1(calcAttackSpeed(souls))));
        lore.add(Component.text("§7Weight: §8-" + weightPercent(souls) + "% speed"));
        lore.add(Component.text("§8Blocks damage and can break guard"));
        meta.lore(lore);

        // Clear old modifiers first
        try {
            meta.removeAttributeModifier(Attribute.ATTACK_DAMAGE);
            meta.removeAttributeModifier(Attribute.ATTACK_SPEED);
            meta.removeAttributeModifier(Attribute.MOVEMENT_SPEED);
        } catch (Throwable ignored) {}

        // Apply attack damage and speed in MAIN_HAND
        double damage = baseDamage() + (souls * damagePerSoul());
        double atkSpeed = calcAttackSpeed(souls);

        AttributeModifier dmgMod = new AttributeModifier(
                new NamespacedKey(plugin, "excalibur_damage"),
                damage,
                AttributeModifier.Operation.ADD_NUMBER,
                EquipmentSlotGroup.MAINHAND
        );

        AttributeModifier speedMod = new AttributeModifier(
                new NamespacedKey(plugin, "excalibur_speed"),
                atkSpeed - 4.0, // player base attack speed is 4.0
                AttributeModifier.Operation.ADD_NUMBER,
                EquipmentSlotGroup.MAINHAND
        );

        meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, dmgMod);
        meta.addAttributeModifier(Attribute.ATTACK_SPEED, speedMod);

        // Heaviness: each soul makes the blade heavier -> slower movement while held (clamped).
        double movePenalty = Math.min(souls * movementPenaltyPerSoul(), PLAYER_BASE_MOVE_SPEED - MIN_MOVEMENT_SPEED);
        if (movePenalty > 0) {
            AttributeModifier weightMod = new AttributeModifier(
                    new NamespacedKey(plugin, "excalibur_weight"),
                    -movePenalty,
                    AttributeModifier.Operation.ADD_NUMBER,
                    EquipmentSlotGroup.MAINHAND
            );
            meta.addAttributeModifier(Attribute.MOVEMENT_SPEED, weightMod);
        }

        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
        meta.setUnbreakable(true);

        ItemModels.apply(meta, "saber_dexcalibur");
        it.setItemMeta(meta);
    }

    private boolean isSaberDark(Player p) {
        // If you use scoreboard tags:
        return p.getScoreboardTags().contains("DarkSaber");

        // If you use PDC/class manager instead, later can replace here.
    }

    private double calcAttackSpeed(int souls) {
        double v = baseAttackSpeed() - (souls * attackSpeedPerSoul());
        return Math.max(MIN_ATTACK_SPEED, v);
    }

    /** Movement-speed penalty as a percent of base speed, for lore display. */
    private int weightPercent(int souls) {
        double pen = Math.min(souls * movementPenaltyPerSoul(), PLAYER_BASE_MOVE_SPEED - MIN_MOVEMENT_SPEED);
        return (int) Math.round(pen / PLAYER_BASE_MOVE_SPEED * 100.0);
    }

    /* =========================
       Sync / cleanup events
       ========================= */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        hideGuardBar(p);

        if (!isSaberDark(p)) return; // only SaberD uses this UI/system state by default

        blockedDamage.putIfAbsent(p.getUniqueId(), 0.0);
        ensureGuardRegen(p);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        cancelGuardRegen(id);
        blockedDamage.remove(id);
        shieldStunUntil.remove(id);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onHeld(PlayerItemHeldEvent e) {
        // Optional: if player switches to Excalibur, refresh UI
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = e.getPlayer();
            ItemStack main = p.getInventory().getItemInMainHand();
            if (isExcalibur(main)) showGuardBar(p);
            else hideGuardBar(p);
        });
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = e.getPlayer();
            ItemStack main = p.getInventory().getItemInMainHand();
            if (isExcalibur(main)) showGuardBar(p);
            else hideGuardBar(p);
        });
    }

    /* =========================
       Helpers
       ========================= */

    private boolean isFrontBlock(Player defender, LivingEntity attacker) {
        Location dLoc = defender.getLocation();
        Location aLoc = attacker.getLocation();

        // Vector from defender -> attacker
        org.bukkit.util.Vector toAttacker = aLoc.toVector().subtract(dLoc.toVector()).setY(0).normalize();
        org.bukkit.util.Vector look = dLoc.getDirection().setY(0).normalize();

        // If attacker is in front, dot should be positive enough
        double dot = look.dot(toAttacker);
        return dot > FRONT_BLOCK_DOT;
    }

    private boolean isTeammate(Player a, Player b) {
        Team ta = teamOf(a, a.getScoreboard());
        if (ta == null) ta = teamOf(a, Bukkit.getScoreboardManager() != null ? Bukkit.getScoreboardManager().getMainScoreboard() : null);

        Team tb = teamOf(b, b.getScoreboard());
        if (tb == null) tb = teamOf(b, Bukkit.getScoreboardManager() != null ? Bukkit.getScoreboardManager().getMainScoreboard() : null);

        return ta != null && ta.equals(tb);
    }

    private Team teamOf(Player p, Scoreboard sb) {
        if (sb == null) return null;
        return sb.getEntryTeam(p.getName());
    }

    private static String format1(double v) {
        return String.format(Locale.US, "%.1f", v);
    }
}