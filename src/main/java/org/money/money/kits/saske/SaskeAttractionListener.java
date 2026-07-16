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
import org.money.money.util.ItemModels;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
            startChargeUp(target, player);
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

    /**
     * Двухсекундный «заряд/телеграф» перед притяжением: чёрная энергия стягивается в точку +
     * жуткие звуки. По истечении — спавним арморстенд с шипами и запускаем зону притяжения
     * (как раньше). Даёт врагам время уйти, поэтому способность больше не «чересчур сильная».
     */
    private void startChargeUp(Block target, Player placer) {
        final Location center = target.getLocation().add(0.5, 0.5, 0.5); // центр ячейки (клон, не мутируем target-loc)
        final World w = center.getWorld();
        final int chargeTicks = org.money.money.meta.ClassRegistry.numInt("saske", "attraction", "chargeUpTicks", 40);

        // жуткий старт зарядки
        w.playSound(center, Sound.BLOCK_PORTAL_TRIGGER, 0.8f, 0.35f);
        w.playSound(center, Sound.ENTITY_WARDEN_HEARTBEAT, 1.3f, 0.6f);

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                t++;
                chargeParticles(center, t);

                if (t % 8 == 0) {
                    float pitch = 0.5f + 0.8f * ((float) t / chargeTicks); // нарастающее «сердцебиение»
                    w.playSound(center, Sound.ENTITY_WARDEN_HEARTBEAT, 1.3f, pitch);
                }
                if (t == chargeTicks / 2) {
                    w.playSound(center, Sound.AMBIENT_CAVE, 1.6f, 0.5f);
                }

                if (t >= chargeTicks) {
                    cancel();
                    if (!KitSession.isInGame(placer)) return; // игра кончилась за время зарядки

                    // «выпуск»: жуткий бум + шипы + запуск притяжения ровно как раньше
                    w.playSound(center, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.0f, 1.0f);
                    w.playSound(center, Sound.ENTITY_WITHER_SPAWN, 0.5f, 1.5f);
                    Location legacy = target.getLocation(); // угол блока (как в оригинале)
                    replaceBlockWithArmorStand(legacy);     // мутирует legacy -> центр X/Z, спавнит шипы
                    startAttraction(legacy, placer);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Частицы зарядки: «чёрная энергия» стягивается внутрь + тёмное сжимающееся кольцо и ядро. */
    private void chargeParticles(Location center, int t) {
        final World w = center.getWorld();
        final Particle.DustOptions black = new Particle.DustOptions(Color.fromRGB(12, 12, 16), 1.7f);

        // потоки энергии внутрь: спавним частицы на сфере и толкаем к центру (count=0 => offset — это скорость)
        for (int i = 0; i < 6; i++) {
            double ang = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
            double phi = ThreadLocalRandom.current().nextDouble(-0.6, 1.0);
            double r = 3.2 * ThreadLocalRandom.current().nextDouble(0.55, 1.0);
            Location from = center.clone().add(Math.cos(ang) * Math.cos(phi) * r, Math.sin(phi) * r, Math.sin(ang) * Math.cos(phi) * r);
            Vector inward = center.toVector().subtract(from.toVector()).normalize().multiply(0.4);
            w.spawnParticle(Particle.SMOKE, from, 0, inward.getX(), inward.getY(), inward.getZ(), 1.0);
            if (i % 2 == 0) {
                w.spawnParticle(Particle.LARGE_SMOKE, from, 0, inward.getX(), inward.getY(), inward.getZ(), 0.7);
            }
        }

        // сжимающееся чёрное кольцо (каждую секунду стягивается к центру)
        double ringR = 3.0 * (1.0 - (double) (t % 20) / 20.0) + 0.35;
        for (int i = 0; i < 18; i++) {
            double a = (Math.PI * 2 * i) / 18;
            w.spawnParticle(Particle.DUST, center.clone().add(Math.cos(a) * ringR, 0.05, Math.sin(a) * ringR), 1, 0.0, 0.0, 0.0, 0.0, black);
        }

        // тёмное «ядро» — сам чёрный сгусток
        w.spawnParticle(Particle.SMOKE, center, 4, 0.18, 0.22, 0.18, 0.01);
        w.spawnParticle(Particle.DUST, center, 6, 0.25, 0.3, 0.25, 0.0, black);
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
        ItemModels.apply(dyeMeta, "attractionsasake_smok1");
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
                if (ticks >= org.money.money.meta.ClassRegistry.numInt("saske", "attraction", "durationTicks", 300)) {
                    if (armorStand != null && !armorStand.isDead()) {
                        armorStand.remove();
                    }
                    cancel();
                    return;
                }

                double pullRadius = org.money.money.meta.ClassRegistry.num("saske", "attraction", "radius", 9.0);
                for (Entity entity : location.getWorld().getNearbyEntities(location, pullRadius, pullRadius, pullRadius)) {
                    if (!(entity instanceof ArmorStand) &&
                            !(entity instanceof Player && isAlly((Player) entity, placer)) &&
                            entity instanceof LivingEntity living) {

                        Vector direction = location.toVector().subtract(entity.getLocation().toVector()).normalize();
                        entity.setVelocity(direction.multiply(org.money.money.meta.ClassRegistry.num("saske", "attraction", "pullStrength", 0.5)));

                        double distSq = entity.getLocation().distanceSquared(location);
                        double dmgRadius = org.money.money.meta.ClassRegistry.num("saske", "attraction", "damageRadius", 3.0);
                        if (distSq <= dmgRadius * dmgRadius) {
                            UUID id = entity.getUniqueId();
                            int timeInZone = entitiesInZone.getOrDefault(id, 0) + 1;
                            entitiesInZone.put(id, timeInZone);

                            if (timeInZone % 20 == 0) {
                                living.damage(org.money.money.meta.ClassRegistry.num("saske", "attraction", "damagePerSecond", 4.0), placer);
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
        }.runTaskTimer(plugin, 0L, 1L); // притяжение стартует сразу после 2с-зарядки (задержку убрали)
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
