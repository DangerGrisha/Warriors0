package org.money.money.kits.timewalker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
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
import org.bukkit.util.Vector;
import org.money.money.meta.ClassRegistry;
import org.money.money.session.KitResettable;
import org.money.money.session.KitSession;

import java.util.*;

/**
 * TimeWalker ABILITY 1 — Future Run / Пробег по будущему («timestep»).
 *
 * <p>RMB the Future Run clock: dash forward in accelerated time for ~1s, keep running with a
 * mild speed boost while the "future" window is open, then get rewound (teleported) back to the
 * saved start location.
 *
 * <h3>Эффект будущего (time echo)</h3>
 * Пока идёт бег по будущему, весь бой «виртуальный»: TimeWalker может бить и её могут бить, но
 * <b>урон не проходит</b> (0), только отбрасывание. Сама она в это время <b>неуязвима</b> — её
 * нельзя убить. Каждый удар (кто, по кому, сколько, и на какой секунде будущего) записывается.
 * Когда её «утаскивает» назад — звучит звук времени, и через {@code echoDelaySeconds} секунд все
 * записанные удары «догоняют» цели: наносятся настоящим уроном в той же последовательности и с тем
 * же смещением по времени. Т.е. удар, прилетевший на 3-й секунде будущего, реально прилетит через
 * (3 + 5) = 8с после возврата — как её собственные удары по врагам, так и удары по ней самой.
 *
 * <p>Много защит, чтобы никто не вклинился в стены, не телепортнулся между мирами и не получил
 * отложку после смерти / входа в лобби.
 */
public final class TimeWalkerRunListener implements Listener, KitResettable {

    private static final String TAG_FUTURE_RUN = "TimeWalkerFutureRun";
    // External-game flag: while running in the future she cannot pick up the flag. Added on
    // activation, removed on rewind AND on every other end-path (death/quit/lobby/reset/abort).
    private static final String TAG_CANT_PICKUP_FLAG = "cantpickupflag";

    private final Plugin plugin;

    private final NamespacedKey KEY_FUTURE_RUN;

    // cooldown stores last use time ms
    private final Map<UUID, Long> lastUse = new HashMap<>();

    // actionbar timer tasks to avoid stacking multiple timers
    private final Map<UUID, BukkitTask> cdTasks = new HashMap<>();

    // ===== Per-player ability state =====
    private final Map<UUID, Location> startLoc = new HashMap<>();
    private final Map<UUID, UUID> startWorld = new HashMap<>();
    private final Map<UUID, BukkitTask> dashTasks = new HashMap<>();
    private final Map<UUID, BukkitTask> auraTasks = new HashMap<>();
    private final Map<UUID, BukkitTask> warnTasks = new HashMap<>();
    private final Map<UUID, BukkitTask> returnTasks = new HashMap<>();

    // ===== Time-echo state =====
    // when the current future window began (ms) — presence also gates the combat handlers
    private final Map<UUID, Long> futureStartMs = new HashMap<>();
    // hits recorded during the owner's future window
    private final Map<UUID, List<TimedHit>> recordedHits = new HashMap<>();
    // pending delayed-damage tasks scheduled after the rewind (independent of clearState)
    private final Map<UUID, List<BukkitTask>> echoTasks = new HashMap<>();
    // short grace after the rewind teleport where fall damage is swallowed (independent of clearState)
    private final Map<UUID, Long> noFallUntil = new HashMap<>();

    public TimeWalkerRunListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_FUTURE_RUN = new NamespacedKey(plugin, "timewalker_future_run");
    }

    /** One recorded "virtual" hit to be re-applied for real after the rewind. */
    private static final class TimedHit {
        final long offsetMs;      // ms since the future window began
        final UUID victimId;      // who receives the delayed damage
        final UUID attackerId;    // damage source for death credit (may be null)
        final double damage;      // base damage to replay (armor re-applies at replay)
        final boolean ontoOwner;  // true => lands on the TimeWalker herself
        TimedHit(long offsetMs, UUID victimId, UUID attackerId, double damage, boolean ontoOwner) {
            this.offsetMs = offsetMs; this.victimId = victimId; this.attackerId = attackerId;
            this.damage = damage; this.ontoOwner = ontoOwner;
        }
    }

    /* ================== Item ================== */

    /** Create the Future Run item. */
    public ItemStack makeFutureRunItem() {
        ItemStack it = new ItemStack(Material.CLOCK);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Future Run", NamedTextColor.AQUA));
        im.getPersistentDataContainer().set(KEY_FUTURE_RUN, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(im);
        return it;
    }

    private boolean isFutureRunItem(ItemStack it) {
        if (it == null || it.getType() != Material.CLOCK || !it.hasItemMeta()) return false;
        ItemMeta im = it.getItemMeta();
        if (im.getPersistentDataContainer().has(KEY_FUTURE_RUN, PersistentDataType.BYTE)) return true;
        return Component.text("Future Run", NamedTextColor.AQUA).equals(im.displayName());
    }

    /* ================== Interact ================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;

        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        if (!isFutureRunItem(p.getInventory().getItemInMainHand())) return;

        e.setCancelled(true);

        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        // Cooldown read at use time so /warriors reload applies without restart.
        int cooldownSeconds = ClassRegistry.seconds("timewalker", "run", 45);
        long cooldownMs = cooldownSeconds * 1000L;

        if (lastUse.containsKey(id)) {
            long last = lastUse.get(id);
            long passed = now - last;
            if (passed < cooldownMs) {
                long secLeft = (cooldownMs - passed + 999) / 1000;
                p.sendActionBar(Component.text(secLeft + " sec", NamedTextColor.RED));
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.8f);
                return;
            }
        }

        // already running -> ignore (don't stack windows)
        if (startLoc.containsKey(id)) {
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.7f);
            return;
        }

        // start cooldown
        lastUse.put(id, now);
        startCooldownTimer(p, cooldownSeconds);

        // activate ability
        activateFutureRun(p);
    }

    private void startCooldownTimer(Player player, int seconds) {
        UUID id = player.getUniqueId();

        // cancel previous timer if exists
        BukkitTask prev = cdTasks.remove(id);
        if (prev != null) prev.cancel();

        BukkitTask task = new BukkitRunnable() {
            int timeLeft = seconds;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }

                if (timeLeft <= 0) {
                    player.sendActionBar(Component.text("Ready", NamedTextColor.GREEN));
                    cancel();
                    return;
                }

                player.sendActionBar(Component.text(timeLeft + " sec", NamedTextColor.YELLOW));
                timeLeft--;
            }
        }.runTaskTimer(plugin, 0L, 20L);

        cdTasks.put(id, task);
    }

    /* ================== Ability ================== */

    private void activateFutureRun(Player caster) {
        UUID id = caster.getUniqueId();
        World w = caster.getWorld();

        // Balance numbers read at use time (per cast) so /warriors reload applies without restart.
        final int dashTicks = ClassRegistry.numInt("timewalker", "run", "dashTicks", 20);
        final int totalTicks = ClassRegistry.numInt("timewalker", "run", "totalTicks", 160);
        final int dashSpeedLevel = Math.max(1, ClassRegistry.numInt("timewalker", "run", "speedLevel", 60));

        // Snapshot start state (preserve yaw/pitch). Do NOT snapshot health.
        Location snapshot = caster.getLocation().clone();
        startLoc.put(id, snapshot);
        startWorld.put(id, w.getUID());
        // time-echo: open the recording window
        futureStartMs.put(id, System.currentTimeMillis());
        recordedHits.put(id, new ArrayList<>());
        caster.addScoreboardTag(TAG_FUTURE_RUN);
        caster.addScoreboardTag(TAG_CANT_PICKUP_FLAG);

        // ==== Activation cue: "time splits open" ====
        Location o = caster.getLocation();
        w.playSound(o, Sound.BLOCK_BEACON_POWER_SELECT, 0.9f, 1.6f);
        w.playSound(o, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.8f, 1.3f);
        w.playSound(o, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.0f, 1.4f);
        w.spawnParticle(Particle.FLASH, o.clone().add(0, 1.0, 0), 1, 0, 0, 0, 0);
        w.spawnParticle(Particle.REVERSE_PORTAL, o.clone().add(0, 1.0, 0), 40, 0.4, 0.7, 0.4, 0.06);
        spawnRing(o.clone().add(0, 0.1, 0), 1.6, 26);
        caster.sendActionBar(Component.text("Future Run", NamedTextColor.AQUA));

        // "Рывок" = очень большая скорость на ~1с (Speed ~25 = почти телепорт). Игрок бежит сам —
        // без velocity-толчка, поэтому в стены не вклинивается. После рывка эффект просто спадает.
        caster.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, dashTicks, dashSpeedLevel - 1, false, false, false));

        final Particle.DustOptions trailDust = new Particle.DustOptions(Color.fromRGB(60, 180, 255), 1.0f);

        // ===== Dash phase: afterimage trail only (movement is the player's own). =====
        BukkitTask dash = new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                Player online = Bukkit.getPlayer(id);
                if (online == null || !online.isOnline() || online.isDead()
                        || !startLoc.containsKey(id) || ticks >= dashTicks) {
                    cancel();
                    dashTasks.remove(id);
                    return;
                }

                Vector dir = online.getEyeLocation().getDirection();
                dir.setY(0);
                if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 0, 1); else dir.normalize();

                Location trail = online.getLocation().add(0, 0.9, 0).subtract(dir.clone().multiply(0.6));
                online.getWorld().spawnParticle(Particle.DUST, trail, 6, 0.18, 0.25, 0.18, 0.0, trailDust);
                online.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, trail, 5, 0.18, 0.25, 0.18, 0.01);
                online.getWorld().spawnParticle(Particle.END_ROD, trail, 3, 0.15, 0.2, 0.15, 0.02);

                ticks++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
        dashTasks.put(id, dash);

        // ===== Future aura: whole window — clock ticking + swirling time particles. =====
        BukkitTask aura = new BukkitRunnable() {
            int t = 0;
            double ang = 0;

            @Override
            public void run() {
                Player online = Bukkit.getPlayer(id);
                if (online == null || !online.isOnline() || online.isDead()
                        || !startLoc.containsKey(id) || t >= totalTicks) {
                    cancel();
                    auraTasks.remove(id);
                    return;
                }
                World cw = online.getWorld();
                Location c = online.getLocation().add(0, 1.0, 0);
                ang += Math.PI / 8.0;

                // rotating "clock hand" (two opposite points) + orbiting cyan motes
                double cx = Math.cos(ang), cz = Math.sin(ang);
                cw.spawnParticle(Particle.END_ROD, c.clone().add(cx * 1.1, 0, cz * 1.1), 1, 0, 0, 0, 0);
                cw.spawnParticle(Particle.DUST, c.clone().add(-cx * 1.1, 0, -cz * 1.1), 2, 0, 0, 0, 0, trailDust);
                // rising time glyphs
                cw.spawnParticle(Particle.ENCHANT, c, 10, 0.5, 0.7, 0.5, 0.9);
                if (t % 6 == 0) cw.spawnParticle(Particle.REVERSE_PORTAL, c, 8, 0.4, 0.5, 0.4, 0.03);

                // ticking clock: tick-tock alternating pitch
                if (t % 5 == 0) {
                    float pitch = ((t / 5) % 2 == 0) ? 1.7f : 1.2f;
                    cw.playSound(online.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, pitch);
                }
                if (t % 20 == 0) cw.playSound(online.getLocation(), Sound.BLOCK_BEACON_AMBIENT, 0.4f, 1.8f);

                t += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
        auraTasks.put(id, aura);

        // ===== Warning effect ~0.5s before return =====
        long warnAt = Math.max(0L, totalTicks - 10L);
        BukkitTask warn = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            warnTasks.remove(id);
            Player online = Bukkit.getPlayer(id);
            if (online == null || !online.isOnline() || online.isDead()) return;
            if (!startLoc.containsKey(id)) return;

            online.sendActionBar(Component.text("Rewinding...", NamedTextColor.LIGHT_PURPLE));
            online.playSound(online.getLocation(), Sound.BLOCK_BEACON_AMBIENT, 0.8f, 0.6f);
            online.playSound(online.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 0.6f);
            spawnRing(online.getLocation().add(0, 1.0, 0), 1.2, 14);
        }, warnAt);
        warnTasks.put(id, warn);

        // ===== Return phase at window end =====
        BukkitTask ret = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            returnTasks.remove(id);
            doReturn(id);
        }, totalTicks);
        returnTasks.put(id, ret);
    }

    /* ================== Combat during the future (0 damage + record) ================== */

    /** Entity-vs-entity combat while someone is in their future window: nullify + knockback + record. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onCombat(EntityDamageByEntityEvent e) {
        if (futureStartMs.isEmpty()) return; // fast path: nobody is running the future
        if (!(e.getEntity() instanceof LivingEntity victim)) return;

        UUID attackerId = attackerUuid(e.getDamager());
        double dmg = e.getDamage();
        Location from = e.getDamager().getLocation();
        double kb = ClassRegistry.num("timewalker", "run", "futureKnockback", 0.45);

        // OUTGOING: the TimeWalker hits someone during her future -> record onto that victim.
        if (attackerId != null && futureStartMs.containsKey(attackerId)) {
            e.setCancelled(true);
            futureKnockback(victim, from, kb);
            recordHit(attackerId, victim.getUniqueId(), attackerId, dmg, false);
            phantomHitFx(victim.getLocation(), false);
            return;
        }

        // INCOMING: the TimeWalker is hit during her future -> 0 damage (invulnerable), record onto her.
        UUID victimId = victim.getUniqueId();
        if (futureStartMs.containsKey(victimId)) {
            e.setCancelled(true);
            futureKnockback(victim, from, kb);
            recordHit(victimId, victimId, attackerId, dmg, true);
            phantomHitFx(victim.getLocation(), true);
        }
    }

    /** Non-entity damage (fall/lava/fire/…) while in the future: she simply cannot be hurt. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onEnvironmentDamage(EntityDamageEvent e) {
        if (e instanceof EntityDamageByEntityEvent) return; // handled above
        if (futureStartMs.isEmpty() && noFallUntil.isEmpty()) return;
        if (!(e.getEntity() instanceof Player p)) return;
        UUID id = p.getUniqueId();
        if (futureStartMs.containsKey(id)) {
            e.setCancelled(true); // invulnerable during the future; environment damage is not echoed
            return;
        }
        // just after the rewind: swallow the fall damage from the drop she got yanked out of
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL) {
            Long until = noFallUntil.get(id);
            if (until != null && System.currentTimeMillis() < until) e.setCancelled(true);
        }
    }

    /** Resolve the player-UUID behind a damager (direct player or their projectile), else null. */
    private UUID attackerUuid(Entity damager) {
        if (damager instanceof Player p) return p.getUniqueId();
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player sp) return sp.getUniqueId();
        return null;
    }

    /** Push {@code victim} away from {@code from} horizontally with a small pop — the "future" knockback. */
    private void futureKnockback(LivingEntity victim, Location from, double strength) {
        Vector kb = victim.getLocation().toVector().subtract(from.toVector());
        kb.setY(0);
        if (kb.lengthSquared() < 1e-6) kb = new Vector(0, 0, 1);
        kb.normalize().multiply(Math.max(0.0, strength));
        kb.setY(0.34);
        try { victim.setVelocity(victim.getVelocity().add(kb)); } catch (Throwable ignored) {}
    }

    /** Record a virtual hit into the owner's buffer (offset from future start). */
    private void recordHit(UUID ownerId, UUID victimId, UUID attackerId, double dmg, boolean ontoOwner) {
        if (dmg <= 0) return;
        Long start = futureStartMs.get(ownerId);
        List<TimedHit> list = recordedHits.get(ownerId);
        if (start == null || list == null) return;
        int cap = ClassRegistry.numInt("timewalker", "run", "maxRecordedHits", 300);
        if (list.size() >= cap) return;
        list.add(new TimedHit(System.currentTimeMillis() - start, victimId, attackerId, dmg, ontoOwner));
    }

    /** "Time-displaced hit" visual — the blow that hasn't really landed yet. */
    private void phantomHitFx(Location at, boolean onOwner) {
        World w = at.getWorld();
        if (w == null) return;
        Location c = at.clone().add(0, 1.0, 0);
        w.spawnParticle(Particle.ENCHANT, c, 12, 0.3, 0.4, 0.3, 0.9);
        w.spawnParticle(Particle.CRIT, c, 8, 0.25, 0.3, 0.25, 0.12);
        w.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, onOwner ? 1.5f : 2.0f);
    }

    /* ================== Return / rewind ================== */

    /** Perform the rewind teleport with all the safety guards, replay echoes, then clear state. */
    private void doReturn(UUID id) {
        Location saved = startLoc.get(id);
        UUID savedWorldId = startWorld.get(id);

        Player p = Bukkit.getPlayer(id);
        // Offline -> nothing to teleport, just clear (echoes discarded).
        if (p == null || !p.isOnline()) {
            clearState(id);
            return;
        }
        // Spectator -> don't yank them around.
        if (p.getGameMode() == GameMode.SPECTATOR) {
            clearState(id);
            return;
        }
        // Game end / lobby -> cancel return.
        if (!KitSession.isInGame(p)) {
            clearState(id);
            return;
        }
        // No saved location / world (shouldn't happen) -> bail.
        if (saved == null || savedWorldId == null) {
            clearState(id);
            return;
        }
        // Cross-world: current world differs from saved world -> cancel.
        if (!p.getWorld().getUID().equals(savedWorldId)) {
            clearState(id);
            return;
        }
        World w = Bukkit.getWorld(savedWorldId);
        if (w == null) {
            clearState(id);
            return;
        }

        // She's a valid, in-game returning player: the future closes now -> queue the delayed damages.
        scheduleEchoes(id);

        // Stuck-in-block: ensure destination is safe.
        Location dest = makeSafe(saved.clone());
        // No safe destination found -> abort the teleport rather than dumping her on a roof;
        // echoes were already scheduled (the fight still "happened"), just clear state in place.
        if (dest == null) {
            clearState(id);
            return;
        }

        // "Pulled back through time" cue at the player's CURRENT spot.
        Location fromLoc = p.getLocation();
        World fromW = fromLoc.getWorld();
        fromW.playSound(fromLoc, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.2f, 0.8f);
        fromW.playSound(fromLoc, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.7f);
        fromW.playSound(fromLoc, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.7f, 1.6f);
        fromW.spawnParticle(Particle.SOUL_FIRE_FLAME, fromLoc.clone().add(0, 1.0, 0), 28, 0.3, 0.6, 0.3, 0.05);
        fromW.spawnParticle(Particle.REVERSE_PORTAL, fromLoc.clone().add(0, 1.0, 0), 40, 0.35, 0.7, 0.35, 0.08);

        // Rewind visuals/sounds at the destination.
        w.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.2f);
        w.playSound(dest, Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 1.4f);
        w.spawnParticle(Particle.SOUL_FIRE_FLAME, dest.clone().add(0, 1.0, 0), 34, 0.3, 0.6, 0.3, 0.05);
        w.spawnParticle(Particle.REVERSE_PORTAL, dest.clone().add(0, 1.0, 0), 46, 0.35, 0.7, 0.35, 0.08);
        w.spawnParticle(Particle.FLASH, dest.clone().add(0, 1.0, 0), 1, 0.0, 0.0, 0.0, 0.0);
        spawnRing(dest.clone().add(0, 1.0, 0), 1.2, 18);

        p.teleport(dest);
        // Kill the fall she was yanked out of so she doesn't splat on arrival (grace covers late ticks).
        p.setFallDistance(0f);
        noFallUntil.put(id, System.currentTimeMillis() + 1500L);
        // Arrival cue the player hears directly after landing.
        p.playSound(dest, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 1.2f);

        clearState(id);
    }

    /**
     * Queue every recorded hit as a real delayed damage. A hit at future-offset {@code t}s lands at
     * {@code (t + echoDelaySeconds)}s after the rewind — same sequence, same spacing, delayed by the
     * flat echo delay. Tasks live outside {@link #clearState} so the rewind cleanup doesn't cancel them.
     */
    private void scheduleEchoes(UUID ownerId) {
        List<TimedHit> hits = recordedHits.get(ownerId);
        if (hits == null || hits.isEmpty()) return;

        double baseDelaySec = ClassRegistry.num("timewalker", "run", "echoDelaySeconds", 5.0);
        long baseDelayTicks = Math.round(baseDelaySec * 20.0);

        Player owner = Bukkit.getPlayer(ownerId);
        if (owner != null && owner.isOnline()) {
            owner.sendActionBar(Component.text("Time echoes returning...", NamedTextColor.LIGHT_PURPLE));
            owner.playSound(owner.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 0.6f);
            owner.playSound(owner.getLocation(), Sound.BLOCK_CONDUIT_ACTIVATE, 0.7f, 1.4f);
        }

        // Fresh list per rewind. The 45s cooldown far exceeds the echo window, so no prior
        // session's echoes are still pending here — replacing can't orphan live tasks.
        List<BukkitTask> tasks = new ArrayList<>();
        echoTasks.put(ownerId, tasks);
        for (TimedHit h : hits) {
            long delay = Math.max(1L, baseDelayTicks + Math.round(h.offsetMs / 50.0));
            tasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> applyEcho(ownerId, h), delay));
        }
    }

    /** Apply one delayed hit for real, now. Self-guards on the victim being present & alive. */
    private void applyEcho(UUID ownerId, TimedHit h) {
        Entity ve = Bukkit.getEntity(h.victimId);
        if (!(ve instanceof LivingEntity victim) || victim.isDead() || !victim.isValid()) return;

        Player attacker = h.attackerId != null ? Bukkit.getPlayer(h.attackerId) : null;
        Vector keepVel = victim.getVelocity(); // knockback already happened in the future — echo is damage-only
        victim.setNoDamageTicks(0);            // ensure each recorded hit actually lands (no i-frame swallow)
        try {
            if (attacker != null) victim.damage(h.damage, attacker);
            else victim.damage(h.damage);
            victim.setVelocity(keepVel);        // cancel the echo's own knockback
        } catch (Throwable ignored) {}

        Location l = victim.getLocation().add(0, 1.0, 0);
        World w = victim.getWorld();
        w.spawnParticle(Particle.REVERSE_PORTAL, l, 14, 0.3, 0.4, 0.3, 0.03);
        w.spawnParticle(Particle.CRIT, l, 8, 0.25, 0.3, 0.25, 0.12);
        w.playSound(victim.getLocation(), Sound.BLOCK_BELL_RESONATE, 0.7f, h.ontoOwner ? 1.5f : 1.1f);
        w.playSound(victim.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.6f, 1.3f);
    }

    private void cancelEchoes(UUID id) {
        List<BukkitTask> tasks = echoTasks.remove(id);
        if (tasks == null) return;
        for (BukkitTask t : tasks) {
            if (t != null) t.cancel();
        }
    }

    /**
     * If {@code loc} is unsafe (feet/head block solid or suffocating), find the nearest safe spot:
     * scan a few blocks up for 2 air blocks with a solid floor, else fall back to highest block.
     * Returns {@code null} if even the highest-block fallback is unsafe (caller should abort).
     */
    private Location makeSafe(Location loc) {
        World w = loc.getWorld();
        if (w == null) return loc;

        if (isSafe(loc)) return loc;

        // Scan upward a few blocks for 2 air blocks above a solid floor.
        for (int dy = 1; dy <= 6; dy++) {
            Location candidate = loc.clone().add(0, dy, 0);
            if (isSafe(candidate)) return candidate;
        }

        // Fallback: highest block at this x/z.
        int hy = w.getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ());
        Location top = new Location(w, loc.getX(), hy + 1.0, loc.getZ(), loc.getYaw(), loc.getPitch());
        // Verify the fallback is genuinely safe; if not (e.g. would land on a roof / in a pocket),
        // signal abort by returning null so the caller can skip the teleport entirely.
        if (!isSafe(top)) return null;
        return top;
    }

    /** Feet + head must be passable, floor (block below feet) must be present. */
    private boolean isSafe(Location loc) {
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

    /** Small particle ring for warn/return effects. */
    private void spawnRing(Location center, double radius, int points) {
        World w = center.getWorld();
        if (w == null) return;
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(60, 180, 255), 1.0f);
        for (int i = 0; i < points; i++) {
            double ang = (2 * Math.PI * i) / points;
            double x = Math.cos(ang) * radius;
            double z = Math.sin(ang) * radius;
            w.spawnParticle(Particle.DUST, center.clone().add(x, 0, z), 1, 0.0, 0.0, 0.0, 0.0, dust);
        }
    }

    /* ================== State / cleanup ================== */

    /** Cancel run tasks, remove tag + speed effects, clear future/recording maps. Idempotent.
     *  Does NOT touch echoTasks — those are the post-rewind delayed damages that must survive. */
    private void clearState(UUID id) {
        BukkitTask dash = dashTasks.remove(id);
        if (dash != null) dash.cancel();
        BukkitTask aura = auraTasks.remove(id);
        if (aura != null) aura.cancel();
        BukkitTask warn = warnTasks.remove(id);
        if (warn != null) warn.cancel();
        BukkitTask ret = returnTasks.remove(id);
        if (ret != null) ret.cancel();

        startLoc.remove(id);
        startWorld.remove(id);
        futureStartMs.remove(id);
        recordedHits.remove(id);

        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            p.removeScoreboardTag(TAG_FUTURE_RUN);
            p.removeScoreboardTag(TAG_CANT_PICKUP_FLAG);
            p.removePotionEffect(PotionEffectType.SPEED);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        clearState(id);
        cancelEchoes(id); // don't let delayed damages fire after they left
        noFallUntil.remove(id);
        BukkitTask cd = cdTasks.remove(id);
        if (cd != null) cd.cancel();
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        // Never teleport a dead player; clear state and drop any pending echoes (fight is void).
        UUID id = e.getEntity().getUniqueId();
        clearState(id);
        cancelEchoes(id);
    }

    @Override
    public void resetPlayer(Player player) {
        if (player == null) return;
        UUID id = player.getUniqueId();
        clearState(id);
        cancelEchoes(id);
        noFallUntil.remove(id);
        BukkitTask cd = cdTasks.remove(id);
        if (cd != null) cd.cancel();
    }
}
