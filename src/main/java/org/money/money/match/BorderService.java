package org.money.money.match;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.scheduler.BukkitRunnable;
import org.money.money.Main;
import org.money.money.config.MapDefinition;

import java.util.HashMap;
import java.util.Map;

public final class BorderService {

    private static final Map<String, BukkitRunnable> shrinkTasks = new HashMap<>();

    private BorderService() {}

    public static void applyOnMatchStart(String worldName, MapDefinition map) {
        if (map == null || map.border == null) return;

        World w = Bukkit.getWorld(worldName);
        if (w == null) return;

        MapDefinition.BorderSettings b = map.border;
        WorldBorder wb = w.getWorldBorder();

        // cancel previous shrink job if any
        cancel(worldName);

        // set base border immediately
        wb.setCenter(b.centerX, b.centerZ);
        wb.setDamageAmount(b.damageAmount);
        wb.setDamageBuffer(b.damageBuffer);
        wb.setWarningTime(b.warningSeconds);
        wb.setWarningDistance(b.warningDistance);

        // IMPORTANT: set start size instantly
        wb.setSize(b.startSize);

        // schedule shrink later
        int delayTicks = Math.max(0, b.shrinkAfterSeconds) * 20;

        BukkitRunnable task = new BukkitRunnable() {
            @Override public void run() {
                World ww = Bukkit.getWorld(worldName);
                if (ww == null) { BorderService.cancel(worldName); return; }

                WorldBorder wbb = ww.getWorldBorder();

                int dur = Math.max(1, b.shrinkDurationSeconds);
                wbb.setSize(b.endSize, dur); // плавно сужаем

                Main.getInstance().getLogger().info("[Border] shrink start world=" + worldName
                        + " " + b.startSize + " -> " + b.endSize + " over " + dur + "s");
            }
        };

        shrinkTasks.put(worldName, task);
        task.runTaskLater(Main.getInstance(), delayTicks);

        Main.getInstance().getLogger().info("[Border] applied world=" + worldName
                + " center=(" + b.centerX + "," + b.centerZ + ") size=" + b.startSize
                + " shrinkAfter=" + b.shrinkAfterSeconds + "s shrinkDur=" + b.shrinkDurationSeconds + "s");
    }

    public static void cancel(String worldName) {
        BukkitRunnable t = shrinkTasks.remove(worldName);
        if (t != null) t.cancel();
    }

    public static void resetToDefault(String worldName) {
        cancel(worldName);

        World w = Bukkit.getWorld(worldName);
        if (w == null) return;

        WorldBorder wb = w.getWorldBorder();
        wb.reset(); // сброс до дефолта мира
    }
}
