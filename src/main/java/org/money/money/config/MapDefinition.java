package org.money.money.config;

public class MapDefinition {
    public final String id;
    public final String displayName;
    public final String worldName;
    public final String templateWorld;
    public final int minPlayers;
    public final int maxPlayers;
    public final boolean enabled;

    public final BorderSettings border;
    public final SpawnSettings spawn;
    public final MobSettings mobs;

    public MapDefinition(String id, String displayName, String worldName, String templateWorld,
                         int minPlayers, int maxPlayers, boolean enabled,
                         BorderSettings border, SpawnSettings spawn, MobSettings mobs) {
        this.id = id;
        this.displayName = displayName;
        this.worldName = worldName;
        this.templateWorld = templateWorld;
        this.minPlayers = minPlayers;
        this.maxPlayers = maxPlayers;
        this.enabled = enabled;
        this.border = border;
        this.spawn = spawn;
        this.mobs = mobs;
    }

    public static class BorderSettings {
        public final double centerX, centerZ;
        public final double startSize, endSize;
        public final int totalSeconds;
        public final int shrinkAfterSeconds;
        public final int shrinkDurationSeconds;
        public final double damageAmount;
        public final double damageBuffer;
        public final int warningSeconds;
        public final int warningDistance;

        public BorderSettings(double centerX, double centerZ,
                              double startSize, double endSize,
                              int totalSeconds, int shrinkAfterSeconds, int shrinkDurationSeconds,
                              double damageAmount, double damageBuffer,
                              int warningSeconds, int warningDistance) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.startSize = startSize;
            this.endSize = endSize;
            this.totalSeconds = totalSeconds;
            this.shrinkAfterSeconds = shrinkAfterSeconds;
            this.shrinkDurationSeconds = shrinkDurationSeconds;
            this.damageAmount = damageAmount;
            this.damageBuffer = damageBuffer;
            this.warningSeconds = warningSeconds;
            this.warningDistance = warningDistance;
        }
    }
    public static class MobSettings {
        public final boolean enabled;
        public final int packs;
        public final int minPackSize;
        public final int maxPackSize;
        public final int maxAttemptsPerPack;
        public final double jitterRadius;
        public final double mobJitterRadius;

        public MobSettings(boolean enabled, int packs, int minPackSize, int maxPackSize,
                           int maxAttemptsPerPack, double jitterRadius, double mobJitterRadius) {
            this.enabled = enabled;
            this.packs = packs;
            this.minPackSize = minPackSize;
            this.maxPackSize = maxPackSize;
            this.maxAttemptsPerPack = maxAttemptsPerPack;
            this.jitterRadius = jitterRadius;
            this.mobJitterRadius = mobJitterRadius;
        }
    }

    public static class SpawnSettings {
        public final double centerX, centerY, centerZ;
        public final double radius;

        public SpawnSettings(double centerX, double centerY, double centerZ, double radius) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.radius = radius;
        }
    }
}
