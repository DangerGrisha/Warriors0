package org.money.money.kits.uraraka;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class UrarakaGloveListener implements Listener {

    private static final int  STUN_TICKS = 20;  // 1 секунда
    private static final int  STUN_LEVEL = 1;   // Slowness II (0 = I, 1 = II). Можно поднять.
    private static final double STUN_RADIUS = 1.5; // игрок должен быть в 1 блоке от цели

    private final Plugin plugin;
    private final NamespacedKey KEY_GLOVE;

    public UrarakaGloveListener(Plugin plugin) {
        this.plugin = plugin;
        this.KEY_GLOVE = new NamespacedKey(plugin, "uraraka_glove");
    }

    /** ItemStack: алмазный меч "Glove" с тегом. */
    public ItemStack makeGloveSword() {
        ItemStack it = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Hammer"));
        // пометим через PDC, чтобы не путать с пользовательскими мечами
        im.getPersistentDataContainer().set(KEY_GLOVE, PersistentDataType.BYTE, (byte)1);
        // косметика по желанию:
        // im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        it.setItemMeta(im);
        return it;
    }

    private boolean isGlove(ItemStack it) {
        if (it == null || it.getType() != Material.DIAMOND_SWORD || !it.hasItemMeta()) return false;
        var im = it.getItemMeta();
        // предпочитаем PDC-маркер
        if (im.getPersistentDataContainer().has(KEY_GLOVE, PersistentDataType.BYTE)) return true;
        // запасной вариант по имени
        return Component.text("Hammer").equals(im.displayName());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker)) return;
        if (!(e.getEntity()  instanceof LivingEntity victim)) return;

        // должен быть именно наш меч
        if (!isGlove(attacker.getInventory().getItemInMainHand())) return;

        // нужен крит
        if (!isCritical(e, attacker)) return;

        // владелец достаточно близко к цели (<= 1 блок)
        if (attacker.getLocation().distanceSquared(victim.getLocation()) > STUN_RADIUS * STUN_RADIUS) return;

        // применяем "оглушение" — короткий Slowness
        victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, STUN_TICKS, STUN_LEVEL, false, true, true));

        // немного фидбэка
        try {
            victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 1.0f);
            victim.getWorld().spawnParticle(Particle.SWEEP_ATTACK, victim.getLocation().add(0, 1.0, 0), 1, 0, 0, 0, 0);
        } catch (Throwable ignored) {}
    }

    /** Крит из Paper, с надёжным фоллбэком на "ванильную" эвристику. */
    private boolean isCritical(EntityDamageByEntityEvent e, Player p) {
        // Пытаемся вызвать Paper-метод EntityDamageByEntityEvent#isCritical()
        try {
            var m = e.getClass().getMethod("isCritical");
            Object res = m.invoke(e);
            if (res instanceof Boolean b) return b;
        } catch (Throwable ignored) {}
        // Фоллбэк: типичные условия ванильного крита (приблизительно)
        return likelyVanillaCritical(p);
    }

    private boolean likelyVanillaCritical(Player p) {
        if (p.isSprinting()) return false;
        if (p.isOnGround())  return false;
        if (p.isInsideVehicle()) return false;
        if (p.hasPotionEffect(PotionEffectType.BLINDNESS)) return false;
        if (p.getAttackCooldown() < 0.9f) return false; // меч откдешен
        // в падении (движение вниз)
        return p.getVelocity().getY() < 0;
    }
}
