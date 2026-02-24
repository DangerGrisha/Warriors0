// FILE: src/main/java/org/money/money/match/MatchTimer.java
package org.money.money.match;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.boss.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.money.money.Main;

import java.util.*;

public final class MatchTimer {

    private static final Map<String, BossBar> bars = new HashMap<>();
    private static final Map<String, BukkitRunnable> tasks = new HashMap<>();

    private MatchTimer() {}

    public static void start(String worldName, int totalSeconds) {
        stop(worldName);

        World w = Bukkit.getWorld(worldName);
        if (w == null) return;

        int safeTotal = Math.max(1, totalSeconds);

        BossBar bar = Bukkit.createBossBar("", BarColor.YELLOW, BarStyle.SOLID);
        bars.put(worldName, bar);

        for (Player p : w.getPlayers()) bar.addPlayer(p);

        BukkitRunnable task = new BukkitRunnable() {
            int left = safeTotal;

            @Override
            public void run() {
                World ww = Bukkit.getWorld(worldName);
                if (ww == null) {
                    cancel();
                    return;
                }

                // ✅ remove players who left this world/offline
                // (copy to avoid concurrent modification)
                List<Player> current = new ArrayList<>(bar.getPlayers());
                for (Player pl : current) {
                    if (pl == null || !pl.isOnline() || pl.getWorld() == null || !pl.getWorld().getName().equals(worldName)) {
                        bar.removePlayer(pl);
                    }
                }

                // ✅ add missing players from this world
                for (Player p : ww.getPlayers()) {
                    if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
                }

                bar.setTitle("§e" + worldName + " §7| §f" + fmt(left));
                bar.setProgress(Math.max(0.0, Math.min(1.0, left / (double) safeTotal)));

                left--;
                if (left < 0) {
                    bar.setTitle("§eTime's up!");
                    cancel();
                    MatchOrchestrator.endMatch(worldName);
                }
            }
        };

        task.runTaskTimer(Main.getInstance(), 0L, 20L);
        tasks.put(worldName, task);
    }

    public static void stop(String worldName) {
        BukkitRunnable t = tasks.remove(worldName);
        if (t != null) t.cancel();

        BossBar b = bars.remove(worldName);
        if (b != null) b.removeAll();
    }

    private static String fmt(int secs) {
        int m = Math.max(0, secs) / 60;
        int s = Math.max(0, secs) % 60;
        return String.format("%02d:%02d", m, s);
    }
}
