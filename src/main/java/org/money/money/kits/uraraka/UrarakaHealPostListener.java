package org.money.money.kits.uraraka;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.*;

public final class UrarakaHealPostListener implements Listener {

    private static final double RADIUS = 10.0;
    private static final long   HEAL_PERIOD = 20L * 5;  // каждые 5с
    private static final long   WATCH_PERIOD = 20L;     // проверка блока раз в 1с
    private static final double HEAL_AMOUNT = 4.0;      // 2 сердечка

    private final Plugin plugin;
    private final NamespacedKey KEY_HEAL_POST; // метка предмета

    // активные посты по координате блока
    private final Map<Location, Post> posts = new HashMap<>();

    public UrarakaHealPostListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_HEAL_POST = new NamespacedKey(plugin, "uraraka_heal_post");
    }

    /* ---------- item ---------- */

    /** Блок для выдачи: красная глазур. терракота с именем "Heal Post". */
    public ItemStack makeHealPostItem() {
        ItemStack it = new ItemStack(Material.RED_GLAZED_TERRACOTTA);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Heal Post"));
        im.getPersistentDataContainer().set(KEY_HEAL_POST, PersistentDataType.BYTE, (byte)1);
        it.setItemMeta(im);
        return it;
    }

    private boolean isHealPostItem(ItemStack it) {
        if (it == null || it.getType() != Material.RED_GLAZED_TERRACOTTA || !it.hasItemMeta()) return false;
        ItemMeta im = it.getItemMeta();
        if (im.getPersistentDataContainer().has(KEY_HEAL_POST, PersistentDataType.BYTE)) return true;
        return Component.text("Heal Post").equals(im.displayName());
    }

    /* ---------- place / break ---------- */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent e) {
        ItemStack inHand = e.getItemInHand();
        if (!isHealPostItem(inHand)) return;

        Player owner = e.getPlayer();
        Location blockLoc = e.getBlockPlaced().getLocation().toBlockLocation();

        // спавним «якорь» — маленький невидимый стенд
        Location standLoc = blockLoc.clone().add(0.5, 0.05, 0.5);
        ArmorStand as = standLoc.getWorld().spawn(standLoc, ArmorStand.class, s -> {
            s.setInvisible(true);
            s.setMarker(true);
            s.setSmall(true);
            s.setGravity(false);
            s.setBasePlate(false);
            s.setInvulnerable(true);
            s.customName(Component.text("heal_post"));
            s.setCustomNameVisible(false);
        });

        // периодическое исцеление
        BukkitTask heal = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player pOwner = Bukkit.getPlayer(owner.getUniqueId());
            if (pOwner == null || !pOwner.isOnline() || !as.isValid()) return;

            Collection<Entity> nearby = as.getWorld().getNearbyEntities(as.getLocation(), RADIUS, RADIUS, RADIUS);
            for (Entity ent : nearby) {
                if (!(ent instanceof Player pl)) continue;
                if (pl.isDead()) continue;
                if (!isTeammate(pOwner, pl)) continue;

                double max = Optional.ofNullable(pl.getAttribute(Attribute.MAX_HEALTH))
                        .map(a -> a.getValue()).orElse(20.0);
                pl.setHealth(Math.min(max, pl.getHealth() + HEAL_AMOUNT));
                pl.getWorld().spawnParticle(Particle.HEART, pl.getLocation().add(0, 1.4, 0),
                        6, 0.5, 0.5, 0.5, 0.0);
            }
        }, HEAL_PERIOD, HEAL_PERIOD);

        // сторож: жив ли блок
        BukkitTask watch = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (blockLoc.getBlock().getType() != Material.RED_GLAZED_TERRACOTTA || !as.isValid()) {
                stopPost(blockLoc);
            }
        }, WATCH_PERIOD, WATCH_PERIOD);

        posts.put(blockLoc, new Post(owner.getUniqueId(), as, heal, watch));
        owner.playSound(owner.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.4f);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent e) {
        Location key = e.getBlock().getLocation().toBlockLocation();
        if (posts.containsKey(key)) stopPost(key);
    }

    private void stopPost(Location key) {
        Post p = posts.remove(key);
        if (p == null) return;
        if (p.healTask != null) p.healTask.cancel();
        if (p.watchTask != null) p.watchTask.cancel();
        if (p.stand != null && p.stand.isValid()) p.stand.remove();
    }

    /* ---------- util ---------- */

    private boolean isTeammate(Player a, Player b) {
        Team ta = teamOf(a, a.getScoreboard());
        if (ta == null) ta = teamOf(a, Bukkit.getScoreboardManager().getMainScoreboard());
        Team tb = teamOf(b, b.getScoreboard());
        if (tb == null) tb = teamOf(b, Bukkit.getScoreboardManager().getMainScoreboard());
        return ta != null && ta.equals(tb);
    }

    private Team teamOf(Player p, Scoreboard sb) {
        if (sb == null) return null;
        return sb.getEntryTeam(p.getName());
    }

    private static final class Post {
        final UUID owner;
        final ArmorStand stand;
        final BukkitTask healTask;
        final BukkitTask watchTask;
        Post(UUID owner, ArmorStand stand, BukkitTask healTask, BukkitTask watchTask) {
            this.owner = owner; this.stand = stand; this.healTask = healTask; this.watchTask = watchTask;
        }
    }
}
