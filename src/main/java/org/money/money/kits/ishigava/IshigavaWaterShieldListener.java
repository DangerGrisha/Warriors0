package org.money.money.kits.ishigava;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

import java.util.List;

/**
 * Перенос 1:1 из Last_Warriors (events/ishigava/WaterShieldListener) — водяной щит «Quick_Wall».
 * Shift+ПКМ — стена из ArmorStand'ов; ПКМ — одиночный летящий щит. Щиты гасят снаряды
 * (стрелы/снежки/яйца). Живут 20с. Детект по display-name.
 */
public class IshigavaWaterShieldListener implements Listener {

    private static final String NAME_OF_ISHIGAVA_SHIELD = "Quick_Wall";

    private final Plugin plugin;

    public IshigavaWaterShieldListener(Plugin plugin) {
        this.plugin = plugin;
    }

    /* ===================== Выдача ===================== */

    public ItemStack makeQuickWall() {
        ItemStack it = new ItemStack(Material.RED_DYE, 1);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(NAME_OF_ISHIGAVA_SHIELD));
        meta.setUnbreakable(true);
        meta.setLore(List.of("something"));
        it.setItemMeta(meta);
        return it;
    }

    /* ===================== Логика ===================== */

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (checkEventForRightClick(event, player)) {
            if (player.isSneaking()) {
                spawnShieldStructure(player, plugin);
            } else {
                Vector direction = player.getEyeLocation().getDirection();
                Location spawnLocation = player.getLocation().add(direction.multiply(4)).add(0, 1, 0);
                spawnSingleMovingShield(spawnLocation, plugin, direction);
            }
        }
    }

    private void spawnSingleMovingShield(Location location, Plugin plugin, Vector direction) {
        location.getWorld().spawn(location, ArmorStand.class, stand -> {
            stand.setVisible(false);
            stand.setGravity(false);
            stand.setArms(true);
            stand.setBasePlate(false);
            stand.setInvulnerable(true);
            stand.setRightArmPose(new EulerAngle(0, 0, 0));
            stand.setCanPickupItems(false);
            stand.setMarker(true);
            stand.setMetadata("water_shield_move", new FixedMetadataValue(plugin, true));

            ItemStack limeDye = new ItemStack(Material.LIME_DYE);
            ItemMeta dyeMeta = limeDye.getItemMeta();
            dyeMeta.displayName(Component.text("WaterShieldMove"));
            limeDye.setItemMeta(dyeMeta);
            stand.getEquipment().setItemInMainHand(limeDye);

            moveArmorStand(stand, direction, plugin);

            plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, stand::remove, 400L);
        });
    }

    private void moveArmorStand(ArmorStand stand, Vector direction, Plugin plugin) {
        new BukkitRunnable() {
            public void run() {
                if (!stand.isValid()) { cancel(); return; }
                stand.teleport(stand.getLocation().add(direction.clone().multiply(0.06)));
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    private void spawnArmorStand(Location location, boolean isMain, Plugin plugin) {
        location.getWorld().spawn(location, ArmorStand.class, stand -> {
            stand.setVisible(false);
            stand.setGravity(false);
            stand.setArms(true);
            stand.setBasePlate(false);
            stand.setInvulnerable(true);
            stand.setRightArmPose(new EulerAngle(0, 0, 0));
            stand.setCanPickupItems(false);
            stand.setMarker(false);
            stand.setMetadata("water_shield", new FixedMetadataValue(plugin, true));

            if (isMain) {
                ItemStack limeDye = new ItemStack(Material.LIME_DYE);
                ItemMeta dyeMeta = limeDye.getItemMeta();
                dyeMeta.displayName(Component.text("WaterShield"));
                limeDye.setItemMeta(dyeMeta);
                stand.getEquipment().setItemInMainHand(limeDye);
            }

            plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, stand::remove, 400L);
        });
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        Location hitLocation = projectile.getLocation();

        List<Entity> nearbyEntities = (List<Entity>) hitLocation.getWorld().getNearbyEntities(hitLocation, 1, 1, 1, entity ->
                (entity instanceof ArmorStand && entity.hasMetadata("water_shield")));

        for (Entity entity : nearbyEntities) {
            ArmorStand armorStand = (ArmorStand) entity;
            if (armorStand.getLocation().distance(hitLocation) <= 1) {
                if (projectile.getType() == EntityType.SNOWBALL || projectile.getType() == EntityType.EGG
                        || projectile.getType() == EntityType.ARROW || projectile.getType() == EntityType.SPECTRAL_ARROW) {
                    projectile.remove();
                    break;
                }
            }
        }
    }

    private void spawnShieldStructure(Player player, Plugin plugin) {
        Vector forward = player.getEyeLocation().getDirection().normalize();
        Vector right = perpendicular(forward);

        Location baseLocation = player.getLocation().add(forward).add(0, player.getEyeHeight() - 1, 0);

        spawnArmorStand(baseLocation, true, plugin);
        spawnArmorStand(baseLocation.clone().add(0, 2, 0), false, plugin);

        double[] distances = {0.5, 1.0, 1.5};
        for (double dist : distances) {
            Location rightLocation = baseLocation.clone().add(right.clone().multiply(dist));
            spawnArmorStand(rightLocation, false, plugin);
            spawnArmorStand(rightLocation.clone().add(0, 2, 0), false, plugin);

            Location rightExtra = baseLocation.clone().add(right.clone().multiply(-dist));
            spawnArmorStand(rightExtra, false, plugin);
            spawnArmorStand(rightExtra.clone().add(0, 2, 0), false, plugin);

            Location leftLocation = baseLocation.clone().add(right.clone().multiply(-dist));
            spawnArmorStand(leftLocation, false, plugin);
            spawnArmorStand(leftLocation.clone().add(0, 2, 0), false, plugin);

            Location leftExtra = baseLocation.clone().add(right.clone().multiply(dist));
            spawnArmorStand(leftExtra, false, plugin);
            spawnArmorStand(leftExtra.clone().add(0, 2, 0), false, plugin);
        }
    }

    private Vector perpendicular(Vector direction) {
        return new Vector(-direction.getZ(), 0, direction.getX()).normalize();
    }

    private boolean checkEventForRightClick(PlayerInteractEvent event, Player player) {
        return (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) &&
                player.getInventory().getItemInMainHand().hasItemMeta() &&
                player.getInventory().getItemInMainHand().getItemMeta().hasDisplayName() &&
                player.getInventory().getItemInMainHand().getItemMeta().getDisplayName().contains(NAME_OF_ISHIGAVA_SHIELD);
    }
}
