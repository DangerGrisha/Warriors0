package org.money.money.kits.bluerose;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;

/**
 * Blue Rose Guardian — общие визуалы (частицы + звуки).
 *
 * <p>Чистые статические функции без состояния. Класс «выращивает» защиту: голубые/белые
 * лепестки, ледяная пыль, морозные шипы. Намеренно экономно по частицам/без entity, чтобы
 * не нагружать сервер — все зоны рисуются частицами, а не реальными блоками/мобами.
 */
public final class BlueRoseVisualUtil {

    private BlueRoseVisualUtil() {}

    // Палитра «голубой морозной розы».
    public static final Color ROSE_BLUE   = Color.fromRGB(80, 170, 255);
    public static final Color ROSE_CYAN   = Color.fromRGB(120, 220, 255);
    public static final Color ROSE_WHITE  = Color.fromRGB(225, 245, 255);
    public static final Color ROSE_DEEP   = Color.fromRGB(40, 110, 220);

    private static final Particle.DustOptions DUST_BLUE  = new Particle.DustOptions(ROSE_BLUE, 1.0f);
    private static final Particle.DustOptions DUST_CYAN  = new Particle.DustOptions(ROSE_CYAN, 1.1f);
    private static final Particle.DustOptions DUST_WHITE = new Particle.DustOptions(ROSE_WHITE, 0.9f);
    private static final Particle.DustOptions DUST_DEEP  = new Particle.DustOptions(ROSE_DEEP, 1.2f);

    // Палитра «сакуры» (только Petal Step): розовый с лёгкой примесью голубого.
    public static final Color SAKURA_PINK  = Color.fromRGB(255, 174, 201);
    public static final Color SAKURA_DEEP  = Color.fromRGB(240, 110, 160);
    public static final Color SAKURA_LIGHT = Color.fromRGB(255, 228, 240);
    public static final Color SAKURA_BLUE  = Color.fromRGB(150, 200, 255);

    private static final Particle.DustOptions DUST_SAKURA       = new Particle.DustOptions(SAKURA_PINK, 1.0f);
    private static final Particle.DustOptions DUST_SAKURA_LIGHT = new Particle.DustOptions(SAKURA_LIGHT, 0.9f);
    private static final Particle.DustOptions DUST_SAKURA_BLUE  = new Particle.DustOptions(SAKURA_BLUE, 0.9f);

    /* ===================== Кольца на земле ===================== */

    /** Голубое кольцо лепестков по границе радиуса (тонкая декоративная окружность). */
    public static void groundRing(Location center, double radius, int points) {
        groundRing(center, radius, points, false);
    }

    /**
     * То же кольцо, но с флагом {@code force}. При {@code force=true} частицы шлются как
     * «важные»: их видно ВСЕМ игрокам рядом независимо от клиентской настройки частиц
     * (Decreased/Minimal) — иначе часть игроков вовсе не видит кольцо (баг «не видно розу-хранителя»).
     */
    public static void groundRing(Location center, double radius, int points, boolean force) {
        World w = center.getWorld();
        if (w == null || radius <= 0 || points <= 0) return;
        for (int i = 0; i < points; i++) {
            double ang = (2 * Math.PI * i) / points;
            double x = Math.cos(ang) * radius;
            double z = Math.sin(ang) * radius;
            Location p = center.clone().add(x, 0.12, z);
            Particle.DustOptions dust = (i % 2 == 0) ? DUST_BLUE : DUST_WHITE;
            w.spawnParticle(Particle.DUST, p, 1, 0.0, 0.0, 0.0, 0.0, dust, force);
        }
    }

    /** Мягкая снежная пыль над областью (немного частиц, рассеяны по кругу). */
    public static void frostDust(Location center, double radius, int amount) {
        frostDust(center, radius, amount, false);
    }

    /** То же, с флагом {@code force} (см. {@link #groundRing(Location, double, int, boolean)}). */
    public static void frostDust(Location center, double radius, int amount, boolean force) {
        World w = center.getWorld();
        if (w == null || amount <= 0) return;
        for (int i = 0; i < amount; i++) {
            double ang = Math.random() * Math.PI * 2;
            double r = Math.sqrt(Math.random()) * radius;
            double x = Math.cos(ang) * r;
            double z = Math.sin(ang) * r;
            double y = 0.4 + Math.random() * 1.6;
            w.spawnParticle(Particle.SNOWFLAKE, center.clone().add(x, y, z), 1, 0.0, 0.0, 0.0, 0.0, null, force);
        }
    }

    /* ===================== Роза / шипы ===================== */

    /** Небольшая голубая роза-«бутон» (компактный столбик лепестков). */
    public static void roseBloom(Location at) {
        World w = at.getWorld();
        if (w == null) return;
        Location base = at.clone().add(0, 0.15, 0);
        w.spawnParticle(Particle.DUST, base, 6, 0.18, 0.10, 0.18, 0.0, DUST_DEEP);
        w.spawnParticle(Particle.DUST, base.clone().add(0, 0.35, 0), 8, 0.16, 0.12, 0.16, 0.0, DUST_BLUE);
        w.spawnParticle(Particle.DUST, base.clone().add(0, 0.6, 0), 6, 0.12, 0.10, 0.12, 0.0, DUST_WHITE);
    }

    /** Раскрытие большой розы (для emergency/death-save/ульты) — расходящаяся вспышка лепестков. */
    public static void roseBurst(Location at, float scale) {
        World w = at.getWorld();
        if (w == null) return;
        Location c = at.clone().add(0, 1.0, 0);
        w.spawnParticle(Particle.DUST, c, (int) (30 * scale), 0.6 * scale, 0.8 * scale, 0.6 * scale, 0.0, DUST_BLUE);
        w.spawnParticle(Particle.DUST, c, (int) (24 * scale), 0.7 * scale, 0.9 * scale, 0.7 * scale, 0.0, DUST_WHITE);
        w.spawnParticle(Particle.SNOWFLAKE, c, (int) (24 * scale), 0.7 * scale, 0.7 * scale, 0.7 * scale, 0.02);
        w.spawnParticle(Particle.FLASH, c, 1, 0.0, 0.0, 0.0, 0.0);
    }

    /** Морозный шип из земли в точке (короткий вертикальный «выстрел» из льда). */
    public static void iceSpike(Location at) {
        World w = at.getWorld();
        if (w == null) return;
        for (double dy = 0; dy <= 1.4; dy += 0.35) {
            w.spawnParticle(Particle.DUST, at.clone().add(0, dy, 0), 2, 0.06, 0.05, 0.06, 0.0, DUST_CYAN);
        }
        w.spawnParticle(Particle.ITEM_SNOWBALL, at.clone().add(0, 0.6, 0), 6, 0.12, 0.4, 0.12, 0.02);
    }

    /** Дорожка лепестков между двумя точками (для Petal Step / Rosebind линии). */
    public static void petalLine(Location from, Location to, double step) {
        World w = from.getWorld();
        if (w == null || to.getWorld() == null || !w.equals(to.getWorld())) return;
        double dist = from.distance(to);
        if (dist < 1e-3) { roseBloom(from); return; }
        org.bukkit.util.Vector dir = to.toVector().subtract(from.toVector()).normalize();
        for (double d = 0; d <= dist; d += step) {
            Location p = from.clone().add(dir.clone().multiply(d)).add(0, 0.12, 0);
            w.spawnParticle(Particle.DUST, p, 1, 0.06, 0.02, 0.06, 0.0, (d % 1.0 < 0.5) ? DUST_BLUE : DUST_WHITE);
        }
    }

    /**
     * Плоская «плитка» сакуры на уровне ног (Petal Step): низкие розовые лепестки с лёгкой
     * голубой примесью + изредка падающий лепесток вишни. Высота ≤ ~0.5 блока — линия в один блок.
     */
    public static void sakuraTrailTile(Location at) {
        World w = at.getWorld();
        if (w == null) return;
        Location base = at.clone();
        w.spawnParticle(Particle.DUST, base.clone().add(0, 0.06, 0), 3, 0.34, 0.03, 0.34, 0.0, DUST_SAKURA);
        w.spawnParticle(Particle.DUST, base.clone().add(0, 0.14, 0), 2, 0.30, 0.04, 0.30, 0.0, DUST_SAKURA_LIGHT);
        if (Math.random() < 0.30)
            w.spawnParticle(Particle.DUST, base.clone().add(0, 0.10, 0), 1, 0.32, 0.03, 0.32, 0.0, DUST_SAKURA_BLUE);
        if (Math.random() < 0.18)
            w.spawnParticle(Particle.CHERRY_LEAVES, base.clone().add(0, 0.45, 0), 1, 0.28, 0.06, 0.28, 0.0);
    }

    /** Линия сакуры на уровне ног между двумя точками (стартовый росчерк Petal Step). */
    public static void sakuraLine(Location from, Location to, double step) {
        World w = from.getWorld();
        if (w == null || to.getWorld() == null || !w.equals(to.getWorld())) return;
        double dist = from.distance(to);
        if (dist < 1e-3) { sakuraTrailTile(from); return; }
        org.bukkit.util.Vector dir = to.toVector().subtract(from.toVector()).normalize();
        for (double d = 0; d <= dist; d += step) {
            Location p = from.clone().add(dir.clone().multiply(d)).add(0, 0.1, 0);
            w.spawnParticle(Particle.DUST, p, 1, 0.06, 0.02, 0.06, 0.0, (d % 1.0 < 0.5) ? DUST_SAKURA : DUST_SAKURA_LIGHT);
        }
    }

    /** Голубые лепестки вокруг тела игрока (Rose Seed / Heritage щит на союзнике). */
    public static void bodyPetals(Location feet) {
        World w = feet.getWorld();
        if (w == null) return;
        w.spawnParticle(Particle.DUST, feet.clone().add(0, 1.0, 0), 4, 0.35, 0.6, 0.35, 0.0, DUST_BLUE);
    }

    /** Метка-семя над головой (вражеская/союзная — отличается цветом). */
    public static void seedMark(Location head, boolean ally) {
        World w = head.getWorld();
        if (w == null) return;
        Particle.DustOptions dust = ally ? DUST_CYAN : DUST_DEEP;
        w.spawnParticle(Particle.DUST, head.clone().add(0, 0.5, 0), 3, 0.12, 0.12, 0.12, 0.0, dust);
    }

    /* ===================== Звуки ===================== */

    public static void soundPlace(Location at) {
        World w = at.getWorld();
        if (w == null) return;
        w.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.4f);
        w.playSound(at, Sound.BLOCK_POWDER_SNOW_PLACE, 0.7f, 1.1f);
    }

    public static void soundBind(Location at) {
        World w = at.getWorld();
        if (w == null) return;
        w.playSound(at, Sound.BLOCK_GLASS_BREAK, 0.7f, 1.5f);
        w.playSound(at, Sound.BLOCK_POWDER_SNOW_STEP, 1.0f, 0.8f);
    }

    public static void soundDash(Location at) {
        World w = at.getWorld();
        if (w == null) return;
        w.playSound(at, Sound.ENTITY_PHANTOM_FLAP, 0.8f, 1.6f);
        w.playSound(at, Sound.BLOCK_POWDER_SNOW_BREAK, 0.6f, 1.3f);
    }

    public static void soundBloom(Location at) {
        World w = at.getWorld();
        if (w == null) return;
        w.playSound(at, Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.6f);
        w.playSound(at, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.9f, 1.2f);
    }

    public static void soundSalvation(Location at) {
        World w = at.getWorld();
        if (w == null) return;
        w.playSound(at, Sound.BLOCK_CONDUIT_ACTIVATE, 1.0f, 1.2f);
        w.playSound(at, Sound.ITEM_TOTEM_USE, 0.6f, 1.4f);
    }

    public static void soundGarden(Location at) {
        World w = at.getWorld();
        if (w == null) return;
        w.playSound(at, Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 0.9f);
        w.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 1.0f);
    }
}
