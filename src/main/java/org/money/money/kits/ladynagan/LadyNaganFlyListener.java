package org.money.money.kits.ladynagan;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Перенос 1:1 из Last_Warriors (events/ladynagan/FlyListener).
 * «Start Fly» (перо) → «Fly+ N»: ПКМ ставит под игрока невидимые блоки (белое стекло),
 * 7 использований, длительность 20с, кулдаун 40с. Детект по display-name (как в оригинале).
 */
public class LadyNaganFlyListener implements Listener {

    // числа 1:1 из LadyConstants
    private static final int  FLY_COUNTER  = 7;
    private static final long FLY_TIME     = 400L; // 20с
    private static final int  COOLDOWN_FLY = 40;   // сек
    private static final int  FLY_SLOT     = 2;

    private int flyCounter = FLY_COUNTER;
    private final Map<Location, Material> previousBlocks = new HashMap<>();

    private final Plugin plugin;
    private final LadyCooldownManager cooldownManager;
    private boolean isInteracted = false;

    public LadyNaganFlyListener(Plugin plugin, LadyCooldownManager cooldownManager) {
        this.plugin = plugin;
        this.cooldownManager = cooldownManager;
    }

    /* ===================== Выдача ===================== */

    public ItemStack makeStartFlyFeather() {
        ItemStack feather = new ItemStack(Material.FEATHER);
        ItemMeta meta = feather.getItemMeta();
        meta.displayName(Component.text("Start Fly"));
        meta.setUnbreakable(true);
        meta.setLore(List.of("something"));
        feather.setItemMeta(meta);
        return feather;
    }

    /* ===================== Активация ===================== */

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (checkEventForRightClick(event, player, "Start Fly") && !isInteracted) {
            ItemStack flyPlusFeather = createFlyPlusFeather();
            isInteracted = true;
            player.getInventory().setItemInMainHand(flyPlusFeather);
            flyCounter = FLY_COUNTER;
            new BukkitRunnable() {
                @Override public void run() { isInteracted = false; }
            }.runTaskLater(plugin, 2);

            // таймер 20с — остановить полёт
            new BukkitRunnable() {
                @Override public void run() {
                    deletePreviousBlocks();
                    delayForUlta(player, "FlyLadyNagan", FLY_SLOT, COOLDOWN_FLY);
                }
            }.runTaskLater(plugin, FLY_TIME);

        } else if (checkEventForRightClickForFly(event, player) && flyCounter >= 0 && !isInteracted) {
            flyCounter--;
            placeInvisibleBlockUnderPlayer(player);
            updateFlyPlusFeather(player);
            isInteracted = true;
            new BukkitRunnable() {
                @Override public void run() { isInteracted = false; }
            }.runTaskLater(plugin, 2);
            if (flyCounter == 0) {
                delayForUlta(player, "FlyLadyNagan", FLY_SLOT, COOLDOWN_FLY);
            }
        }
    }

    private void delayForUlta(Player player, String nameOfAbilitySpecific, int inventorySlotIgnored, int delayInSeconds) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand != null && hand.getType() == Material.FEATHER && hand.hasItemMeta()
                && hand.getItemMeta().hasDisplayName()
                && hand.getItemMeta().getDisplayName().startsWith("Fly+")) {
            player.getInventory().setItemInMainHand(null);
        }

        cooldownManager.startCooldownAndReturn(
                player,
                nameOfAbilitySpecific,
                delayInSeconds,
                makeStartFlyFeather(),
                true
        );

        flyCounter = 0;
    }

    private ItemStack createFlyPlusFeather() {
        ItemStack feather = new ItemStack(Material.FEATHER);
        ItemMeta meta = feather.getItemMeta();
        meta.displayName(Component.text("Fly+"));
        feather.setItemMeta(meta);
        return feather;
    }

    private void updateFlyPlusFeather(Player player) {
        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        ItemMeta meta = itemInHand.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Fly+ " + (flyCounter)));
            itemInHand.setItemMeta(meta);
        }
    }

    private boolean checkEventForRightClick(PlayerInteractEvent event, Player player, String nameOfItem) {
        return (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) &&
                player.getInventory().getItemInMainHand().getType() == Material.FEATHER &&
                player.getInventory().getItemInMainHand().hasItemMeta() &&
                player.getInventory().getItemInMainHand().getItemMeta().hasDisplayName() &&
                player.getInventory().getItemInMainHand().getItemMeta().getDisplayName().equals(nameOfItem);
    }

    private boolean checkEventForRightClickForFly(PlayerInteractEvent event, Player player) {
        final Set<String> flyOptions = new HashSet<>(Arrays.asList(
                "Fly+", "Fly+ 1", "Fly+ 2", "Fly+ 3", "Fly+ 4", "Fly+ 5", "Fly+ 6"));
        final ItemStack mainHand = player.getInventory().getItemInMainHand();
        final ItemMeta mainHandItemMeta = mainHand.hasItemMeta() ? mainHand.getItemMeta() : null;

        return (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) &&
                mainHand.getType() == Material.FEATHER &&
                mainHand.hasItemMeta() &&
                mainHandItemMeta.hasDisplayName() &&
                flyOptions.contains(mainHandItemMeta.getDisplayName());
    }

    private void placeInvisibleBlockUnderPlayer(Player player) {
        deletePreviousBlocks();
        Location playerLocation = player.getLocation();
        double playerX = playerLocation.getX();
        double playerZ = playerLocation.getZ();

        int blockX = (int) Math.floor(playerX);
        int blockZ = (int) Math.floor(playerZ);

        boolean isCloseToEdgeX = Math.abs(playerX - blockX) >= 0.6 || Math.abs(playerX - blockX) <= 0.4;
        boolean isCloseToEdgeZ = Math.abs(playerZ - blockZ) >= 0.6 || Math.abs(playerZ - blockZ) <= 0.4;

        placeIfAir(player.getWorld().getBlockAt(blockX, playerLocation.getBlockY() - 1, blockZ));

        if (isCloseToEdgeX) {
            placeIfAir(player.getWorld().getBlockAt(blockX + (playerX >= blockX + 0.5 ? 1 : -1), playerLocation.getBlockY() - 1, blockZ));
        }
        if (isCloseToEdgeZ) {
            placeIfAir(player.getWorld().getBlockAt(blockX, playerLocation.getBlockY() - 1, blockZ + (playerZ >= blockZ + 0.5 ? 1 : -1)));
        }
        if (isCloseToEdgeX && isCloseToEdgeZ) {
            placeIfAir(player.getWorld().getBlockAt(blockX + (playerX >= blockX + 0.5 ? 1 : -1),
                    playerLocation.getBlockY() - 1, blockZ + (playerZ >= blockZ + 0.5 ? 1 : -1)));
        }
    }

    private void placeIfAir(Block block) {
        if (block.getType() == Material.AIR) {
            block.setType(Material.WHITE_STAINED_GLASS);
            previousBlocks.put(block.getLocation(), Material.AIR);
        }
    }

    private void deletePreviousBlocks() {
        for (Map.Entry<Location, Material> entry : previousBlocks.entrySet()) {
            entry.getKey().getBlock().setType(entry.getValue());
        }
        previousBlocks.clear();
    }
}
