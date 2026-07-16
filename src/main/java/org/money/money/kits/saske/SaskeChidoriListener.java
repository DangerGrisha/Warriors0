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
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import org.money.money.kits.ladynagan.LadyCooldownManager;
import org.money.money.util.ItemModels;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Перенос 1:1 из Last_Warriors (events/saske/ChidoryListener) — чидори/рывок.
 * ПКМ «Chidori» (чернильный мешок): подготовка ~1.65с (заморозка + частицы), затем рывок
 * (скорость ×20) с уроном 19 по области во время рывка. Кулдаун 50с. Звук «saske.chidory».
 */
public class SaskeChidoriListener implements Listener {

    private static final String NAME_OF_CHIDORY = "Chidori";
    private static final long   SPEACH_BEFORE_DASH = 33L;

    // Прямой «копейный» рывок: горизонтальная линия, пробивает ломаемые блоки.
    private static double dashRange()          { return org.money.money.meta.ClassRegistry.num("saske", "chidori", "dashRange", 40); }
    private static double dashBlocksPerTick()  { return org.money.money.meta.ClassRegistry.num("saske", "chidori", "dashBlocksPerTick", 2.0); }
    private static double selfDamagePerBlock() { return org.money.money.meta.ClassRegistry.num("saske", "chidori", "selfDamagePerBlock", 0.5); } // 2 блока = 1 HP (пол сердца)
    private static double chidoriDamage()      { return org.money.money.meta.ClassRegistry.num("saske", "chidori", "damage", 19); }
    private static double hitRadius()          { return org.money.money.meta.ClassRegistry.num("saske", "chidori", "hitRadius", 1.5); }

    private final Plugin plugin;
    private final LadyCooldownManager cooldownManager;

    public SaskeChidoriListener(Plugin plugin, LadyCooldownManager cooldownManager) {
        this.plugin = plugin;
        this.cooldownManager = cooldownManager;
    }

    /* ===================== Выдача ===================== */

    public ItemStack makeChidori() {
        ItemStack it = new ItemStack(Material.INK_SAC, 1);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(NAME_OF_CHIDORY));
        meta.setUnbreakable(true);
        meta.setLore(List.of("something"));
        ItemModels.apply(meta, "abbility_chidori");
        it.setItemMeta(meta);
        return it;
    }

    /* ===================== Логика ===================== */

    @EventHandler
    public void onPlayerUse(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (!checkEvent(event, player)) return;
        event.setCancelled(true);

        final String abilityId = "CHIDORY";
        final int cooldownSec = 50;

        if (!cooldownManager.isCooldownComplete(player, abilityId)) {
            player.sendMessage(ChatColor.RED + "Ability is recharging!");
            return;
        }

        final int slot = player.getInventory().getHeldItemSlot();
        final ItemStack originalItem = player.getInventory().getItemInMainHand().clone();
        player.getInventory().setItemInMainHand(null);

        cooldownManager.startCooldown(player, abilityId, slot, cooldownSec, false);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            if (!cooldownManager.isCooldownComplete(player, abilityId)) return;

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
        }, cooldownSec * 20L);

        World world = player.getWorld();
        Location initialLocation = player.getLocation();

        world.playSound(initialLocation, "saske.chidory", 1.0F, 1.0F);

        player.setWalkSpeed(0f);
        player.setVelocity(new Vector(0, 0, 0));
        // Заморозка каста: walkSpeed=0 уже держит на месте. Амплитуда 255 у SLOWNESS
        // ПЕРЕПОЛНЯЛА множитель скорости в отрицательный (= рывок/ускорение) — берём безопасный уровень.
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) SPEACH_BEFORE_DASH, 6, false, false));

        particleStaff(player, world);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;
                // снимаем заморозку-slow ДО возврата контроля, чтобы остаток эффекта не
                // наложился на движение и не дал случайный «спид» в момент рывка
                player.removePotionEffect(PotionEffectType.SLOWNESS);
                player.setWalkSpeed(0.2f);
                world.spawnParticle(Particle.SONIC_BOOM, player.getLocation(), 7, 0.5, 0.5, 0.5, 0);
                startPierceDash(player);
            }
        }.runTaskLater(plugin, SPEACH_BEFORE_DASH);
    }

    private void particleStaff(Player player, World world) {
        new BukkitRunnable() {
            int iterations = 20;
            @Override
            public void run() {
                if (iterations-- <= 0) { cancel(); return; }
                Location particleLocation = player.getLocation().add(0, 1, 0);
                world.spawnParticle(Particle.ELECTRIC_SPARK, particleLocation, 7, 0.5, 0.5, 0.5, 0);
            }
        }.runTaskTimer(plugin, 1L, 4L);
    }

    /**
     * Прямой горизонтальный рывок-копьё: летим строго по прямой (направление зафиксировано в момент
     * старта, только горизонталь) до {@code dashRange} блоков, пробивая ЛОМАЕМЫЕ блоки; на неломаемом
     * (бедрок/барьер/защищённый) — стоп. Каждого врага в пути бьём один раз. Чем больше блоков сломали,
     * тем больше самоурона ({@code selfDamagePerBlock} за блок; по умолчанию 2 блока = пол сердца).
     */
    private void startPierceDash(Player player) {
        final Location origin = player.getLocation().clone();
        Vector d = origin.getDirection();
        d.setY(0);                                   // только горизонталь
        if (d.lengthSquared() < 1.0e-6) d = new Vector(1, 0, 0);
        final Vector dir = d.normalize();

        final double range = dashRange();
        final double perTick = Math.max(0.5, dashBlocksPerTick());
        final double subStep = 0.5;                  // мелкий шаг, чтобы не перепрыгивать блоки
        final World world = origin.getWorld();
        final Set<UUID> hitEnemies = new HashSet<>();

        new BukkitRunnable() {
            double traveled = 0;
            int broken = 0;
            boolean stopped = false;

            @Override
            public void run() {
                if (!player.isOnline() || stopped || traveled >= range) { finish(); return; }

                double budget = perTick;
                Location last = null;
                while (budget > 0 && traveled < range) {
                    double adv = Math.min(subStep, budget);
                    double nextTravel = traveled + adv;
                    Location next = origin.clone().add(dir.clone().multiply(nextTravel));

                    int res = breakColumnAt(next);
                    if (res < 0) { stopped = true; break; } // неломаемая стена — стоп
                    broken += res;

                    traveled = nextTravel;
                    budget -= adv;
                    last = next;
                    damageEnemiesAround(player, next, hitEnemies);
                    world.spawnParticle(Particle.ELECTRIC_SPARK, next.clone().add(0, 1, 0), 6, 0.3, 0.5, 0.3, 0.02);
                }

                if (last != null) {
                    last.setYaw(player.getLocation().getYaw());
                    last.setPitch(player.getLocation().getPitch());
                    player.teleport(last);
                    player.setVelocity(new Vector(0, 0, 0)); // держим прямую линию, без падения/дрейфа
                }
                if (stopped || traveled >= range) finish();
            }

            private void finish() {
                cancel();
                if (!player.isOnline()) return;
                player.setWalkSpeed(0.2f);
                double selfDmg = broken * selfDamagePerBlock();
                if (selfDmg > 0 && !player.isDead()) {
                    player.setNoDamageTicks(0);
                    player.damage(selfDmg); // самоурон за пробитые блоки (без источника)
                }
                world.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.7f, 1.0f);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /**
     * Ломает ломаемые блоки в 2-высотной колонне на позиции (ноги + голова). Возвращает число
     * сломанных, либо -1 если наткнулись на неломаемый блок (сигнал остановить рывок).
     */
    private int breakColumnAt(Location loc) {
        Block feet = loc.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        int broken = 0;
        for (Block b : new Block[]{feet, head}) {
            Material t = b.getType();
            if (t.isAir() || b.isPassable()) continue;               // проходимо — не мешает
            if (t.getHardness() < 0 || isProtectedBlock(t)) return -1; // неломаемо — стоп
            World w = b.getWorld();
            w.spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 15, 0.3, 0.3, 0.3, 0.0, b.getBlockData());
            w.playSound(b.getLocation(), Sound.BLOCK_STONE_BREAK, 0.7f, 1.2f);
            b.setType(Material.AIR, false);                          // без дропа
            broken++;
        }
        return broken;
    }

    private void damageEnemiesAround(Player player, Location at, Set<UUID> hitEnemies) {
        double r = hitRadius();
        for (Entity e : at.getWorld().getNearbyEntities(at, r, r, r)) {
            if (e == player || e.isDead()) continue;
            if (!(e instanceof LivingEntity le) || e instanceof ArmorStand) continue;
            if (!hitEnemies.add(e.getUniqueId())) continue;          // каждого — один раз за рывок
            le.damage(chidoriDamage(), player);
        }
    }

    private static boolean isProtectedBlock(Material t) {
        if (t == Material.BEDROCK || t == Material.BARRIER) return true;
        if (t == Material.END_PORTAL || t == Material.END_PORTAL_FRAME) return true;
        if (t == Material.REINFORCED_DEEPSLATE) return true;
        String n = t.name();
        return n.endsWith("COMMAND_BLOCK") || n.contains("STRUCTURE") || n.equals("JIGSAW");
    }

    private boolean checkEvent(PlayerInteractEvent event, Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        return (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) &&
                hand.getType() == Material.INK_SAC &&
                hand.hasItemMeta() && hand.getItemMeta().hasDisplayName() &&
                hand.getItemMeta().getDisplayName().equals(NAME_OF_CHIDORY);
    }
}
