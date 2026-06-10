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
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * Перенос 1:1 из Last_Warriors (events/saske/SwordSaskeListener) — катана.
 * ПКМ: рывок назад, звук, урон 5 по области перед игроком (3 клетки), отбрасывание,
 * частицы взмаха. Кулдаун 6с. Детект по display-name «Saske_katana».
 */
public class SaskeSwordListener implements Listener {

    private static final String KATANA_NAME = "Saske_katana";
    private static final Material KATANA_MATERIAL = Material.IRON_SWORD;

    private final Plugin plugin;
    private final HashMap<UUID, Long> cooldownMap = new HashMap<>();

    public SaskeSwordListener(Plugin plugin) {
        this.plugin = plugin;
    }

    /* ===================== Выдача ===================== */

    public ItemStack makeKatana() {
        ItemStack sword = new ItemStack(KATANA_MATERIAL, 1);
        ItemMeta meta = sword.getItemMeta();
        meta.displayName(Component.text(KATANA_NAME));
        meta.setUnbreakable(true);
        meta.setLore(List.of("something"));
        sword.setItemMeta(meta);
        return sword;
    }

    /* ===================== Логика ===================== */

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (isSwordEvent(event, player)) {
            UUID uuid = player.getUniqueId();
            long currentTime = System.currentTimeMillis();

            if (cooldownMap.containsKey(uuid)) {
                long timePassed = currentTime - cooldownMap.get(uuid);
                long cooldown = 6000;
                if (timePassed < cooldown) {
                    return;
                }
            }

            cooldownMap.put(uuid, currentTime);
            executeSwordAbility(player);
            startCooldownTimer(player, 6);
        }
    }

    private void startCooldownTimer(Player player, int seconds) {
        new BukkitRunnable() {
            int timeLeft = seconds;
            @Override
            public void run() {
                if (!player.isOnline()) { cancel(); return; }
                if (timeLeft <= 0) {
                    player.sendActionBar(Component.text("Ready"));
                    cancel();
                    return;
                }
                player.sendActionBar(Component.text(timeLeft + " sec"));
                timeLeft--;
            }
        }.runTaskTimer(plugin, 0, 20);
    }

    private void executeSwordAbility(Player player) {
        pushPlayerBack(player);
        playAudio(player);
        dealAreaDamage(player);
    }

    private boolean isSwordEvent(PlayerInteractEvent event, Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        return (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)
                && hand.getType() == KATANA_MATERIAL
                && hand.hasItemMeta() && hand.getItemMeta().hasDisplayName()
                && hand.getItemMeta().getDisplayName().equals(KATANA_NAME);
    }

    private void pushPlayerBack(Player player) {
        Vector direction = player.getLocation().getDirection().multiply(-0.8);
        player.setVelocity(player.getVelocity().add(direction));
    }

    private void playAudio(Player player) {
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.0f);
    }

    private void dealAreaDamage(Player player) {
        World world = player.getWorld();
        Vector direction = player.getEyeLocation().getDirection().normalize();
        Vector sideDirection = new Vector(-direction.getZ(), 0, direction.getX()).normalize();
        Location frontBlock = player.getEyeLocation().add(direction);

        applyDamageAndPushEntities(world, frontBlock, player);
        applyDamageAndPushEntities(world, frontBlock.clone().add(sideDirection), player);
        applyDamageAndPushEntities(world, frontBlock.clone().subtract(sideDirection.multiply(1.5)), player);
        spawnSwordCutParticles(world, frontBlock, direction, player);
    }

    private void applyDamageAndPushEntities(World world, Location location, Player player) {
        double damageRadius = 1.0;
        Vector pushDirection = player.getLocation().getDirection().normalize().multiply(1.5);

        Collection<Entity> entities = world.getNearbyEntities(location, damageRadius, damageRadius, damageRadius);
        for (Entity entity : entities) {
            if (entity instanceof LivingEntity livingEntity && !entity.equals(player)) {
                livingEntity.damage(5.0);
                livingEntity.setVelocity(pushDirection);
                world.spawnParticle(Particle.CRIT, livingEntity.getLocation(), 5, 0.3, 0.3, 0.3, 0.05);
            }
        }
    }

    private void spawnSwordCutParticles(World world, Location startLocation, Vector direction, Player player) {
        world.spawnParticle(Particle.SWEEP_ATTACK, startLocation.add(0.1, 0.1, 0.1), 1);
        Vector sideDirection = new Vector(-direction.getZ(), 0, direction.getX()).normalize();
        double yOffset = -0.2;
        double lineLength = 6;
        int particleCount = 30;

        spawnParticleLine(world, startLocation, sideDirection, direction, yOffset, lineLength - 2, particleCount);
        spawnParticleLineSweepAttack(world, startLocation, sideDirection, direction, yOffset, lineLength - 2, particleCount - 27);
        Location oneBlockFurther = startLocation.clone().add(direction);
        spawnParticleLine(world, oneBlockFurther, sideDirection, direction, yOffset, lineLength, particleCount);
        spawnParticleLineSweepAttack(world, oneBlockFurther, sideDirection, direction, yOffset, lineLength, particleCount - 24);
    }

    private void spawnParticleLine(World world, Location startLocation, Vector sideDirection, Vector direction, double yOffset, double lineLength, int particleCount) {
        for (int i = 0; i < particleCount; i++) {
            double progress = (double) i / particleCount;
            Vector offset = sideDirection.clone().multiply(progress * lineLength - (lineLength / 2));
            Location particleLocation = startLocation.clone().add(offset).add(0, yOffset, 0);
            world.spawnParticle(Particle.NAUTILUS, particleLocation, 1, 0.3, 0.1, 0.3, 0);
        }
    }

    private void spawnParticleLineSweepAttack(World world, Location startLocation, Vector sideDirection, Vector direction, double yOffset, double lineLength, int particleCount) {
        for (int i = 0; i < particleCount; i++) {
            double progress = (double) i / particleCount;
            Vector offset = sideDirection.clone().multiply(progress * lineLength - (lineLength / 2));
            Location particleLocation = startLocation.clone().add(offset).add(0, yOffset, 0);
            world.spawnParticle(Particle.SWEEP_ATTACK, particleLocation, 1, 0.3, 0.1, 0.3, 0);
        }
    }
}
