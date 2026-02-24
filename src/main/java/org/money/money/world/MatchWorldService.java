package org.money.money.world;

import java.util.HashMap;
import java.util.Map;

public class MatchWorldService {

    private static final Map<String, MatchWorld> worlds = new HashMap<>();

    public static MatchWorld getOrCreate(String worldName) {
        return worlds.computeIfAbsent(worldName, MatchWorld::new);
    }

    public static MatchWorld get(String worldName) {
        return worlds.get(worldName);
    }

    public static void remove(String worldName) {
        worlds.remove(worldName);
    }
}
