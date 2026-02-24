package org.money.money.match;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.money.money.Main;
import org.money.money.config.MapDefinition;
import org.money.money.session.PlayerState;
import org.money.money.session.SessionService;

public class MatchEndService {

    public static void end(String worldName) {
        World w = Bukkit.getWorld(worldName);
        if (w == null) return;

        for (Player p : w.getPlayers()) {
            // сбросить кит-тег чтобы в следующей игре кит выдался снова
            p.removeScoreboardTag("KIT_GIVEN");
            SessionService.get().setState(p, PlayerState.LOBBY, "world");
        }

        new BukkitRunnable() {
            @Override public void run() {
                MapDefinition map = Main.getInstance().getMapRegistry().byWorld(worldName);
                WorldService.resetWorld(map.worldName);
            }
        }.runTaskLater(Main.getInstance(), 40L);
    }
}
