package org.money.money.kits.saberL;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.money.money.meta.ClassRegistry;
import org.money.money.util.ItemModels;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class SaberLightUltimateListener implements Listener {

    private final Plugin plugin;
    private final SaberLightExcaliburListener excalibur; // reuse your soul methods (recommended)

    // Active cast/ultimate states
    private final Map<UUID, CastState> activeCasts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> ultCooldownUntil = new ConcurrentHashMap<>();
    private final Set<UUID> firingPhase = ConcurrentHashMap.newKeySet();

    // Config (read from ClassRegistry at use time)
    private static int SOUL_COST() { return ClassRegistry.numInt("saberlight", "ult", "soulCost", 4); }
    //private static final long CAST_LOCK_TICKS = 20L;     // 1 second charge
    private static final double MAX_RANGE = 48.0;

    // Cinematic tuning (easy to tweak)
// =========================

    // Total charge duration before slash starts
    // 17.5s -> beam release starts after this.
    private static final long CAST_LOCK_TICKS = 350L;

    // Phase timing (1:1 requested sequence)
    private static final int PHASE_BEAM_START_TICKS = 160;      // 8.0s
    private static final int PHASE_BEAM_MAX_TICKS = 190;        // 9.5s
    private static final int PHASE_DISPERSAL_START_TICKS = 290; // 14.5s

    // Slash duration window (Phase 4) - actual runtime depends on length/speed
    private static final int SLASH_MAX_TICKS = 100;   // up to 5 sec (10-15 sec overall target)

    // Beam geometry / reach
    private static double BEAM_MAX_RANGE() { return ClassRegistry.num("saberlight", "ult", "beamRange", 111); }       // easy beam length tuning
    private static final double BEAM_HEIGHT_ABOVE_TARGET = 34.0;
    private static final double BEAM_BACK_DISTANCE = 14.0;

    // Slash speed (smaller = slower/more cinematic)
    private static final double BEAM_STEP_PER_TICK = 0.85;

    // Beam destructive radii
    private static final double BEAM_CORE_RADIUS = 3;      // guaranteed destruction core
    private static final double BEAM_WAVE_RADIUS = 3.2;      // cinematic outer wave
    private static double BEAM_HIT_RADIUS() { return ClassRegistry.num("saberlight", "ult", "beamHitRadius", 3.0); }

    // Final impact
    private static final double FINAL_BREAK_RADIUS = 5.0;
    private static final double FINAL_HIT_RADIUS = 50.0;
    private static final double FINAL_ONESHOT_RADIUS = 14.0; // guaranteed lethal zone

    // Particle density multipliers (easy tuning)
    private static final double CHARGE_PARTICLE_MULT = 1.0;
    private static final double ASCEND_PARTICLE_MULT = 1.0;
    private static final double OVERLOAD_PARTICLE_MULT = 1.25;
    private static final double SLASH_PARTICLE_MULT = 1.0;

    // Sound cadence
    private static final int CHARGE_SOUND_INTERVAL_MIN = 20; // ticks
    private static final int CHARGE_SOUND_INTERVAL_MAX = 30; // ticks

    // ===== Phase 4 tuning: falling pillar / minefield =====

    // Safe zone around caster (no explosions/destruction too close)
    private static final double BEAM_SAFE_START_DISTANCE = 6.0; // start detonations only after this many blocks from caster

    // Explosion size scaler (easy global tuning for phase 4 visuals + destruction feel)
    private static final double PHASE4_BLAST_SIZE = 1.4; // 0.7 small, 1.0 normal, 1.4 huge

    // Falling pillar height scaling by beam length
    private static final double PILLAR_HEIGHT_MIN = 24.0;
    private static final double PILLAR_HEIGHT_MAX = 46.0;

    // Falling pillar width scaling
    private static final double PILLAR_WIDTH_BASE = 1.4;
    private static final double PILLAR_WIDTH_PULSE = 0.35;

    public SaberLightUltimateListener(Plugin plugin, SaberLightExcaliburListener excalibur) {
        this.plugin = plugin;
        this.excalibur = excalibur;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /* =========================
       Activation: Blue Dye "Soul Release"
       ========================= */
    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onSoulReleaseUse(PlayerInteractEvent e) {
        Player p = e.getPlayer();

        Action action = e.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        if(!isSoulRelease(p.getInventory().getItemInMainHand())) return;
        e.setCancelled(true);

        ItemStack hand = e.getItem();

        boolean soulHand = isSoulRelease(hand);
        if (!soulHand) {
            return;
        }

        e.setCancelled(true);

        if (!isSaberLight(p)) {
            p.sendActionBar(Component.text("§cOnly LightSaber can use this."));
            return;
        }

        if (activeCasts.containsKey(p.getUniqueId())) {
            p.sendActionBar(Component.text("§eAlready casting..."));
            return;
        }

        if (isUltOnCooldown(p)) {
            long ms = ultCooldownUntil.get(p.getUniqueId()) - System.currentTimeMillis();
            p.sendActionBar(Component.text("§cUltimate cooldown: §f" + Math.max(1, (ms / 1000)) + "s"));
            return;
        }

        ItemStack exc = findExcaliburInInventory(p);
        if (exc == null) {
            p.sendActionBar(Component.text("§cExcalibur not found in inventory."));
            return;
        }

        int souls = excalibur.getSouls(exc);

        if (souls < SOUL_COST()) {
            p.sendActionBar(Component.text("§cNeed " + SOUL_COST() + " souls. §7(" + souls + "/" + SOUL_COST() + ")"));
            return;
        }

        // Lock aim NOW (important)
        Location eye = p.getEyeLocation().clone();
        Vector lockedDir = eye.getDirection().normalize();
        float lockedYaw = eye.getYaw();
        float lockedPitch = eye.getPitch();

        // Ray trace target using locked direction
        Location target = findTargetPoint(p, eye, lockedDir, BEAM_MAX_RANGE());

        // Consume souls immediately (anti-abuse)
        boolean consumed = consumeSoulsFromInventoryExcalibur(p, SOUL_COST());

        if (!consumed) {
            p.sendActionBar(Component.text("§cFailed to consume souls."));
            return;
        }

        // Start cast state
        CastState state = new CastState(
                p.getUniqueId(),
                p.getLocation().clone(),
                lockedYaw,
                lockedPitch,
                eye.clone(),
                lockedDir.clone(),
                target.clone()
        );
        activeCasts.put(p.getUniqueId(), state);

        startChargeAndBeam(p, state);
    }
    private ItemStack findExcaliburInInventory(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        for (ItemStack it : contents) {
            if (it == null) continue;
            if (excalibur.isExcalibur(it)) return it;
        }
        return null;
    }
    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onSoulReleaseCrossbowShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;

        ItemStack bow = e.getBow();
        if (!isSoulRelease(bow)) return;

        // Cancel actual projectile shot for ultimate item
        e.setCancelled(true);

        // Optional safety: remove spawned projectile if event was partially processed somewhere
        if (e.getProjectile() != null && e.getProjectile().isValid()) {
            e.getProjectile().remove();
        }
    }

    private boolean consumeSoulsFromInventoryExcalibur(Player p, int cost) {
        ItemStack[] contents = p.getInventory().getContents();

        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack it = contents[slot];
            if (it == null) continue;
            if (!excalibur.isExcalibur(it)) continue;

            int souls = excalibur.getSouls(it);
            if (souls < cost) return false;

            int newSouls = souls - cost;
            excalibur.updateExcaliburSouls(it, newSouls);

            // Put back explicitly (safe)
            p.getInventory().setItem(slot, it);
            p.sendActionBar(Component.text("§bSoul Release §7- §c" + cost + " souls §7(" + newSouls + " left)"));
            return true;
        }
        return false;
    }

    private String itemDebug(ItemStack it) {
        if (it == null) return "null";
        String type = it.getType().name();
        if (!it.hasItemMeta()) return type + " (no meta)";
        ItemMeta meta = it.getItemMeta();
        String name = (meta != null && meta.hasDisplayName()) ? ChatColor.stripColor(meta.getDisplayName()) : "no-name";
        return type + " x" + it.getAmount() + " name=" + name;
    }

    /* =========================
       Freeze movement & look during cast/beam
       ========================= */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onMoveFreeze(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (!firingPhase.contains(id)) return; // free look/move during charge

        CastState s = activeCasts.get(id);
        if (s == null) return;

        // Allow tiny vertical server corrections? For now hard lock all.
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;

        // Keep exact position + locked look
        Location locked = s.lockedBodyLocation.clone();
        locked.setYaw(s.lockedYaw);
        locked.setPitch(s.lockedPitch);

        // If changed anything -> snap back
        if (from.distanceSquared(to) > 0.0001
                || Math.abs(to.getYaw() - s.lockedYaw) > 0.01
                || Math.abs(to.getPitch() - s.lockedPitch) > 0.01) {
            e.setTo(locked);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onSwapDuringCast(PlayerSwapHandItemsEvent e) {
        if (activeCasts.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onDropDuringCast(PlayerDropItemEvent e) {
        if (activeCasts.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent e) {
        activeCasts.remove(e.getPlayer().getUniqueId());
        firingPhase.remove(e.getPlayer().getUniqueId());
    }

    /* =========================
       Main sequence
       ========================= */
    private void startChargeAndBeam(Player p, CastState state) {
        World w = p.getWorld();

        p.sendActionBar(Component.text("§6Excalibur §7- §bSoul Release"));
        p.sendTitle("§6EXCALIBUR", "§7Soul Release", 5, 40, 10);
        playSaberUltPrologueForNearby(p);

        // Initial soft start

        new BukkitRunnable() {
            int ticks = 0;
            boolean skyFlareDone = false;

            @Override
            public void run() {
                if (!p.isOnline() || !activeCasts.containsKey(p.getUniqueId())) {
                    cancel();
                    return;
                }

                ticks++;

                // Phase 1 (0-10s): prayer / charge
                drawGoldenChargeRings(p, ticks);
                drawRisingSparks(p, ticks);

                // Soft sounds every ~20-30 ticks
                if (ticks % 25 == 0) {
                    playChargeSoundLayer(p, ticks);
                }

                // Phase 2 (8.0s -> 9.5s): beam rises to max height
                if (ticks >= PHASE_BEAM_START_TICKS && ticks <= PHASE_BEAM_MAX_TICKS) {
                    int local = ticks - PHASE_BEAM_START_TICKS;
                    int phaseTicks = Math.max(1, PHASE_BEAM_MAX_TICKS - PHASE_BEAM_START_TICKS);
                    drawAscendingExcaliburColumn(p, local, phaseTicks);
                    playAscendingEnergySounds(p, ticks);
                }

                // At 9.5s: sky flare at max charge point
                if (!skyFlareDone && ticks >= PHASE_BEAM_MAX_TICKS) {
                    skyFlareDone = true;
                    playMaxHeightSkyFlare(p, state);
                }

                // Phase 3 (after 9.5s): charged overload hold
                if (ticks >= PHASE_BEAM_MAX_TICKS) {
                    int local = ticks - PHASE_BEAM_MAX_TICKS;
                    // Keep the beam fully visible until the explosion phase starts.
                    int phaseTicks = Math.max(1, PHASE_BEAM_MAX_TICKS - PHASE_BEAM_START_TICKS);
                    drawAscendingExcaliburColumn(p, phaseTicks, phaseTicks);
                    drawOverloadBurst(p, local);
                    lightNearbyShake(p, ticks);
                }

                // Phase 4 pre-release (from 14.5s): particles fly apart from caster
                if (ticks >= PHASE_DISPERSAL_START_TICKS) {
                    int local = ticks - PHASE_DISPERSAL_START_TICKS;
                    drawCasterReleaseDispersal(p, local);
                }

                // Phase complete -> Phase 4 slash
                if (ticks >= CAST_LOCK_TICKS) {
                    cancel();

                    // Big release flash at caster
                    Location c = p.getLocation().add(0, 1.1, 0);
                    w.spawnParticle(Particle.FLASH, c, 3, 0.25, 0.25, 0.25, 0.0);
                    w.spawnParticle(Particle.EXPLOSION, c, 6, 0.6, 0.6, 0.6, 0.01);
                    w.spawnParticle(Particle.CLOUD, c, (int) (50 * OVERLOAD_PARTICLE_MULT), 1.2, 0.8, 1.2, 0.03);
                    w.spawnParticle(Particle.ELECTRIC_SPARK, c, (int) (80 * OVERLOAD_PARTICLE_MULT), 1.0, 0.7, 1.0, 0.12);


                    p.sendTitle("§6EXCALIBUR!", "§eRelease", 0, 20, 10);
                    CastState releaseState = captureCurrentCastState(p);
                    activeCasts.put(p.getUniqueId(), releaseState);
                    runExcaliburBeam(p, releaseState);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void playSaberUltPrologueForNearby(Player p) {
        World w = p.getWorld();
        Location origin = p.getLocation();
        double r2 = 200.0 * 200.0;

        for (Player near : w.getPlayers()) {
            if (near.getLocation().distanceSquared(origin) > r2) continue;
            try {
                near.playSound(near.getLocation(), "minecraft:my_sounds.saberult", SoundCategory.PLAYERS, 1.0f, 1.0f);
            } catch (Throwable ignored) {}
        }
    }

    private void playMaxHeightSkyFlare(Player p, CastState state) {
        World w = p.getWorld();
        Location sky = p.getLocation().add(0, 18.0, 0);

        w.spawnParticle(Particle.FLASH, sky, 4, 0.45, 0.25, 0.45, 0.0);
        w.spawnParticle(Particle.EXPLOSION, sky, 5, 0.70, 0.35, 0.70, 0.0);
        w.spawnParticle(Particle.END_ROD, sky, 24, 0.9, 0.6, 0.9, 0.02);
        w.spawnParticle(Particle.ELECTRIC_SPARK, sky, 36, 0.85, 0.55, 0.85, 0.08);
        w.spawnParticle(Particle.FIREWORK, sky, 20, 0.75, 0.45, 0.75, 0.03);

    }

    private void drawCasterReleaseDispersal(Player p, int localTick) {
        World w = p.getWorld();
        Location c = p.getLocation().add(0, 1.2, 0);

        // Grow burst radius as we approach 17.5s
        double prog = Math.min(1.0, localTick / 60.0);
        double radius = 0.8 + 2.8 * prog;
        int rays = 18 + (int) Math.round(14 * prog);
        int trail = 3 + (int) Math.round(4 * prog);

        for (int i = 0; i < rays; i++) {
            double a = (Math.PI * 2.0) * (i / (double) rays) + (localTick * 0.11);
            Vector dir = new Vector(Math.cos(a), 0.08 + Math.random() * 0.08, Math.sin(a)).normalize();

            for (int t = 1; t <= trail; t++) {
                double d = (radius * t) / trail;
                Location pt = c.clone().add(dir.clone().multiply(d));
                w.spawnParticle(Particle.FIREWORK, pt, 1, 0.02, 0.02, 0.02, 0.0);
                if ((i + t + localTick) % 2 == 0) {
                    w.spawnParticle(Particle.END_ROD, pt, 1, 0.01, 0.01, 0.01, 0.0);
                } else {
                    w.spawnParticle(Particle.ELECTRIC_SPARK, pt, 1, 0.01, 0.01, 0.01, 0.0);
                }
            }
        }

        if (localTick % 8 == 0) {
        }
    }
    private void runExcaliburBeam(Player caster, CastState state) {
        World w = caster.getWorld();
        firingPhase.add(caster.getUniqueId());

        // Locked look direction from cast start
        final Vector dir = state.lockedDir.clone().normalize();
        final Location eye = state.lockedEyeLocation.clone();

        // End point (clamped by tunable range)
        Location rawTarget = state.targetPoint.clone();
        double distFromEye = eye.distance(rawTarget);
        final Location target = (distFromEye > BEAM_MAX_RANGE())
                ? eye.clone().add(dir.clone().multiply(BEAM_MAX_RANGE()))
                : rawTarget.clone();

        // Ground sweep direction (horizontal-ish)
        Vector flatDir = dir.clone();
        flatDir.setY(0);
        if (flatDir.lengthSquared() < 0.0001) {
            flatDir = dir.clone();
        }
        final Vector sweepDir = flatDir.normalize();

        // Start line slightly behind target for cinematic forward sweep
        final Location sweepStart = target.clone().subtract(sweepDir.clone().multiply(BEAM_BACK_DISTANCE));

        // Total sweep length (tunable / safe)
        final double sweepLength = Math.min(BEAM_MAX_RANGE(), BEAM_BACK_DISTANCE + target.distance(sweepStart) + 10.0);

        // Dynamic pillar height based on beam length (longer beam = taller falling pillar)
        final double normalizedLen = Math.max(0.0, Math.min(1.0, sweepLength / BEAM_MAX_RANGE()));
        final double dynamicPillarHeight = PILLAR_HEIGHT_MIN + (PILLAR_HEIGHT_MAX - PILLAR_HEIGHT_MIN) * normalizedLen;

        // Caster position snapshot for safe-zone checks
        final Location casterOrigin = caster.getLocation().clone();

        // Start cooldown when phase 4 begins
        long ultCooldownMs = ClassRegistry.millis("saberlight", "ult", 25_000L);
        ultCooldownUntil.put(caster.getUniqueId(), System.currentTimeMillis() + ultCooldownMs);

        new BukkitRunnable() {
            double traveled = 0.0;
            int tick = 0;
            boolean finaleStarted = false;

            @Override
            public void run() {
                tick++;

                if (!caster.isOnline()) {
                    cleanupCast(caster);
                    cancel();
                    return;
                }

                forceLockPose(caster, state);

                if (traveled >= sweepLength || tick > SLASH_MAX_TICKS) {
                    if (finaleStarted) return;
                    Location endPoint = sweepStart.clone().add(sweepDir.clone().multiply(Math.min(traveled, sweepLength)));
                    endPoint = snapImpactTowardGround(w, endPoint, 10, 10);

                    // Final blast only if not inside caster safe-zone
                    if (endPoint.distance(casterOrigin) >= BEAM_SAFE_START_DISTANCE) {
                        finaleStarted = true;
                        playFinalSwordDropSequence(caster, endPoint, dir, () -> cleanupCast(caster));
                        cancel();
                        return;
                    }

                    cleanupCast(caster);
                    cancel();
                    return;
                }

                // Moving detonation point
                Location impact = sweepStart.clone().add(sweepDir.clone().multiply(traveled));
                impact = snapImpactTowardGround(w, impact, 10, 10);

                // ===== SAFE ZONE: skip explosions too close to caster =====
                // If blast radius is 5, user asked to begin at 6+ forward. This does exactly that.
                double distToCaster = impact.distance(casterOrigin);
                if (distToCaster < BEAM_SAFE_START_DISTANCE) {
                    traveled += BEAM_STEP_PER_TICK;
                    return;
                }

                // 1) Giant falling pillar (height scales with beam length)
                drawFallingBeamPillar(w, impact, tick, dynamicPillarHeight);

                // 2) Minefield-style detonations (size scales via PHASE4_BLAST_SIZE)
                spawnMinefieldDetonationEffects(w, impact, tick, PHASE4_BLAST_SIZE);

                // 3) Damage + knockback (radius scales with blast size)
                damageEntitiesInSlashBeam(caster, impact, (BEAM_HIT_RADIUS() + 1.5) * PHASE4_BLAST_SIZE);

                // 4) Block destruction (radius scales with blast size, no drops)
                destroyBlocksNoDrop(w, impact, (BEAM_CORE_RADIUS + 0.8) * PHASE4_BLAST_SIZE);
                destroyBlocksNoDropNoisy(w, impact, (BEAM_WAVE_RADIUS + 1.6) * PHASE4_BLAST_SIZE, 0.55);

                // 5) Extra cracks slightly ahead for forward momentum
                Location ahead = impact.clone().add(sweepDir.clone().multiply(1.6));
                if (ahead.distance(casterOrigin) >= BEAM_SAFE_START_DISTANCE) {
                    destroyBlocksNoDropNoisy(w, ahead, 2.2 * PHASE4_BLAST_SIZE, 0.30);
                }

                traveled += BEAM_STEP_PER_TICK;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void playFinalSwordDropSequence(Player caster, Location groundImpact, Vector lockedDir, Runnable onFinish) {
        World w = groundImpact.getWorld();
        if (w == null) {
            onFinish.run();
            return;
        }

        final int DROP_TICKS = 28;      // slower, heavier fall
        final double START_HEIGHT = 34.0;
        final double BLADE_LENGTH = 13.5;

        Vector down = new Vector(0, -1, 0);
        Vector dir = lockedDir.clone().normalize();
        Vector side = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() < 0.0001) side = new Vector(1, 0, 0);
        side.normalize();
        final Vector finalSide = side;

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!caster.isOnline()) {
                    cancel();
                    onFinish.run();
                    return;
                }

                // Ease-in for "heavy sword" acceleration near the end
                double progress = Math.min(1.0, t / (double) DROP_TICKS);
                double eased = progress * progress * progress;
                double y = START_HEIGHT * (1.0 - eased);
                Location tip = groundImpact.clone().add(0, y, 0);

                int steps = 16;
                for (int i = 0; i <= steps; i++) {
                    double d = (i / (double) steps) * BLADE_LENGTH;
                    Location p = tip.clone().add(down.clone().multiply(d));
                    w.spawnParticle(Particle.END_ROD, p, 2, 0.01, 0.01, 0.01, 0.0);
                    spawnGoldParticle(w, p, 1.25f);
                    if ((i + t) % 3 == 0) {
                        w.spawnParticle(Particle.ELECTRIC_SPARK, p, 1, 0.02, 0.02, 0.02, 0.0);
                    }
                }

                double cutHalfWidth = 2.8;
                int cutPoints = 10;
                for (int i = -cutPoints; i <= cutPoints; i++) {
                    double k = i / (double) cutPoints;
                    Location edge = tip.clone().add(finalSide.clone().multiply(k * cutHalfWidth));
                    w.spawnParticle(Particle.FIREWORK, edge, 1, 0.03, 0.02, 0.03, 0.0);
                    if ((i + t) % 2 == 0) {
                        w.spawnParticle(Particle.CLOUD, edge, 1, 0.02, 0.01, 0.02, 0.0);
                    }
                }

                if (t % 2 == 0) {
                }
                if (t % 4 == 0) {
                }

                Location destructiveSlice = tip.clone().add(0, -0.5, 0);
                destroyBlocksNoDropNoisy(w, destructiveSlice, 2.3, 0.60);

                if (t >= DROP_TICKS) {
                    w.spawnParticle(Particle.FLASH, groundImpact, 5, 0.35, 0.25, 0.35, 0.0);
                    finalImpactCinematic(caster, groundImpact, lockedDir);
                    cancel();
                    onFinish.run();
                    return;
                }

                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }


    private Location snapImpactTowardGround(World w, Location center, int up, int down) {
        // Try to find the nearest non-air block around this vertical band and place impact slightly above it.
        int x = center.getBlockX();
        int z = center.getBlockZ();
        int y0 = center.getBlockY();

        // Search downward first (usually better for "impact on terrain")
        for (int dy = 0; dy <= down; dy++) {
            int y = y0 - dy;
            if (y < w.getMinHeight()) break;

            Material m = w.getBlockAt(x, y, z).getType();
            if (!m.isAir()) {
                return new Location(w, x + 0.5, y + 1.0, z + 0.5);
            }
        }

        // Then upward fallback
        for (int dy = 1; dy <= up; dy++) {
            int y = y0 + dy;
            if (y > w.getMaxHeight()) break;

            Material m = w.getBlockAt(x, y, z).getType();
            if (!m.isAir()) {
                return new Location(w, x + 0.5, y + 1.0, z + 0.5);
            }
        }

        return center;
    }
    private void drawFallingBeamPillar(World w, Location impact, int tick, double pillarHeight) {
        // "Huge stick falling from sky" visual
        // Height now passed in and can scale with beam length.
        double topHeight = pillarHeight;

        // Width pulse scaled by global phase4 blast size
        double width = (PILLAR_WIDTH_BASE * PHASE4_BLAST_SIZE)
                + (PILLAR_WIDTH_PULSE * PHASE4_BLAST_SIZE) * Math.sin(tick * 0.35);

        int verticalSteps = (int) Math.max(20, Math.round(topHeight * 1.35));
        for (int i = 0; i <= verticalSteps; i++) {
            double t = i / (double) verticalSteps;
            double y = topHeight * t;

            Location pt = impact.clone().add(0, y, 0);

            // Bright core line
            w.spawnParticle(Particle.END_ROD, pt, 2, 0.03, 0.03, 0.03, 0.0);
            spawnGoldParticle(w, pt, (float) (1.15 + 0.20 * PHASE4_BLAST_SIZE));

            // Shell ring around the core at this height
            int ringPts = (int) Math.max(8, Math.round(8 * PHASE4_BLAST_SIZE));
            for (int rp = 0; rp < ringPts; rp++) {
                double a = (Math.PI * 2.0) * (rp / (double) ringPts) + (tick * 0.15) + (t * 1.4);
                double r = width * (0.75 + 0.25 * Math.sin(tick * 0.2 + t * 6.0));

                Location ring = pt.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
                spawnGoldParticle(w, ring, (float) (0.85 + 0.10 * PHASE4_BLAST_SIZE));

                if ((rp + tick + i) % 3 == 0) {
                    w.spawnParticle(Particle.ELECTRIC_SPARK, ring, 1, 0.02, 0.02, 0.02, 0.0);
                }
            }
        }

        // Impact contact visuals scaled by blast size
        Location c = impact.clone().add(0, 0.15, 0);
        w.spawnParticle(Particle.FLASH, c, 1, 0.18 * PHASE4_BLAST_SIZE, 0.08, 0.18 * PHASE4_BLAST_SIZE, 0.0);
        w.spawnParticle(Particle.EXPLOSION, c, (int) Math.max(2, Math.round(2 * PHASE4_BLAST_SIZE)),
                0.45 * PHASE4_BLAST_SIZE, 0.18, 0.45 * PHASE4_BLAST_SIZE, 0.0);
        w.spawnParticle(Particle.CLOUD, c, (int) Math.max(10, Math.round(12 * PHASE4_BLAST_SIZE)),
                0.9 * PHASE4_BLAST_SIZE, 0.15, 0.9 * PHASE4_BLAST_SIZE, 0.03);
        w.spawnParticle(Particle.SMOKE, c, (int) Math.max(10, Math.round(14 * PHASE4_BLAST_SIZE)),
                0.95 * PHASE4_BLAST_SIZE, 0.2, 0.95 * PHASE4_BLAST_SIZE, 0.04);

        if (tick % 2 == 0) {
        }
        if (tick % 5 == 0) {
        }
    }

    private void spawnMinefieldDetonationEffects(World w, Location impact, int tick, double sizeMul) {
        double mul = Math.max(0.4, sizeMul);

        // Main ring blast
        w.spawnParticle(Particle.EXPLOSION, impact, (int) Math.max(3, Math.round(5 * mul)),
                1.25 * mul, 0.30, 1.25 * mul, 0.02);

        // Random mini-detonations (minefield feel)
        int bursts = (int) Math.max(3, Math.round((4 + (tick % 3)) * mul));
        for (int i = 0; i < bursts; i++) {
            double angle = Math.random() * Math.PI * 2.0;
            double r = (1.2 + Math.random() * 3.6) * mul;

            double x = Math.cos(angle) * r;
            double z = Math.sin(angle) * r;

            Location p = impact.clone().add(x, 0.1, z);

            w.spawnParticle(Particle.EXPLOSION, p, 1, 0.35 * mul, 0.16, 0.35 * mul, 0.0);
            w.spawnParticle(Particle.CLOUD, p, (int) Math.max(3, Math.round(6 * mul)),
                    0.38 * mul, 0.10, 0.38 * mul, 0.02);
            w.spawnParticle(Particle.SMOKE, p, (int) Math.max(4, Math.round(8 * mul)),
                    0.42 * mul, 0.14, 0.42 * mul, 0.03);

            if (i % 2 == 0) {
                spawnGoldParticle(w, p.clone().add(0, 0.15, 0), (float) (0.9 + 0.15 * mul));
                w.spawnParticle(Particle.ELECTRIC_SPARK, p.clone().add(0, 0.1, 0),
                        (int) Math.max(1, Math.round(2 * mul)),
                        0.12 * mul, 0.05, 0.12 * mul, 0.02);
            }
        }

        // Shockwave ring
        if (tick % 3 == 0) {
            drawGroundShockRing(w, impact, 5.2 * mul, (int) Math.max(16, Math.round(28 * mul)));
        }
    }
    private void drawGroundShockRing(World w, Location center, double radius, int points) {
        for (int i = 0; i < points; i++) {
            double a = (Math.PI * 2.0) * (i / (double) points);
            double x = Math.cos(a) * radius;
            double z = Math.sin(a) * radius;

            Location p = center.clone().add(x, 0.05, z);
            w.spawnParticle(Particle.CLOUD, p, 1, 0.04, 0.01, 0.04, 0.0);

            if (i % 3 == 0) {
                w.spawnParticle(Particle.SMOKE, p, 1, 0.03, 0.01, 0.03, 0.0);
            }
        }
    }

    private void finalImpactCinematic(Player caster, Location target, Vector lockedDir) {
        World w = target.getWorld();
        if (w == null) return;

        // Huge final flash / blast
        w.spawnParticle(Particle.FLASH, target, 8, 1.4, 0.7, 1.4, 0.0);
        w.spawnParticle(Particle.EXPLOSION, target, 14, 1.6, 0.8, 1.6, 0.02);
        w.spawnParticle(Particle.CLOUD, target, 120, 2.8, 1.0, 2.8, 0.05);
        w.spawnParticle(Particle.SMOKE, target, 160, 3.0, 1.2, 3.0, 0.06);
        w.spawnParticle(Particle.ELECTRIC_SPARK, target, 160, 2.4, 1.0, 2.4, 0.20);
        w.spawnParticle(Particle.FIREWORK, target, 100, 2.2, 1.0, 2.2, 0.08);
        spawnGoldenSphere(w, target, 2.8, 48);

        // Sound stack

        // Final destruction (no drops)
        destroyBlocksNoDrop(w, target, FINAL_BREAK_RADIUS);
        destroyBlocksNoDropNoisy(w, target, FINAL_BREAK_RADIUS + 1.8, 0.35);
        destroyBlocksNoDropNoisy(w, target, FINAL_BREAK_RADIUS + 3.2, 0.60);
        destroyBlocksNoDropNoisy(w, target, FINAL_BREAK_RADIUS + 5.2, 0.72);

        // Real explosion layer for true blast feel
        try {
            w.createExplosion(target, 8.0f, false, true, caster);
        } catch (Throwable ignored) {
            w.createExplosion(target.getX(), target.getY(), target.getZ(), 8.0f, false, true);
        }

        // Directional chain to sell "cut through then explode"
        Vector chainDir = lockedDir.clone().setY(0).normalize();
        if (chainDir.lengthSquared() > 0.0001) {
            for (int i = 1; i <= 5; i++) {
                Location p = target.clone().add(chainDir.clone().multiply(i * 3.4));
                p = snapImpactTowardGround(w, p, 5, 8);
                try {
                    w.createExplosion(p, 5.4f, false, true, caster);
                } catch (Throwable ignored) {
                    w.createExplosion(p.getX(), p.getY(), p.getZ(), 5.4f, false, true);
                }
                destroyBlocksNoDropNoisy(w, p, 4.6, 0.70);
            }
        }

        // Final blast keeps cinematic knockback only (no lethal finisher damage).
        for (Entity e : w.getNearbyEntities(target, FINAL_HIT_RADIUS, 8.0, FINAL_HIT_RADIUS)) {
            if (!(e instanceof LivingEntity le)) continue;
            if (e.getUniqueId().equals(caster.getUniqueId())) continue;
            if (e instanceof Player p && isTeammate(caster, p)) continue;

            Vector kb = e.getLocation().toVector().subtract(target.toVector());
            if (kb.lengthSquared() < 0.0001) kb = lockedDir.clone();
            kb.normalize().multiply(1.1).setY(0.45);
            e.setVelocity(kb);
        }
    }
    /* =========================
       Visuals / damage / destruction
       ========================= */

    private void drawChargeAura(Player p) {
        Location l = p.getLocation().add(0, 1.1, 0);
        World w = p.getWorld();

        w.spawnParticle(Particle.END_ROD, l, 10, 0.35, 0.4, 0.35, 0.01);
        w.spawnParticle(Particle.ELECTRIC_SPARK, l, 8, 0.30, 0.35, 0.30, 0.04);
        w.spawnParticle(Particle.FIREWORK, l, 4, 0.20, 0.25, 0.20, 0.01);

        // Small line toward target direction from locked look feel (current eye ok for charge)
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        for (int i = 1; i <= 6; i++) {
            Location pt = eye.clone().add(dir.clone().multiply(i * 1.2));
            w.spawnParticle(Particle.END_ROD, pt, 1, 0, 0, 0, 0);
        }
    }
    private void drawGoldenChargeRings(Player p, int tick) {
        World w = p.getWorld();
        Location base = p.getLocation().add(0, 0.15, 0);

        // Radius grows from ~1 to 2 over 10 sec
        double progress = Math.min(1.0, tick / (double) CAST_LOCK_TICKS);
        double radius = 1.0 + (1.0 * progress);

        int points = (int) Math.round((18 + (12 * progress)) * CHARGE_PARTICLE_MULT);
        if (points < 10) points = 10;

        // Two rings at different heights
        for (int i = 0; i < points; i++) {
            double a = (Math.PI * 2.0) * (i / (double) points);

            double x = Math.cos(a) * radius;
            double z = Math.sin(a) * radius;

            Location p1 = base.clone().add(x, 0.15, z);
            Location p2 = base.clone().add(x * 0.85, 1.05, z * 0.85);

            spawnGoldParticle(w, p1, 1.2f);
            if (i % 2 == 0) spawnGoldParticle(w, p2, 0.9f);

            // occasional white-hot sparkle accents
            if (i % 5 == 0) {
                w.spawnParticle(Particle.END_ROD, p1, 1, 0.01, 0.01, 0.01, 0.0);
            }
        }
    }

    private void drawRisingSparks(Player p, int tick) {
        World w = p.getWorld();
        Location center = p.getLocation().add(0, 1.1, 0);

        int count = (int) Math.round((2 + (tick >= 140 ? 5 : 0)) * CHARGE_PARTICLE_MULT); // rare early, more later
        for (int i = 0; i < count; i++) {
            double ox = (Math.random() - 0.5) * 0.7;
            double oz = (Math.random() - 0.5) * 0.7;
            double oy = Math.random() * 0.8;

            Location pt = center.clone().add(ox, oy, oz);
            w.spawnParticle(Particle.ELECTRIC_SPARK, pt, 1, 0.01, 0.06, 0.01, 0.02);
            if (tick % 8 == 0) w.spawnParticle(Particle.END_ROD, pt, 1, 0, 0, 0, 0);
        }
    }

    private void playChargeSoundLayer(Player p, int tick) {
        World w = p.getWorld();
        Location l = p.getLocation();

        // Quiet pulse every 20-30 ticks feeling
        float progress = (float) Math.min(1.0, tick / (double) CAST_LOCK_TICKS);


        if (tick >= 120) {
        }
    }

    private void drawAscendingExcaliburColumn(Player p, int localTick, int phaseTicks) {
        World w = p.getWorld();
        Location base = p.getLocation().add(0, 0.6, 0);

        // Height grows quickly during configured beam-rise phase.
        double phaseProgress = Math.min(1.0, localTick / (double) Math.max(1, phaseTicks));
        double height = 2.0 + 16.0 * phaseProgress;

        // Narrow bright core
        int coreSteps = Math.max(6, (int) (height * 2));
        for (int i = 0; i <= coreSteps; i++) {
            double y = (height * i) / coreSteps;
            Location pt = base.clone().add(0, y, 0);

            // Gold + bright accents
            spawnGoldParticle(w, pt, 1.4f);
            if (i % 2 == 0) w.spawnParticle(Particle.END_ROD, pt, 1, 0.01, 0.01, 0.01, 0.0);
            if (i % 3 == 0) w.spawnParticle(Particle.FIREWORK, pt, 1, 0.03, 0.03, 0.03, 0.0);
        }

        // Two spirals around the core
        double spiralRadius = 0.35 + 0.35 * phaseProgress;
        int spiralPoints = (int) Math.round((18 + 14 * phaseProgress) * ASCEND_PARTICLE_MULT);
        if (spiralPoints < 12) spiralPoints = 12;

        for (int i = 0; i < spiralPoints; i++) {
            double t = i / (double) spiralPoints;
            double y = t * height;

            double angle1 = (localTick * 0.22) + (t * Math.PI * 8.0);
            double angle2 = angle1 + Math.PI;

            Location s1 = base.clone().add(Math.cos(angle1) * spiralRadius, y, Math.sin(angle1) * spiralRadius);
            Location s2 = base.clone().add(Math.cos(angle2) * spiralRadius, y, Math.sin(angle2) * spiralRadius);

            spawnGoldParticle(w, s1, 1.0f);
            spawnGoldParticle(w, s2, 1.0f);
        }
    }

    private void playAscendingEnergySounds(Player p, int tick) {
        if (tick % 20 != 0) return; // every second
        World w = p.getWorld();
        Location l = p.getLocation();

        float progress = (float) Math.min(1.0, (tick - 120) / 80.0);
    }

    private void drawOverloadBurst(Player p, int localTick) {
        World w = p.getWorld();
        Location center = p.getLocation().add(0, 1.15, 0);

        double phase = Math.min(1.0, localTick / 60.0);
        double radius = 0.8 + 1.1 * phase;

        int sparks = (int) Math.round((16 + 28 * phase) * OVERLOAD_PARTICLE_MULT);
        int clouds = (int) Math.round((6 + 10 * phase) * OVERLOAD_PARTICLE_MULT);

        w.spawnParticle(Particle.ELECTRIC_SPARK, center, sparks, radius, 0.6 + 0.4 * phase, radius, 0.12);
        w.spawnParticle(Particle.CLOUD, center, clouds, 0.6 + 0.3 * phase, 0.25, 0.6 + 0.3 * phase, 0.02);

        // Flash pulses near end
        if (localTick > 40 && localTick % 8 == 0) {
            w.spawnParticle(Particle.FLASH, center, 1, 0.15, 0.15, 0.15, 0.0);
        }
    }

    private void lightNearbyShake(Player p, int tick) {
        if (tick % 10 != 0) return; // light pulse, not constant
        World w = p.getWorld();
        Location c = p.getLocation().add(0, 1.0, 0);

        for (Entity e : w.getNearbyEntities(c, 3.5, 2.5, 3.5)) {
            if (!(e instanceof LivingEntity)) continue;
            if (e.getUniqueId().equals(p.getUniqueId())) continue;

            Vector v = e.getLocation().toVector().subtract(c.toVector());
            if (v.lengthSquared() < 0.0001) continue;

            // Tiny shake/push
            e.setVelocity(e.getVelocity().add(v.normalize().multiply(0.08).setY(0.03)));
        }
    }

    private void drawSlashBeamFrame(World w, Location head, Vector beamStepDir, Vector lockedLookDir, int tick) {
        // Anime slash look = bright core + wide cut plane
        // Build a perpendicular vector for slash width
        Vector up = new Vector(0, 1, 0);
        Vector side = beamStepDir.clone().crossProduct(up);
        if (side.lengthSquared() < 0.0001) side = new Vector(1, 0, 0);
        side.normalize();

        // Slight vertical axis for "blade thickness"
        Vector bladeUp = side.clone().crossProduct(beamStepDir).normalize();
        if (bladeUp.lengthSquared() < 0.0001) bladeUp = new Vector(0, 1, 0);

        // 1) Bright core trail behind head
        for (int i = 0; i < 12; i++) {
            Location pt = head.clone().subtract(beamStepDir.clone().multiply(i * 0.55));
            w.spawnParticle(Particle.END_ROD, pt, 4, 0.10, 0.10, 0.10, 0.01);
            w.spawnParticle(Particle.FIREWORK, pt, 3, 0.08, 0.08, 0.08, 0.01);
            spawnGoldParticle(w, pt, 1.3f);
        }

        // 2) Wide slash plane (like sword cut)
        double halfWidth = 3.2;
        double halfHeight = 1.15;
        int widthPoints = (int) Math.round(13 * SLASH_PARTICLE_MULT);
        int heightPoints = (int) Math.round(5 * SLASH_PARTICLE_MULT);

        for (int wi = -widthPoints; wi <= widthPoints; wi++) {
            double wx = (wi / (double) widthPoints) * halfWidth;

            for (int hi = -heightPoints; hi <= heightPoints; hi++) {
                double hy = (hi / (double) heightPoints) * halfHeight;

                // Sparse sampling for performance
                if ((Math.abs(wi) + Math.abs(hi) + tick) % 2 != 0) continue;

                Location pt = head.clone()
                        .add(side.clone().multiply(wx))
                        .add(bladeUp.clone().multiply(hy));

                // edge brighter, center denser
                boolean edge = Math.abs(wx) > halfWidth * 0.75 || Math.abs(hy) > halfHeight * 0.75;

                if (edge) {
                    w.spawnParticle(Particle.ELECTRIC_SPARK, pt, 1, 0.01, 0.01, 0.01, 0.0);
                    spawnGoldParticle(w, pt, 0.9f);
                } else {
                    w.spawnParticle(Particle.CLOUD, pt, 1, 0.02, 0.02, 0.02, 0.0);
                    if ((wi + hi + tick) % 3 == 0) spawnGoldParticle(w, pt, 1.0f);
                }
            }
        }

        // 3) Random flash pops
        if (tick % 4 == 0) {
            w.spawnParticle(Particle.FLASH, head, 1, 0.2, 0.2, 0.2, 0.0);
        }
    }

    private void spawnSlashPulseEffects(World w, Location center, int tick) {
        // Particle-only explosion pulses (safe)
        w.spawnParticle(Particle.EXPLOSION, center, 1, 0.35, 0.25, 0.35, 0.0);

        if (tick % 2 == 0) {
            w.spawnParticle(Particle.SMOKE, center, 10, 0.7, 0.35, 0.7, 0.03);
            w.spawnParticle(Particle.CLOUD, center, 6, 0.6, 0.2, 0.6, 0.02);
        }

        if (tick % 3 == 0) {
        }
    }

    private void damageEntitiesInSlashBeam(Player caster, Location center, double radius) {
        World w = center.getWorld();
        if (w == null) return;

        for (Entity e : w.getNearbyEntities(center, radius, radius, radius)) {
            if (!(e instanceof LivingEntity le)) continue;
            if (e.getUniqueId().equals(caster.getUniqueId())) continue;

            if (e instanceof Player p && isTeammate(caster, p)) continue;

            // Landmine beam should hit hard and consistently even through invulnerability windows.
            le.setNoDamageTicks(0);
            le.damage(ClassRegistry.num("saberlight", "ult", "beamDamage", 12.0), caster);

            Vector kb = e.getLocation().toVector().subtract(center.toVector());
            if (kb.lengthSquared() > 0.0001) {
                kb.normalize().multiply(0.18).setY(0.04);
                e.setVelocity(e.getVelocity().add(kb));
            }
        }
    }

    private void destroyBlocksNoDropNoisy(World w, Location center, double radius, double chance) {
        int r = (int) Math.ceil(radius);
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        double r2 = radius * radius;

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    double dist2 = x * x + y * y + z * z;
                    if (dist2 > r2) continue;

                    if (Math.random() > chance) continue; // noisy outer destruction

                    Block b = w.getBlockAt(cx + x, cy + y, cz + z);
                    Material m = b.getType();
                    if (m.isAir()) continue;
                    if (!isBreakableByExcalibur(m)) continue;

                    b.setType(Material.AIR, false);
                }
            }
        }
    }

    private void spawnGoldParticle(World w, Location loc, float size) {
        // Gold dust look
        Particle.DustOptions gold = new Particle.DustOptions(Color.fromRGB(255, 215, 70), size);
        w.spawnParticle(Particle.DUST, loc, 1, 0.01, 0.01, 0.01, 0.0, gold);
    }

    private void spawnGoldenSphere(World w, Location center, double radius, int points) {
        for (int i = 0; i < points; i++) {
            double u = Math.random();
            double v = Math.random();
            double theta = 2.0 * Math.PI * u;
            double phi = Math.acos(2.0 * v - 1.0);

            double x = radius * Math.sin(phi) * Math.cos(theta);
            double y = radius * Math.cos(phi);
            double z = radius * Math.sin(phi) * Math.sin(theta);

            spawnGoldParticle(w, center.clone().add(x, y, z), 1.0f);
        }
    }

    private void drawBeamSegment(World w, Location head, Vector beamDir, double radius) {
        // Bright core line around head
        for (int i = 0; i < 8; i++) {
            Location p = head.clone().subtract(beamDir.clone().multiply(i * 0.5));
            w.spawnParticle(Particle.FIREWORK, p, 3, 0.08, 0.08, 0.08, 0.01);
            w.spawnParticle(Particle.END_ROD, p, 5, 0.18, 0.18, 0.18, 0.01);
            w.spawnParticle(Particle.ELECTRIC_SPARK, p, 4, 0.25, 0.20, 0.25, 0.06);
        }

        // Ring/cylinder-ish shell (cheap approximation)
        for (int j = 0; j < 10; j++) {
            double angle = (Math.PI * 2.0) * (j / 10.0);
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            Location ring = head.clone().add(x, 0, z);
            w.spawnParticle(Particle.CLOUD, ring, 1, 0.03, 0.08, 0.03, 0.0);
            w.spawnParticle(Particle.ELECTRIC_SPARK, ring, 1, 0.03, 0.05, 0.03, 0.0);
        }
    }

    private void spawnPulseExplosion(World w, Location center) {
        w.spawnParticle(Particle.EXPLOSION, center, 1, 0.2, 0.2, 0.2, 0.0);
        w.spawnParticle(Particle.SMOKE, center, 12, 0.5, 0.3, 0.5, 0.02);
    }

    private void damageEntitiesAlongBeam(Player caster, Location center, double radius) {
        World w = center.getWorld();
        if (w == null) return;

        for (Entity e : w.getNearbyEntities(center, radius, radius, radius)) {
            if (!(e instanceof LivingEntity le)) continue;
            if (e.getUniqueId().equals(caster.getUniqueId())) continue;

            if (e instanceof Player p && isTeammate(caster, p)) continue;

            // Pulse beam damage (small ticks, stacks over path)
            le.damage(4.0, caster);

            // Light knockback downward/away from beam center
            Vector kb = e.getLocation().toVector().subtract(center.toVector());
            if (kb.lengthSquared() > 0.0001) {
                kb.normalize().multiply(0.35).setY(0.08);
                e.setVelocity(e.getVelocity().add(kb));
            }
        }
    }

    private void destroyBlocksNoDrop(World w, Location center, double radius) {
        int r = (int) Math.ceil(radius);
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        double r2 = radius * radius;

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    double dist2 = x * x + y * y + z * z;
                    if (dist2 > r2) continue;

                    Block b = w.getBlockAt(cx + x, cy + y, cz + z);
                    Material m = b.getType();
                    if (m.isAir()) continue;
                    if (!isBreakableByExcalibur(m)) continue;

                    // No drops, no entities
                    b.setType(Material.AIR, false);
                }
            }
        }
    }

    private boolean isBreakableByExcalibur(Material m) {
        // Protect unbreakables and important stuff
        if (m == Material.BEDROCK || m == Material.BARRIER || m == Material.END_PORTAL_FRAME) return false;
        if (m == Material.OBSIDIAN || m == Material.CRYING_OBSIDIAN) return false; // optional
        if (m.name().contains("CHEST")) return false; // optional
        if (m.name().contains("SPAWNER")) return false; // optional

        // Let most normal blocks break
        return m.isBlock();
    }

    /* =========================
       Helpers
       ========================= */

    private void forceLockPose(Player p, CastState s) {
        Location current = p.getLocation();
        Location locked = s.lockedBodyLocation.clone();
        locked.setYaw(s.lockedYaw);
        locked.setPitch(s.lockedPitch);

        // Keep exact position and view
        if (current.distanceSquared(locked) > 0.0001
                || Math.abs(current.getYaw() - s.lockedYaw) > 0.01
                || Math.abs(current.getPitch() - s.lockedPitch) > 0.01) {
            p.teleport(locked);
        }
    }

    private void cleanupCast(Player p) {
        activeCasts.remove(p.getUniqueId());
        firingPhase.remove(p.getUniqueId());

        // Release lock and feedback
        if (p.isOnline()) {
            p.sendActionBar(Component.text("§aExcalibur complete."));
        }
    }

    private boolean isUltOnCooldown(Player p) {
        Long until = ultCooldownUntil.get(p.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    private CastState captureCurrentCastState(Player p) {
        Location eye = p.getEyeLocation().clone();
        Vector dir = eye.getDirection().normalize();
        Location target = findTargetPoint(p, eye, dir, BEAM_MAX_RANGE());

        return new CastState(
                p.getUniqueId(),
                p.getLocation().clone(),
                eye.getYaw(),
                eye.getPitch(),
                eye,
                dir,
                target
        );
    }

    private Location findTargetPoint(Player p, Location eye, Vector dir, double maxRange) {
        RayTraceResult hit = p.getWorld().rayTraceBlocks(eye, dir, maxRange, FluidCollisionMode.NEVER, true);
        if (hit != null && hit.getHitPosition() != null) {
            Vector v = hit.getHitPosition(); // already a Vector in your Paper version
            return new Location(p.getWorld(), v.getX(), v.getY(), v.getZ());
        }
        return eye.clone().add(dir.clone().multiply(maxRange));
    }

    private boolean isSoulRelease(ItemStack it) {
        if (it == null || it.getType() != Material.CROSSBOW || !it.hasItemMeta()) return false;

        ItemMeta rawMeta = it.getItemMeta();
        if (!(rawMeta instanceof CrossbowMeta meta)) return false;

        if (!meta.hasDisplayName()) return false;

        String plain = ChatColor.stripColor(meta.getDisplayName());
        if (plain == null || !plain.equalsIgnoreCase("Soul Release")) return false;

        // Must be charged (your requirement)
        if (!meta.hasChargedProjectiles()) return false;

        return true;
    }

    private boolean isSaberLight(Player p) {
        return p.getScoreboardTags().contains("LightSaber");
    }

    private boolean isTeammate(Player a, Player b) {
        if (a.getScoreboard() == null || b.getScoreboard() == null) return false;
        var ta = a.getScoreboard().getEntryTeam(a.getName());
        var tb = b.getScoreboard().getEntryTeam(b.getName());
        return ta != null && ta.equals(tb);
    }

    /**
     * Consumes souls directly from Excalibur in main hand by rebuilding item with reduced soul count.
     * This uses your existing listener's API + one extra helper you should add:
     * excalibur.setSouls(ItemStack, int) OR excalibur.rebuildExcaliburMeta(item, souls) public method.
     */
    private boolean consumeSoulsFromMainHandExcalibur(Player p, int cost) {
        ItemStack main = p.getInventory().getItemInMainHand();
        if (!excalibur.isExcalibur(main)) return false;

        int souls = excalibur.getSouls(main);
        if (souls < cost) return false;

        int newSouls = souls - cost;

        // --- IMPORTANT ---
        // Add a public method in SaberLightExcaliburListener:
        // excalibur.updateExcaliburSouls(main, newSouls);
        excalibur.updateExcaliburSouls(main, newSouls);

        p.getInventory().setItemInMainHand(main);
        p.sendActionBar(Component.text("§bSoul Release §7- §c" + cost + " souls §7(" + newSouls + " left)"));
        return true;
    }

    /* =========================
       State container
       ========================= */
    private static final class CastState {
        final UUID playerId;
        final Location lockedBodyLocation;
        final float lockedYaw;
        final float lockedPitch;
        final Location lockedEyeLocation;
        final Vector lockedDir;
        final Location targetPoint;

        CastState(UUID playerId,
                  Location lockedBodyLocation,
                  float lockedYaw,
                  float lockedPitch,
                  Location lockedEyeLocation,
                  Vector lockedDir,
                  Location targetPoint) {
            this.playerId = playerId;
            this.lockedBodyLocation = lockedBodyLocation;
            this.lockedYaw = lockedYaw;
            this.lockedPitch = lockedPitch;
            this.lockedEyeLocation = lockedEyeLocation;
            this.lockedDir = lockedDir;
            this.targetPoint = targetPoint;
        }
    }
    public static ItemStack makeSoulReleaseCrossbow() {
        ItemStack it = new ItemStack(Material.CROSSBOW);
        CrossbowMeta meta = (CrossbowMeta) it.getItemMeta();
        if (meta == null) return it;

        meta.displayName(Component.text("Soul Release"));

        // Make it charged (visual + logic)
        meta.addChargedProjectile(new ItemStack(Material.ARROW));

        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);

        ItemModels.apply(meta, "saber_lexcaliburult");
        it.setItemMeta(meta);
        return it;
    }
}
