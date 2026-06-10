package org.money.money.kits.fukuko;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
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
 * Fukuko — Mortira (мортира-турель).
 * Перенос 1:1 из Last_Warriors (MortiraListener), стиль Warriors0 (PDC вместо display-name).
 * ПКМ по блоку — ставит турель из GOLD_BLOCK; каждые 5с стреляет навесным снарядом
 * по ближайшему врагу в радиусе 30, взрыв 4.0 / урон 10.0.
 */
public final class FukukoMortiraListener implements Listener {

    private final Plugin plugin;
    private final NamespacedKey KEY_MORTIRA;

    private static final Material MORTIRA_MATERIAL = Material.GOLD_BLOCK;
    private static final int RADIUS = 30;

    public FukukoMortiraListener(Plugin plugin) {
        this.plugin = plugin;
        this.KEY_MORTIRA = new NamespacedKey(plugin, "fukuko_mortira");
    }

    /* ===================== Выдача ===================== */

    public ItemStack makeMortiraBlock() {
        ItemStack it = new ItemStack(MORTIRA_MATERIAL, 1);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName("§eMortira");
        meta.setUnbreakable(true);
        meta.setLore(List.of("§7something"));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
        meta.getPersistentDataContainer().set(KEY_MORTIRA, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    private boolean isMortira(ItemStack it) {
        return it != null && it.getType() == MORTIRA_MATERIAL
                && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(KEY_MORTIRA, PersistentDataType.BYTE);
    }

    /* ===================== Установка ===================== */

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        final Player player = event.getPlayer();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!isMortira(player.getInventory().getItemInMainHand())) return;
        if (event.getClickedBlock() == null) return;

        final String playerTeam = getTeam(player);
        Location mortiraBlockLocation = event.getClickedBlock().getLocation().add(0, 1, 0);

        placeMortira(player, mortiraBlockLocation);
        turnOnCycle(mortiraBlockLocation, playerTeam);
    }

    private void turnOnCycle(Location mortiraLocation, String ownerTeam) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (mortiraLocation.getBlock().getType() != MORTIRA_MATERIAL) {
                    cancel();
                    return;
                }
                Player target = findTarget(mortiraLocation, RADIUS, ownerTeam);
                if (target != null) {
                    Location spawnLocation = mortiraLocation.clone().add(0, 1, 0);
                    ArmorStand projectile = spawnProjectile(spawnLocation);
                    launchProjectile(projectile, target.getLocation());
                }
            }
        }.runTaskTimer(plugin, 0L, 100L); // каждые 5с
    }

    private Player findTarget(Location location, int radius, String ownerTeam) {
        for (Entity entity : location.getWorld().getNearbyEntities(location, radius, radius, radius)) {
            if (entity instanceof Player p) {
                if (!getTeam(p).equals(ownerTeam)) return p;
            }
        }
        return null;
    }

    private ArmorStand spawnProjectile(Location location) {
        ArmorStand projectile = location.getWorld().spawn(location.clone().add(0, 1, 0), ArmorStand.class);
        projectile.setVisible(true);
        projectile.setGravity(false);
        projectile.setSmall(true);
        projectile.setCustomName("projectile");
        projectile.setCustomNameVisible(false);
        projectile.setInvulnerable(true);
        return projectile;
    }

    private void launchProjectile(ArmorStand projectile, Location targetLocation) {
        Location start = projectile.getLocation();
        Vector direction = targetLocation.toVector().subtract(start.toVector()).normalize();

        double distance = start.distance(targetLocation);
        double speed = Math.min(1.5, distance / 80);

        direction.multiply(speed);
        direction.setY(1.5);

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (ticks > 100 || projectile.isDead()) {
                    projectile.remove();
                    cancel();
                    return;
                }
                Location currentLocation = projectile.getLocation();
                Block blockBelow = currentLocation.clone().add(0, -1, 0).getBlock();
                if (blockBelow.getType() != Material.AIR && blockBelow.getType() != MORTIRA_MATERIAL) {
                    explodeProjectile(projectile);
                    cancel();
                    return;
                }
                projectile.teleport(currentLocation.add(direction));
                direction.setY(direction.getY() - 0.05);
                ticks++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void explodeProjectile(ArmorStand projectile) {
        Location explosionLocation = projectile.getLocation();
        projectile.getWorld().spawnParticle(Particle.EXPLOSION, explosionLocation, 1);
        projectile.getWorld().playSound(explosionLocation, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f);

        double explosionRadius = 4.0;
        for (Entity entity : projectile.getWorld().getNearbyEntities(explosionLocation, explosionRadius, explosionRadius, explosionRadius)) {
            if (entity instanceof LivingEntity le) {
                le.damage(10.0);
            }
        }
        projectile.remove();
    }

    private void placeMortira(Player player, Location blockLocation) {
        if (!isNearPlayersOrArmorStands(blockLocation, player, 2)) {
            summonTurret(blockLocation);
            placeBlockBack(blockLocation.getBlock());
        } else {
            player.sendRawMessage("Can't place here");
        }
    }

    private void placeBlockBack(Block block) {
        block.setType(Material.AIR);
        new BukkitRunnable() {
            @Override public void run() { block.setType(MORTIRA_MATERIAL); }
        }.runTaskLater(plugin, 0L);
    }

    private ArmorStand summonTurret(Location location) {
        ArmorStand armorStand = location.getWorld().spawn(location.clone().add(0.5, 0, 0.5), ArmorStand.class);
        armorStand.setVisible(true);
        armorStand.setGravity(false);
        armorStand.setCanPickupItems(false);
        armorStand.setBasePlate(false);
        armorStand.setInvulnerable(true);
        armorStand.setCustomName("Mortira");
        armorStand.setCustomNameVisible(false);
        armorStand.setArms(true);
        armorStand.setSmall(false);

        ItemStack arm = new ItemStack(Material.BLUE_DYE);
        ItemMeta armMeta = arm.getItemMeta();
        armMeta.setDisplayName("MortiraArm");
        arm.setItemMeta(armMeta);
        armorStand.getEquipment().setItemInMainHand(arm);
        return armorStand;
    }

    private boolean isNearPlayersOrArmorStands(Location location, Player placer, int radius) {
        for (Entity entity : location.getWorld().getNearbyEntities(location, radius, radius, radius)) {
            if (entity instanceof Player && !entity.equals(placer)) return true;
            if (entity instanceof ArmorStand) return true;
        }
        return false;
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
