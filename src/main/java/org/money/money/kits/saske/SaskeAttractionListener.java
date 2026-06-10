package org.money.money.kits.saske;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;

import org.money.money.kits.ladynagan.LadyCooldownManager;
import org.money.money.session.KitSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Перенос 1:1 из Last_Warriors (events/saske/AttractionListener) — ульта «Attraction».
 * ПКМ по блоку «Attraction» (бедрок): создаёт зону притяжения (невидимый ArmorStand),
 * стягивает врагов к центру, урон 4/сек в радиусе ~3, длится 300 тиков. Кулдаун 120с.
 */
public class SaskeAttractionListener implements Listener {

    private final Plugin plugin;
    private final LadyCooldownManager cooldownManager;

    public SaskeAttractionListener(Plugin plugin, LadyCooldownManager cooldownManager) {
        this.plugin = plugin;
        this.cooldownManager = cooldownManager;
    }

    /* ===================== Выдача ===================== */

    public ItemStack makeAttractionBlock() {
        ItemStack it = new ItemStack(Material.BEDROCK, 1);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text("Attraction"));
        meta.setUnbreakable(true);
        meta.setLore(List.of("something"));
        it.setItemMeta(meta);
        return it;
    }

    /* ===================== Логика ===================== */

    private void spawnParticlesAroundBlock(Location location) {
        location.getWorld().spawnParticle(Particle.SMOKE, location, 50, 5, 5, 5);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Block clickedBlock = event.getClickedBlock();

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK
                && event.getHand() == EquipmentSlot.HAND
                && player.getInventory().getItemInMainHand().getType() == Material.BEDROCK
                && player.getInventory().getItemInMainHand().hasItemMeta()
                && "Attraction".equals(player.getInventory().getItemInMainHand().getItemMeta().getDisplayName())) {

            event.setCancelled(true);

            if (!cooldownManager.isCooldownComplete(player, "Attraction")) {
                player.sendMessage(ChatColor.RED + "Ability is recharging!");
                return;
            }
            if (clickedBlock == null) return;

            final int slot = player.getInventory().getHeldItemSlot();
            final ItemStack originalItem = player.getInventory().getItemInMainHand().clone();
            player.getInventory().setItemInMainHand(null);
            startCooldownAndReturn(player, originalItem, slot, 120);

            Block target = clickedBlock.getRelative(event.getBlockFace());
            Location loc = target.getLocation();

            spawnParticlesAroundBlock(loc);
            replaceBlockWithArmorStand(loc);
            startAttraction(loc, player);
        }
    }

    private void startCooldownAndReturn(Player player, ItemStack item, int preferredSlot, int seconds) {
        cooldownManager.startCooldown(player, "Attraction", preferredSlot, seconds, false);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !KitSession.isInGame(player)) return;

            // Защита от дубля: если «Attraction» уже есть в инвентаре (например, кит выдали
            // заново во время перезарядки), второй предмет не выдаём.
            if (hasAttraction(player)) return;

            ItemStack inSlot = player.getInventory().getItem(preferredSlot);
            if (inSlot == null || inSlot.getType().isAir()) {
                player.getInventory().addItem(item);
            } else {
                var leftover = player.getInventory().addItem(item);
                leftover.values().forEach(rem -> player.getWorld().dropItemNaturally(player.getLocation(), rem));
            }
            try {
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
            } catch (Throwable ignored) {}
        }, seconds * 20L);
    }

    private boolean hasAttraction(Player player) {
        for (ItemStack it : player.getInventory().getContents()) {
            if (it != null && it.getType() == Material.BEDROCK
                    && it.hasItemMeta() && it.getItemMeta().hasDisplayName()
                    && "Attraction".equals(it.getItemMeta().getDisplayName())) {
                return true;
            }
        }
        return false;
    }

    public void replaceBlockWithArmorStand(Location location) {
        ArmorStand armorStand = location.getWorld().spawn(location.add(0.5, 0, 0.5), ArmorStand.class);
        armorStand.setVisible(false);
        armorStand.setGravity(false);
        armorStand.setCanPickupItems(false);
        armorStand.setInvulnerable(true);
        armorStand.setBasePlate(false);
        armorStand.setMarker(true);
        armorStand.setCustomName("Attraction");
        armorStand.setCustomNameVisible(false);
        armorStand.setArms(true);

        ItemStack redDye = new ItemStack(Material.RED_DYE);
        ItemMeta dyeMeta = redDye.getItemMeta();
        dyeMeta.displayName(Component.text("Attraction"));
        redDye.setItemMeta(dyeMeta);
        armorStand.getEquipment().setItemInMainHand(redDye);
    }

    public void startAttraction(Location location, Player placer) {
        new BukkitRunnable() {
            int ticks = 0;
            ArmorStand armorStand = null;
            final Map<UUID, Integer> entitiesInZone = new HashMap<>();

            @Override
            public void run() {
                ticks++;
                if (ticks >= 300) {
                    if (armorStand != null && !armorStand.isDead()) {
                        armorStand.remove();
                    }
                    cancel();
                    return;
                }

                for (Entity entity : location.getWorld().getNearbyEntities(location, 9, 9, 9)) {
                    if (!(entity instanceof ArmorStand) &&
                            !(entity instanceof Player && isAlly((Player) entity, placer)) &&
                            entity instanceof LivingEntity living) {

                        Vector direction = location.toVector().subtract(entity.getLocation().toVector()).normalize();
                        entity.setVelocity(direction.multiply(0.5));

                        double distSq = entity.getLocation().distanceSquared(location);
                        if (distSq <= 9) {
                            UUID id = entity.getUniqueId();
                            int timeInZone = entitiesInZone.getOrDefault(id, 0) + 1;
                            entitiesInZone.put(id, timeInZone);

                            if (timeInZone % 20 == 0) {
                                living.damage(4.0, placer);
                            }
                        } else {
                            entitiesInZone.remove(entity.getUniqueId());
                        }
                    }
                }

                if (ticks % 5 == 0) {
                    spawnParticlesAroundBlock(location);
                }
                if (ticks == 1) {
                    armorStand = getArmorStand(location);
                }
            }
        }.runTaskTimer(plugin, 20L, 1L);
    }

    private ArmorStand getArmorStand(Location location) {
        for (Entity entity : location.getWorld().getNearbyEntities(location, 0.5, 0.5, 0.5)) {
            if (entity instanceof ArmorStand && "Attraction".equals(entity.getCustomName())) {
                return (ArmorStand) entity;
            }
        }
        return null;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (player.getLocation().getBlock().getType() == Material.ARMOR_STAND) {
            if (player.getLocation().getBlock().getState() instanceof ArmorStand armorStand) {
                if (!armorStand.isVisible() && !armorStand.hasGravity() && armorStand.isMarker()) {
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof ArmorStand && event.getCause() == DamageCause.ENTITY_ATTACK) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemMeta meta = event.getItemInHand().getItemMeta();
        if (event.getBlockPlaced().getType() == Material.BEDROCK
                && meta != null && meta.hasDisplayName()
                && "Attraction".equals(meta.getDisplayName())) {
            event.getBlockPlaced().setType(Material.AIR);
        }
        if (event.getBlockPlaced().getType() == Material.ARMOR_STAND) {
            event.setCancelled(true);
        }
    }

    private boolean isAlly(Player player, Player placer) {
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return true;
        }
        Team placerTeam = placer.getScoreboard().getPlayerTeam(placer);
        Team playerTeam = player.getScoreboard().getPlayerTeam(player);
        return placerTeam != null && playerTeam != null && placerTeam.equals(playerTeam);
    }
}
