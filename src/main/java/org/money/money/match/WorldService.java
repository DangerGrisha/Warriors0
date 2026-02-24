package org.money.money.match;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;
import org.money.money.Main;
import org.money.money.config.MapDefinition;
import org.money.money.gui.GuiRefreshService;
import org.money.money.world.MatchWorld;
import org.money.money.world.MatchWorldService;
import org.money.money.world.WorldState;

public final class WorldService {

    private WorldService() {}

    // lowercase the key to avoid case bugs
    private static final java.util.Set<String> restarting = new java.util.HashSet<>();

    public static boolean isRestarting(String worldName) {
        return restarting.contains(worldName.toLowerCase());
    }

    public static void markRestarting(String worldName) {
        restarting.add(worldName.toLowerCase());
        Main.getInstance().getLogger().info("[WorldService] markRestarting " + worldName);
    }

    public static void unmarkRestarting(String worldName) {
        restarting.remove(worldName.toLowerCase());
        Main.getInstance().getLogger().info("[WorldService] unmarkRestarting " + worldName);
    }

    private static void cmd(String s) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), s);
        Main.getInstance().getLogger().info("[WorldService] cmd> " + s);
    }

    public static void resetWorld(String worldName) {
        markRestarting(worldName);

        new BukkitRunnable() {
            @Override public void run() {
                // 0) если мир реально в памяти -> unload через Bukkit
                World w = Bukkit.getWorld(worldName);
                if (w != null) {
                    // телепортни игроков в лобби заранее в твоём endMatch
                    boolean ok = Bukkit.unloadWorld(w, false);
                    Main.getInstance().getLogger().info("[WorldService] unload Bukkit " + worldName + " -> " + ok);
                }

                // 1) убедиться что MV "знает" мир, иначе delete не сработает
                // если MV не знает — делаем import (это безопасно, если папка есть)
                cmd("mv import " + worldName + " normal");

                // 2) теперь уже delete + confirm
                cmd("mv delete " + worldName);
                cmd("mv confirm");

                // 3) на всякий случай remove из конфигов (если остался)
                cmd("mv remove " + worldName);

                // 4) clone из шаблона
                MapDefinition map = Main.getInstance().getMapRegistry().byWorld(worldName);
                cmd("mv clone " + map.templateWorld + " " + worldName);

                // 5) import + load (не всегда нужно, но стабильно)
                cmd("mv import " + worldName + " normal");
                cmd("mv load " + worldName);

                // 6) чуть подождём и снимем restarting
                new BukkitRunnable() {
                    @Override public void run() {
                        boolean loaded = Bukkit.getWorld(worldName) != null;
                        Main.getInstance().getLogger().info("[WorldService] post-load check " + worldName + " loaded=" + loaded);

                        if (loaded) {
                            unmarkRestarting(worldName);

                            MatchWorld mw = MatchWorldService.getOrCreate(worldName);
                            mw.setState(WorldState.AVAILABLE);
                            mw.setCountdownTask(null); // если надо

                            GuiRefreshService.refreshServerSelectionForAllViewers();
                        } else {
                            Main.getInstance().getLogger().warning("[WorldService] world not loaded yet: " + worldName);
                        }
                    }
                }.runTaskLater(Main.getInstance(), 40L); // 2 секунды
            }
        }.runTask(Main.getInstance());


    }
}
