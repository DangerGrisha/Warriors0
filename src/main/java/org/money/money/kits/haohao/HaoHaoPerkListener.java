package org.money.money.kits.haohao;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class HaoHaoPerkListener implements Listener {

    private static final String TAG_HAOHAO = "HaoHao";

    private static final int KILL_STR_DURATION_TICKS = 20 * 10; // 10s
    private static final int KILL_STR_AMPLIFIER = 2; // Strength III

    private static final int TOTEM_STR_DURATION_TICKS = 20 * 10; // 10s
    private static final int TOTEM_STR_AMPLIFIER = 1; // Strength II
    private static final double TOTEM_RADIUS = 10.0;

    private static final int DEATH_STR_DURATION_TICKS = 20 * 30; // 30s
    private static final int DEATH_STR_AMPLIFIER = 1; // Strength II

    private static final long LAST_HIT_WINDOW_MS = 4000L;

    private final Plugin plugin;

    // victim -> last king attacker + time
    private final Map<UUID, UUID> lastKingDamager = new HashMap<>();
    private final Map<UUID, Long> lastKingHitAt = new HashMap<>();

    public HaoHaoPerkListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker)) return;
        if (!(e.getEntity() instanceof LivingEntity victim)) return;
        if (!isKing(attacker)) return;

        lastKingDamager.put(victim.getUniqueId(), attacker.getUniqueId());
        lastKingHitAt.put(victim.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onTotem(EntityResurrectEvent e) {
        Entity entity = e.getEntity();
        if (!(entity instanceof Player victim)) return;

        UUID lastAttackerId = lastKingDamager.get(victim.getUniqueId());
        Long hitAt = lastKingHitAt.get(victim.getUniqueId());
        if (lastAttackerId == null || hitAt == null) return;
        if (System.currentTimeMillis() - hitAt > LAST_HIT_WINDOW_MS) return;

        Player king = Bukkit.getPlayer(lastAttackerId);
        if (king == null || !king.isOnline() || king.isDead()) return;

        giveStrengthToTeammatesInRadius(king, TOTEM_RADIUS, TOTEM_STR_AMPLIFIER, TOTEM_STR_DURATION_TICKS);
        king.sendMessage("People got the strength");
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onKill(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer == null) return;
        if (!isKing(killer)) return;

        giveStrengthToTeammates(killer, KILL_STR_AMPLIFIER, KILL_STR_DURATION_TICKS);
        killer.sendMessage("People got the strength");
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onKingDeath(PlayerDeathEvent e) {
        Player king = e.getEntity();
        if (!isKing(king)) return;

        giveStrengthToTeammates(king, DEATH_STR_AMPLIFIER, DEATH_STR_DURATION_TICKS);
        king.sendMessage("People got the strength");
    }

    private void giveStrengthToTeammates(Player king, int amplifier, int durationTicks) {
        Team team = teamOf(king);
        if (team == null) return;
        for (String entry : team.getEntries()) {
            Player p = Bukkit.getPlayerExact(entry);
            if (p == null || !p.isOnline() || p.isDead()) continue;
            if (p.getUniqueId().equals(king.getUniqueId())) continue;
            p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, durationTicks, amplifier, false, false, true));
        }
    }

    private void giveStrengthToTeammatesInRadius(Player king, double radius, int amplifier, int durationTicks) {
        Team team = teamOf(king);
        if (team == null) return;
        Location center = king.getLocation();

        for (String entry : team.getEntries()) {
            Player p = Bukkit.getPlayerExact(entry);
            if (p == null || !p.isOnline() || p.isDead()) continue;
            if (p.getUniqueId().equals(king.getUniqueId())) continue;
            if (!p.getWorld().equals(center.getWorld())) continue;
            if (p.getLocation().distanceSquared(center) > radius * radius) continue;

            p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, durationTicks, amplifier, false, false, true));
        }
    }

    private boolean isKing(Player p) {
        return p.getScoreboardTags().contains(TAG_HAOHAO);
    }

    private Team teamOf(Player p) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        return sb.getEntryTeam(p.getName());
    }
}
