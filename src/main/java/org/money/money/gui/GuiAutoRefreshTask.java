// FILE: src/main/java/org/money/money/gui/GuiAutoRefreshTask.java
package org.money.money.gui;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;
import org.money.money.Main;

public final class GuiAutoRefreshTask {

    private static BukkitRunnable task;

    public static void start() {
        if (task != null) return;

        task = new BukkitRunnable() {
            @Override public void run() {
                // если никто не смотрит GUI — не тратим тик
                boolean anyone = Bukkit.getOnlinePlayers().stream().anyMatch(p ->
                        p.getOpenInventory() != null &&
                                ServerSelectionGUI.TITLE.equals(p.getOpenInventory().getTitle())
                );
                if (!anyone) return;

                GuiRefreshService.refreshServerSelectionForAllViewers();
            }
        };

        // каждые 10 тиков (0.5 сек) — приятно и не жрёт
        task.runTaskTimer(Main.getInstance(), 10L, 10L);
    }
}
