// FILE: src/main/java/org/money/money/match/QueueService.java
package org.money.money.match;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.money.money.Main;
import org.money.money.world.MatchWorld;
import org.money.money.world.MatchWorldService;
import org.money.money.world.WorldState;

import java.util.List;

public class QueueService {

    private static final int MIN_PLAYERS = 2;
    private static final int COUNTDOWN_SECONDS = 20;

    public static void tryStartCountdown(String worldName) {
        World w = Bukkit.getWorld(worldName);
        if (w == null) return;

        Main.getInstance().getLogger().info("[Queue] tryStartCountdown world=" + worldName
                + " players=" + w.getPlayers().size());

        // hard block while resetting
        if (WorldService.isRestarting(worldName)) return;

        MatchWorld mw = MatchWorldService.getOrCreate(worldName);

        TeamService.setLocked(worldName, false);


        // no queue if match running or restarting
        if (mw.getState() == WorldState.RUNNING || mw.getState() == WorldState.RESTARTING) return;

        List<Player> players = w.getPlayers();
        if (players.size() < MIN_PLAYERS) {
            stopCountdown(worldName);
            mw.setState(WorldState.WAITING);
            return;
        }

        // countdown already running
        if (mw.getCountdownTask() != null) return;

        mw.setState(WorldState.WAITING);

        BukkitRunnable task = new BukkitRunnable() {
            int left = COUNTDOWN_SECONDS;

            @Override
            public void run() {
                World ww = Bukkit.getWorld(worldName);
                if (ww == null) {
                    stopCountdown(worldName);
                    return;
                }

                List<Player> now = ww.getPlayers();
                if (now.size() < MIN_PLAYERS) {
                    now.forEach(p -> p.sendMessage("§cNot enough players. Countdown cancelled."));
                    stopCountdown(worldName);
                    return;
                }

                if (left <= 0) {
                    stopCountdown(worldName);

                    // ✅ lock teams now
                    TeamService.setLocked(worldName, true);

                    // ✅ assign missing players using balance rule
                    TeamService.assignMissingBalanced(worldName);

                    // go to class selection
                    MatchOrchestrator.onCountdownFinished(worldName);
                    return;
                }

                now.forEach(p -> p.sendMessage("§eGame starts in §6" + left + "§e..."));
                left--;
            }
        };

        mw.setCountdownTask(task);
        task.runTaskTimer(Main.getInstance(), 0L, 20L);
    }

    public static void stopCountdown(String worldName) {
        MatchWorld mw = MatchWorldService.get(worldName);
        if (mw == null) return;

        BukkitRunnable r = mw.getCountdownTask();
        if (r != null) r.cancel();
        mw.setCountdownTask(null);
    }
}
