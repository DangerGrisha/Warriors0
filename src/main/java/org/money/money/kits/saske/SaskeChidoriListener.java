package org.money.money.kits.saske;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import org.money.money.kits.ladynagan.LadyCooldownManager;

import java.util.List;

/**
 * Перенос 1:1 из Last_Warriors (events/saske/ChidoryListener) — чидори/рывок.
 * ПКМ «Chidori» (чернильный мешок): подготовка ~1.65с (заморозка + частицы), затем рывок
 * (скорость ×20) с уроном 19 по области во время рывка. Кулдаун 50с. Звук «saske.chidory».
 */
public class SaskeChidoriListener implements Listener {

    private static final String NAME_OF_CHIDORY = "Chidori";
    private static final int    MULTIPLY_VELOCITY = 20;
    private static final long   SPEACH_BEFORE_DASH = 33L;

    private final Plugin plugin;
    private final LadyCooldownManager cooldownManager;

    public SaskeChidoriListener(Plugin plugin, LadyCooldownManager cooldownManager) {
        this.plugin = plugin;
        this.cooldownManager = cooldownManager;
    }

    /* ===================== Выдача ===================== */

    public ItemStack makeChidori() {
        ItemStack it = new ItemStack(Material.INK_SAC, 1);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(NAME_OF_CHIDORY));
        meta.setUnbreakable(true);
        meta.setLore(List.of("something"));
        it.setItemMeta(meta);
        return it;
    }

    /* ===================== Логика ===================== */

    @EventHandler
    public void onPlayerUse(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (!checkEvent(event, player)) return;
        event.setCancelled(true);

        final String abilityId = "CHIDORY";
        final int cooldownSec = 50;

        if (!cooldownManager.isCooldownComplete(player, abilityId)) {
            player.sendMessage(ChatColor.RED + "Ability is recharging!");
            return;
        }

        final int slot = player.getInventory().getHeldItemSlot();
        final ItemStack originalItem = player.getInventory().getItemInMainHand().clone();
        player.getInventory().setItemInMainHand(null);

        cooldownManager.startCooldown(player, abilityId, slot, cooldownSec, false);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            if (!cooldownManager.isCooldownComplete(player, abilityId)) return;

            ItemStack inSlot = player.getInventory().getItem(slot);
            if (inSlot == null || inSlot.getType().isAir()) {
                player.getInventory().setItem(slot, originalItem);
            } else {
                var leftover = player.getInventory().addItem(originalItem);
                if (!leftover.isEmpty()) {
                    leftover.values().forEach(rem -> player.getWorld().dropItemNaturally(player.getLocation(), rem));
                }
            }
            try {
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
            } catch (Throwable ignored) {}
        }, cooldownSec * 20L);

        World world = player.getWorld();
        Location initialLocation = player.getLocation();

        world.playSound(initialLocation, "saske.chidory", 1.0F, 1.0F);

        player.setWalkSpeed(0f);
        player.setVelocity(new Vector(0, 0, 0));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) SPEACH_BEFORE_DASH, 255, false, false));

        particleStaff(player, world);

        new BukkitRunnable() {
            @Override
            public void run() {
                player.setWalkSpeed(0.2f);
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) SPEACH_BEFORE_DASH, 255, false, false));

                Vector direction = player.getLocation().getDirection();
                world.spawnParticle(Particle.SONIC_BOOM, player.getLocation(), 7, 0.5, 0.5, 0.5, 0);
                Vector dashVelocity = direction.multiply(MULTIPLY_VELOCITY);
                player.setVelocity(dashVelocity);

                applyDamageInPlayer(player, player.getLocation(), dashVelocity);
            }
        }.runTaskLater(plugin, SPEACH_BEFORE_DASH);
    }

    private void particleStaff(Player player, World world) {
        new BukkitRunnable() {
            int iterations = 20;
            @Override
            public void run() {
                if (iterations-- <= 0) { cancel(); return; }
                Location particleLocation = player.getLocation().add(0, 1, 0);
                world.spawnParticle(Particle.ELECTRIC_SPARK, particleLocation, 7, 0.5, 0.5, 0.5, 0);
            }
        }.runTaskTimer(plugin, 1L, 4L);
    }

    private void applyDamageInPlayer(Player player, Location startLocation, Vector dashVelocity) {
        final double searchRadius = 1.5;
        final int iterations = 20;
        final int delayBetweenIterations = 1;

        BukkitScheduler scheduler = Bukkit.getServer().getScheduler();
        BukkitTask task = scheduler.runTaskTimer(plugin, () -> {
            for (Entity e : player.getNearbyEntities(searchRadius, searchRadius, searchRadius)) {
                if (e instanceof LivingEntity le && e != player) {
                    le.damage(19, player);
                }
            }
        }, 0L, delayBetweenIterations);

        scheduler.runTaskLater(plugin, task::cancel, (long) iterations * delayBetweenIterations);
    }

    private boolean checkEvent(PlayerInteractEvent event, Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        return (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) &&
                hand.getType() == Material.INK_SAC &&
                hand.hasItemMeta() && hand.getItemMeta().hasDisplayName() &&
                hand.getItemMeta().getDisplayName().equals(NAME_OF_CHIDORY);
    }
}
