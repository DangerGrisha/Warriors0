package org.money.money.kits.blastborn;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.money.money.session.KitResettable;
import org.money.money.session.KitSession;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Blastborn — Self-Destruction resource system + per-player state.
 *
 * <p>The "self-destruction" meter ({@code points}) builds up as the player uses
 * their gloves (and other gain hooks). A global decay task slowly bleeds it back
 * down while the player is in-game. When the meter caps out the player enters an
 * {@code overloaded} state and, after a short charge-up, detonates in a blast
 * centred on themselves.
 *
 * <p>The meter is surfaced to the player through their XP bar (level = points,
 * exp = points/max). Decay and bar updates only happen while {@link KitSession#isInGame}
 * is true — in the lobby the bar is left at 0 and nothing ticks.
 */
public final class BlastbornManager implements Listener, KitResettable {

    private static final String TAG_BLASTBORN = "Blastborn";

    private final Plugin plugin;

    // ===== Config (read once) =====
    private final int max;
    private final int decayIntervalTicks;
    private final int decayAmount;
    private final int gainPerGloveExplosion;
    private final int overloadDelayTicks;
    private final double overloadExplosionRadius;
    private final double overloadBlockBreakRadius;
    private final double overloadDamage;
    private final double overloadKnockback;
    private final double selfKnockbackMultiplier;
    private final boolean overloadKillsSelf;
    private final double overloadTotemDamageMultiplier;

    // ===== Per-player resource state =====
    private final Map<UUID, Integer> points = new HashMap<>();
    private final Map<UUID, Boolean> overloaded = new HashMap<>();
    private final Map<UUID, Boolean> ultActive = new HashMap<>();
    private final Map<UUID, BukkitTask> overloadTasks = new HashMap<>();

    // ===== Global decay task =====
    private BukkitTask decayTask;

    public BlastbornManager(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);

        this.max = Math.max(1, plugin.getConfig().getInt("classes.blastborn.selfDestruction.max", 100));
        this.decayIntervalTicks = Math.max(1, plugin.getConfig().getInt("classes.blastborn.selfDestruction.decayIntervalTicks", 10));
        this.decayAmount = Math.max(0, plugin.getConfig().getInt("classes.blastborn.selfDestruction.decayAmount", 1));
        this.gainPerGloveExplosion = plugin.getConfig().getInt("classes.blastborn.selfDestruction.gainPerGloveExplosion", 10);
        this.overloadDelayTicks = Math.max(1, plugin.getConfig().getInt("classes.blastborn.selfDestruction.overloadDelayTicks", 20));
        this.overloadExplosionRadius = plugin.getConfig().getDouble("classes.blastborn.selfDestruction.overloadExplosionRadius", 5.5);
        this.overloadBlockBreakRadius = plugin.getConfig().getDouble("classes.blastborn.selfDestruction.overloadBlockBreakRadius", 4.0);
        this.overloadDamage = plugin.getConfig().getDouble("classes.blastborn.selfDestruction.overloadDamage", 28.0);
        this.overloadKnockback = plugin.getConfig().getDouble("classes.blastborn.selfDestruction.overloadKnockback", 2.0);
        this.selfKnockbackMultiplier = plugin.getConfig().getDouble("classes.blastborn.selfKnockbackMultiplier", 2.25);
        this.overloadKillsSelf = plugin.getConfig().getBoolean("classes.blastborn.selfDestruction.overloadKillsSelf", true);
        this.overloadTotemDamageMultiplier = plugin.getConfig().getDouble("classes.blastborn.selfDestruction.overloadTotemDamageMultiplier", 0.5);

        startDecayTask();
    }

    /* ================== Global decay ================== */

    private void startDecayTask() {
        this.decayTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!p.getScoreboardTags().contains(TAG_BLASTBORN)) continue;
                    // Only decay / touch the bar while actually in a game; lobby is left alone.
                    if (!KitSession.isInGame(p)) continue;

                    UUID id = p.getUniqueId();
                    int cur = points.getOrDefault(id, 0);
                    if (cur <= 0) continue;

                    int next = Math.max(0, cur - decayAmount);
                    if (next != cur) {
                        points.put(id, next);
                        updateXpBar(p);
                    }
                }
            }
        }.runTaskTimer(plugin, decayIntervalTicks, decayIntervalTicks);
    }

    /* ================== XP bar ================== */

    private void updateXpBar(Player p) {
        int cur = points.getOrDefault(p.getUniqueId(), 0);
        p.setLevel(cur);
        p.setExp(Math.min(0.999f, cur / (float) max));
    }

    private void restoreXpBar(Player p) {
        p.setLevel(0);
        p.setExp(0f);
    }

    /* ================== Membership / points ================== */

    public void markBlastborn(Player p) {
        if (p == null) return;
        p.addScoreboardTag(TAG_BLASTBORN);
        points.put(p.getUniqueId(), 0);
        updateXpBar(p);
    }

    public boolean isBlastborn(Player p) {
        return p != null && p.getScoreboardTags().contains(TAG_BLASTBORN);
    }

    public void addPoints(Player p, int amount) {
        // Only ever touch a real Blastborn's meter/XP bar — never hijack a non-Blastborn's
        // vanilla XP bar (e.g. someone holding a stray gloves item after a drop/trade).
        if (p == null || amount == 0 || !isBlastborn(p)) return;
        UUID id = p.getUniqueId();
        int cur = points.getOrDefault(id, 0);
        int next = Math.max(0, Math.min(max, cur + amount));
        points.put(id, next);
        // While overloaded the blink task owns the bar; don't fight it.
        if (!isOverloaded(p)) updateXpBar(p);

        if (next >= max && !isOverloaded(p)) {
            triggerOverload(p);
        }
    }

    public void addGloveGain(Player p) {
        if (p == null) return;
        if (isUltActive(p)) return; // glove blasts don't build the meter during the ult
        addPoints(p, gainPerGloveExplosion);
    }

    public int getPoints(Player p) {
        if (p == null) return 0;
        return points.getOrDefault(p.getUniqueId(), 0);
    }

    public boolean isOverloaded(Player p) {
        return p != null && overloaded.getOrDefault(p.getUniqueId(), false);
    }

    /** TODO hook: subtract points (ice/water dousing). */
    public void coolDownOverheat(Player p, double amount) {
        if (p == null) return;
        addPoints(p, -(int) Math.round(amount));
    }

    /* ================== Ult flag ================== */

    public boolean isUltActive(Player p) {
        return p != null && ultActive.getOrDefault(p.getUniqueId(), false);
    }

    public void setUltActive(Player p, boolean active) {
        if (p == null) return;
        // Does NOT touch points.
        if (active) ultActive.put(p.getUniqueId(), true);
        else ultActive.remove(p.getUniqueId());
    }

    /* ================== Overload / detonation ================== */

    private void triggerOverload(Player p) {
        final UUID id = p.getUniqueId();
        overloaded.put(id, true);

        // Cancel any stale charge-up task (defensive; should not normally exist).
        BukkitTask prev = overloadTasks.remove(id);
        if (prev != null) prev.cancel();

        // Warning cue.
        Location warnLoc = p.getLocation();
        p.getWorld().playSound(warnLoc, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 0.5f);
        p.getWorld().playSound(warnLoc, Sound.ENTITY_TNT_PRIMED, 1.2f, 1.0f);
        p.sendActionBar(Component.text("OVERLOAD!", NamedTextColor.RED));

        BukkitTask task = new BukkitRunnable() {
            int elapsed = 0;

            @Override
            public void run() {
                Player online = Bukkit.getPlayer(id);

                // Abort the detonation if the player is gone/dead/out of the game.
                if (online == null || !online.isOnline() || online.isDead() || !KitSession.isInGame(online)) {
                    overloaded.put(id, false);
                    overloadTasks.remove(id);
                    if (online != null && online.isOnline()) restoreXpBar(online);
                    cancel();
                    return;
                }

                if (elapsed >= overloadDelayTicks) {
                    detonate(online);
                    overloadTasks.remove(id);
                    cancel();
                    return;
                }

                // Blink the XP bar between full and low + spit smoke/flame, with a ticking prime.
                boolean high = (elapsed / 2) % 2 == 0;
                online.setExp(high ? 0.999f : 0.15f);

                Location loc = online.getLocation().add(0, 1.0, 0);
                online.getWorld().spawnParticle(Particle.SMOKE, loc, 8, 0.4, 0.6, 0.4, 0.02);
                online.getWorld().spawnParticle(Particle.FLAME, loc, 6, 0.4, 0.6, 0.4, 0.02);

                if (elapsed % 4 == 0) {
                    online.getWorld().playSound(online.getLocation(), Sound.ENTITY_TNT_PRIMED, 1.0f, 1.4f);
                }

                elapsed++;
            }
        }.runTaskTimer(plugin, 0L, 1L);

        overloadTasks.put(id, task);
    }

    private void detonate(Player p) {
        Location loc = p.getLocation();
        UUID id = p.getUniqueId();

        // Holding a Totem of Undying? It soaks the self-destruct: the totem pops (vanilla, when the
        // lethal hit below lands) and the blast lands SOFTER on everyone else.
        boolean hasTotem = hasTotem(p);
        double othersMult = hasTotem ? overloadTotemDamageMultiplier : 1.0;

        ExplosionUtil.visualExplosion(loc, 5f, true);
        // Everyone else (reduced if a totem absorbed it). The source is handled separately below.
        ExplosionUtil.knockbackPlayers(loc, overloadExplosionRadius, overloadKnockback * othersMult, 0.5, p, false, false);
        ExplosionUtil.damagePlayers(loc, overloadExplosionRadius, overloadDamage * othersMult, p, false, 1.0, false);
        ExplosionUtil.breakBlocksSafely(loc, overloadBlockBreakRadius);

        // Clear the meter before the self-damage (death cleanup also clears it).
        points.put(id, 0);
        overloaded.put(id, false);

        // The self-destruct: launch Bakugo up and deal lethal damage to HIMSELF. A Totem of Undying
        // pops via vanilla mechanics (he survives at 1 HP, totem consumed); otherwise he dies.
        p.setVelocity(p.getVelocity().add(new Vector(0, 0.9 * selfKnockbackMultiplier, 0)));
        if (overloadKillsSelf) {
            p.damage(1000.0); // lethal -> totem pops if held, else death
        } else {
            p.damage(overloadDamage, p);
        }

        // If he survived (totem) refresh the now-zero bar.
        if (p.isOnline() && !p.isDead()) updateXpBar(p);
    }

    /** True if the player holds a Totem of Undying in either hand (vanilla pops it on lethal damage). */
    private boolean hasTotem(Player p) {
        return p.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || p.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
    }

    /* ================== KitResettable / lifecycle ================== */

    /**
     * Idempotent full clear of this player's resource state: points 0, overloaded false,
     * ult flag cleared, pending overload task cancelled, XP bar restored. The "Blastborn"
     * class-membership tag is intentionally left in place (the in-game guard already stops
     * decay and bar updates in the lobby).
     */
    @Override
    public void resetPlayer(Player player) {
        if (player == null) return;
        UUID id = player.getUniqueId();

        BukkitTask task = overloadTasks.remove(id);
        if (task != null) task.cancel();

        points.put(id, 0);
        overloaded.put(id, false);
        ultActive.remove(id);

        restoreXpBar(player);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        resetPlayer(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        resetPlayer(e.getEntity());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onGameModeChange(PlayerGameModeChangeEvent e) {
        if (e.getNewGameMode() == GameMode.SPECTATOR) {
            resetPlayer(e.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (KitSession.isLobbyWorld(p.getWorld())) {
            resetPlayer(p);
        }
    }

    /** Cancel the global decay task (Main.onDisable). */
    public void stop() {
        if (decayTask != null) {
            decayTask.cancel();
            decayTask = null;
        }
    }
}
