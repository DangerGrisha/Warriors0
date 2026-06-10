package org.money.money.kits.fukuko;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;

import java.util.List;

/**
 * Fukuko — BombZone (ульта).
 * Перенос 1:1 из Last_Warriors (BombZoneListener), стиль Warriors0 (PDC вместо display-name).
 * ПКМ ставит REDSTONE_BLOCK-зону (радиус 10): по границам идут частицы, каждые 2.5с на
 * врагов внутри 20×20 падают фаерболы; зона исчезает через 20с.
 */
public final class FukukoBombZoneListener implements Listener {

    private final Plugin plugin;
    private final NamespacedKey KEY_BOMBZONE;

    private static final Material ZONE_MATERIAL = Material.REDSTONE_BLOCK;
    private static final int RADIUS = 10;

    public FukukoBombZoneListener(Plugin plugin) {
        this.plugin = plugin;
        this.KEY_BOMBZONE = new NamespacedKey(plugin, "fukuko_bombzone");
    }

    /* ===================== Выдача ===================== */

    public ItemStack makeBombZoneBlock() {
        ItemStack it = new ItemStack(ZONE_MATERIAL, 1);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName("§cBombZone");
        meta.setUnbreakable(true);
        meta.setLore(List.of("§7something"));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
        meta.getPersistentDataContainer().set(KEY_BOMBZONE, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    private boolean isBombZone(ItemStack it) {
        return it != null && it.getType() == ZONE_MATERIAL
                && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(KEY_BOMBZONE, PersistentDataType.BYTE);
    }

    /* ===================== Активация ===================== */

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        final Player player = event.getPlayer();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!isBombZone(player.getInventory().getItemInMainHand())) return;
        if (event.getClickedBlock() == null) return;

        final String playerTeam = getTeam(player);
        // центр = там, где ванилла поставит REDSTONE_BLOCK
        Location bombZoneLocation = event.getClickedBlock().getLocation().add(0, 1, 0);

        createParticleSquare(bombZoneLocation, RADIUS);
        createCycleOfCheckPlayers(bombZoneLocation, RADIUS, playerTeam);
        removeBombZoneAfterDelay(bombZoneLocation, 20);
    }

    private void removeBombZoneAfterDelay(Location location, int seconds) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (location.getBlock().getType() == ZONE_MATERIAL) {
                    location.getBlock().setType(Material.AIR);
                }
            }
        }.runTaskLater(plugin, seconds * 20L);
    }

    private void createCycleOfCheckPlayers(Location center, int radius, String ownerTeam) {
        new BukkitRunnable() {
            @Override
            public void run() {
                World world = center.getWorld();
                if (world == null) { cancel(); return; }
                if (center.getBlock().getType() != ZONE_MATERIAL) { cancel(); return; }

                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getWorld() != world) continue;

                    Location playerLoc = player.getLocation();
                    double dx = Math.abs(playerLoc.getX() - center.getX());
                    double dz = Math.abs(playerLoc.getZ() - center.getZ());

                    if (dx <= radius && dz <= radius) {
                        if (!ownerTeam.equals(getTeam(player))) {
                            Location fireballLoc = playerLoc.clone().add(0, 10, 0);
                            Fireball fireball = (Fireball) world.spawnEntity(fireballLoc, EntityType.FIREBALL);
                            fireball.setDirection(new Vector(0, -1, 0));
                            fireball.setVelocity(new Vector(0, -1, 0));
                            fireball.setShooter(null);
                            fireball.setIsIncendiary(false);
                            fireball.setYield(0);
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 50L); // каждые 2.5с
    }

    private void createParticleSquare(Location center, int radius) {
        World world = center.getWorld();
        if (world == null) return;

        new BukkitRunnable() {
            @Override
            public void run() {
                if (center.getBlock().getType() != ZONE_MATERIAL) { cancel(); return; }
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (x == -radius || x == radius || z == -radius || z == radius) {
                            Location particleLocation = center.clone().add(x, 10, z);
                            world.spawnParticle(Particle.FALLING_DUST, particleLocation, 5,
                                    Material.RED_SAND.createBlockData());
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    /* ===================== Команды ===================== */

    private String getTeam(Player player) {
        Team playerTeam = getPlayerTeam(player);
        return playerTeam != null ? playerTeam.getName() : "DEFAULT";
    }

    private Team getPlayerTeam(Player player) {
        Scoreboard scoreboard = player.getScoreboard();
        for (Team team : scoreboard.getTeams()) {
            if (team.hasEntry(player.getName())) return team;
        }
        return null;
    }
}
