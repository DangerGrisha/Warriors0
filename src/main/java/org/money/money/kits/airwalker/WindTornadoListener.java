package org.money.money.kits.airwalker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;
import org.money.money.meta.ClassRegistry;
import org.money.money.session.KitResettable;
import org.money.money.session.KitSession;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * AirWalker — Tornado.
 *
 * <p>RMB the Tornado item to conjure a spinning funnel of wind at the point you are looking. The
 * tornado slowly walks forward in your facing direction for a few seconds. Enemies caught in it are
 * <b>sucked toward the core, spun around it and lifted off the ground</b> — their movement is fully
 * seized while inside — take periodic wind-cut damage and get disoriented (Nausea). When the funnel
 * finally collapses it <b>hurls everyone still caught high into the air</b> (they take the fall).
 *
 * <p>The caster is immune; teammates/spectators are never sucked or damaged. Held item with an
 * internal cooldown (config {@code airwalker.tornado.cooldownTicks}); everything self-terminates on
 * death / quit / lobby return so nothing leaks.
 */
public final class WindTornadoListener implements Listener, KitResettable {

    private final Plugin plugin;
    private final NamespacedKey KEY_TORNADO;

    // internal cooldown + one live tornado per caster
    private final Map<UUID, Long> cooldownMap = new HashMap<>();
    private final Map<UUID, BukkitTask> cdTasks = new HashMap<>();
    private final Map<UUID, BukkitTask> activeTornadoes = new HashMap<>();

    public WindTornadoListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_TORNADO = new NamespacedKey(plugin, "airwalker_tornado");
    }

    /* ================== Tuning (hot-reloadable) ================== */

    private static int   cooldownTicks()      { return Math.max(0, ClassRegistry.ticks("airwalker", "tornado", 440)); }
    private static int   durationTicks()      { return Math.max(20, ClassRegistry.numInt("airwalker", "tornado", "durationTicks", 140)); }
    private static double moveSpeed()         { return ClassRegistry.num("airwalker", "tornado", "moveSpeed", 0.12); }
    private static double radius()            { return ClassRegistry.num("airwalker", "tornado", "radius", 4.5); }
    private static double height()            { return ClassRegistry.num("airwalker", "tornado", "height", 8.0); }
    private static double pullStrength()      { return ClassRegistry.num("airwalker", "tornado", "pullStrength", 0.5); }
    private static double spinStrength()      { return ClassRegistry.num("airwalker", "tornado", "spinStrength", 0.9); }
    private static double liftStrength()      { return ClassRegistry.num("airwalker", "tornado", "liftStrength", 0.35); }
    private static double damagePerHit()      { return ClassRegistry.num("airwalker", "tornado", "damagePerTick", 1.0); }
    private static int    damageInterval()    { return Math.max(1, ClassRegistry.numInt("airwalker", "tornado", "damageIntervalTicks", 12)); }
    private static double finaleLaunch()      { return ClassRegistry.num("airwalker", "tornado", "finaleLaunch", 1.45); }
    private static double spawnAhead()        { return ClassRegistry.num("airwalker", "tornado", "spawnAhead", 3.0); }

    /* ================== Item ================== */

    /** Create the Tornado item. /kitgive AirWalker tornado */
    public ItemStack makeTornadoDye() {
        ItemStack it = new ItemStack(Material.LIGHT_BLUE_DYE);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Tornado", NamedTextColor.AQUA));
        im.getPersistentDataContainer().set(KEY_TORNADO, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(im);
        return it;
    }

    private boolean isTornado(ItemStack it) {
        if (it == null || it.getType() != Material.LIGHT_BLUE_DYE || !it.hasItemMeta()) return false;
        ItemMeta im = it.getItemMeta();
        if (im.getPersistentDataContainer().has(KEY_TORNADO, PersistentDataType.BYTE)) return true;
        return Component.text("Tornado", NamedTextColor.AQUA).equals(im.displayName());
    }

    /* ================== Interact ================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        if (!isTornado(p.getInventory().getItemInMainHand())) return;
        e.setCancelled(true);
        if (!KitSession.isInGame(p)) return;

        UUID id = p.getUniqueId();

        // one tornado at a time
        if (activeTornadoes.containsKey(id)) {
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 0.7f);
            return;
        }

        long now = System.currentTimeMillis();
        long cooldownMs = cooldownTicks() * 50L;
        if (cooldownMap.containsKey(id)) {
            long passed = now - cooldownMap.get(id);
            if (passed < cooldownMs) {
                long secLeft = (cooldownMs - passed + 999) / 1000;
                p.sendActionBar(Component.text(secLeft + " sec", NamedTextColor.RED));
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.8f);
                return;
            }
        }

        cooldownMap.put(id, now);
        startCooldownTimer(p, Math.max(1, (cooldownTicks() + 19) / 20));
        castTornado(p);
    }

    private void startCooldownTimer(Player player, int seconds) {
        UUID id = player.getUniqueId();
        BukkitTask prev = cdTasks.remove(id);
        if (prev != null) prev.cancel();
        BukkitTask task = new BukkitRunnable() {
            int timeLeft = seconds;
            @Override public void run() {
                if (!player.isOnline()) { cdTasks.remove(id); cancel(); return; }
                if (timeLeft <= 0) {
                    player.sendActionBar(Component.text("Tornado ready", NamedTextColor.GREEN));
                    cdTasks.remove(id);
                    cancel();
                    return;
                }
                timeLeft--;
            }
        }.runTaskTimer(plugin, 20L, 20L);
        cdTasks.put(id, task);
    }

    /* ================== Ability ================== */

    private void castTornado(Player caster) {
        final UUID casterId = caster.getUniqueId();
        final World w = caster.getWorld();

        // Direction (horizontal only) + a spawn point a few blocks ahead, dropped to the ground.
        Vector dir = caster.getLocation().getDirection();
        dir.setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 0, 1);
        dir.normalize();
        final Vector fwd = dir;

        Location origin = caster.getLocation().add(fwd.clone().multiply(spawnAhead()));
        origin.setY(groundY(w, origin.getX(), origin.getBlockY(), origin.getZ()));

        w.playSound(origin, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.2f, 0.6f);
        w.playSound(origin, Sound.ITEM_ELYTRA_FLYING, 1.0f, 0.7f);
        caster.sendActionBar(Component.text("Tornado unleashed", NamedTextColor.AQUA));

        final int duration = durationTicks();
        final Location center = origin.clone();

        BukkitTask task = new BukkitRunnable() {
            int t = 0;
            double phase = 0;

            @Override public void run() {
                Player owner = plugin.getServer().getPlayer(casterId);
                boolean ownerOk = owner != null && owner.isOnline() && !owner.isDead() && KitSession.isInGame(owner);
                if (!ownerOk || t >= duration) {
                    finale(center, owner);
                    activeTornadoes.remove(casterId);
                    cancel();
                    return;
                }

                // Advance forward (unless a wall is dead ahead) and follow the ground height.
                Location next = center.clone().add(fwd.clone().multiply(moveSpeed()));
                Location probe = next.clone().add(0, 1.0, 0);
                if (!probe.getBlock().getType().isSolid()) {
                    center.setX(next.getX());
                    center.setZ(next.getZ());
                    center.setY(groundY(center.getWorld(), center.getX(), center.getBlockY(), center.getZ()));
                }

                phase += 0.45;
                drawFunnel(center, phase);
                if (t % 6 == 0) center.getWorld().playSound(center, Sound.ENTITY_BREEZE_WIND_BURST, 0.8f, 0.7f);

                swirlVictims(owner, center, t);
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);

        activeTornadoes.put(casterId, task);
    }

    /** Suck / spin / lift / disorient every enemy inside the funnel this tick. */
    private void swirlVictims(Player caster, Location center, int t) {
        final double r = radius();
        final double h = height();
        final double pull = pullStrength();
        final double spin = spinStrength();
        final double lift = liftStrength();
        final boolean dmgTick = (t % damageInterval() == 0);
        final double dmg = damagePerHit();
        final double r2 = r * r;

        World w = center.getWorld();
        if (w == null) return;
        for (Entity ent : w.getNearbyEntities(center, r, h, r)) {
            if (!(ent instanceof Player pl) || !pl.isOnline() || pl.isDead()) continue;
            if (pl.getUniqueId().equals(caster.getUniqueId())) continue;
            if (pl.getGameMode() == GameMode.SPECTATOR || pl.getGameMode() == GameMode.CREATIVE) continue;
            if (isFriendly(caster, pl)) continue;

            Location l = pl.getLocation();
            double dx = l.getX() - center.getX();
            double dz = l.getZ() - center.getZ();
            double dy = l.getY() - center.getY();
            if (dx * dx + dz * dz > r2) continue;        // outside the funnel radius
            if (dy < -1.5 || dy > h) continue;           // outside the funnel height band

            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 0.001) { dx = 0.05; dz = 0.0; dist = 0.05; } // nudge off dead-center
            double rx = dx / dist, rz = dz / dist;       // outward radial unit
            double tx = -rz, tz = rx;                    // tangential (CCW)

            // suck inward + orbit + rise; near the top, ease the lift so they don't rocket away
            double liftNow = lift * (1.0 - Math.min(1.0, dy / h) * 0.6);
            Vector vel = new Vector(-rx * pull + tx * spin, liftNow, -rz * pull + tz * spin);
            pl.setVelocity(vel);
            pl.setFallDistance(0f);                        // no fall damage while caught (the finale supplies it)
            pl.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 40, 0, false, false, false));

            if (dmgTick && dmg > 0) {
                pl.setNoDamageTicks(0);
                pl.damage(dmg, caster);
                pl.getWorld().spawnParticle(Particle.SWEEP_ATTACK, l.clone().add(0, 1.0, 0), 1, 0, 0, 0, 0);
            }
        }
    }

    /** Collapse: throw everyone still inside skyward + a burst. */
    private void finale(Location center, Player caster) {
        World w = center.getWorld();
        if (w == null) return;
        w.playSound(center, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.9f, 0.9f);
        w.playSound(center, Sound.ENTITY_BREEZE_WIND_BURST, 1.2f, 0.6f);
        w.spawnParticle(Particle.EXPLOSION, center.clone().add(0, 1.0, 0), 2, 0.3, 0.3, 0.3, 0.0);
        w.spawnParticle(Particle.CLOUD, center.clone().add(0, 1.0, 0), 60, radius() * 0.5, 1.0, radius() * 0.5, 0.15);

        final double r = radius();
        final double h = height();
        final double launch = finaleLaunch();
        for (Entity ent : w.getNearbyEntities(center, r, h, r)) {
            if (!(ent instanceof Player pl) || !pl.isOnline() || pl.isDead()) continue;
            if (caster != null && pl.getUniqueId().equals(caster.getUniqueId())) continue;
            if (pl.getGameMode() == GameMode.SPECTATOR || pl.getGameMode() == GameMode.CREATIVE) continue;
            if (caster != null && isFriendly(caster, pl)) continue;
            Location l = pl.getLocation();
            double dx = l.getX() - center.getX(), dz = l.getZ() - center.getZ();
            if (dx * dx + dz * dz > r * r) continue;
            Vector out = new Vector(dx, 0, dz);
            if (out.lengthSquared() > 1e-6) out.normalize().multiply(0.4); else out = new Vector();
            out.setY(launch); // tossed high — they take the fall
            pl.setVelocity(out);
        }
    }

    /* ================== Visual ================== */

    private void drawFunnel(Location center, double phase) {
        World w = center.getWorld();
        if (w == null) return;
        final double h = height();
        final double maxR = radius();
        final double coreR = 0.6;
        final Particle.DustOptions grey = new Particle.DustOptions(Color.fromRGB(210, 225, 235), 1.1f);

        // stacked rings — narrow at the base, flaring toward the top, twisting into a spiral
        for (double y = 0; y <= h; y += 0.5) {
            double frac = y / h;
            double ring = coreR + (maxR - coreR) * frac;
            double twist = y * 0.55;
            int points = 3 + (int) (frac * 3);
            for (int i = 0; i < points; i++) {
                double ang = phase + twist + (2 * Math.PI * i) / points;
                double x = center.getX() + Math.cos(ang) * ring;
                double z = center.getZ() + Math.sin(ang) * ring;
                double py = center.getY() + y;
                w.spawnParticle(Particle.CLOUD, x, py, z, 1, 0.0, 0.0, 0.0, 0.0);
                if (i == 0) w.spawnParticle(Particle.DUST, x, py, z, 1, 0.0, 0.0, 0.0, 0.0, grey);
            }
        }
        // central updraft column
        w.spawnParticle(Particle.POOF, center.clone().add(0, h * 0.5, 0), 2, 0.1, h * 0.4, 0.1, 0.02);
    }

    /** Ground Y at (x,z): drop from {@code fromY} until a solid floor, so the funnel stands on terrain. */
    private double groundY(World w, double x, int fromY, double z) {
        if (w == null) return fromY;
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        int y = Math.min(w.getMaxHeight() - 1, Math.max(w.getMinHeight() + 1, fromY + 2));
        for (int i = 0; i < 8; i++) {
            if (w.getBlockAt(bx, y - 1, bz).getType().isSolid() && !w.getBlockAt(bx, y, bz).getType().isSolid()) {
                return y;
            }
            y--;
            if (y <= w.getMinHeight()) break;
        }
        return fromY;
    }

    /* ================== Team check ================== */

    private boolean isFriendly(Player a, Player b) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team ta = sb.getEntryTeam(a.getName());
        Team tb = sb.getEntryTeam(b.getName());
        if (ta == null || tb == null) return false; // no teams -> FFA, everyone is fair game
        return ta.getName().equalsIgnoreCase(tb.getName());
    }

    /* ================== Cleanup ================== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) { cleanup(e.getPlayer().getUniqueId()); }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) { cleanup(e.getEntity().getUniqueId()); }

    private void cleanup(UUID id) {
        BukkitTask tornado = activeTornadoes.remove(id);
        if (tornado != null) tornado.cancel();
        BukkitTask cd = cdTasks.remove(id);
        if (cd != null) cd.cancel();
    }

    @Override
    public void resetPlayer(Player player) {
        if (player == null) return;
        cleanup(player.getUniqueId());
        cooldownMap.remove(player.getUniqueId());
    }

    /** Cancel every live tornado (Main.onDisable). */
    public void stop() {
        for (BukkitTask t : activeTornadoes.values()) if (t != null) t.cancel();
        activeTornadoes.clear();
        for (BukkitTask t : cdTasks.values()) if (t != null) t.cancel();
        cdTasks.clear();
    }
}
