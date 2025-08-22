package org.money.money.kits.hutao;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public final class HuTaoInvisListener implements Listener {
    private final Plugin plugin;

    // PDC keys
    private final NamespacedKey KEY_HOMA;   // <-- исправлено: везде единое имя
    private final NamespacedKey KEY_REMOVE;

    // активные состояния
    private final Set<UUID> active = new HashSet<>();
    private final Map<UUID, ItemStack> storedSword = new HashMap<>();
    private final Map<UUID, Integer> storedSlot = new HashMap<>();
    private final Map<UUID, BukkitTask> particleTask = new HashMap<>();
    private final Map<UUID, BukkitTask> timeoutTask  = new HashMap<>();

    public HuTaoInvisListener(Plugin plugin) {
        this.plugin = plugin;
        this.KEY_HOMA   = new NamespacedKey(plugin, "hutao_homa");
        this.KEY_REMOVE = new NamespacedKey(plugin, "hutao_remove_invis");
    }

    /* ===================== Public factory ===================== */

    /** Алмазный меч Staff of Homa (урон 3.5, скорость атаки 3.2). */
    public ItemStack makeHoma() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta im = sword.getItemMeta();
        im.displayName(Component.text("§6Staff of Homa"));
        im.setUnbreakable(true);
        im.getPersistentDataContainer().set(KEY_HOMA, PersistentDataType.BYTE, (byte) 1);

        applyHomaStats(im);

        // (опционально) спрятать «зелёные» строки и написать свои в lore
        // im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        sword.setItemMeta(im);
        return sword;
    }

    /** Применяем атрибуты: финальный dmg=3.5, speed=3.2 (т.е. +2.5 и -0.8 к базовым 1.0/4.0). */
    private void applyHomaStats(ItemMeta im) {
        im.setAttributeModifiers(null); // убрать ванильные

        AttributeModifier dmg = new AttributeModifier(
                new NamespacedKey(plugin, "homa_damage"),
                3.5,
                AttributeModifier.Operation.ADD_NUMBER,
                EquipmentSlotGroup.MAINHAND
        );
        AttributeModifier spd = new AttributeModifier(
                new NamespacedKey(plugin, "homa_speed"),
                0.8,
                AttributeModifier.Operation.ADD_NUMBER,
                EquipmentSlotGroup.MAINHAND
        );

        im.addAttributeModifier(Attribute.ATTACK_DAMAGE, dmg);
        im.addAttributeModifier(Attribute.ATTACK_SPEED, spd);
    }

    /* ===================== Detect jump+sprint ===================== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.NORMAL)
    public void onJump(PlayerJumpEvent e) {
        Player p = e.getPlayer();
        if (!p.isSprinting()) return;

        ItemStack hand = p.getInventory().getItemInMainHand();

        // триггерим, если держит либо Homa, либо красный краситель
        if (isHoma(hand) || isRemove(hand)) {
            boolean holdingRemove = isRemove(hand);
            startOrRefreshInvis(p, holdingRemove);
        }
    }

    /* ===================== Manual revert on "Remove Invis" ===================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onUseRemove(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!isRemove(hand)) return;

        e.setUseItemInHand(Event.Result.DENY);
        e.setCancelled(true);

        stopInvis(p, true);
    }
    private boolean replaceRemoveDyeWithSwordAnywhere(Player p, ItemStack sword) {
        var inv = p.getInventory();

        // 1) main hand
        ItemStack hand = inv.getItemInMainHand();
        if (isRemove(hand)) { inv.setItemInMainHand(sword); return true; }

        // 2) off hand
        ItemStack off = inv.getItemInOffHand();
        if (isRemove(off)) { inv.setItemInOffHand(sword); return true; }

        // 3) хранение/горячая панель: индексы 0..35
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (isRemove(it)) {
                inv.setItem(i, sword);
                return true;
            }
        }
        return false;
    }


    /* страховки */
    @EventHandler public void onQuit(PlayerQuitEvent e)   { stopInvis(e.getPlayer(), true); }
    @EventHandler public void onDeath(PlayerDeathEvent e) { stopInvis(e.getEntity(), false); }

    /* ===================== Core logic ===================== */

    private boolean isHoma(ItemStack it) {
        return it != null && it.getType() == Material.DIAMOND_SWORD
                && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(KEY_HOMA, PersistentDataType.BYTE);
    }

    private boolean isRemove(ItemStack it) {
        return it != null && it.getType() == Material.RED_DYE
                && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(KEY_REMOVE, PersistentDataType.BYTE);
    }

    private ItemStack makeRemoveItem() {
        ItemStack it = new ItemStack(Material.RED_DYE);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("§cRemove Invis"));
        im.getPersistentDataContainer().set(KEY_REMOVE, PersistentDataType.BYTE, (byte) 1);
        im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(im);
        return it;
    }

    /** Запуск или продление инвиза. */
    private void startOrRefreshInvis(Player p, boolean holdingRemove) {
        UUID id = p.getUniqueId();

        if (active.contains(id)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 22, 0, false, false, false));
            cancelTask(timeoutTask.remove(id));
            timeoutTask.put(id, plugin.getServer().getScheduler()
                    .runTaskLater(plugin, () -> stopInvis(p, true), 20L));
            return;
        }

        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!holdingRemove) {
            if (!isHoma(hand)) return;
            storedSword.put(id, hand.clone());
            storedSlot.put(id, p.getInventory().getHeldItemSlot());
            p.getInventory().setItemInMainHand(makeRemoveItem());
        }

        p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.5f, 1.8f);
        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 22, 0, false, false, false));
        active.add(id);
        startButterflies(p);

        timeoutTask.put(id, plugin.getServer().getScheduler()
                .runTaskLater(plugin, () -> stopInvis(p, true), 20L));
    }

    private void stopInvis(Player p, boolean restoreSword) {
        UUID id = p.getUniqueId();
        if (!active.remove(id)) { cleanupTasks(id); return; }

        cleanupTasks(id);
        p.removePotionEffect(PotionEffectType.INVISIBILITY);

        if (!restoreSword) {
            storedSword.remove(id);
            storedSlot.remove(id);
            return;
        }

        ItemStack sword = storedSword.remove(id);
        Integer slot = storedSlot.remove(id);
        if (sword == null) return;

        // СНАЧАЛА пробуем заменить краситель где угодно
        if (replaceRemoveDyeWithSwordAnywhere(p, sword)) return;

        // Если красителя уже нет — вернём в исходный слот, если пуст
        if (slot != null) {
            ItemStack cur = p.getInventory().getItem(slot);
            if (cur == null || cur.getType() == Material.AIR) {
                p.getInventory().setItem(slot, sword);
                return;
            }
        }

        // Иначе — просто в инвентарь/на землю
        Map<Integer, ItemStack> rem = p.getInventory().addItem(sword);
        rem.values().forEach(it -> p.getWorld().dropItemNaturally(p.getLocation(), it));
    }


    private void startButterflies(Player p) {
        UUID id = p.getUniqueId();
        Particle.DustOptions dust1 = new Particle.DustOptions(Color.fromRGB(255, 140, 50), 1.0f);
        Particle.DustOptions dust2 = new Particle.DustOptions(Color.fromRGB(255, 100, 30), 1.0f);

        particleTask.put(id, plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!active.contains(id) || !p.isOnline()) { cancelTask(particleTask.remove(id)); return; }
            var base = p.getLocation().add(0, 1.2, 0);
            for (int i = 0; i < 8; i++) {
                double ang = Math.random() * Math.PI * 2;
                double r = 0.35 + Math.random() * 0.25;
                double y = (Math.random() - 0.5) * 0.4;
                var spot = base.clone().add(Math.cos(ang) * r, y, Math.sin(ang) * r);
                p.getWorld().spawnParticle(Particle.FIREWORK, spot, 1, 0, 0, 0, 0.01);
                p.getWorld().spawnParticle(Particle.DUST, spot, 1, 0, 0, 0, 0, (Math.random() < 0.5 ? dust1 : dust2));
            }
        }, 0L, 2L));
    }

    private void cleanupTasks(UUID id) {
        cancelTask(timeoutTask.remove(id));
        cancelTask(particleTask.remove(id));
    }
    private void cancelTask(BukkitTask t) { if (t != null) t.cancel(); }
}
