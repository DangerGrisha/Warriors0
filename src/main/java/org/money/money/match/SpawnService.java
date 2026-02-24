package org.money.money.match;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.money.money.Main;

import java.util.*;

public final class SpawnService {

    private static final Random rnd = new Random();

    // ✅ freeze storage
    private static final Map<UUID, Location> frozen = new HashMap<>();

    private SpawnService() {}

    /* ===================== FREEZE API ===================== */

    public static boolean isFrozen(Player p) {
        return p != null && frozen.containsKey(p.getUniqueId());
    }

    public static Location getFrozenLocation(Player p) {
        if (p == null) return null;
        return frozen.get(p.getUniqueId());
    }

    public static void freezePlayer(Player p, Location lock) {
        if (p == null || lock == null) return;
        frozen.put(p.getUniqueId(), lock.clone());
    }

    public static void unfreezePlayer(Player p) {
        if (p == null) return;
        frozen.remove(p.getUniqueId());
    }

    public static void unfreezeWorld(String worldName) {
        World w = Bukkit.getWorld(worldName);
        if (w == null) return;
        for (Player p : w.getPlayers()) frozen.remove(p.getUniqueId());
    }

    /** Freeze everyone in world for N seconds (camera movement allowed) */
    public static void freezeWorldForSeconds(String worldName, int seconds) {
        World w = Bukkit.getWorld(worldName);
        if (w == null) return;

        // store lock positions
        for (Player p : w.getPlayers()) {
            Location lock = p.getLocation().clone();
            freezePlayer(p, lock);
        }

        // unfreeze later
        new BukkitRunnable() {
            @Override public void run() {
                unfreezeWorld(worldName);
                for (Player p : w.getPlayers()) {
                    p.sendMessage("§aYou can move!");
                }
            }
        }.runTaskLater(Main.getInstance(), Math.max(1, seconds) * 20L);

        for (Player p : w.getPlayers()) {
            p.sendMessage("§eFrozen for §6" + seconds + "§e seconds...");
        }
    }

    /* ===================== TEAM SPAWN (your previous logic) ===================== */

    public static void teleportTeamsTogether(String worldName) {
        World w = Bukkit.getWorld(worldName);
        if (w == null) return;

        var map = Main.getInstance().getMapRegistry().byWorld(worldName);
        if (map == null) return;

        // assign teams before teleport
        TeamService.assignMissingBalanced(worldName);

        // use config spawn center/radius
        var s = map.spawn; // ✅ поле
        Location center = new Location(w, s.centerX, s.centerY, s.centerZ);
        double radius = Math.max(5.0, s.radius);

        Set<TeamKey> used = new HashSet<>();
        for (Player p : w.getPlayers()) {
            TeamKey t = TeamService.getTeam(p).orElse(null);
            if (t != null) used.add(t);
        }
        if (used.isEmpty()) return;

        Map<TeamKey, Location> bases = new HashMap<>();
        List<TeamKey> order = new ArrayList<>(used);
        Collections.shuffle(order, rnd);

        double minTeamDist = 25.0;

        for (TeamKey team : order) {
            Location chosen = null;

            for (int attempt = 0; attempt < 120; attempt++) {
                Location cand = randomPointInCircle(w, center, radius);
                cand.setY(w.getHighestBlockYAt(cand) + 1);

                if (!isSafeSpawn(w, cand)) continue;

                boolean ok = true;
                for (Location other : bases.values()) {
                    if (other.distance(cand) < minTeamDist) { ok = false; break; }
                }
                if (ok) { chosen = cand; break; }
            }

            if (chosen == null) {
                chosen = center.clone();
                chosen.setY(w.getHighestBlockYAt(chosen) + 1);
            }

            bases.put(team, chosen);
        }

        for (Player p : w.getPlayers()) {
            TeamKey t = TeamService.getTeam(p).orElse(null);
            if (t == null) continue;

            Location base = bases.get(t);
            if (base == null) continue;

            Location spawn = jitterNear(w, base, 4);
            p.teleport(spawn);
            p.setFallDistance(0f);
        }
    }


    private static Location randomPointInCircle(World w, Location center, double radius) {
        double ang = rnd.nextDouble() * Math.PI * 2.0;
        double r = Math.sqrt(rnd.nextDouble()) * radius;
        double x = center.getX() + Math.cos(ang) * r;
        double z = center.getZ() + Math.sin(ang) * r;
        return new Location(w, x, center.getY(), z);
    }

    private static Location jitterNear(World w, Location base, double r) {
        for (int i = 0; i < 40; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2.0;
            double dist = rnd.nextDouble() * r;

            double x = base.getX() + Math.cos(ang) * dist;
            double z = base.getZ() + Math.sin(ang) * dist;

            Location loc = new Location(w, x, base.getY(), z);
            loc.setY(w.getHighestBlockYAt(loc) + 1);

            if (isSafeSpawn(w, loc)) return loc;
        }

        Location fallback = base.clone();
        fallback.setY(w.getHighestBlockYAt(fallback) + 1);
        return fallback;
    }
    private static boolean isSafeSpawn(World w, Location loc) {
        var feet = loc.getBlock();
        var head = loc.clone().add(0, 1, 0).getBlock();
        var below = loc.clone().add(0, -1, 0).getBlock();

        // нужно: под ногами твёрдый блок, в ногах/голове воздух
        if (!below.getType().isSolid()) return false;
        if (!feet.getType().isAir()) return false;
        if (!head.getType().isAir()) return false;

        return true;
    }


}
