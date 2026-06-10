package org.money.money.kits.blastborn;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Blastborn shared explosion helpers.
 *
 * <p>All effects here are <em>manual</em>: knockback, damage, particles and (optionally) safe
 * block breaking. Nothing in this class ever calls {@code World#createExplosion}, so it can never
 * grief the world by itself — block breaking only happens through {@link #breakBlocksSafely} and
 * respects a hard denylist + a radius cap.
 */
public final class ExplosionUtil {

    private ExplosionUtil() {
    }

    /* ================== Ray tracing ================== */

    /**
     * First solid block hit along the player's eye direction within {@code maxDist}
     * (fluids ignored), or {@code null} if nothing solid is in the way.
     */
    public static RayTraceResult rayTrace(Player p, double maxDist) {
        World w = p.getWorld();
        return w.rayTraceBlocks(
                p.getEyeLocation(),
                p.getEyeLocation().getDirection(),
                maxDist,
                FluidCollisionMode.NEVER,
                true);
    }

    /** True if there is NO solid block within {@code dist} along the eye direction (clear air path). */
    public static boolean hasClearPath(Player p, double dist) {
        return rayTrace(p, dist) == null;
    }

    /* ================== Visuals ================== */

    /**
     * Particles + sound only — NO block damage. Spawns an explosion burst scaled by {@code size},
     * smoke, optional flames, and the generic explosion sound at {@code center}'s world.
     */
    public static void visualExplosion(Location center, float size, boolean flame) {
        World w = center.getWorld();
        if (w == null) return;

        int emitters = Math.max(1, Math.round(size / 3.0f));

        w.spawnParticle(Particle.EXPLOSION, center, 1, 0.0, 0.0, 0.0, 0.0);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, center, emitters, 0.0, 0.0, 0.0, 0.0);
        w.spawnParticle(Particle.LARGE_SMOKE, center, 12, size * 0.25, size * 0.25, size * 0.25, 0.02);
        if (flame) {
            w.spawnParticle(Particle.FLAME, center, 16, size * 0.3, size * 0.3, size * 0.3, 0.05);
        }

        w.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.4f, 1.0f);
    }

    /* ================== Knockback ================== */

    /**
     * Push players within {@code radius} away from {@code center} (horizontal + a little up),
     * with distance falloff. {@code includeSource} also pushes the source; {@code enemiesOnly}
     * skips allies of the source.
     */
    public static void knockbackPlayers(Location center, double radius, double strength, double extraUp,
                                        Player source, boolean includeSource, boolean enemiesOnly) {
        knockbackPlayers(center, radius, strength, extraUp, source, includeSource, enemiesOnly, 1.0);
    }

    /**
     * Same as above, but the SOURCE (caster) is flung {@code selfMultiplier}× harder than everyone
     * else — Blastborn rides his own blasts, so his self-knockback is several times stronger.
     */
    public static void knockbackPlayers(Location center, double radius, double strength, double extraUp,
                                        Player source, boolean includeSource, boolean enemiesOnly,
                                        double selfMultiplier) {
        World w = center.getWorld();
        if (w == null || radius <= 0) return;

        for (Entity ent : w.getNearbyEntities(center, radius, radius, radius)) {
            if (!(ent instanceof Player target)) continue;
            if (!target.isOnline() || target.isDead()) continue;

            boolean isSource = source != null && target.getUniqueId().equals(source.getUniqueId());
            if (isSource && !includeSource) continue;
            if (enemiesOnly && !isSource && source != null && isFriendly(source, target)) continue;

            double dist = target.getLocation().distance(center);
            if (dist > radius) continue;

            // horizontal direction from center -> target
            Vector dir = target.getLocation().toVector().subtract(center.toVector());
            dir.setY(0);
            if (dir.lengthSquared() < 1e-6) {
                dir = new Vector(0, 1, 0); // dead-center: shove straight up
            } else {
                dir.normalize();
            }

            double falloff = Math.max(0.0, 1.0 - dist / radius);
            double mult = isSource ? Math.max(1.0, selfMultiplier) : 1.0;

            Vector v = dir.multiply(strength * falloff * mult);
            v.setY(extraUp * falloff * mult);

            // guard against NaN / absurd values before applying (cap scales with the self mult)
            if (!isFinite(v)) continue;
            clamp(v, 10.0 * mult);

            target.setVelocity(target.getVelocity().add(v));
        }
    }

    /* ================== Damage ================== */

    /**
     * Damage players within a spherical {@code radius}. Allies are skipped unless
     * {@code friendlyFire}. The source is only damaged when {@code includeSource}
     * (taking {@code damage * sourceDamageMult}). Uses {@code target.damage(dmg, source)}.
     */
    public static void damagePlayers(Location center, double radius, double damage,
                                     Player source, boolean includeSource, double sourceDamageMult,
                                     boolean friendlyFire) {
        World w = center.getWorld();
        if (w == null || radius <= 0) return;

        for (Entity ent : w.getNearbyEntities(center, radius, radius, radius)) {
            if (!(ent instanceof Player target)) continue;
            if (!target.isOnline() || target.isDead()) continue;

            double dist = target.getLocation().distance(center);
            if (dist > radius) continue;

            boolean isSource = source != null && target.getUniqueId().equals(source.getUniqueId());
            if (isSource) {
                if (includeSource && damage > 0) {
                    target.damage(damage * sourceDamageMult, source);
                }
                continue;
            }

            if (!friendlyFire && source != null && isFriendly(source, target)) continue;
            if (damage > 0) {
                target.damage(damage, source);
            }
        }
    }

    /* ================== Block breaking ================== */

    /**
     * Break breakable blocks within a spherical {@code radius} around {@code center}. Skips air,
     * liquids, unbreakable blocks (hardness &lt; 0) and a hard denylist. Spawns block-crack
     * particles per broken block and returns the count. Iteration is capped at radius &lt;= 8.
     */
    public static int breakBlocksSafely(Location center, double radius) {
        World w = center.getWorld();
        if (w == null || radius <= 0) return 0;

        // hard cap for safety so a bad config value can't try to iterate a huge cube
        double r = Math.min(radius, 8.0);
        int ir = (int) Math.ceil(r);
        double rSq = r * r;

        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        int count = 0;
        for (int dx = -ir; dx <= ir; dx++) {
            for (int dy = -ir; dy <= ir; dy++) {
                for (int dz = -ir; dz <= ir; dz++) {
                    if (dx * dx + dy * dy + dz * dz > rSq) continue;

                    Block block = w.getBlockAt(cx + dx, cy + dy, cz + dz);
                    Material type = block.getType();

                    if (type == Material.AIR || block.isLiquid()) continue;
                    if (type.getHardness() < 0) continue; // unbreakable (e.g. bedrock)
                    if (isProtectedMaterial(type)) continue;

                    // TODO: integrate a region/protection check here before breaking.

                    Location bc = block.getLocation().add(0.5, 0.5, 0.5);
                    w.spawnParticle(Particle.BLOCK, bc, 12, 0.25, 0.25, 0.25, 0.0, block.getBlockData());

                    // Remove WITHOUT dropping items (setType air, no physics) — blasted blocks
                    // are destroyed, not harvested.
                    block.setType(Material.AIR, false);
                    count++;
                }
            }
        }
        return count;
    }

    /** Materials we never break even if technically breakable. */
    private static boolean isProtectedMaterial(Material type) {
        if (type == Material.BEDROCK || type == Material.BARRIER) return true;
        if (type == Material.END_PORTAL || type == Material.END_PORTAL_FRAME) return true;
        if (type == Material.REINFORCED_DEEPSLATE) return true;

        String name = type.name();
        if (name.endsWith("COMMAND_BLOCK")) return true; // COMMAND_BLOCK / CHAIN_COMMAND_BLOCK / REPEATING_COMMAND_BLOCK
        if (name.contains("STRUCTURE")) return true;     // STRUCTURE_BLOCK / STRUCTURE_VOID
        if (name.equals("JIGSAW")) return true;
        return false;
    }

    /* ================== Safe teleport ================== */

    /**
     * Nearest safe standing spot to {@code loc} (feet + head passable, solid floor below). If
     * {@code loc} itself is safe it is returned; otherwise scan +1..+6 up; otherwise fall back to
     * the highest block only if that is safe; otherwise {@code null} so the caller aborts.
     */
    public static Location safeTeleport(Location loc) {
        if (loc == null) return null;
        World w = loc.getWorld();
        if (w == null) return null;

        if (isSafe(loc)) return loc;

        for (int dy = 1; dy <= 6; dy++) {
            Location candidate = loc.clone().add(0, dy, 0);
            if (isSafe(candidate)) return candidate;
        }

        int hy = w.getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ());
        Location top = new Location(w, loc.getX(), hy + 1.0, loc.getZ(), loc.getYaw(), loc.getPitch());
        if (!isSafe(top)) return null;
        return top;
    }

    /** Feet + head must be passable, floor (block below feet) must be solid. */
    private static boolean isSafe(Location loc) {
        World w = loc.getWorld();
        if (w == null) return false;
        Block feet = loc.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block floor = feet.getRelative(0, -1, 0);
        if (feet.getType().isSolid()) return false;
        if (head.getType().isSolid()) return false;
        // require something to stand on (avoid teleporting into a void pocket)
        return floor.getType().isSolid();
    }

    /* ================== Team check ================== */

    /** Both players on the same non-null main-scoreboard team => allies. */
    public static boolean isFriendly(Player a, Player b) {
        if (a == null || b == null) return false;
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team ta = sb.getEntryTeam(a.getName());
        Team tb = sb.getEntryTeam(b.getName());
        if (ta == null || tb == null) return false;
        return ta.getName().equalsIgnoreCase(tb.getName());
    }

    /* ================== Vector helpers ================== */

    private static boolean isFinite(Vector v) {
        return Double.isFinite(v.getX()) && Double.isFinite(v.getY()) && Double.isFinite(v.getZ());
    }

    /** Clamp each component into [-max, max] so a stray multiplier can't fling players absurdly. */
    private static void clamp(Vector v, double max) {
        v.setX(Math.max(-max, Math.min(max, v.getX())));
        v.setY(Math.max(-max, Math.min(max, v.getY())));
        v.setZ(Math.max(-max, Math.min(max, v.getZ())));
    }
}
