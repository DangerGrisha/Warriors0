package org.money.money.kits.uraraka;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Uraraka "gravity":
 *  - ПКМ по FEATHER "gravity": 3s Slowness (разгон) + звук; сразу выдаётся "CancelGravity".
 *  - Через 3s включается 10s окно (armed):
 *      * владелец бьёт ЛЮБОЙ атакой → цель получает Levitation 8s (можно много целей за окно);
 *      * если владельца ударили → подлетает ТОЛЬКО владелец (атакер не подлетает);
 *    Удары НЕ завершают способность — она заканчивается только по таймауту окна или по Cancel.
 *  - ПКМ по "CancelGravity": немедленная отмена, снятие левитаций, запуск 2-мин кд на возврат.
 *  - Никаких проверок "в кд": предмет всегда возвращается через 2 минуты со звуком и сообщением.
 */
public final class UrarakaGravityListener implements Listener {

    private static final long WINDUP_TICKS       = 20L * 3;   // 3 сек разгон
    private static final long ARMED_WINDOW_TICKS = 20L * 10;  // 10 сек окно
    private static final long SOUND_PERIOD       = 10L;       // раз в 0.5с во время разгона
    private static final int  SLOW_LEVEL         = 2;         // Slowness III (0=I)
    private static final int  LEVITATION_TICKS   = 20 * 8;    // 8 сек
    private static final int  LEVITATION_LVL     = 0;         // Levitation I
    private static final long COOLDOWN_MS        = 120_000L;  // 2 минуты

    private final Plugin plugin;

    // PDC-ключи предметов
    private final NamespacedKey KEY_GRAVITY;
    private final NamespacedKey KEY_CANCEL;

    // состояние
    private final Set<UUID> windingUp = new HashSet<>();
    private final Set<UUID> armed     = new HashSet<>();
    private final Map<UUID, BukkitTask> windupTask      = new HashMap<>();
    private final Map<UUID, BukkitTask> soundTask       = new HashMap<>();
    private final Map<UUID, BukkitTask> armedWindowTask = new HashMap<>();

    // кого этот владелец уже поднял (для снятия при Cancel/финише)
    private final Map<UUID, Set<UUID>> liftedByOwner = new HashMap<>();

    // таймеры возврата предмета по кд
    private final Map<UUID, Long> cooldownUntilMs = new HashMap<>();

    public UrarakaGravityListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_GRAVITY = new NamespacedKey(plugin, "uraraka_gravity");
        this.KEY_CANCEL  = new NamespacedKey(plugin, "uraraka_gravity_cancel");
    }

    /* -------------------- Items -------------------- */

    public ItemStack makeGravityDye() {
        ItemStack it = new ItemStack(Material.FEATHER);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("gravity"));
        im.getPersistentDataContainer().set(KEY_GRAVITY, PersistentDataType.BYTE, (byte)1);
        it.setItemMeta(im);
        return it;
    }

    private ItemStack makeCancelDye() {
        ItemStack it = new ItemStack(Material.FEATHER);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("CancelGravity"));
        im.getPersistentDataContainer().set(KEY_CANCEL, PersistentDataType.BYTE, (byte)1);
        it.setItemMeta(im);
        return it;
    }

    private boolean isGravity(ItemStack it) {
        if (it == null || it.getType() != Material.FEATHER || !it.hasItemMeta()) return false;
        ItemMeta im = it.getItemMeta();
        if (im.getPersistentDataContainer().has(KEY_GRAVITY, PersistentDataType.BYTE)) return true;
        return Component.text("gravity").equals(im.displayName());
    }

    private boolean isCancel(ItemStack it) {
        if (it == null || it.getType() != Material.FEATHER || !it.hasItemMeta()) return false;
        ItemMeta im = it.getItemMeta();
        if (im.getPersistentDataContainer().has(KEY_CANCEL, PersistentDataType.BYTE)) return true;
        return Component.text("CancelGravity").equals(im.displayName());
    }

    /* -------------------- Use / Cancel -------------------- */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();

        if (isGravity(hand)) {
            // если уже идёт разгон/окно — игнор
            if (windingUp.contains(p.getUniqueId()) || armed.contains(p.getUniqueId())) return;

            // убрать один "gravity" из инвентаря
            consumeOne(p, KEY_GRAVITY, "gravity");

            // выдать Cancel сразу (если нет)
            if (!playerHas(p, KEY_CANCEL)) giveToHandOrInv(p, makeCancelDye());

            // Slowness на 3 сек и тревожный звук
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int)WINDUP_TICKS, SLOW_LEVEL, false, false, false));
            startWindupSound(p);

            // разгон => окно 10с
            UUID id = p.getUniqueId();
            windingUp.add(id);
            BukkitTask t = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                windingUp.remove(id);
                stopSound(id);
                armed.add(id);
                p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.7f, 0.8f);

                // авто-таймаут: финиш и запуск кд
                BukkitTask wnd = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (armed.contains(id)) {
                        finishAndCooldown(id, FinishReason.TIMEOUT);
                    }
                }, ARMED_WINDOW_TICKS);
                armedWindowTask.put(id, wnd);

            }, WINDUP_TICKS);
            windupTask.put(id, t);
            return;
        }

        if (isCancel(hand)) {
            // отмена: немедленно финиш и кд
            finishAndCooldown(p.getUniqueId(), FinishReason.CANCELLED);
        }
    }

    /* -------------------- Damage hooks -------------------- */

    // ловим даже при отменённом уроне другими плагинами
    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onHit(EntityDamageByEntityEvent e) {
        // владелец бьёт кого-то (ЛЮБОЙ атакой) во время окна → цель подлетает; способность НЕ завершается
        if (e.getDamager() instanceof Player attacker && armed.contains(attacker.getUniqueId())) {
            if (e.getEntity() instanceof LivingEntity victim) {
                applyLevitation(attacker.getUniqueId(), victim);
            }
            return;
        }

        // ударили владельца во время окна → подлетает только владелец; способность НЕ завершается
        if (e.getEntity() instanceof Player owner && armed.contains(owner.getUniqueId())) {
            applyLevitation(owner.getUniqueId(), owner);
        }
    }

    /* -------------------- Core helpers -------------------- */

    private enum FinishReason { CANCELLED, TIMEOUT , USED_ON_TARGET, HIT_OWNER}

    private void applyLevitation(UUID ownerId, LivingEntity target) {
        liftedByOwner.computeIfAbsent(ownerId, k -> new HashSet<>()).add(target.getUniqueId());
        target.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, LEVITATION_TICKS, LEVITATION_LVL, false, true, true));
        try {
            var l = target.getLocation().add(0, 0.6, 0);
            target.getWorld().spawnParticle(Particle.END_ROD, l, 10, 0.3, 0.4, 0.3, 0.01);
            target.getWorld().playSound(l, Sound.ITEM_TRIDENT_RIPTIDE_1, 0.6f, 1.6f);
        } catch (Throwable ignored) {}
    }

    private void startWindupSound(Player p) {
        UUID id = p.getUniqueId();
        stopSound(id);
        BukkitTask s = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline()) { stopSound(id); return; }
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 0.6f, 0.7f);
        }, 0L, SOUND_PERIOD);
        soundTask.put(id, s);
    }

    private void stopSound(UUID id) {
        BukkitTask s = soundTask.remove(id);
        if (s != null) s.cancel();
    }

    /** Полное завершение режима и запуск КД (2 мин) с последующим возвратом предмета. */
    /** Полное завершение режима и запуск КД (2 мин). */
    private void finishAndCooldown(UUID ownerId, FinishReason why) {
        // остановить таймеры
        BukkitTask t = windupTask.remove(ownerId); if (t != null) t.cancel();
        stopSound(ownerId);
        BukkitTask w = armedWindowTask.remove(ownerId); if (w != null) w.cancel();

        // выключить флаги
        windingUp.remove(ownerId);
        armed.remove(ownerId);

        // --- РАНЬШЕ: всегда снимали левитацию
        // Теперь: снимаем, ТОЛЬКО если это не TIMEOUT.
        Set<UUID> set = liftedByOwner.remove(ownerId); // забываем список в любом случае
        if (set != null && !set.isEmpty() && why != FinishReason.TIMEOUT) {
            for (UUID uid : set) {
                Entity e = Bukkit.getEntity(uid);
                if (e instanceof LivingEntity le) le.removePotionEffect(PotionEffectType.LEVITATION);
            }
        }

        // убрать Cancel и запустить КД...
        Player p = Bukkit.getPlayer(ownerId);
        if (p != null && p.isOnline()) {
            removeOne(p, KEY_CANCEL, "CancelGravity");
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.0f);
            switch (why) {
                case USED_ON_TARGET -> p.sendMessage(Component.text("Gravity: применена.", NamedTextColor.GREEN));
                case HIT_OWNER      -> p.sendMessage(Component.text("Gravity: сработала при получении урона.", NamedTextColor.GREEN));
                case CANCELLED      -> p.sendMessage(Component.text("Gravity: отменена.", NamedTextColor.YELLOW));
                case TIMEOUT        -> p.sendMessage(Component.text("Gravity: время вышло.", NamedTextColor.YELLOW));
            }
        }

        long backAt = System.currentTimeMillis() + COOLDOWN_MS;
        cooldownUntilMs.put(ownerId, backAt);

        Bukkit.getAsyncScheduler().runDelayed(plugin, task ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            Player pl = Bukkit.getPlayer(ownerId);
                            if (pl != null && pl.isOnline() && !playerHas(pl, KEY_GRAVITY)) {
                                giveToHandOrInv(pl, makeGravityDye());
                                pl.playSound(pl.getLocation(), Sound.UI_TOAST_IN, 0.7f, 1.2f);
                                pl.sendMessage(Component.text("Gravity восстановлена.", NamedTextColor.GREEN));
                            }
                        })
                , COOLDOWN_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }


    /* -------------------- Inventory utils -------------------- */

    private boolean playerHas(Player p, NamespacedKey key) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (hasKey(it, key)) return true;
        }
        return false;
    }

    private void giveToHandOrInv(Player p, ItemStack it) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            p.getInventory().setItemInMainHand(it);
        } else {
            HashMap<Integer, ItemStack> left = p.getInventory().addItem(it);
            if (!left.isEmpty()) p.getWorld().dropItemNaturally(p.getLocation(), left.values().iterator().next());
        }
    }

    private boolean hasKey(ItemStack it, NamespacedKey key) {
        if (it == null || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    private void consumeOne(Player p, NamespacedKey key, String fallbackName) {
        // в приоритете — main hand
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hasKey(hand, key) || isNamed(hand, fallbackName)) {
            decOrRemoveHand(p);
            return;
        }
        // поиск в инвентаре
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (hasKey(it, key) || isNamed(it, fallbackName)) {
                decOrRemoveSlot(p, i);
                return;
            }
        }
    }

    private void removeOne(Player p, NamespacedKey key, String fallbackName) {
        // попытаться main hand
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hasKey(hand, key) || isNamed(hand, fallbackName)) {
            decOrRemoveHand(p);
            return;
        }
        // поиск в инвентаре
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (hasKey(it, key) || isNamed(it, fallbackName)) {
                decOrRemoveSlot(p, i);
                return;
            }
        }
    }

    private boolean isNamed(ItemStack it, String exact) {
        if (it == null || !it.hasItemMeta()) return false;
        return Component.text(exact).equals(it.getItemMeta().displayName());
    }

    private void decOrRemoveHand(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        int amt = hand.getAmount();
        if (amt <= 1) p.getInventory().setItemInMainHand(null);
        else hand.setAmount(amt - 1);
    }

    private void decOrRemoveSlot(Player p, int slot) {
        ItemStack it = p.getInventory().getItem(slot);
        if (it == null) return;
        if (it.getAmount() <= 1) p.getInventory().setItem(slot, null);
        else it.setAmount(it.getAmount() - 1);
    }

    /* -------------------- Cleanup / join -------------------- */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        // при выходе — завершаем как CANCELLED (чтобы гарантированно запустился возврат)
        finishAndCooldown(e.getPlayer().getUniqueId(), FinishReason.CANCELLED);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        // смерть владельца — тоже завершаем и запускаем возврат
        finishAndCooldown(e.getEntity().getUniqueId(), FinishReason.CANCELLED);
    }

}
