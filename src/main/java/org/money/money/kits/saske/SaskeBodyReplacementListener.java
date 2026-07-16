package org.money.money.kits.saske;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.ArmorStand;
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
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import org.money.money.kits.ladynagan.LadyCooldownManager;
import org.money.money.util.ItemModels;

import java.util.List;

/**
 * Перенос 1:1 из Last_Warriors (events/saske/BodyReplacemenListener) — замена телом.
 * ПКМ «Body Replacement» (чернильный мешок): через ~3.25с меняешься местами с целью
 * в радиусе 40 (если нет блоков между). Кулдаун 70с. Звук «saske.katon» сохранён.
 * Адаптация: убрана серверная механика флага (dropFlag/баннер из старого CTF).
 */
public class SaskeBodyReplacementListener implements Listener {

    private static final String NAME_OF_REPLACEMENT = "Body Replacement";
    private static double DISTANCE_OF_TRIGGERING() { return org.money.money.meta.ClassRegistry.num("saske", "body", "range", 40); }
    private static long SPEACH_BEFORE_REPLACEMENT() { return org.money.money.meta.ClassRegistry.numInt("saske", "body", "castDelayTicks", 65); }

    private final Plugin plugin;
    private final LadyCooldownManager cooldownManager;

    public SaskeBodyReplacementListener(Plugin plugin, LadyCooldownManager cooldownManager) {
        this.plugin = plugin;
        this.cooldownManager = cooldownManager;
    }

    /* ===================== Выдача ===================== */

    public ItemStack makeBodyReplacement() {
        ItemStack it = new ItemStack(Material.INK_SAC, 1);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(NAME_OF_REPLACEMENT));
        meta.setUnbreakable(true);
        meta.setLore(List.of("something"));
        ItemModels.apply(meta, "abbility_body_replacement");
        it.setItemMeta(meta);
        return it;
    }

    /* ===================== Логика ===================== */

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!checkEvent(event)) return;

        Player player = event.getPlayer();
        event.setCancelled(true);

        if (!cooldownManager.isCooldownComplete(player, NAME_OF_REPLACEMENT)) {
            player.sendMessage(ChatColor.RED + "Ability is recharging!");
            return;
        }

        final int slot = player.getInventory().getHeldItemSlot();
        final ItemStack originalItem = player.getInventory().getItemInMainHand().clone();
        player.getInventory().setItemInMainHand(null);

        final int COOLDOWN = 70;
        cooldownManager.startCooldown(player, NAME_OF_REPLACEMENT, slot, COOLDOWN, false);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            if (!cooldownManager.isCooldownComplete(player, NAME_OF_REPLACEMENT)) return;

            ItemStack inSlot = player.getInventory().getItem(slot);
            if (inSlot == null || inSlot.getType().isAir()) {
                player.getInventory().setItem(slot, originalItem);
            } else {
                var leftover = player.getInventory().addItem(originalItem);
                if (!leftover.isEmpty()) {
                    leftover.values().forEach(rem -> player.getWorld().dropItemNaturally(player.getLocation(), rem));
                }
            }
            try {
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
            } catch (Throwable ignored) {}
        }, COOLDOWN * 20L);

        World world = player.getWorld();
        world.playSound(player.getLocation(), "saske.katon", SoundCategory.MASTER, 1.0F, 1.0F);

        scheduleAimAndSwap(player);
    }

    private boolean checkEvent(PlayerInteractEvent event) {
        ItemStack hand = event.getPlayer().getInventory().getItemInMainHand();
        return (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)
                && hand.getType() == Material.INK_SAC
                && hand.hasItemMeta()
                && NAME_OF_REPLACEMENT.equals(hand.getItemMeta().getDisplayName());
    }

    /**
     * Раньше цель искалась ОДНИМ тонким рейтрейсом ровно в момент срабатывания — попасть трудно.
     * Теперь: последнюю секунду перед телепортом каждый тик семплируем, на кого смотрит игрок
     * (ЖИРНЫМ лучом raySize), и меняемся с ПОСЛЕДНЕЙ валидной целью за это окно.
     */
    private void scheduleAimAndSwap(Player player) {
        final long castDelay = SPEACH_BEFORE_REPLACEMENT();
        final int sampleWindow = org.money.money.meta.ClassRegistry.numInt("saske", "body", "aimSampleTicks", 20);
        final long samplingStart = Math.max(0L, castDelay - sampleWindow);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            new BukkitRunnable() {
                int i = 0;
                LivingEntity last = null;

                @Override
                public void run() {
                    if (!player.isOnline()) { cancel(); return; }
                    i++;
                    LivingEntity t = raycastTarget(player);
                    if (t != null) last = t; // запоминаем последнюю валидную цель за окно
                    if (i >= sampleWindow) {
                        cancel();
                        performSwap(player, last);
                    }
                }
            }.runTaskTimer(plugin, 0L, 1L);
        }, samplingStart);
    }

    /** Жирный рейтрейс на цель + проверка стены. null — если сейчас цели нет. */
    private LivingEntity raycastTarget(Player player) {
        final Location eye = player.getEyeLocation();
        final Vector dir = eye.getDirection().normalize();
        final double range = DISTANCE_OF_TRIGGERING();
        final double raySize = org.money.money.meta.ClassRegistry.num("saske", "body", "raySize", 0.75);

        RayTraceResult res = player.getWorld().rayTraceEntities(eye, dir, range, raySize, e -> validTarget(player, e));
        if (res == null || !(res.getHitEntity() instanceof LivingEntity target)) return null;

        // нельзя меняться сквозь стену: луч на центр цели
        Location tc = target.getLocation().add(0, target.getHeight() * 0.5, 0);
        Vector toTarget = tc.toVector().subtract(eye.toVector());
        double dist = toTarget.length();
        if (dist > range || dist < 1.0e-6) return null;
        RayTraceResult wall = player.getWorld().rayTraceBlocks(eye, toTarget.normalize(), dist, FluidCollisionMode.NEVER, true);
        if (wall != null && wall.getHitBlock() != null) return null;

        return target;
    }

    private boolean validTarget(Player caster, Entity e) {
        if (e == caster) return false;
        if (!(e instanceof LivingEntity) || e.isDead()) return false;
        if (e instanceof ArmorStand) return false; // не меняемся с невидимыми маркер-стендами
        return !(e instanceof Player p && p.getGameMode() == GameMode.SPECTATOR);
    }

    private void performSwap(Player player, LivingEntity target) {
        if (!player.isOnline() || target == null || target.isDead()) {
            try { player.stopSound("saske.katon", SoundCategory.MASTER); } catch (Throwable ignored) {}
            return;
        }
        Location playerLocation = player.getLocation();
        Location targetLocation = target.getLocation();

        target.teleport(playerLocation);
        player.teleport(targetLocation);

        spawnSmokeParticles(playerLocation);
        spawnSmokeParticles(targetLocation);
    }

    private void spawnSmokeParticles(Location location) {
        location.getWorld().spawnParticle(Particle.SMOKE, location, 50, 0.5, 0.5, 0.5);
    }
}
