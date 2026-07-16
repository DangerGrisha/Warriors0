package org.money.money.kits.bluerose;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ayaka Ultimate — «Kamisato Art: Soumetsu» (морозная буря).
 *
 * <p>Волна морозных цветов летит ВПЕРЁД (снапится к стороне света) на {@code stormLength} блоков,
 * шириной ±{@code stormHalfWidth} и высотой {@code stormHeight}. Всё СОЛИДное (кроме обсидиана,
 * бедрока и защищённых блоков), чего касается буря, мгновенно замерзает в BLUE_ICE, буря наносит
 * урон врагам. Лёд держится {@code iceHoldSeconds}с, потом откатывается в исходные блоки. Игроки,
 * стоящие на нашем льду, получают замедление.
 *
 * <p>Производительность: морозим срез за срезом по мере продвижения (воздух пропускаем дёшево),
 * очередь восстановления упорядочена по времени истечения (без полного перебора), проверка «на
 * льду» — O(1) по множеству ключей, есть жёсткий кап на число замороженных блоков. Весь лёд
 * гарантированно откатывается при выключении плагина ({@link #shutdown()} из manager.stop()).
 */
final class BlueRoseFrostStorm {

    private final Plugin plugin;
    private final BlueRoseGuardianManager m;

    private static final class Frozen {
        final World world; final int x, y, z; final String key;
        final BlockData original; final long expiryMs;
        Frozen(World world, int x, int y, int z, String key, BlockData original, long expiryMs) {
            this.world = world; this.x = x; this.y = y; this.z = z; this.key = key;
            this.original = original; this.expiryMs = expiryMs;
        }
    }

    private final Set<String> frozenKeys = new HashSet<>();     // O(1) проверка «этот блок — наш лёд»
    private final Deque<Frozen> restoreQueue = new ArrayDeque<>(); // упорядочена по expiry (порядок заморозки)
    private BukkitTask globalTask;
    private final Map<UUID, Long> casterExemptUntil = new HashMap<>(); // создатель бури не мёрзнет на своём льду

    BlueRoseFrostStorm(Plugin plugin, BlueRoseGuardianManager m) {
        this.plugin = plugin;
        this.m = m;
        startGlobalTask();
    }

    private static String keyOf(World w, int x, int y, int z) {
        return w.getUID() + ":" + x + ":" + y + ":" + z;
    }

    private boolean isCasterExempt(UUID id) {
        Long until = casterExemptUntil.get(id);
        return until != null && System.currentTimeMillis() < until;
    }

    /* ===================== Запуск бури ===================== */

    void cast(Player caster) {
        final World w = caster.getWorld();
        final Location origin = caster.getLocation();

        Vector look = origin.getDirection(); look.setY(0);
        final int lx, lz; // ось длины — ближайшая сторона света
        if (Math.abs(look.getX()) >= Math.abs(look.getZ())) { lx = look.getX() >= 0 ? 1 : -1; lz = 0; }
        else { lx = 0; lz = look.getZ() >= 0 ? 1 : -1; }
        final int wx = (lz != 0) ? 1 : 0; // ось ширины — перпендикуляр
        final int wz = (lx != 0) ? 1 : 0;

        final int length    = m.numInt("soumetsu", "stormLength", 100);
        final int halfWidth = m.numInt("soumetsu", "stormHalfWidth", 30);
        final int height    = m.numInt("soumetsu", "stormHeight", 50);
        final double speed  = Math.max(0.05, m.num("soumetsu", "stormBlocksPerTick", 0.25)); // 0.25/тик = 5 бл/с
        final long holdMs   = m.numInt("soumetsu", "iceHoldSeconds", 60) * 1000L;
        final double dmg    = m.num("soumetsu", "stormDamage", 6.0); // первая волна: 3 сердца
        final int maxFrozen = m.numInt("soumetsu", "maxFrozenBlocks", 20000);

        // создатель ульты НЕ замедляется от своего льда (на всё время его жизни); тиммейты — как враги
        casterExemptUntil.put(caster.getUniqueId(), System.currentTimeMillis() + holdMs + 5000L);

        final int baseX = origin.getBlockX();
        final int baseY = origin.getBlockY();
        final int baseZ = origin.getBlockZ();
        final int floorY = baseY - 1;               // морозим от блока под ногами...
        final int topY   = baseY + height - 1;      // ...и вверх на height

        BlueRoseVisualUtil.soundGarden(origin);
        try { w.playSound(origin, Sound.ENTITY_PLAYER_HURT_FREEZE, 1.4f, 0.5f); } catch (Throwable ignored) {}
        caster.sendActionBar(net.kyori.adventure.text.Component.text("Kamisato Art: Soumetsu",
                net.kyori.adventure.text.format.NamedTextColor.AQUA));

        final Set<UUID> hitEnemies = new HashSet<>();

        new BukkitRunnable() {
            double front = 0;
            int frozenUpTo = -1;   // последний замороженный целочисленный срез (каждый морозим один раз)
            int tick = 0;
            @Override public void run() {
                if (!caster.isOnline() || front >= length) { cancel(); return; }
                tick++;
                double next = Math.min(length, front + speed);

                // 1) заморозить НОВЫЕ целочисленные срезы, до которых дошёл фронт
                int target = (int) Math.floor(next);
                for (int d = frozenUpTo + 1; d <= target && d < length; d++) {
                    if (frozenKeys.size() >= maxFrozen) break;
                    freezeSlice(w, baseX + lx * d, baseZ + lz * d, wx, wz, halfWidth, floorY, topY, holdMs, maxFrozen);
                    frozenUpTo = d;
                }

                // 2) фронт бури — стена частиц, урон «первой волны» (раз на врага), звуки
                Location front3 = new Location(w, baseX + 0.5 + lx * next, baseY, baseZ + 0.5 + lz * next);
                stormParticles(w, front3, wx, wz, halfWidth, height);
                damageEnemies(caster, front3, lx, lz, halfWidth, baseY, height, hitEnemies, dmg);
                if (tick % 8 == 0) {
                    try {
                        w.playSound(front3, Sound.ENTITY_BREEZE_WIND_BURST, 1.0f, 0.7f);
                        w.playSound(front3, Sound.BLOCK_POWDER_SNOW_BREAK, 1.1f, 0.6f);
                    } catch (Throwable ignored) {}
                }

                front = next;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void freezeSlice(World w, int cx, int cz, int wx, int wz, int halfWidth,
                             int floorY, int topY, long holdMs, int maxFrozen) {
        long expiry = System.currentTimeMillis() + holdMs;
        for (int off = -halfWidth; off <= halfWidth; off++) {
            int x = cx + wx * off;
            int z = cz + wz * off;
            for (int y = floorY; y <= topY; y++) {
                Block b = w.getBlockAt(x, y, z);
                Material t = b.getType();
                if (t.isAir() || b.isLiquid()) continue;                 // воздух/жидкость — дёшево пропускаем
                if (t == Material.OBSIDIAN || t == Material.BEDROCK || t == Material.BLUE_ICE) continue;
                if (isProtected(t)) continue;
                String key = keyOf(w, x, y, z);
                if (!frozenKeys.add(key)) continue;                      // уже наш лёд
                restoreQueue.addLast(new Frozen(w, x, y, z, key, b.getBlockData(), expiry));
                b.setType(Material.BLUE_ICE, false);                     // без физики — иначе каскад апдейтов
                if (frozenKeys.size() >= maxFrozen) return;
            }
        }
    }

    private void stormParticles(World w, Location front, int wx, int wz, int halfWidth, int height) {
        // Стена морозной волны: снежинки по всей ширине на разных высотах + голубые «искры» инея.
        for (int i = 0; i < 100; i++) {
            double off = (Math.random() * 2 - 1) * halfWidth;
            double yy = Math.random() * height;
            double px = front.getX() + wx * off;
            double pz = front.getZ() + wz * off;
            w.spawnParticle(Particle.SNOWFLAKE, px, front.getY() + yy, pz, 1, 0.12, 0.12, 0.12, 0.02);
            if (i % 4 == 0) {
                w.spawnParticle(Particle.DUST, px, front.getY() + yy, pz, 1, 0.1, 0.1, 0.1, 0.0,
                        new Particle.DustOptions(BlueRoseVisualUtil.ROSE_CYAN, 1.7f));
            }
        }
        // низовая позёмка вдоль всей стены
        BlueRoseVisualUtil.frostDust(front.clone().add(0, 1, 0), halfWidth, 24);
    }

    /** Урон «первой волны»: строго на фронте, по КАЖДОМУ врагу ОДИН раз за бурю. */
    private void damageEnemies(Player caster, Location front, int lx, int lz,
                               int halfWidth, int baseY, int height, Set<UUID> hitEnemies, double dmg) {
        World w = front.getWorld();
        double thinLen = 2.5;                                     // тонкий срез строго на гребне волны
        double xExt = (lx != 0) ? thinLen : halfWidth;
        double zExt = (lz != 0) ? thinLen : halfWidth;
        Location box = new Location(w, front.getX(), baseY + height / 2.0, front.getZ());
        for (Entity ent : w.getNearbyEntities(box, xExt, height / 2.0 + 2, zExt)) {
            if (!(ent instanceof Player pl) || !pl.isOnline() || pl.isDead()) continue;
            if (pl.getUniqueId().equals(caster.getUniqueId())) continue;
            if (pl.getGameMode() == GameMode.SPECTATOR || pl.getGameMode() == GameMode.CREATIVE) continue;
            if (m.isFriendly(caster, pl)) continue;
            if (!hitEnemies.add(pl.getUniqueId())) continue;      // по разу на врага
            m.safeDamage(pl, dmg, caster);
            Location l = pl.getLocation();
            BlueRoseVisualUtil.iceSpike(l);
            try {
                w.playSound(l, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.8f);
                w.playSound(l, Sound.ENTITY_PLAYER_HURT_FREEZE, 1.0f, 1.0f);
            } catch (Throwable ignored) {}
        }
    }

    /* ===================== Глобальный таск: откат + замедление на льду ===================== */

    private void startGlobalTask() {
        globalTask = new BukkitRunnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();

                // 1) откатить истёкший лёд (очередь по времени — снимаем с головы, пока истекло)
                int budget = 800;
                while (!restoreQueue.isEmpty() && budget-- > 0) {
                    Frozen f = restoreQueue.peekFirst();
                    if (now < f.expiryMs) break;                 // голова ещё не истекла — дальше тоже
                    restoreQueue.pollFirst();
                    frozenKeys.remove(f.key);
                    Block b = f.world.getBlockAt(f.x, f.y, f.z);
                    if (b.getType() == Material.BLUE_ICE) b.setBlockData(f.original, false);
                }

                // 2) замедление всем, кто стоит на нашем льду
                if (!frozenKeys.isEmpty()) {
                    int slowAmp = m.numInt("soumetsu", "iceSlowAmplifier", 0); // Slowness I на льду (не «утоп в бетоне»)
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.getGameMode() == GameMode.SPECTATOR) continue;
                        if (isCasterExempt(p.getUniqueId())) continue; // только СОЗДАТЕЛЬ ульты не мёрзнет
                        Block below = p.getLocation().getBlock().getRelative(0, -1, 0);
                        if (below.getType() != Material.BLUE_ICE) continue;
                        if (frozenKeys.contains(keyOf(below.getWorld(), below.getX(), below.getY(), below.getZ()))) {
                            m.applySlow(p, 20, slowAmp);
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 5L);
    }

    /** Откатить ВЕСЬ лёд немедленно и остановить таск (вызывается из manager.stop() при выключении). */
    void shutdown() {
        if (globalTask != null) { globalTask.cancel(); globalTask = null; }
        for (Frozen f : restoreQueue) {
            try {
                Block b = f.world.getBlockAt(f.x, f.y, f.z);
                if (b.getType() == Material.BLUE_ICE) b.setBlockData(f.original, false);
            } catch (Throwable ignored) {}
        }
        restoreQueue.clear();
        frozenKeys.clear();
    }

    private static boolean isProtected(Material t) {
        if (t == Material.BARRIER || t == Material.END_PORTAL || t == Material.END_PORTAL_FRAME) return true;
        if (t == Material.REINFORCED_DEEPSLATE) return true;
        if (t.getHardness() < 0) return true; // прочие неломаемые
        String n = t.name();
        return n.endsWith("COMMAND_BLOCK") || n.contains("STRUCTURE") || n.equals("JIGSAW")
                || n.endsWith("SPAWNER") || n.contains("SHULKER_BOX");
    }
}
