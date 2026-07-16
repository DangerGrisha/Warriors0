package org.money.money.kits.ishigava;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;
import org.money.money.meta.ClassRegistry;
import org.money.money.util.ItemModels;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Перенос 1:1 из Last_Warriors (events/ishigava/LastWaterWallListener) — «Last Wall».
 * ПКМ — вкл/выкл; рекурсивно поднимает цепочку «маяков» (до 12) перед игроком, стены
 * поднимаются/опускаются, звуки Warden. Детект по display-name.
 * Адаптация API: Particle.REDSTONE → Particle.DUST.
 */
public class IshigavaLastWaterWallListener implements Listener {

    private static final String NAME_OF_ISHIGAVA_WALL = "Last Wall";

    private static int beaconCount() { return ClassRegistry.numInt("ishigava", "wall", "beaconCount", 12); }

    private boolean isOn = false;
    private boolean isInteracted = false;

    private final Plugin plugin;
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();

    public IshigavaLastWaterWallListener(Plugin plugin) {
        this.plugin = plugin;
    }

    /* ===================== Выдача ===================== */

    public ItemStack makeLastWall() {
        ItemStack it = new ItemStack(Material.RED_DYE, 1);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(NAME_OF_ISHIGAVA_WALL));
        meta.setUnbreakable(true);
        meta.setLore(List.of("something"));
        it.setItemMeta(meta);
        return it;
    }

    /* ===================== Логика ===================== */

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (checkEventForRightClick(event, player) && !isOn && !isInteracted) {
            long now = System.currentTimeMillis();
            long until = cooldownUntil.getOrDefault(player.getUniqueId(), 0L);
            if (now < until) {
                long sec = (until - now + 999) / 1000;
                player.sendActionBar(Component.text(sec + " sec", NamedTextColor.RED));
                return;
            }
            isOn = true;
            initiateCycle(player, beaconCount());
            cooldownUntil.put(player.getUniqueId(), now + ClassRegistry.millis("ishigava", "wall", 180_000L));

            isInteracted = true;
            new BukkitRunnable() {
                @Override public void run() { isInteracted = false; }
            }.runTaskLater(plugin, 2);

        } else if (checkEventForRightClick(event, player) && isOn && !isInteracted) {
            isOn = false;

            isInteracted = true;
            new BukkitRunnable() {
                @Override public void run() { isInteracted = false; }
            }.runTaskLater(plugin, 2);
        }
    }

    private boolean checkEventForRightClick(PlayerInteractEvent event, Player player) {
        return (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) &&
                player.getInventory().getItemInMainHand().hasItemMeta() &&
                player.getInventory().getItemInMainHand().getItemMeta().hasDisplayName() &&
                player.getInventory().getItemInMainHand().getItemMeta().getDisplayName().contains(NAME_OF_ISHIGAVA_WALL);
    }

    private void initiateCycle(Player player, int maxBeacons) {
        spawnArmorStandsRecursively(player, player.getLocation(), player.getLocation().getDirection(), maxBeacons, maxBeacons);
        spawnArmorStandsRecursively(player, player.getLocation().add(0, 4, 0), player.getLocation().getDirection(), maxBeacons, maxBeacons);
    }

    private void spawnArmorStandsRecursively(Player player, Location currentLocation, Vector direction, int remainingBeacons, int initialBeacons) {
        if (remainingBeacons <= 0) {
            return;
        }

        direction.setY(0);
        direction.normalize();
        double distanceToBeacon;
        double distanceToDuration;
        double distanceToTemporary;
        if (remainingBeacons == initialBeacons) {
            distanceToBeacon = 2.6;
            distanceToDuration = 2.5;
            distanceToTemporary = 1.345;
        } else {
            distanceToBeacon = 1.5;
            distanceToDuration = 3.5;
            distanceToTemporary = 1.4;
        }

        player.playSound(player, Sound.ITEM_BUCKET_EMPTY, 0.3f, 1.0f);

        spawnArmorStand(currentLocation.clone().add(direction.clone().multiply(distanceToBeacon)), "beacon", "beacon", remainingBeacons);
        spawnArmorStand(currentLocation.clone().add(direction.clone().multiply(distanceToDuration)), "duration", "duration", remainingBeacons);
        ArmorStand temporary = spawnArmorStand(currentLocation.clone().add(direction.clone().multiply(distanceToTemporary)), "temporary", "temporary", remainingBeacons);

        new BukkitRunnable() {
            @Override
            public void run() {
                temporary.setRotation(player.getLocation().getYaw(), 0);
                if (isOn) {
                    spawnArmorStandsRecursively(player, temporary.getLocation().add(0, 8, 0), temporary.getLocation().getDirection(), remainingBeacons - 1, initialBeacons);
                }
            }
        }.runTaskLater(plugin, 20);
    }

    private ArmorStand spawnArmorStand(Location location, String tag, String entityTag, int remainingBeacons) {
        Location startLocation = location.clone().add(0, -8, 0);
        Location targetLocation = location.clone().add(0, 1, 0);

        ArmorStand armorStand = (ArmorStand) startLocation.getWorld().spawnEntity(startLocation, EntityType.ARMOR_STAND);
        armorStand.setGravity(false);
        armorStand.setVisible(false);
        armorStand.setMarker(true);
        armorStand.setCustomNameVisible(false);
        armorStand.setCustomName(tag);
        armorStand.setArms(true);
        armorStand.setRightArmPose(new EulerAngle(0, 0, 0));

        if (!"temporary".equals(entityTag)) {
            ItemStack limeDye = new ItemStack(Material.LIME_DYE);
            ItemMeta dyeMeta = limeDye.getItemMeta();
            dyeMeta.displayName(Component.text("Last Wall"));
            ItemModels.apply(dyeMeta, "ishigava_last_water_wall_last_water_wall");
            limeDye.setItemMeta(dyeMeta);
            armorStand.getEquipment().setItemInMainHand(limeDye);
        } else {
            final Location particleStart = armorStand.getLocation();
            final double particleStep = 0.2;
            final int particleHeight = 25;

            for (double y = 0; y <= particleHeight; y += particleStep) {
                Location particleLocation = particleStart.clone().add(0, y, 0);
                particleLocation.getWorld().spawnParticle(Particle.DUST, particleLocation, 1,
                        new Particle.DustOptions(Color.BLUE, 1.0F));
            }
        }

        if (entityTag != null) {
            armorStand.addScoreboardTag(entityTag);
        }

        new BukkitRunnable() {
            double currentY = startLocation.getY();
            final double targetY = targetLocation.getY();
            final double step = 0.05;
            boolean soundsPlayed = false;

            @Override
            public void run() {
                if (currentY >= targetY || armorStand.isDead()) {
                    cancel();
                    return;
                }

                currentY += step;
                Location newLocation = armorStand.getLocation();
                newLocation.setY(currentY);
                armorStand.teleport(newLocation);

                if (!soundsPlayed) {
                    soundsPlayed = true;
                    new BukkitRunnable() {
                        @Override public void run() { playNearbySound(startLocation, Sound.ENTITY_WARDEN_DIG, 0.25f, 1.0f); }
                    }.runTaskLater(plugin, 5L);
                    new BukkitRunnable() {
                        @Override public void run() { playNearbySound(startLocation, Sound.ENTITY_WARDEN_ROAR, 0.2f, 1.0f); }
                    }.runTaskLater(plugin, 15L);
                    new BukkitRunnable() {
                        @Override public void run() { playNearbySound(startLocation, Sound.ENTITY_WARDEN_ROAR, 0.2f, 1.0f); }
                    }.runTaskLater(plugin, 30L);
                }
            }
        }.runTaskTimer(plugin, remainingBeacons * 20L + 5L, 1L);

        new BukkitRunnable() {
            double currentY = targetLocation.getY();
            final double targetY = startLocation.getY();
            final double step = 0.05;
            boolean soundsPlayed = false;

            @Override
            public void run() {
                if (currentY <= targetY || armorStand.isDead()) {
                    armorStand.remove();
                    cancel();
                    return;
                }

                if (!soundsPlayed) {
                    soundsPlayed = true;
                    new BukkitRunnable() {
                        @Override public void run() { playNearbySound(startLocation, Sound.ENTITY_WARDEN_ROAR, 0.2f, 1.0f); }
                    }.runTaskLater(plugin, 10L);
                }

                currentY -= step;
                Location newLocation = armorStand.getLocation();
                newLocation.setY(currentY);
                armorStand.teleport(newLocation);
            }
        }.runTaskTimer(plugin, ClassRegistry.numInt("ishigava", "wall", "holdTicks", 1200), 1L); // стена держится ~1 минуту перед опусканием
        return armorStand;
    }

    private void playNearbySound(Location location, Sound sound, float volume, float pitch) {
        int radius = 30;
        location.getWorld().getPlayers().stream()
                .filter(player -> player.getLocation().distance(location) <= radius)
                .forEach(player -> player.playSound(player.getLocation(), sound, volume, pitch));
    }
}
