package org.money.money.kits.dio;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.TimeUnit;

public final class TimeStopListener implements Listener {

    // тайминги
    private static final long WINDUP_TICKS = Math.round(20 * 3.9); // 3.9 c до стопа
    private static final long FREEZE_TICKS = 20L * 5;              // 5 c стопа

    // визуал разгона
    private static final long FLICKER_PERIOD   = 6L;  // каждые 0.3 c
    private static final int  FLICKER_ON_TICKS = 5;   // импульс Blindness ~0.25 c
    private static final long DARKNESS_AT      = 20L * 3 + 10L; // ~3.5 c

    // кулдаун возврата предмета
    private static final long RETURN_AFTER_MS  = 120_000L; // 2 минуты

    // пробуем настоящий /tick freeze поверх нашей селективной заморозки
    private static final boolean USE_VANILLA_TICK_FREEZE = true;

    private final Plugin plugin;

    // ключ для пометки предмета
    private final NamespacedKey KEY_TIME_STOP;

    // антиспам каста (фаза «заводки»)
    private final Set<UUID> windingUp = new HashSet<>();

    // счётчик заморозки (селективный режим)
    private final Map<UUID, Integer> frozenCount = new HashMap<>();

    // визуал разгона
    private final Map<UUID, BukkitTask> windupFx = new HashMap<>();
    private final Map<UUID, BukkitTask> darknessApply = new HashMap<>();

    // кулдаун по real-time
    private final Map<UUID, Long> cooldownUntilMs = new HashMap<>();

    public TimeStopListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_TIME_STOP = new NamespacedKey(plugin, "dio_time_stop");
    }

    /* ================== предмет ================== */

    /** Создать предмет TIME_STOP. */
    public ItemStack makeTimeStopDye() {
        ItemStack it = new ItemStack(Material.RED_DYE);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("TIME_STOP"));
        im.getPersistentDataContainer().set(KEY_TIME_STOP, PersistentDataType.BYTE, (byte)1);
        it.setItemMeta(im);
        return it;
    }

    private boolean isTimeStopItem(ItemStack it) {
        if (it == null || it.getType() != Material.RED_DYE || !it.hasItemMeta()) return false;
        var im = it.getItemMeta();
        if (im.getPersistentDataContainer().has(KEY_TIME_STOP, PersistentDataType.BYTE)) return true;
        return Component.text("TIME_STOP").equals(im.displayName());
    }

    private boolean playerHasTimeStopItem(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (isTimeStopItem(it)) return true;
        }
        return false;
    }

    /** Съесть ОДИН предмет TIME_STOP (из main-hand или из инвентаря). */
    private boolean consumeOneTimeStop(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (isTimeStopItem(hand)) {
            int amt = hand.getAmount();
            if (amt <= 1) p.getInventory().setItemInMainHand(null);
            else hand.setAmount(amt - 1);
            return true;
        }
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (isTimeStopItem(it)) {
                if (it.getAmount() <= 1) p.getInventory().setItem(i, null);
                else it.setAmount(it.getAmount() - 1);
                return true;
            }
        }
        return false;
    }

    /** Вернуть предмет игроку (в руку/инвентарь/дроп). */
    private void giveBackTimeStop(Player p) {
        if (playerHasTimeStopItem(p)) return;
        ItemStack dye = makeTimeStopDye();
        ItemStack mh = p.getInventory().getItemInMainHand();
        if (mh == null || mh.getType() == Material.AIR) {
            p.getInventory().setItemInMainHand(dye);
        } else {
            HashMap<Integer, ItemStack> left = p.getInventory().addItem(dye);
            if (!left.isEmpty()) p.getWorld().dropItemNaturally(p.getLocation(), left.values().iterator().next());
        }
        p.playSound(p.getLocation(), Sound.UI_TOAST_IN, 0.6f, 1.2f);
        p.sendMessage(Component.text("Способность восстановлена.", NamedTextColor.GREEN));
    }

    /* ================== запуск способности ================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        if (!isTimeStopItem(p.getInventory().getItemInMainHand())) return;

        long now = System.currentTimeMillis();
        long until = cooldownUntilMs.getOrDefault(p.getUniqueId(), 0L);
        if (now < until) {
            long sec = Math.max(0, (until - now + 999) / 1000);
            p.sendMessage(Component.text("Перезарядка: " + sec + "с", NamedTextColor.RED));
            return;
        }
        if (windingUp.contains(p.getUniqueId())) return;

        // потребляем предмет и ставим кулдаун/возврат
        if (!consumeOneTimeStop(p)) return;
        long backAt = now + RETURN_AFTER_MS;
        cooldownUntilMs.put(p.getUniqueId(), backAt);
        Bukkit.getAsyncScheduler().runDelayed(plugin, task ->
                        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                            Player online = Bukkit.getPlayer(p.getUniqueId());
                            if (online != null && online.isOnline()) giveBackTimeStop(online);
                        }),
                RETURN_AFTER_MS, TimeUnit.MILLISECONDS);

        // звук пролога
        try {
            p.getWorld().playSound(p.getLocation(), "minecraft:my_sounds.dio1", SoundCategory.PLAYERS, 1.0f, 1.0f);
        } catch (Throwable ignored) {}

        UUID id = p.getUniqueId();
        windingUp.add(id);

        // визуалы разгона
        startWindupVisuals(p);

        // запуск стопа через 3.9с
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            windingUp.remove(id);
            stopWindupVisuals(id);
            startFreezeFor(p, FREEZE_TICKS);
        }, WINDUP_TICKS);
    }

    /* ================== визуалы разгона ================== */

    private Collection<Player> targetsExceptOwner(Player owner) {
        List<Player> list = new ArrayList<>();
        for (Player pl : Bukkit.getOnlinePlayers())
            if (!pl.getUniqueId().equals(owner.getUniqueId())) list.add(pl);
        return list;
    }

    private void startWindupVisuals(Player owner) {
        UUID id = owner.getUniqueId();
        stopWindupVisuals(id);

        // мигающий Blindness (0.25с «вкл» каждые 0.3с)
        final boolean[] on = {false};
        BukkitTask flicker = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!owner.isOnline()) { stopWindupVisuals(id); return; }
            on[0] = !on[0];
            if (on[0]) {
                PotionEffect eff = new PotionEffect(PotionEffectType.BLINDNESS, FLICKER_ON_TICKS, 0, false, false, false);
                for (Player t : targetsExceptOwner(owner)) t.addPotionEffect(eff);
            } else {
                for (Player t : targetsExceptOwner(owner)) t.removePotionEffect(PotionEffectType.BLINDNESS);
            }
        }, 0L, FLICKER_PERIOD);
        windupFx.put(id, flicker);

        // плотная тьма за ~0.4с до стопа и до конца фриза
        int darknessDur = (int)((WINDUP_TICKS - DARKNESS_AT) + FREEZE_TICKS + 2L);
        BukkitTask dark = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!owner.isOnline()) { stopWindupVisuals(id); return; }
            stopFlickerOnly(id);
            PotionEffect eff = new PotionEffect(PotionEffectType.DARKNESS, darknessDur, 0, false, false, false);
            for (Player t : targetsExceptOwner(owner)) t.addPotionEffect(eff);
        }, DARKNESS_AT);
        darknessApply.put(id, dark);
    }

    private void stopFlickerOnly(UUID id) {
        BukkitTask f = windupFx.remove(id);
        if (f != null) f.cancel();
    }
    private void stopWindupVisuals(UUID id) {
        stopFlickerOnly(id);
        BukkitTask d = darknessApply.remove(id);
        if (d != null) d.cancel();
    }

    /* ================== tick freeze / сообщения ================== */

    private void broadcastTickMsg(boolean freeze) {
        var msg = Component.text("/tick ", NamedTextColor.GRAY)
                .append(Component.text(freeze ? "freeze" : "unfreeze", NamedTextColor.GRAY))
                .decorate(TextDecoration.ITALIC);
        for (Player pl : Bukkit.getOnlinePlayers()) pl.sendMessage(msg);
        plugin.getLogger().info("[TimeStop] " + (freeze ? "tick freeze" : "tick unfreeze"));
    }

    /* ================== сама «остановка времени» ================== */

    private void startFreezeFor(Player owner, long ticks) {
        // цели: все кроме владельца
        Set<Player> targets = new HashSet<>();
        for (Player pl : Bukkit.getOnlinePlayers())
            if (!pl.getUniqueId().equals(owner.getUniqueId())) targets.add(pl);
        if (targets.isEmpty()) return;

        // (A) немедленно включаем селективный «фриз» (движение/инвентарь/атаки/команды)
        for (Player t : targets) {
            incrementFrozen(t);
            t.closeInventory();
        }

        CommandSender console = Bukkit.getConsoleSender();
        final boolean vanilla =
                USE_VANILLA_TICK_FREEZE &&
                        (Bukkit.dispatchCommand(console, "minecraft:tick freeze")
                                || Bukkit.dispatchCommand(console, "tick freeze"));

        if (!vanilla) {
            plugin.getLogger().warning("[TimeStop] '/tick freeze' недоступна — селективный режим остаётся.");
        }
        broadcastTickMsg(true);

        final long ms = ticks * 50L;
        Bukkit.getAsyncScheduler().runDelayed(plugin, task ->
                        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                            if (vanilla) {
                                Bukkit.dispatchCommand(console, "tick unfreeze");
                            }
                            broadcastTickMsg(false);

                            // снятие селективного фриза и эффектов
                            for (Player t : targets) {
                                decrementFrozen(t);
                                if (!isFrozen(t)) {
                                    t.removePotionEffect(PotionEffectType.DARKNESS);
                                    t.removePotionEffect(PotionEffectType.BLINDNESS);
                                }
                            }
                        })
                , ms, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void incrementFrozen(Player p) { frozenCount.merge(p.getUniqueId(), 1, Integer::sum); }
    private void decrementFrozen(Player p) { frozenCount.computeIfPresent(p.getUniqueId(), (id, c) -> (c <= 1 ? null : c - 1)); }
    private boolean isFrozen(Player p) { return frozenCount.getOrDefault(p.getUniqueId(), 0) > 0; }

    /* ================== запреты во время фриза (селективно) ================== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent e) { if (isFrozen(e.getPlayer())) e.setCancelled(true); }

    /*@EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p && isFrozen(p)) {
            e.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, p::closeInventory);
        }
    }*/
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p && isFrozen(p)) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) { if (e.getWhoClicked() instanceof Player p && isFrozen(p)) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent e) { if (isFrozen(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onSwap(PlayerSwapHandItemsEvent e) { if (isFrozen(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onHeld(PlayerItemHeldEvent e) { if (isFrozen(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) { if (isFrozen(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onCmd(PlayerCommandPreprocessEvent e) { if (isFrozen(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && isFrozen(p)) e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onShoot(org.bukkit.event.entity.ProjectileLaunchEvent e) {
        ProjectileSource src = e.getEntity().getShooter();
        if (src instanceof Player p && isFrozen(p)) e.setCancelled(true);
    }

    /* ================== подчистка/восстановление ================== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        windingUp.remove(id);
        stopWindupVisuals(id);
        frozenCount.remove(id);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        UUID id = e.getEntity().getUniqueId();
        windingUp.remove(id);
        stopWindupVisuals(id);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        long until = cooldownUntilMs.getOrDefault(p.getUniqueId(), 0L);
        if (System.currentTimeMillis() >= until && !playerHasTimeStopItem(p)) {
            Bukkit.getScheduler().runTask(plugin, () -> giveBackTimeStop(p));
        }
    }
}
