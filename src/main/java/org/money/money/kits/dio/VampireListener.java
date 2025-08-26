package org.money.money.kits.dio;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.TimeUnit;

public final class VampireListener implements Listener {

    // тайминги
    private static final long WINDUP_TICKS     = 20L * 3;   // 3s замедление/звуки
    private static final long FORM_TICKS       = 20L * 40;  // 40s форма
    private static final long COOLDOWN_MS      = 180_000L;  // 3 min (real-time)

    // lifesteal
    private static final double LIFESTEAL_PCT  = 0.36;      // 36%
    private static final int    HEAL_ON_START  = 10;        // 5 сердечек = 10 HP
    private static final double EXTRA_MAX_HP   = 20.0;      // +10 сердечек

    // звуки
    private static final String SND_START_ONCE = "minecraft:entity.wither.spawn";
    private static final String SND_PULSE      = "minecraft:entity.warden.heartbeat";

    private final Plugin plugin;

    // PDC ключи
    private final NamespacedKey KEY_ITEM;
    private final NamespacedKey KEY_MASK;

    // состояния
    private final Set<UUID> winding = new HashSet<>();           // 3-сек «завод»
    private final Set<UUID> active  = new HashSet<>();           // 40-сек форма
    private final Map<UUID, Long> cooldownUntil = new HashMap<>(); // real-time кд

    // вернуть шлем и макс. хп
    private final Map<UUID, ItemStack> prevHelm = new HashMap<>();
    private final Map<UUID, Double>    prevMax  = new HashMap<>();

    // тикеры звука во время windup
    private final Map<UUID, BukkitTask> heartbeat = new HashMap<>();

    public VampireListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_ITEM = new NamespacedKey(plugin, "vampire_item");
        this.KEY_MASK = new NamespacedKey(plugin, "vampire_mask");
    }

    /* =============== предметы =============== */

    public ItemStack makeVampireDye() {
        ItemStack it = new ItemStack(Material.RED_DYE);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Vampire"));
        im.getPersistentDataContainer().set(KEY_ITEM, PersistentDataType.BYTE, (byte)1);
        it.setItemMeta(im);
        return it;
    }

    private ItemStack makeMask() {
        ItemStack it = new ItemStack(Material.DIAMOND_HELMET);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Vampire"));
        im.addEnchant(Enchantment.BINDING_CURSE, 1, true); // проклятая несьемность
        im.setUnbreakable(true);
        im.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS,
                org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES,
                org.bukkit.inventory.ItemFlag.HIDE_UNBREAKABLE);
        im.getPersistentDataContainer().set(KEY_MASK, PersistentDataType.BYTE, (byte)1);
        it.setItemMeta(im);
        return it;
    }

    private boolean isVampireDye(ItemStack it) {
        if (it == null || it.getType() != Material.RED_DYE || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(KEY_ITEM, PersistentDataType.BYTE);
    }
    private boolean isOurMask(ItemStack it) {
        if (it == null || it.getType() != Material.DIAMOND_HELMET || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(KEY_MASK, PersistentDataType.BYTE);
    }

    private boolean consumeOne(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (isVampireDye(hand)) {
            int a = hand.getAmount();
            if (a <= 1) p.getInventory().setItemInMainHand(null);
            else hand.setAmount(a - 1);
            return true;
        }
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (isVampireDye(it)) {
                if (it.getAmount() <= 1) p.getInventory().setItem(i, null);
                else it.setAmount(it.getAmount() - 1);
                return true;
            }
        }
        return false;
    }

    private void giveBackDye(Player p) {
        // не дублируем
        for (ItemStack it : p.getInventory()) if (isVampireDye(it)) return;
        ItemStack dye = makeVampireDye();
        if (p.getInventory().getItemInMainHand() == null || p.getInventory().getItemInMainHand().getType() == Material.AIR) {
            p.getInventory().setItemInMainHand(dye);
        } else {
            Map<Integer, ItemStack> left = p.getInventory().addItem(dye);
            if (!left.isEmpty()) p.getWorld().dropItemNaturally(p.getLocation(), left.values().iterator().next());
        }
        p.playSound(p.getLocation(), Sound.UI_TOAST_IN, 0.6f, 1.2f);
    }

    /* =============== активация =============== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        if (!isVampireDye(p.getInventory().getItemInMainHand())) return;

        long now = System.currentTimeMillis();
        long until = cooldownUntil.getOrDefault(p.getUniqueId(), 0L);
        if (now < until) {
            long sec = Math.max(0, (until - now + 999) / 1000);
            p.sendMessage("§cПерезарядка: " + sec + "с");
            return;
        }
        if (winding.contains(p.getUniqueId()) || active.contains(p.getUniqueId())) return;

        if (!consumeOne(p)) return;

        // старт — звук и сильная медлительность на 3с
        p.getWorld().playSound(p.getLocation(), SND_START_ONCE, SoundCategory.PLAYERS, 1.0f, 1.0f);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int)WINDUP_TICKS, 4, false, false, false));

        // 3с «сердцебиение»
        BukkitTask beat = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> p.getWorld().playSound(p.getLocation(), SND_PULSE, SoundCategory.PLAYERS, 0.8f, 1.0f),
                0L, 10L);
        heartbeat.put(p.getUniqueId(), beat);

        winding.add(p.getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            winding.remove(p.getUniqueId());
            BukkitTask hb = heartbeat.remove(p.getUniqueId());
            if (hb != null) hb.cancel();
            if (!p.isOnline() || p.isDead()) return;
            enterForm(p);
        }, WINDUP_TICKS);
    }

    private void enterForm(Player p) {
        UUID id = p.getUniqueId();
        // сохранить прежний шлем и макс.хп
        prevHelm.put(id, safeClone(p.getInventory().getHelmet()));
        AttributeInstance inst = p.getAttribute(Attribute.MAX_HEALTH);
        if (inst != null) {
            prevMax.put(id, inst.getBaseValue());
            inst.setBaseValue(inst.getBaseValue() + EXTRA_MAX_HP);
        }
        // мгновенное лечение на 5 сердечек
        double max = p.getAttribute(Attribute.MAX_HEALTH).getBaseValue();
        p.setHealth(Math.min(max, p.getHealth() + HEAL_ON_START));

        // надеваем маску
        p.getInventory().setHelmet(makeMask());
        p.playSound(p.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 0.6f, 0.6f);
        active.add(id);

        // окончание формы
        Bukkit.getScheduler().runTaskLater(plugin, () -> exitForm(p, true), FORM_TICKS);
    }

    private void exitForm(Player p, boolean startCooldown) {
        UUID id = p.getUniqueId();
        if (!active.remove(id)) return;

        // вернуть макс. хп
        AttributeInstance inst = p.getAttribute(Attribute.MAX_HEALTH);
        Double prev = prevMax.remove(id);
        if (inst != null && prev != null) {
            inst.setBaseValue(prev);
            if (p.getHealth() > prev) p.setHealth(prev);
        }

        // снять нашу маску и вернуть прежний шлем
        ItemStack curr = p.getInventory().getHelmet();
        if (isOurMask(curr)) p.getInventory().setHelmet(null);
        ItemStack old = prevHelm.remove(id);
        if (old != null && (p.getInventory().getHelmet() == null || p.getInventory().getHelmet().getType() == Material.AIR)) {
            p.getInventory().setHelmet(old);
        } else if (old != null) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(old);
            if (!left.isEmpty()) p.getWorld().dropItemNaturally(p.getLocation(), left.values().iterator().next());
        }

        // кд и возврат предмета
        if (startCooldown) {
            long backAt = System.currentTimeMillis() + COOLDOWN_MS;
            cooldownUntil.put(id, backAt);
            Bukkit.getAsyncScheduler().runDelayed(plugin, task ->
                    Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                        Player online = Bukkit.getPlayer(id);
                        if (online != null && online.isOnline()) giveBackDye(online);
                    }), COOLDOWN_MS, TimeUnit.MILLISECONDS);
        }
    }

    private ItemStack safeClone(ItemStack it) { return (it == null || it.getType() == Material.AIR) ? null : it.clone(); }

    /* =============== lifesteal =============== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (!(e.getEntity() instanceof Player)) return; // воруем только с игроков
        if (!active.contains(p.getUniqueId())) return;
        double heal = Math.max(0.0, e.getFinalDamage() * LIFESTEAL_PCT);
        AttributeInstance inst = p.getAttribute(Attribute.MAX_HEALTH);
        double max = inst != null ? inst.getBaseValue() : 20.0;
        p.setHealth(Math.min(max, p.getHealth() + heal));
        p.spawnParticle(Particle.HEART, p.getLocation().add(0, 1.2, 0), 2, 0.2, 0.2, 0.2, 0.0);
    }

    /* =============== запрет снимать маску =============== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onInvClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!active.contains(p.getUniqueId())) return;

        // запретим любые операции со слотом головы, если там наша маска
        if (e.getSlotType() == InventoryType.SlotType.ARMOR || e.getSlot() == p.getInventory().getHeldItemSlot()) {
            ItemStack helm = p.getInventory().getHelmet();
            if (isOurMask(helm)) {
                // попытки снять/переместить
                if (e.getSlot() == 5 /*helmet slot index in player inv*/ || e.getSlotType() == InventoryType.SlotType.ARMOR) {
                    e.setCancelled(true);
                }
                // блокируем shift-клик/движение маски
                if (isOurMask(e.getCurrentItem()) || isOurMask(e.getCursor())) {
                    e.setCancelled(true);
                }
            }
        }
    }

    /* =============== очистка =============== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        winding.remove(id);
        BukkitTask hb = heartbeat.remove(id);
        if (hb != null) hb.cancel();
        if (active.contains(id)) exitForm(e.getPlayer(), false); // без запуска кд — кд уже тикает real-time по возврату предмета
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        UUID id = p.getUniqueId();
        winding.remove(id);
        BukkitTask hb = heartbeat.remove(id);
        if (hb != null) hb.cancel();
        if (active.contains(id)) exitForm(p, true);
    }

    // на входе — если кд прошёл, но предмета нет, вернём
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        long until = cooldownUntil.getOrDefault(p.getUniqueId(), 0L);
        if (System.currentTimeMillis() >= until) {
            Bukkit.getScheduler().runTask(plugin, () -> giveBackDye(p));
        }
    }
}
