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

import org.money.money.session.KitSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Перенос 1:1 из Last_Warriors (events/ladynagan/TrapsListener).
 * Мины из фиолетового бетона «Trap»: невидимый ArmorStand, подрыв по приближению врага
 * (радиус 3), урон 10 в радиусе 3, кд 15с на перезарядку. Детект по display-name.
 */
public class LadyNaganTrapsListener implements Listener {

    // числа 1:1 из LadyConstants
    private static final Material SET_UP_BLOCK = Material.PURPLE_CONCRETE;
    private static final String   TRAP_NAME = "Trap";
    private static final double   DETECT_RADIUS = 3;
    private static final float    EXPLOSION_POWER = 4f;
    private static final boolean  DAMAGE_TERRAIN = false;
    private static final double   DAMAGE_RADIUS = 3.0;
    private static final double   DAMAGE_AMOUNT = 10.0;
    private static final int      COOLDOWN_SECONDS = 15;
    private static final int      TRAPS_SLOT = 6;

    private static final String ABILITY_ID = "LadyTrap";
    private static final int    TIMER_SECONDS = 240;
    private static final double PLACE_SELF_MIN_DIST_SQ = 1.0;

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
        timer.runTaskLater(plugin, TIMER_SECONDS * 20L);
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
        cooldownManager.startCooldown(owner, ABILITY_ID, TRAPS_SLOT, COOLDOWN_SECONDS, false);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!KitSession.isInGame(owner)) return; // игра кончилась — не выдаём в лобби
            if (cooldownManager.isCooldownComplete(owner, ABILITY_ID)) {
                giveOrDrop(owner, makeTrapBlock());
                try {
                    owner.playSound(owner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
                } catch (Throwable ignored) {}
            }
        }, COOLDOWN_SECONDS * 20L);
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

        for (ArmorStand as : player.getWorld().getEntitiesByClass(ArmorStand.class)) {
            if (!TRAP_NAME.equals(as.getCustomName())) continue;
            if (as.getLocation().distanceSquared(playerLoc) > DETECT_RADIUS * DETECT_RADIUS) continue;

            Player owner = armorStandOwners.get(as);
            if (owner != null && sameTeam(owner, player)) {
                continue;
            }
            triggerExplosion(as, owner);
        }
    }

    private void triggerExplosion(ArmorStand armorStand, Player owner) {
        Location explosionLoc = armorStand.getLocation();
        armorStand.getWorld().createExplosion(explosionLoc, EXPLOSION_POWER, false, DAMAGE_TERRAIN);

        if (owner != null) {
            try { owner.sendMessage(ChatColor.RED + "Your mine was triggered!"); } catch (Throwable ignored) {}
        }

        for (Entity e : armorStand.getNearbyEntities(DAMAGE_RADIUS, DAMAGE_RADIUS, DAMAGE_RADIUS)) {
            if (e instanceof Player target) {
                if (owner == null || !sameTeam(owner, target)) {
                    target.damage(DAMAGE_AMOUNT);
                }
            }
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
