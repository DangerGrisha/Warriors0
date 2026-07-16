package org.money.money.kits.ladynagan;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import org.money.money.meta.ClassRegistry;
import org.money.money.session.KitSession;
import org.money.money.util.ItemModels;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Мины из фиолетового бетона «Trap»: невидимый ArmorStand, подрыв по приближению ВРАГА.
 * Владелицу и её тиммейтов мина НЕ детектит (сами они её не активируют), но если мину
 * подорвал враг — взрыв бьёт ВСЕХ рядом, включая владелицу/тиммейтов, с уроном по дистанции
 * (центр = полный trap.damage, край радиуса = 0). Радиусы/кд из конфига, кд 15с.
 */
public class LadyNaganTrapsListener implements Listener {

    // числа 1:1 из LadyConstants (баланс читается из ClassRegistry при использовании)
    private static final Material SET_UP_BLOCK = Material.PURPLE_CONCRETE;
    private static final String   TRAP_NAME = "Trap";
    private static final boolean  DAMAGE_TERRAIN = false;
    private static final int      TRAPS_SLOT = 6;

    private static final String ABILITY_ID = "LadyTrap";
    private static final double PLACE_SELF_MIN_DIST_SQ = 1.0;

    private static double triggerRadius()  { return ClassRegistry.num("ladynagan", "trap", "triggerRadius", 3.0); }
    private static float  explosionPower() { return (float) ClassRegistry.num("ladynagan", "trap", "explosionPower", 4.0); }
    private static double damageRadius()   { return ClassRegistry.num("ladynagan", "trap", "damageRadius", 3.0); }
    private static double trapDamage()     { return ClassRegistry.num("ladynagan", "trap", "damage", 10.0); }
    private static int    lifetimeSeconds(){ return ClassRegistry.numInt("ladynagan", "trap", "lifetimeSeconds", 240); }

    private final Map<Player, Integer> activeBombCounts = new HashMap<>();
    private final Map<ArmorStand, BukkitRunnable> armorStandTimers = new HashMap<>();
    private final Map<ArmorStand, Player> armorStandOwners = new HashMap<>();

    private final Plugin plugin;
    private final LadyCooldownManager cooldownManager;

    public LadyNaganTrapsListener(Plugin plugin, LadyCooldownManager cooldownManager) {
        this.plugin = plugin;
        this.cooldownManager = cooldownManager;
    }

    /* ===================== Выдача ===================== */

    public ItemStack makeTrapBlock() {
        ItemStack it = new ItemStack(SET_UP_BLOCK, 1);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(TRAP_NAME));
        meta.setUnbreakable(true);
        meta.setLore(List.of("something"));
        it.setItemMeta(meta);
        return it;
    }

    /* ================= placement ================= */

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        final Player player = event.getPlayer();
        final Block clicked = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isTrapItem(hand)) return;
        if (clicked == null) return;

        boolean cdActive = !cooldownManager.isCooldownComplete(player, ABILITY_ID);
        int totalNow = countTrapItems(player);
        if (cdActive && totalNow <= 0) {
            player.sendMessage(ChatColor.RED + "Mine is recharging!");
            event.setCancelled(true);
            return;
        }

        Block place = clicked.getRelative(event.getBlockFace());
        if (place.getType() != Material.AIR) return;

        Location loc = place.getLocation();
        if (distSqToBlockCenter(player.getLocation(), loc) <= PLACE_SELF_MIN_DIST_SQ) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "You cannot place a mine so close to yourself!");
            return;
        }
        if (isNearPlayersOrMines(loc, player, 3)) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "You cannot place a mine near players or other mines!");
            return;
        }

        consumeOneInMainHand(player);

        startBlockRemoval(place);
        Bukkit.getScheduler().runTask(plugin, () -> {
            ArmorStand as = replaceBlockWithArmorStand(loc);

            Team ownerTeam = getPlayerTeam(player);
            if (ownerTeam != null) ownerTeam.addEntry(as.getUniqueId().toString());

            armorStandOwners.put(as, player);
            activeBombCounts.merge(player, 1, Integer::sum);

            startArmorStandTimer(as, player);
        });
    }

    private static double distSqToBlockCenter(Location playerLoc, Location blockLoc) {
        double px = playerLoc.getX(), py = playerLoc.getY(), pz = playerLoc.getZ();
        double bx = blockLoc.getX() + 0.5, by = blockLoc.getY() + 0.5, bz = blockLoc.getZ() + 0.5;
        double dx = px - bx, dy = py - by, dz = pz - bz;
        return dx * dx + dy * dy + dz * dz;
    }

    private void consumeOneInMainHand(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) return;
        if (hand.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
        else hand.setAmount(hand.getAmount() - 1);
    }

    /* ================= lifecycle ================= */

    private void startArmorStandTimer(ArmorStand armorStand, Player owner) {
        BukkitRunnable timer = new BukkitRunnable() {
            @Override public void run() {
                if (!armorStand.isDead()) armorStand.remove();
                armorStandTimers.remove(armorStand);
                finishMine(owner);
            }
        };
        timer.runTaskLater(plugin, lifetimeSeconds() * 20L);
        armorStandTimers.put(armorStand, timer);
    }

    private void finishMine(Player owner) {
        activeBombCounts.merge(owner, -1, Integer::sum);
        if (activeBombCounts.getOrDefault(owner, 0) < 0) activeBombCounts.put(owner, 0);

        if (countTrapItems(owner) > 0) return;
        if (!cooldownManager.isCooldownComplete(owner, ABILITY_ID)) return;

        startCooldownAndReturn(owner);
    }

    private void startCooldownAndReturn(Player owner) {
        int cooldownSeconds = ClassRegistry.seconds("ladynagan", "trap", 15);
        cooldownManager.startCooldown(owner, ABILITY_ID, TRAPS_SLOT, cooldownSeconds, false);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!KitSession.isInGame(owner)) return; // игра кончилась — не выдаём в лобби
            if (cooldownManager.isCooldownComplete(owner, ABILITY_ID)) {
                giveOrDrop(owner, makeTrapBlock());
                try {
                    owner.playSound(owner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
                } catch (Throwable ignored) {}
            }
        }, cooldownSeconds * 20L);
    }

    /* ================= interactions / trigger ================= */

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ArmorStand as) || !(event.getDamager() instanceof Player damager)) return;
        if (!TRAP_NAME.equals(as.getCustomName())) return;

        event.setCancelled(true);

        BukkitRunnable t = armorStandTimers.remove(as);
        if (t != null) t.cancel();

        Player owner = armorStandOwners.remove(as);
        as.remove();

        if (owner == null) return;

        if (damager.equals(owner)) {
            giveOrDrop(owner, makeTrapBlock());
            owner.sendMessage(ChatColor.GREEN + "You retrieved your mine!");
            activeBombCounts.merge(owner, -1, Integer::sum);
        } else {
            finishMine(owner);
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location playerLoc = player.getLocation();

        double detectRadius = triggerRadius();
        for (ArmorStand as : player.getWorld().getEntitiesByClass(ArmorStand.class)) {
            if (!TRAP_NAME.equals(as.getCustomName())) continue;
            if (as.getLocation().distanceSquared(playerLoc) > detectRadius * detectRadius) continue;

            Player owner = armorStandOwners.get(as);
            if (owner != null && (player.equals(owner) || sameTeam(owner, player))) {
                continue; // владелица и её тиммейты мину НЕ активируют (в т.ч. в FFA без команды)
            }
            triggerExplosion(as, owner);
        }
    }

    private void triggerExplosion(ArmorStand armorStand, Player owner) {
        Location explosionLoc = armorStand.getLocation();
        World w = armorStand.getWorld();

        // Сила 0 → ванильный взрыв НЕ наносит AoE-урон и не рушит блоки (иначе задело бы
        // владелицу/тиммейтов). Звук остаётся; масштабный визуал добавляем частицей.
        w.createExplosion(explosionLoc, 0, false, DAMAGE_TERRAIN);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, explosionLoc, Math.max(1, Math.round(explosionPower() / 3f)));

        if (owner != null) {
            try { owner.sendMessage(ChatColor.RED + "Your mine was triggered!"); } catch (Throwable ignored) {}
        }

        // Урон по ВСЕМ игрокам в радиусе (включая владелицу и её тиммейтов) со спадом по дистанции.
        // Своих мина не ДЕТЕКТИТ (не активируется от них, см. onPlayerMove), но раз её подорвал
        // враг — это обычный взрыв: бьёт всех рядом тем сильнее, чем ближе они к центру.
        double dmgRadius = damageRadius();
        for (Entity e : armorStand.getNearbyEntities(dmgRadius, dmgRadius, dmgRadius)) {
            if (!(e instanceof Player target)) continue;
            double dist = target.getLocation().distance(explosionLoc);
            if (dist > dmgRadius) continue;
            double scaled = trapDamage() * (1.0 - dist / dmgRadius); // линейный спад: центр = полный, край = 0
            if (scaled <= 0.0) continue;
            target.damage(scaled); // без источника — чтобы FF-настройки команды не блокировали урон по своим
        }

        BukkitRunnable t = armorStandTimers.remove(armorStand);
        if (t != null) t.cancel();
        armorStand.remove();

        if (owner != null) finishMine(owner);
    }

    /* ================= spawn/visual ================= */

    public ArmorStand replaceBlockWithArmorStand(Location location) {
        ArmorStand armorStand = location.getWorld().spawn(location.add(0.5, 0, 0.5), ArmorStand.class);
        armorStand.setVisible(false);
        armorStand.setGravity(false);
        armorStand.setCanPickupItems(false);
        armorStand.setBasePlate(false);
        armorStand.setInvulnerable(true);
        armorStand.setCustomName(TRAP_NAME);
        armorStand.setCustomNameVisible(false);
        armorStand.setArms(true);
        armorStand.setSmall(true);

        ItemStack dye = new ItemStack(Material.BLUE_DYE);
        ItemMeta im = dye.getItemMeta();
        im.displayName(Component.text("TrapLedy"));
        ItemModels.apply(im, "ledynagan_mine_gun");
        dye.setItemMeta(im);
        armorStand.getEquipment().setItemInMainHand(dye);

        return armorStand;
    }

    @EventHandler
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof ArmorStand as && TRAP_NAME.equals(as.getCustomName())) {
            event.setCancelled(true);
        }
    }

    private void startBlockRemoval(Block block) {
        block.setType(Material.AIR);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (block.getType() == SET_UP_BLOCK) block.setType(Material.AIR);
        }, 0L);
    }

    /* ================= teams/helpers ================= */

    private Team getPlayerTeam(Player p) {
        Scoreboard sb = plugin.getServer().getScoreboardManager().getMainScoreboard();
        return sb.getEntryTeam(p.getName());
    }

    private boolean sameTeam(Player a, Player b) {
        Team ta = getPlayerTeam(a), tb = getPlayerTeam(b);
        return ta != null && ta.equals(tb);
    }

    private boolean isNearPlayersOrMines(Location location, Player placer, int radius) {
        for (Entity e : location.getWorld().getNearbyEntities(location, radius, radius, radius)) {
            if (e instanceof Player && !e.equals(placer)) return true;
            if (e instanceof ArmorStand as && TRAP_NAME.equals(as.getCustomName())) return true;
        }
        return false;
    }

    private boolean isTrapItem(ItemStack it) {
        if (it == null || it.getType() != SET_UP_BLOCK || !it.hasItemMeta()) return false;
        ItemMeta im = it.getItemMeta();
        return im.hasDisplayName() && TRAP_NAME.equals(im.getDisplayName());
    }

    private int countTrapItems(Player p) {
        int sum = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (isTrapItem(it)) sum += it.getAmount();
        }
        ItemStack off = p.getInventory().getItemInOffHand();
        if (isTrapItem(off)) sum += off.getAmount();
        return sum;
    }

    private void giveOrDrop(Player p, ItemStack it) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(it);
        if (!left.isEmpty()) {
            for (ItemStack rem : left.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), rem);
            }
        }
    }
}
