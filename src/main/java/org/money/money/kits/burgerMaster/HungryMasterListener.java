package org.money.money.kits.burgerMaster;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.TimeUnit;

/** BurgerMaster ability: red dye “Hungry master”. */
public final class HungryMasterListener implements Listener {

    // тайминги
    private static final long EAT_WINDOW_TICKS   = 20L * 10;   // 10 секунд окно на бургеры
    private static final long HUNGER_TICKS       = 20L * 5;    // 5 сек сильный голод
    private static final long BEAST_TICKS        = 20L * 60;   // 60 сек зверь
    private static final long RETURN_AFTER_BEAST = 60_000L;    // +1 мин к возврату
    private static final long RETURN_AFTER_FAIL  = 60_000L;    // если провал, вернём через ~минуту
    private static final long TICK_MS            = 50L;

    // антидубликация событий потребления (антидребезг)
    private static final long CONSUME_DEBOUNCE_MS = 250L;

    private final Plugin plugin;

    // ключи
    private final NamespacedKey KEY_ITEM;         // сам предмет абилки
    private final NamespacedKey KEY_BURGER_KIND;  // маркер бургеров (тот же, что в GrillManager)

    // окно «съешь 3 бургера»: uuid -> сколько съел
    private final Map<UUID, Integer> eatCounter = new HashMap<>();
    // чтобы не запускать второе окно
    private final Set<UUID> activeWindow = new HashSet<>();
    // когда вернуть предмет (real time)
    private final Map<UUID, Long> returnAtMs = new HashMap<>();
    // антидребезг: последний засчитанный момент по игроку
    private final Map<UUID, Long> lastEatMs = new HashMap<>();

    public HungryMasterListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_ITEM        = new NamespacedKey(plugin, "hungry_master_item");
        this.KEY_BURGER_KIND = new NamespacedKey(plugin, "burger_kind"); // совпадает с GrillManager
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /* ========== Публичный фабричный метод предмета ========== */

    public ItemStack makeHungryDye() {
        ItemStack it = new ItemStack(Material.RED_DYE);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Hungry master", NamedTextColor.RED));
        im.getPersistentDataContainer().set(KEY_ITEM, PersistentDataType.BYTE, (byte)1);
        it.setItemMeta(im);
        return it;
    }

    private boolean isHungryItem(ItemStack it) {
        if (it == null || it.getType() != Material.RED_DYE || !it.hasItemMeta()) return false;
        var im = it.getItemMeta();
        return im.getPersistentDataContainer().has(KEY_ITEM, PersistentDataType.BYTE)
                || Component.text("Hungry master", NamedTextColor.RED).equals(im.displayName());
    }

    /* ========== Активация ПКМ ========== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        var a = e.getAction();
        if (a != org.bukkit.event.block.Action.RIGHT_CLICK_AIR && a != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!isHungryItem(hand)) return;

        e.setCancelled(true);

        UUID id = p.getUniqueId();
        if (activeWindow.contains(id)) {
            p.sendMessage(Component.text("Уже активно: съешь 3 бургера!", NamedTextColor.YELLOW));
            return;
        }

        // съедаем предмет
        int amt = hand.getAmount();
        if (amt <= 1) p.getInventory().setItemInMainHand(null);
        else hand.setAmount(amt - 1);

        // мощный голод + гарантированно обнулить еду
        p.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, (int)HUNGER_TICKS, 9, false, true, true));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try { p.setFoodLevel(0); p.setSaturation(0f); } catch (Throwable ignored) {}
        }, 2L);

        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_BURP, 0.7f, 0.5f);
        p.sendMessage(Component.text("Съешь 3 бургера за 10 секунд!", NamedTextColor.GOLD));

        // открыть окно на 10 секунд
        activeWindow.add(id);
        eatCounter.put(id, 0);
        lastEatMs.remove(id); // сброс антидребезга на старте окна

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // окно закончилось?
            if (!activeWindow.remove(id)) return; // уже успел войти в режим зверя
            eatCounter.remove(id);
            lastEatMs.remove(id);
            p.sendMessage(Component.text("Не успел накормить зверя.", NamedTextColor.GRAY));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);

            // план возврата предмета через ~минуту после фейла
            long back = System.currentTimeMillis() + RETURN_AFTER_FAIL;
            returnAtMs.put(id, back);
            Bukkit.getAsyncScheduler().runDelayed(plugin,
                    t -> Bukkit.getScheduler().runTask(plugin, () -> giveBackIfMissing(id)),
                    RETURN_AFTER_FAIL, TimeUnit.MILLISECONDS);
        }, EAT_WINDOW_TICKS);
    }

    /* ========== Подсчёт бургеров (с антидребезгом) ========== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onEat(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (!activeWindow.contains(id)) return;

        ItemStack it = e.getItem();
        if (it == null || it.getType() != Material.PUMPKIN_PIE || !it.hasItemMeta()) return;
        if (!it.getItemMeta().getPersistentDataContainer().has(KEY_BURGER_KIND, PersistentDataType.STRING)) return;

        // антидребезг: не засчитываем второй раз в пределах 250мс
        long now = System.currentTimeMillis();
        Long last = lastEatMs.get(id);
        if (last != null && (now - last) < CONSUME_DEBOUNCE_MS) {
            return; // игнор дубля
        }
        lastEatMs.put(id, now);

        int nowCount = eatCounter.getOrDefault(id, 0) + 1;
        eatCounter.put(id, nowCount);
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.4f);

        if (nowCount >= 3) {
            // успех — запустить режим зверя
            activeWindow.remove(id);
            eatCounter.remove(id);
            lastEatMs.remove(id);
            enterBeastMode(p);
        }
    }

    private void enterBeastMode(Player p) {
        UUID id = p.getUniqueId();

        p.sendMessage(Component.text("БУЙСТВО! На 60 секунд.", NamedTextColor.RED));
        p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.8f, 1.0f);
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, (int)BEAST_TICKS, 9, false, true, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,             (int)BEAST_TICKS, 3, false, true, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS,          (int)BEAST_TICKS, 5, false, true, true));
        try {
            p.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, p.getLocation().add(0,1,0), 30, 0.6, 0.8, 0.6, 0.02);
        } catch (Throwable ignored) {}

        // по окончании 60с — просто эффекты спадут, а предмет вернём ещё через минуту
        long back = System.currentTimeMillis() + (BEAST_TICKS*TICK_MS) + RETURN_AFTER_BEAST; // 60с + 60с
        returnAtMs.put(id, back);

        Bukkit.getScheduler().runTaskLater(plugin, () ->
                        p.sendMessage(Component.text("Зверь успокоился.", NamedTextColor.GRAY)),
                BEAST_TICKS);

        Bukkit.getAsyncScheduler().runDelayed(plugin,
                t -> Bukkit.getScheduler().runTask(plugin, () -> giveBackIfMissing(id)),
                (BEAST_TICKS*TICK_MS) + RETURN_AFTER_BEAST,
                TimeUnit.MILLISECONDS);
    }

    /* ========== Возврат предмета и очистка состояний ========== */

    private void giveBackIfMissing(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p == null || !p.isOnline()) return;

        // уже есть?
        for (ItemStack it : p.getInventory().getContents()) {
            if (isHungryItem(it)) return;
        }
        ItemStack dye = makeHungryDye();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) p.getInventory().setItemInMainHand(dye);
        else {
            Map<Integer, ItemStack> left = p.getInventory().addItem(dye);
            if (!left.isEmpty()) p.getWorld().dropItemNaturally(p.getLocation(), left.values().iterator().next());
        }
        p.playSound(p.getLocation(), Sound.UI_TOAST_IN, 0.8f, 1.2f);
        p.sendMessage(Component.text("Hungry master восстановлен.", NamedTextColor.GREEN));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        UUID id = e.getEntity().getUniqueId();
        activeWindow.remove(id);
        eatCounter.remove(id);
        lastEatMs.remove(id);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        activeWindow.remove(id);
        eatCounter.remove(id);
        lastEatMs.remove(id);
    }
}
