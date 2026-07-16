package org.money.money.kits.ishigava;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.money.money.meta.ClassRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Перенос 1:1 из Last_Warriors (events/ishigava/WaterBridgesListener) — мосты «Bridge».
 * Shift+ПКМ/ЛКМ — менять дистанцию (5..20); ПКМ — поставить платформу из LAPIS_BLOCK
 * на текущей дистанции (живёт 20с). Детект по display-name (RED_DYE «Bridge»).
 */
public class IshigavaWaterBridgesListener implements Listener {

    private static final String NAME_OF_ISHIGAVA_BRIDGE = "Bridge";
    private static final int STEP = 1;

    private static int minDistance() { return ClassRegistry.numInt("ishigava", "bridge", "minDistance", 5); }
    private static int maxDistance() { return ClassRegistry.numInt("ishigava", "bridge", "maxDistance", 20); }
    private static int maxCharges()  { return ClassRegistry.numInt("ishigava", "bridge", "charges", 5); }

    private final Plugin plugin;
    private final Map<UUID, Integer> distanceMap = new HashMap<>();
    private final Map<UUID, Integer> chargesMap = new HashMap<>();
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();

    public IshigavaWaterBridgesListener(Plugin plugin) {
        this.plugin = plugin;
    }

    /* ===================== Выдача ===================== */

    public ItemStack makeBridge() {
        ItemStack it = new ItemStack(Material.RED_DYE, 1);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(NAME_OF_ISHIGAVA_BRIDGE));
        meta.setUnbreakable(true);
        meta.setLore(List.of("something"));
        it.setItemMeta(meta);
        return it;
    }

    /* ===================== Логика ===================== */

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        if (!isBridgeItem(event.getItem())) return;

        Action action = event.getAction();
        boolean rightClick = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        boolean leftClick  = action == Action.LEFT_CLICK_AIR  || action == Action.LEFT_CLICK_BLOCK;

        if (player.isSneaking()) {
            if (rightClick) {
                event.setCancelled(true);
                changeDistance(player, +STEP);
            } else if (leftClick) {
                event.setCancelled(true);
                changeDistance(player, -STEP);
            }
            return;
        }

        if (rightClick) {
            event.setCancelled(true);
            placeBridge(player);
        }
    }

    private int getDistance(Player player) {
        return distanceMap.getOrDefault(player.getUniqueId(), minDistance());
    }

    private void changeDistance(Player player, int delta) {
        int current = getDistance(player);
        int updated = Math.max(minDistance(), Math.min(maxDistance(), current + delta));
        distanceMap.put(player.getUniqueId(), updated);
        showDistance(player, updated);
    }

    private void showDistance(Player player, int distance) {
        player.sendActionBar(Component.text("Дистанция моста: " + distance + " бл.", NamedTextColor.AQUA));
    }

    private void placeBridge(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        int maxCharges = maxCharges();
        int ch = chargesMap.getOrDefault(id, maxCharges);

        // Out of charges -> 45s cooldown, then the 5 charges refill.
        if (ch <= 0) {
            long until = cooldownUntil.getOrDefault(id, 0L);
            if (now < until) {
                long sec = (until - now + 999) / 1000;
                player.sendActionBar(Component.text("Bridges: " + sec + " sec", NamedTextColor.RED));
                return;
            }
            ch = maxCharges;
        }

        int distance = getDistance(player);
        Vector direction = player.getEyeLocation().getDirection();
        Location spawnLocation = player.getLocation().add(direction.multiply(distance));

        if (isNearPlayersOrBridges(spawnLocation, player, 3)) {
            player.sendMessage("Cannot spawn a bridge here");
            return;
        }

        spawnBridge(spawnLocation, player);
        ch--;
        chargesMap.put(id, ch);
        if (ch <= 0) {
            long cooldownMs = ClassRegistry.millis("ishigava", "bridge", 45_000L);
            cooldownUntil.put(id, now + cooldownMs);
            player.sendActionBar(Component.text("Bridges spent — " + (cooldownMs / 1000) + "s cooldown", NamedTextColor.GRAY));
        } else {
            player.sendActionBar(Component.text("Bridges: " + ch + "/" + maxCharges, NamedTextColor.AQUA));
        }
    }

    private void spawnBridge(Location location, Player player) {
        World world = player.getWorld();
        ArmorStand armorStand = world.spawn(location, ArmorStand.class, stand -> {
            stand.setVisible(false);
            stand.setGravity(false);
            stand.setMarker(true);
            stand.setInvulnerable(true);
            stand.setMetadata("bridge", new FixedMetadataValue(plugin, true));
        });

        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                Location blockLocation = location.clone().add(x, 1, z);
                if (blockLocation.getBlock().getType() == Material.AIR) {
                    blockLocation.getBlock().setType(Material.LAPIS_BLOCK);
                }
            }
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    Location blockLocation = location.clone().add(x, 1, z);
                    if (blockLocation.getBlock().getType() == Material.LAPIS_BLOCK) {
                        blockLocation.getBlock().setType(Material.AIR);
                    }
                }
            }
            armorStand.remove();
        }, ClassRegistry.numInt("ishigava", "bridge", "lifetimeTicks", 400));
    }

    private boolean isBridgeItem(ItemStack item) {
        return item != null
                && item.getType() == Material.RED_DYE
                && item.hasItemMeta()
                && item.getItemMeta().hasDisplayName()
                && item.getItemMeta().getDisplayName().equals(NAME_OF_ISHIGAVA_BRIDGE);
    }

    private boolean isNearPlayersOrBridges(Location location, Player placer, int radius) {
        for (Entity entity : location.getWorld().getNearbyEntities(location, radius, radius, radius)) {
            if (entity instanceof Player && !entity.equals(placer)) return true;
            if (entity instanceof ArmorStand && entity.hasMetadata("bridge")) return true;
        }
        return false;
    }
}
