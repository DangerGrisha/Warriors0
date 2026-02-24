// FILE: src/main/java/org/money/money/world/WorldStateResolver.java
package org.money.money.world;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.money.money.Main;
import org.money.money.match.WorldService;
import org.money.money.world.MatchWorld;

public final class WorldStateResolver {

    private WorldStateResolver() {}

    public static WorldState get(String worldName) {

        // 1) если ресетим — всегда чёрный
        if (WorldService.isRestarting(worldName)) return WorldState.RESTARTING;

        // 2) если мир не загружен — недоступен
        World w = Bukkit.getWorld(worldName);
        if (w == null) return WorldState.RESTARTING;


        // 3) WAITING / RUNNING берём из MatchWorld, но НЕ RESTARTING
        MatchWorld mw = MatchWorldService.get(worldName);
        if (mw != null) {
            WorldState st = mw.getState();
            if (st == WorldState.RUNNING || st == WorldState.WAITING) return st;
        }

        // 4) иначе доступен
        return WorldState.AVAILABLE;
    }
}
