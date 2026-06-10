package org.money.money.kits.saske;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import org.money.money.kits.ladynagan.LadyCooldownManager;

import java.util.List;

/**
 * Перенос 1:1 из Last_Warriors (events/saske/BodyReplacemenListener) — замена телом.
 * ПКМ «Body Replacement» (чернильный мешок): через ~3.25с меняешься местами с целью
 * в радиусе 40 (если нет блоков между). Кулдаун 70с. Звук «saske.katon» сохранён.
 * Адаптация: убрана серверная механика флага (dropFlag/баннер из старого CTF).
 */
public class SaskeBodyReplacementListener implements Listener {

    private static final String NAME_OF_REPLACEMENT = "Body Replacement";
    private static final double DISTANCE_OF_TRIGGERING = 40;
    private static final long   SPEACH_BEFORE_REPLACEMENT = 65L;

    private final Plugin plugin;
    private final LadyCooldownManager cooldownManager;

    public SaskeBodyReplacementListener(Plugin plugin, LadyCooldownManager cooldownManager) {
        this.plugin = plugin;
        this.cooldownManager = cooldownManager;
    }

    /* ===================== Выдача ===================== */

    public ItemStack makeBodyReplacement() {
        ItemStack it = new ItemStack(Material.INK_SAC, 1);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(NAME_OF_REPLACEMENT));
        meta.setUnbreakable(true);
        meta.setLore(List.of("something"));
        it.setItemMeta(meta);
        return it;
    }

    /* ===================== Логика ===================== */

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!checkEvent(event)) return;

        Player player = event.getPlayer();
        event.setCancelled(true);

        if (!cooldownManager.isCooldownComplete(player, NAME_OF_REPLACEMENT)) {
            player.sendMessage(ChatColor.RED + "Ability is recharging!");
            return;
        }

        final int slot = player.getInventory().getHeldItemSlot();
        final ItemStack originalItem = player.getInventory().getItemInMainHand().clone();
        player.getInventory().setItemInMainHand(null);

        final int COOLDOWN = 70;
        cooldownManager.startCooldown(player, NAME_OF_REPLACEMENT, slot, COOLDOWN, false);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            if (!cooldownManager.isCooldownComplete(player, NAME_OF_REPLACEMENT)) return;

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
        }, COOLDOWN * 20L);

        World world = player.getWorld();
        world.playSound(player.getLocation(), "saske.katon", SoundCategory.MASTER, 1.0F, 1.0F);

        Bukkit.getScheduler().runTaskLater(plugin, () -> useAbility(player), SPEACH_BEFORE_REPLACEMENT);
    }

    private boolean checkEvent(PlayerInteractEvent event) {
        ItemStack hand = event.getPlayer().getInventory().getItemInMainHand();
        return (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)
                && hand.getType() == Material.INK_SAC
                && hand.hasItemMeta()
                && NAME_OF_REPLACEMENT.equals(hand.getItemMeta().getDisplayName());
    }

    private void useAbility(Player player) {
        Location location = player.getEyeLocation();
        Vector direction = location.getDirection().normalize();

        RayTraceResult result = player.getWorld()
                .rayTraceEntities(location, direction, DISTANCE_OF_TRIGGERING, entity -> entity != player);

        if (result != null && result.getHitEntity() instanceof LivingEntity targetEntity) {
            double distance = player.getLocation().distance(targetEntity.getLocation());
            RayTraceResult checkBlocks = player.rayTraceBlocks(distance);
            if (checkBlocks == null) {
                if (targetEntity.getLocation().distanceSquared(location) <= DISTANCE_OF_TRIGGERING * DISTANCE_OF_TRIGGERING) {
                    Location playerLocation = player.getLocation();
                    Location targetLocation = targetEntity.getLocation();

                    targetEntity.teleport(playerLocation);
                    player.teleport(targetLocation);

                    spawnSmokeParticles(playerLocation);
                    spawnSmokeParticles(targetLocation);
                }
            }
        } else {
            player.stopSound("saske.katon", SoundCategory.MASTER);
        }
    }

    private void spawnSmokeParticles(Location location) {
        location.getWorld().spawnParticle(Particle.SMOKE, location, 50, 0.5, 0.5, 0.5);
    }
}
