package org.money.money.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class MapRegistry {

    private final List<MapDefinition> maps = new ArrayList<>();

    public MapRegistry(JavaPlugin plugin) {
        load(plugin);
    }

    public List<MapDefinition> getMaps() {
        return Collections.unmodifiableList(maps);
    }

    private void load(JavaPlugin plugin) {
        maps.clear();

        try (var is = plugin.getResource("maps.yml")) {
            if (is == null) {
                plugin.getLogger().warning("[MapRegistry] maps.yml not found in resources!");
                return;
            }

            try (var reader = new InputStreamReader(is)) {
                FileConfiguration cfg = YamlConfiguration.loadConfiguration(reader);

                var list = cfg.getMapList("maps");
                // FILE: MapRegistry.java (фрагмент внутри for)
                for (Object obj : list) {
                    if (!(obj instanceof Map<?, ?> m)) continue;

                    String id = getStr(m, "id", null);
                    if (id == null || id.isBlank()) continue;

                    String displayName = getStr(m, "displayName", id);
                    String worldName   = getStr(m, "worldName", id);
                    String templateWorld = getStr(m, "templateWorld", "lastwarGame0");

                    int minPlayers   = getInt(m, "minPlayers", 2);
                    int maxPlayers   = getInt(m, "maxPlayers", 10);
                    boolean enabled  = getBool(m, "enabled", true);

                    @SuppressWarnings("unchecked")
                    Map<?, ?> borderMap = (m.get("border") instanceof Map<?, ?> bm) ? bm : java.util.Collections.emptyMap();
                    @SuppressWarnings("unchecked")
                    Map<?, ?> spawnMap  = (m.get("spawn")  instanceof Map<?, ?> sm) ? sm : java.util.Collections.emptyMap();
                    @SuppressWarnings("unchecked")
                    Map<?, ?> mobsMap   = (m.get("mobs")   instanceof Map<?, ?> mm) ? mm : java.util.Collections.emptyMap();

                    // border
                    var border = new MapDefinition.BorderSettings(
                            getDouble(borderMap, "centerX", 0.5),
                            getDouble(borderMap, "centerZ", 0.5),
                            getDouble(borderMap, "startSize", 200.0),
                            getDouble(borderMap, "endSize", 1.0),
                            getInt(borderMap, "totalSeconds", 1200),
                            getInt(borderMap, "shrinkAfterSeconds", 600),
                            getInt(borderMap, "shrinkDurationSeconds", 540),
                            getDouble(borderMap, "damageAmount", 2.0),
                            getDouble(borderMap, "damageBuffer", 0.0),
                            getInt(borderMap, "warningSeconds", 10),
                            getInt(borderMap, "warningDistance", 5)
                    );

                    // spawn
                    var spawn = new MapDefinition.SpawnSettings(
                            getDouble(spawnMap, "centerX", 0.5),
                            getDouble(spawnMap, "centerY", 70.0),
                            getDouble(spawnMap, "centerZ", 0.5),
                            getDouble(spawnMap, "radius", 25.0)
                    );

                    // mobs
                    var mobs = new MapDefinition.MobSettings(
                            getBool(mobsMap, "enabled", false),
                            getInt(mobsMap, "packs", 10),
                            getInt(mobsMap, "minPackSize", 2),
                            getInt(mobsMap, "maxPackSize", 4),
                            getInt(mobsMap, "maxAttemptsPerPack", 16),
                            getDouble(mobsMap, "jitterRadius", 6.0),
                            getDouble(mobsMap, "mobJitterRadius", 3.0)
                    );

                    maps.add(new MapDefinition(id, displayName, worldName, templateWorld,
                            minPlayers, maxPlayers, enabled, border, spawn, mobs));
                }

            }
        } catch (Exception e) {
            plugin.getLogger().warning("[MapRegistry] Failed to load maps.yml: " + e.getMessage());
        }

        plugin.getLogger().info("[MapRegistry] Loaded maps: " + maps.size());
    }

    private static double getDouble(Map<?, ?> m, String key, double def) {
        Object v = m.get(key);
        if (v == null) return def;
        try {
            if (v instanceof Number n) return n.doubleValue();
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception ignored) {
            return def;
        }
    }

    private static String getStr(Map<?, ?> m, String key, String def) {
        Object v = m.get(key);
        if (v == null) return def;
        String s = String.valueOf(v);
        return s.isBlank() ? def : s;
    }

    private static int getInt(Map<?, ?> m, String key, int def) {
        Object v = m.get(key);
        if (v == null) return def;
        try {
            if (v instanceof Number n) return n.intValue();
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception ignored) {
            return def;
        }
    }

    private static boolean getBool(Map<?, ?> m, String key, boolean def) {
        Object v = m.get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    public MapDefinition byWorld(String worldName) {
        if (worldName == null) return null;

        for (MapDefinition m : maps) {
            if (m == null) continue;
            if (worldName.equalsIgnoreCase(m.worldName)) return m; // ignore case на всякий
        }
        return null;
    }

    public MapDefinition byId(String id) {
        if (id == null) return null;

        for (MapDefinition m : maps) {
            if (m == null) continue;
            if (id.equalsIgnoreCase(m.id)) return m;
        }
        return null;
    }

}
