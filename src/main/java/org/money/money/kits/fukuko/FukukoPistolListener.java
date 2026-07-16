package org.money.money.kits.fukuko;

import org.bukkit.*;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.money.money.meta.ClassRegistry;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Fukuko — пистолет.
 * Перенос 1:1 из Last_Warriors (PistolListener), но в стиле Warriors0:
 *  - предмет помечается через PDC (а не по display-name);
 *  - кулдаун/дебаунс — per-UUID (в оригинале были общие static-флаги на всех игроков).
 * ЛКМ — выстрел невидимой пулей (ArmorStand), урон 1.0, кулдаун 5с.
 */
public final class FukukoPistolListener implements Listener {

    private final Plugin plugin;
    private final NamespacedKey KEY_PISTOL;

    // числа 1:1 из FukukoConstants
    private static double DAMAGE_OF_BULLET() { return ClassRegistry.num("fukuko", "pistol", "bulletDamage", 1.0); }
    private static long REMOVE_BULLET_AFTER() { return ClassRegistry.numInt("fukuko", "pistol", "bulletLifetimeTicks", 22); } // время жизни пули

    // состояние per-игрок
    private final Set<UUID> onCooldown = new HashSet<>();
    private final Set<UUID> firing     = new HashSet<>(); // дебаунс 2 тика

    public FukukoPistolListener(Plugin plugin) {
        this.plugin = plugin;
        this.KEY_PISTOL = new NamespacedKey(plugin, "fukuko_pistol");
    }

    /* ===================== Выдача предмета ===================== */

    /** Заряженный арбалет «pistol» с PDC-меткой. Дай из /kitgive Fukuko pistol. */
    public ItemStack makePistol() {
        ItemStack crossbow = new ItemStack(Material.CROSSBOW, 1);
        CrossbowMeta meta = (CrossbowMeta) crossbow.getItemMeta();
        meta.setDisplayName("§fpistol");
        meta.setUnbreakable(true);
        meta.setLore(List.of("§7A sniper's best friend"));
        meta.addChargedProjectile(new ItemStack(Material.ARROW)); // выглядит заряженным
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
        meta.getPersistentDataContainer().set(KEY_PISTOL, PersistentDataType.BYTE, (byte) 1);
        crossbow.setItemMeta(meta);
        return crossbow;
    }

    private boolean isPistol(ItemStack it) {
        return it != null && it.getType() == Material.CROSSBOW
                && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(KEY_PISTOL, PersistentDataType.BYTE);
    }

    /* ===================== Активация ===================== */

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isPistol(hand)) return;

        Action a = event.getAction();

        // ПКМ с пистолетом — гасим ванильную логику арбалета (не стреляем стрелой)
        if (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) {
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            event.setCancelled(true);
            return;
        }

        // ЛКМ — выстрел
        if (a != Action.LEFT_CLICK_AIR && a != Action.LEFT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        UUID id = player.getUniqueId();
        if (onCooldown.contains(id) || firing.contains(id)) return;

        // дебаунс 2 тика (как isInteracted в оригинале)
        firing.add(id);
        new BukkitRunnable() {
            @Override public void run() { firing.remove(id); }
        }.runTaskLater(plugin, 2L);

        shootSystem(player);

        // кулдаун 100 тиков
        long cooldownTicks = ClassRegistry.ticks("fukuko", "pistol", 100);
        onCooldown.add(id);
        new BukkitRunnable() {
            @Override public void run() { onCooldown.remove(id); }
        }.runTaskLater(plugin, cooldownTicks);
    }

    /* ===================== Стрельба (1:1) ===================== */

    private void shootSystem(Player player) {
        Location eyeLocation = player.getEyeLocation();
        Vector direction = eyeLocation.getDirection();
        ArmorStand armorStand = summonBullet(player, eyeLocation, direction);
        shoot(player, armorStand, direction);
    }

    private void shoot(Player player, ArmorStand armorStand, Vector direction) {
        // удалить пулю через REMOVE_BULLET_AFTER() тиков
        Bukkit.getScheduler().runTaskLater(plugin, armorStand::remove, REMOVE_BULLET_AFTER());

        Vector finalDirection = direction.clone();
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!armorStand.isDead()) {
                checkBulletCollision(armorStand, player);
                // лёгкая компенсация гравитации
                finalDirection.setY(finalDirection.getY() + 0.006);
                armorStand.setVelocity(finalDirection.clone().normalize().multiply(ClassRegistry.num("fukuko", "pistol", "bulletSpeed", 1.0)));
                checkBulletCollision(armorStand, player);
            }
        }, 0L, 2L);
    }

    private void checkBulletCollision(ArmorStand bullet, Player shooter) {
        if (bullet.isDead()) return;
        Location bulletLocation = bullet.getLocation().clone().add(0, -0.4, 0);

        for (Player nearbyPlayer : Bukkit.getOnlinePlayers()) {
            if (nearbyPlayer.equals(shooter)) continue;
            if (nearbyPlayer.getLocation().distance(bulletLocation) <= ClassRegistry.num("fukuko", "pistol", "bulletHitRadius", 1.0)) {
                if (!nearbyPlayer.isInvulnerable()) {
                    nearbyPlayer.damage(DAMAGE_OF_BULLET());
                    nearbyPlayer.getWorld().playSound(nearbyPlayer.getLocation(),
                            Sound.ENTITY_ARROW_HIT_PLAYER, 1.0f, 1.0f);
                }
                bullet.remove();
                return;
            }
        }
    }

    private static ArmorStand summonBullet(Player player, Location eyeLocation, Vector direction) {
        ArmorStand armorStand = player.getWorld()
                .spawn(eyeLocation.clone().add(direction).add(0, -0.5, 0), ArmorStand.class);
        armorStand.setVisible(false);
        armorStand.setGravity(true);
        armorStand.setSmall(true);

        ItemStack bullet = new ItemStack(Material.RED_DYE);
        var bulletMeta = bullet.getItemMeta();
        bulletMeta.setDisplayName("Bullet");
        bullet.setItemMeta(bulletMeta);
        armorStand.getEquipment().setItemInMainHand(bullet);

        armorStand.addScoreboardTag("bullet");
        return armorStand;
    }
}
