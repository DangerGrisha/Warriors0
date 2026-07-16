package org.money.money.kits.ishigava;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.money.money.meta.ClassRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Перенос 1:1 из Last_Warriors (events/ishigava/KunaiBowShootListener) — кунай-крюк.
 * Лук «Kunai»: натянуть и выстрелить кунаем (стрела+верёвка из частиц). Воткнулась в блок —
 * ПКМ красным «Tether» подтягивает тебя; попала в игрока — зелёным подтягиваешь его. 15 блоков.
 * Адаптации под Warriors0: убрана само-регистрация в конструкторе (регистрирует Main);
 * AttributeModifier на новый API (NamespacedKey + EquipmentSlotGroup); Particle.REDSTONE → DUST;
 * KunaiGive.getItem() → makeKunaiBow().
 */
public class IshigavaKunaiBowListener implements Listener {

    private final Map<UUID, Location> tetherTargets = new HashMap<>();
    private final Map<UUID, Integer> clickCounter = new HashMap<>();
    private final Map<UUID, Integer> originalSlotMap = new HashMap<>();
    private final Map<UUID, Arrow> activeArrowMap = new HashMap<>();
    private final Map<UUID, ArmorStand> activeStandMap = new HashMap<>();
    private final Map<UUID, BukkitTask> activeTasks = new HashMap<>();
    private final Map<UUID, UUID> attachedPlayers = new HashMap<>();
    private final Map<UUID, Player> hookedPlayerMap = new HashMap<>();

    private final Plugin plugin;

    public IshigavaKunaiBowListener(Plugin plugin) {
        this.plugin = plugin;
        // ВНИМАНИЕ: регистрацию событий делает Main (в оригинале класс регистрировал сам себя).
    }

    /* ===================== Выдача ===================== */

    public ItemStack makeKunaiBow() {
        ItemStack bow = new ItemStack(Material.BOW, 1);
        ItemMeta meta = bow.getItemMeta();
        meta.displayName(Component.text("Kunai"));
        meta.setUnbreakable(true);

        // урон как у железного меча (6.0)
        AttributeModifier damage = new AttributeModifier(
                new NamespacedKey(plugin, "ishigava_kunai_damage"),
                ClassRegistry.num("ishigava", "kunai", "bowDamage", 6.0),
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND);
        meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, damage);

        // скорость атаки как у железного меча (1.6 = база 4.0 - 2.4)
        AttributeModifier speed = new AttributeModifier(
                new NamespacedKey(plugin, "ishigava_kunai_speed"),
                1.6 - 4.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND);
        meta.addAttributeModifier(Attribute.ATTACK_SPEED, speed);

        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        bow.setItemMeta(meta);
        return bow;
    }

    /* ===================== Выстрел ===================== */

    @EventHandler
    public void onKunaiBowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        double power = event.getForce();
        if (power < 0.1) return;

        ItemStack bow = event.getBow();
        if (!isKunaiBow(bow)) return;

        event.setCancelled(true);
        event.setConsumeItem(false);
        if (event.getProjectile() != null) {
            event.getProjectile().remove();
        }
        UUID uuid = player.getUniqueId();
        if (activeArrowMap.containsKey(uuid)) return;
        if (activeStandMap.containsKey(uuid)) return;

        int slot = player.getInventory().getHeldItemSlot();
        ItemStack tether = createTetherItemWithSwordAttributes(Material.RED_DYE);
        player.getInventory().setItem(slot, tether);

        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1.0f, 1.0f);

        Location start = player.getEyeLocation().add(player.getLocation().getDirection().normalize().multiply(1.0));
        double forceMultiplier = ClassRegistry.num("ishigava", "kunai", "forceMultiplier", 2.1);
        Vector velocity = player.getLocation().getDirection().normalize().multiply(power * forceMultiplier);

        Arrow arrow = player.getWorld().spawnArrow(start, velocity, (float) velocity.length(), 0f);
        arrow.setShooter(player);
        arrow.setPickupStatus(Arrow.PickupStatus.DISALLOWED);
        activeArrowMap.put(uuid, arrow);

        ArmorStand stand = player.getWorld().spawn(start, ArmorStand.class);

        String tag = "uuid:" + player.getUniqueId();
        arrow.addScoreboardTag("kunai_arrow");
        arrow.addScoreboardTag(tag);
        stand.addScoreboardTag(tag);

        activeStandMap.put(uuid, stand);
        originalSlotMap.put(uuid, slot);
        stand.setVisible(false);
        stand.setMarker(false);
        stand.setGravity(false);
        stand.setInvulnerable(true);
        stand.setSmall(true);
        stand.setSilent(true);
        stand.setCollidable(false);

        BukkitTask task = new BukkitRunnable() {
            boolean stuck = false;

            @Override
            public void run() {
                if (!arrow.isValid()) {
                    cleanup();
                    return;
                }

                if (arrow.isOnGround()) {
                    if (arrow.getLocation().distance(player.getLocation()) > ClassRegistry.num("ishigava", "kunai", "tetherRange", 15.0)) {
                        cleanup();
                        return;
                    }

                    Location stuckPoint = arrow.getLocation().clone().add(0, 0.2, 0);
                    if (!stuck) {
                        stuck = true;
                        tetherTargets.put(uuid, stuckPoint.clone());
                    }
                    stand.teleport(stuckPoint);
                    drawRope(player, stuckPoint);
                    return;
                }

                Vector dir = arrow.getVelocity().normalize();
                Location followLoc = arrow.getLocation().subtract(dir.multiply(0.5)).add(0, 0.1, 0);
                stand.teleport(followLoc);
                drawRope(player, followLoc);
            }

            private void cleanup() {
                arrow.remove();
                stand.remove();

                int slot = originalSlotMap.getOrDefault(uuid, -1);
                if (slot >= 0) {
                    player.getInventory().setItem(slot, makeKunaiBow());
                }

                activeArrowMap.remove(uuid);
                activeStandMap.remove(uuid);
                originalSlotMap.remove(uuid);
                tetherTargets.remove(uuid);
                clickCounter.remove(uuid);
                attachedPlayers.remove(uuid);
                hookedPlayerMap.remove(uuid);
                activeTasks.remove(uuid);
                cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);

        activeTasks.put(uuid, task);
    }

    private void drawRope(Player player, Location to) {
        if (to == null || to.getWorld() != player.getWorld()) return;

        Location start = player.getLocation().clone().add(0, 1.0, 0);
        Vector diff = to.toVector().subtract(start.toVector());
        double length = diff.length();
        if (length < 0.1 || length > 40) return;

        Vector step = diff.normalize().multiply(0.4);
        int points = (int) (length / 0.4);
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(101, 67, 33), 0.8f);

        Location point = start.clone();
        for (int i = 0; i <= points; i++) {
            player.getWorld().spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, dust);
            point.add(step);
        }
    }

    private boolean hasDisplayName(ItemStack item, String name) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return false;
        Component displayName = meta.displayName();
        return displayName != null
                && PlainTextComponentSerializer.plainText().serialize(displayName).equals(name);
    }

    private boolean isKunaiBow(ItemStack item) {
        return item != null && item.getType() == Material.BOW && hasDisplayName(item, "Kunai");
    }

    private boolean isTether(ItemStack item) {
        return hasDisplayName(item, "Tether");
    }

    @EventHandler
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (!isTether(item)) return;

        UUID uuid = player.getUniqueId();

        int clicks = clickCounter.getOrDefault(uuid, 0);
        if (clicks >= 15) return;
        clickCounter.put(uuid, clicks + 1);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Integer current = clickCounter.get(uuid);
            if (current != null && current > 0) {
                clickCounter.put(uuid, current - 1);
            }
        }, 20L);

        Material type = item.getType();

        if (type == Material.RED_DYE) {
            if (!tetherTargets.containsKey(uuid)) return;
            Location target = tetherTargets.get(uuid).clone();

            Vector direction = target.toVector().subtract(player.getLocation().toVector()).normalize();
            Vector pull = direction.multiply(ClassRegistry.num("ishigava", "kunai", "pullSelfSpeed", 1.1 / 3.0));

            player.setVelocity(pull);
        } else if (type == Material.LIME_DYE) {
            Player target = hookedPlayerMap.get(uuid);
            if (target == null || !target.isOnline() || target.isDead()) return;

            Location self = player.getLocation();
            boolean targetInAir = !target.isOnGround();

            double speed = targetInAir
                    ? ClassRegistry.num("ishigava", "kunai", "pullTargetAirSpeed", 0.8)
                    : ClassRegistry.num("ishigava", "kunai", "pullTargetGroundSpeed", 0.4);
            Vector direction = self.toVector().subtract(target.getLocation().toVector()).normalize();
            Vector pull = direction.multiply(speed);

            target.setVelocity(pull);
        }
    }

    @EventHandler
    public void onSlotChange(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!originalSlotMap.containsKey(uuid)) return;

        int originalSlot = originalSlotMap.get(uuid);
        int newSlot = event.getNewSlot();

        updateOffhandKunai(player);

        if (newSlot != originalSlot) {
            player.getInventory().setItem(originalSlot, makeKunaiBow());

            String tag = "uuid:" + uuid;
            for (Entity entity : player.getWorld().getEntities()) {
                if (!(entity instanceof Arrow || entity instanceof ArmorStand)) continue;
                if (entity.getScoreboardTags().contains(tag)) {
                    entity.remove();
                }
            }

            Arrow arrow = activeArrowMap.remove(uuid);
            if (arrow != null && arrow.isValid()) arrow.remove();

            BukkitTask task = activeTasks.remove(uuid);
            if (task != null) task.cancel();

            originalSlotMap.remove(uuid);
            tetherTargets.remove(uuid);
            clickCounter.remove(uuid);
            attachedPlayers.remove(uuid);
            hookedPlayerMap.remove(uuid);
            activeStandMap.remove(uuid);
        }
    }

    @EventHandler
    public void onArrowHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Arrow arrow)) return;
        if (!(arrow.getShooter() instanceof Player shooter)) return;
        if (!(event.getHitEntity() instanceof Player target)) return;

        UUID shooterUUID = shooter.getUniqueId();
        String tag = "uuid:" + shooterUUID;

        if (!arrow.getScoreboardTags().contains("kunai_arrow")) return;
        if (!arrow.getScoreboardTags().contains(tag)) return;

        for (Entity e : target.getWorld().getNearbyEntities(target.getLocation(), 1, 2, 1)) {
            if (e instanceof Arrow otherArrow && e != arrow) {
                if (otherArrow.getScoreboardTags().contains("kunai_arrow")
                        && otherArrow.getScoreboardTags().contains(tag)) {
                    otherArrow.remove();
                }
            }
        }

        hookedPlayerMap.put(shooterUUID, target);
        attachedPlayers.put(shooterUUID, target.getUniqueId());
        tetherTargets.put(shooterUUID, target.getLocation().clone().add(0, 0.2, 0));

        double[] initialHealth = {target.getHealth()};
        double[] thresholdHealth = {Math.max(initialHealth[0] - 10.0, 0)};

        int slot = originalSlotMap.getOrDefault(shooterUUID, shooter.getInventory().getHeldItemSlot());
        shooter.getInventory().setItem(slot, createTetherItemWithSwordAttributes(Material.LIME_DYE));

        ArmorStand stand = activeStandMap.get(shooterUUID);
        if (stand == null) return;

        BukkitTask oldTask = activeTasks.get(shooterUUID);
        if (oldTask != null) {
            oldTask.cancel();
            activeTasks.remove(shooterUUID);
        }

        Arrow oldArrow = activeArrowMap.remove(shooterUUID);
        if (oldArrow != null && oldArrow.isValid()) {
            oldArrow.remove();
        }

        BukkitTask newTask = new BukkitRunnable() {
            int airTicks = 0;

            @Override
            public void run() {
                if (!target.isOnline() || target.getGameMode() == GameMode.SPECTATOR ||
                        !shooter.isOnline() || shooter.getGameMode() == GameMode.SPECTATOR) {
                    cleanupAndCancel();
                    shooter.getInventory().setItem(slot, makeKunaiBow());
                    return;
                }
                double currentHealth = target.getHealth();
                if (currentHealth > initialHealth[0]) {
                    initialHealth[0] = currentHealth;
                    thresholdHealth[0] = Math.min(currentHealth - 10.0, 20.0);
                }

                if (currentHealth <= thresholdHealth[0]) {
                    shooter.sendMessage(Component.text("Target broke the tether by losing too much health."));
                    shooter.getInventory().setItem(slot, makeKunaiBow());
                    cleanupAndCancel();
                    return;
                }

                if (shooter.getLocation().distance(target.getLocation()) > ClassRegistry.num("ishigava", "kunai", "tetherRange", 15.0)) {
                    cleanupAndCancel();
                    shooter.getInventory().setItem(slot, makeKunaiBow());
                    return;
                }

                if (!target.isOnGround()) {
                    airTicks++;
                    if (airTicks >= 60) {
                        cleanupAndCancel();
                        shooter.getInventory().setItem(slot, makeKunaiBow());
                        return;
                    }
                } else {
                    airTicks = 0;
                }

                int currentSlot = shooter.getInventory().getHeldItemSlot();
                int expectedSlot = originalSlotMap.getOrDefault(shooterUUID, -1);
                if (currentSlot != expectedSlot) {
                    cleanupAndCancel();
                    shooter.getInventory().setItem(slot, makeKunaiBow());
                    return;
                }

                Location follow = target.getLocation().clone().add(0, 0.2, 0);
                tetherTargets.put(shooterUUID, follow.clone());

                stand.teleport(follow);
                drawRope(shooter, target.getLocation().clone().add(0, 1.0, 0));

                Material dyeMaterial = shooter.isSneaking() ? Material.LIME_DYE : Material.RED_DYE;
                ItemStack current = shooter.getInventory().getItem(expectedSlot);
                if (current == null || current.getType() != dyeMaterial || !current.hasItemMeta()) {
                    shooter.getInventory().setItem(expectedSlot, createTetherItemWithSwordAttributes(dyeMaterial));
                }
            }

            private void cleanupAndCancel() {
                stand.remove();

                originalSlotMap.remove(shooterUUID);
                tetherTargets.remove(shooterUUID);
                clickCounter.remove(shooterUUID);
                attachedPlayers.remove(shooterUUID);
                hookedPlayerMap.remove(shooterUUID);
                activeStandMap.remove(shooterUUID);
                activeTasks.remove(shooterUUID);
                target.damage(ClassRegistry.num("ishigava", "kunai", "releaseDamage", 4.0));
                cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);

        activeTasks.put(shooterUUID, newTask);
    }

    private void updateOffhandKunai(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();

        if (isKunaiBow(main)) {
            if (off.getType() == Material.AIR) {
                ItemStack sword = new ItemStack(Material.WOODEN_SWORD);
                ItemMeta meta = sword.getItemMeta();
                if (meta != null) {
                    meta.displayName(Component.text("Kunai"));
                    sword.setItemMeta(meta);
                }
                player.getInventory().setItemInOffHand(sword);
            }
            if (!hasKunaiArrow(player)) {
                player.getInventory().addItem(createKunaiArrow());
            }
        } else {
            if (off.getType() == Material.WOODEN_SWORD) {
                ItemMeta meta = off.getItemMeta();
                if (meta != null && Component.text("Kunai").equals(meta.displayName())) {
                    player.getInventory().setItemInOffHand(null);
                }
            }
            removeKunaiArrows(player);
        }
    }

    private ItemStack createKunaiArrow() {
        ItemStack arrow = new ItemStack(Material.ARROW, 1);
        ItemMeta meta = arrow.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Kunai"));
            arrow.setItemMeta(meta);
        }
        return arrow;
    }

    private boolean hasKunaiArrow(Player player) {
        for (ItemStack it : player.getInventory().getContents()) {
            if (it != null && it.getType() == Material.ARROW && hasDisplayName(it, "Kunai")) {
                return true;
            }
        }
        return false;
    }

    private void removeKunaiArrows(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it != null && it.getType() == Material.ARROW && hasDisplayName(it, "Kunai")) {
                player.getInventory().setItem(i, null);
            }
        }
    }

    @EventHandler
    public void onItemSwitch(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> updateOffhandKunai(player), 1L);
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        ItemStack main = event.getMainHandItem();
        ItemStack off = event.getOffHandItem();
        if (hasDisplayName(main, "Kunai") || hasDisplayName(off, "Kunai")) {
            event.setCancelled(true);
        }
    }

    public ItemStack createTetherItemWithSwordAttributes(Material dyeColor) {
        ItemStack dye = new ItemStack(dyeColor);
        ItemMeta meta = dye.getItemMeta();

        if (meta != null) {
            meta.displayName(Component.text("Tether"));

            AttributeModifier damageModifier = new AttributeModifier(
                    new NamespacedKey(plugin, "ishigava_tether_damage"),
                    ClassRegistry.num("ishigava", "kunai", "tetherDamage", 5.0),
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND);
            meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, damageModifier);

            AttributeModifier speedModifier = new AttributeModifier(
                    new NamespacedKey(plugin, "ishigava_tether_speed"),
                    -2.4, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND);
            meta.addAttributeModifier(Attribute.ATTACK_SPEED, speedModifier);

            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            dye.setItemMeta(meta);
        }
        return dye;
    }
}
