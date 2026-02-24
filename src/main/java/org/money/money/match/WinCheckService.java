// FILE: src/main/java/org/money/money/match/WinCheckService.java
package org.money.money.match;

import org.bukkit.*;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.money.money.Main;
import org.money.money.world.MatchWorld;
import org.money.money.world.MatchWorldService;
import org.money.money.world.WorldState;

import java.util.*;

public final class WinCheckService {

    private WinCheckService() {}

    // чтобы не запускать празднование 10 раз
    private static final Set<String> endingWorlds = new HashSet<>();

    /** Call this periodically OR after every death. */
    public static void checkLastTeamAlive(String worldName) {
        if (endingWorlds.contains(worldName)) return;

        MatchWorld mw = MatchWorldService.get(worldName);
        if (mw == null || mw.getState() != WorldState.RUNNING) return;

        World w = Bukkit.getWorld(worldName);
        if (w == null) return;

        // collect alive teams
        Set<TeamKey> aliveTeams = new HashSet<>();
        List<Player> alivePlayers = new ArrayList<>();

        for (Player p : w.getPlayers()) {
            if (p.getGameMode() != GameMode.SURVIVAL) continue;
            if (p.isDead()) continue;

            TeamKey t = TeamService.getTeam(p).orElse(null);
            if (t == null) continue;

            aliveTeams.add(t);
            alivePlayers.add(p);
        }

        if (aliveTeams.size() == 1 && !alivePlayers.isEmpty()) {
            TeamKey winner = aliveTeams.iterator().next();
            startCelebration(worldName, winner);
        }
    }

    private static void startCelebration(String worldName, TeamKey winner) {
        World w = Bukkit.getWorld(worldName);
        if (w == null) return;

        endingWorlds.add(worldName);

        // mark match as not running (optional, but prevents other logic)
        MatchWorld mw = MatchWorldService.get(worldName);
        if (mw != null) mw.setState(WorldState.RESTARTING);

        Bukkit.broadcastMessage(winner.chat.toString() + "§l" + winner.id + " §aWINS! §7(ending in 10s)");

        // 10 seconds, fireworks every 0.5 sec (20 ticks = 1 sec)
        new BukkitRunnable() {
            int ticksLeft = 10 * 2; // 10s, each run every 10 ticks

            @Override public void run() {
                World ww = Bukkit.getWorld(worldName);
                if (ww == null) {
                    cancel();
                    return;
                }

                if (ticksLeft-- <= 0) {
                    cancel();
                    endingWorlds.remove(worldName);

                    // ✅ endgame (твоя логика)
                    MatchEndService.end(worldName);
                    return;
                }

                // spawn fireworks near winners only
                for (Player p : ww.getPlayers()) {
                    if (p.getGameMode() != GameMode.SURVIVAL) continue;

                    TeamKey t = TeamService.getTeam(p).orElse(null);
                    if (t != winner) continue;

                    Location c = p.getLocation().clone();
                    for (int i = 0; i < 3; i++) {
                        spawnCelebrationFireworkAround(c, 2.0, 5.0);
                    }
                }
            }
        }.runTaskTimer(Main.getInstance(), 0L, 30L);
    }

    private static final Random rnd = new Random();

    private static void spawnCelebrationFireworkAround(Location center, double radiusMin, double radiusMax) {
        World w = center.getWorld();
        if (w == null) return;

        // угол + радиус
        double ang = rnd.nextDouble() * Math.PI * 2.0;
        double r = radiusMin + rnd.nextDouble() * Math.max(0.0, (radiusMax - radiusMin));

        double x = center.getX() + Math.cos(ang) * r;
        double z = center.getZ() + Math.sin(ang) * r;

        // спавним чуть выше земли, чтобы не в игроке
        int y = w.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z)) + 1;
        Location loc = new Location(w, x, y + 0.2, z);

        Firework fw = w.spawn(loc, Firework.class);
        FireworkMeta meta = fw.getFireworkMeta();

        meta.addEffect(FireworkEffect.builder()
                .with(FireworkEffect.Type.BALL_LARGE)
                .trail(true)
                .flicker(true)
                .withColor(Color.WHITE, Color.AQUA, Color.YELLOW)
                .withFade(Color.ORANGE)
                .build());

        // power влияет на высоту полёта (0..3 обычно). 1-2 = норм
        meta.setPower(1 + rnd.nextInt(2)); // 1 или 2
        fw.setFireworkMeta(meta);

        // НЕ detonating instantly — пусть летит сам
    }
}