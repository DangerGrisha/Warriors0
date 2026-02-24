// FILE: src/main/java/org/money/money/match/MobSpawnService.java
package org.money.money.match;

import org.bukkit.*;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Player;
import org.money.money.Main;
import org.money.money.config.MapDefinition;

import java.util.Random;

public final class MobSpawnService {
    private static final Random rnd = new Random();

    private MobSpawnService() {}

    /** Spawn passive mobs (cow/sheep/pig) in arena area for this map. */
    public static void spawnPassiveMobsForMap(String worldName) {
        World w = Bukkit.getWorld(worldName);
        if (w == null) return;

        MapDefinition map = Main.getInstance().getMapRegistry().byWorld(worldName);
        if (map == null || map.mobs == null || !map.mobs.enabled) return;

        var s = map.spawn; // center + radius
        Location center = new Location(w, s.centerX, s.centerY, s.centerZ);
        double arenaRadius = Math.max(5.0, s.radius);

        int packs = Math.max(1, map.mobs.packs);
        int spawned = 0;

        for (int i = 0; i < packs; i++) {
            // pick a base point in circle
            Location base = randomPointInCircle(w, center, arenaRadius);

            // try find good spot near base
            Location good = null;
            for (int a = 0; a < map.mobs.maxAttemptsPerPack; a++) {
                Location cand = jitterInCircle(w, base, map.mobs.jitterRadius);
                cand.setY(w.getHighestBlockYAt(cand) + 1);

                if (isGoodPassiveSpawnSpot(w, cand)) {
                    good = cand;
                    break;
                }
            }
            if (good == null) continue;

            EntityType type = switch (rnd.nextInt(3)) {
                case 0 -> EntityType.COW;
                case 1 -> EntityType.SHEEP;
                default -> EntityType.PIG;
            };

            int minSize = Math.max(1, map.mobs.minPackSize);
            int maxSize = Math.max(minSize, map.mobs.maxPackSize);
            int count = minSize + rnd.nextInt(maxSize - minSize + 1);

            int ok = 0;
            for (int j = 0; j < count; j++) {
                Location spot = jitterInCircle(w, good, map.mobs.mobJitterRadius);
                spot.setY(w.getHighestBlockYAt(spot) + 1);

                if (!isGoodPassiveSpawnSpot(w, spot)) continue;

                var ent = w.spawnEntity(spot, type);
                ok++;

                if (ent instanceof Sheep sheep) {
                    DyeColor[] colors = {
                            DyeColor.WHITE, DyeColor.LIGHT_GRAY, DyeColor.GRAY,
                            DyeColor.BROWN, DyeColor.BLACK
                    };
                    sheep.setColor(colors[rnd.nextInt(colors.length)]);
                }
            }
            spawned += ok;
        }

        Main.getInstance().getLogger().info("[MobSpawn] world=" + worldName + " packs=" + packs + " spawned=" + spawned);
    }

    private static Location randomPointInCircle(World w, Location center, double radius) {
        double ang = rnd.nextDouble() * Math.PI * 2.0;
        double r = Math.sqrt(rnd.nextDouble()) * radius;
        double x = center.getX() + Math.cos(ang) * r;
        double z = center.getZ() + Math.sin(ang) * r;
        return new Location(w, x, center.getY(), z);
    }

    private static Location jitterInCircle(World w, Location base, double radius) {
        double ang = rnd.nextDouble() * Math.PI * 2.0;
        double r = rnd.nextDouble() * radius;
        double x = base.getX() + Math.cos(ang) * r;
        double z = base.getZ() + Math.sin(ang) * r;
        return new Location(w, x, base.getY(), z);
    }

    /** Safe passive spawn: solid below, air at feet+head, not in liquid, not inside blocks. */
    private static boolean isGoodPassiveSpawnSpot(World w, Location loc) {
        var feet = loc.getBlock();
        var head = loc.clone().add(0, 1, 0).getBlock();
        var below = loc.clone().add(0, -1, 0).getBlock();

        if (!below.getType().isSolid()) return false;
        if (!feet.getType().isAir()) return false;
        if (!head.getType().isAir()) return false;

        // avoid liquids
        if (below.isLiquid()) return false;

        // optional: avoid spawning in player lobby area or too close to players
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(loc) < 10 * 10) return false;
        }

        return true;
    }
}
