package org.money.money.kits.timewalker;

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
import org.money.money.session.KitResettable;
import org.money.money.session.KitSession;

import java.util.*;

/**
 * TimeWalker — Ability 4: Chrono Mirage / Хроно-Мираж (ultimate).
 *
 * <p>RMB the Chrono Mirage item to deploy a FIXED particle domain at the caster's
 * current location. Spectral clones whirl around an anchor (rendered purely with
 * particles — NO real entities), slashing inward and ticking small damage on enemy
 * players in range. The caster gains the "TimeWalkerMirage" tag and an optional
 * self-buff for the duration. After a cooldown (from activation) the item is returned
 * only if the player is still in-game (WindUlt style).
 */
public final class TimeWalkerUltListener implements Listener, KitResettable {

    private static final String TAG_MIRAGE = "TimeWalkerMirage";

    private final Plugin plugin;

    // Item marker
    private final NamespacedKey KEY_CHRONO_MIRAGE;

    // ===== Config (read once) =====
    private final double radius;
    private final long durationTicks;     // duration-seconds * 20
    private final double damagePerTick;
    private final int tickPeriod;         // ticks between damage applications
    private final int cloneCount;
    private final String cloneSkin;
    private final long cooldownTicks;     // cooldown-seconds * 20
    private final int selfSpeedAmp;
    private final int selfResistanceAmp;  // < 0 means "no resistance"

    // ===== Active state =====
    private final Map<UUID, BukkitTask> activeTasks = new HashMap<>();
    private final Map<UUID, BukkitTask> cooldownTasks = new HashMap<>();

    // Citizens NPC clones — optional soft-depend. Lazily created only when Citizens is installed,
    // so the Citizens-referencing class is never loaded on servers without the plugin.
    private TimeWalkerMirageClones clones;

    public TimeWalkerUltListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_CHRONO_MIRAGE = new NamespacedKey(plugin, "timewalker_chrono_mirage");

        this.radius = plugin.getConfig().getDouble("timewalker.ult.radius", 36.0);
        this.durationTicks = Math.max(1L, plugin.getConfig().getInt("timewalker.ult.duration-seconds", 6)) * 20L;
        this.damagePerTick = plugin.getConfig().getDouble("timewalker.ult.damage-per-tick", 4.0);
        this.tickPeriod = Math.max(1, plugin.getConfig().getInt("timewalker.ult.tick-period", 10));
        this.cloneCount = Math.max(1, plugin.getConfig().getInt("timewalker.ult.clone-count", 6));
        this.cloneSkin = plugin.getConfig().getString("timewalker.ult.clone-skin", "seirah515");
        this.cooldownTicks = Math.max(1L, plugin.getConfig().getInt("timewalker.ult.cooldown-seconds", 60)) * 20L;
        this.selfSpeedAmp = plugin.getConfig().getInt("timewalker.ult.self-speed-amplifier", 1);
        this.selfResistanceAmp = plugin.getConfig().getInt("timewalker.ult.self-resistance-amplifier", 0);
    }

    /* ================== Item ================== */

    public ItemStack makeChronoMirageItem() {
        ItemStack it = new ItemStack(Material.ENDER_EYE);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Chrono Mirage", NamedTextColor.DARK_PURPLE));
        im.getPersistentDataContainer().set(KEY_CHRONO_MIRAGE, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(im);
        return it;
    }

    private boolean isChronoMirageItem(ItemStack it) {
        if (it == null || it.getType() != Material.ENDER_EYE || !it.hasItemMeta()) return false;
        ItemMeta im = it.getItemMeta();
        if (im.getPersistentDataContainer().has(KEY_CHRONO_MIRAGE, PersistentDataType.BYTE)) return true;
        return Component.text("Chrono Mirage", NamedTextColor.DARK_PURPLE).equals(im.displayName());
    }

    private boolean playerHasChronoMirageItem(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (isChronoMirageItem(it)) return true;
        }
        return false;
    }

    // Consume exactly 1 Chrono Mirage item from main-hand or inventory
    private boolean consumeOneChronoMirage(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (isChronoMirageItem(hand)) {
            int amt = hand.getAmount();
            if (amt <= 1) p.getInventory().setItemInMainHand(null);
            else hand.setAmount(amt - 1);
            return true;
        }
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (isChronoMirageItem(it)) {
                if (it.getAmount() <= 1) p.getInventory().setItem(i, null);
                else it.setAmount(it.getAmount() - 1);
                return true;
            }
        }
        return false;
    }

    // Return the ult item (only if player doesn't already have one)
    private void giveBackChronoMirage(Player p) {
        if (playerHasChronoMirageItem(p)) return;

        ItemStack item = makeChronoMirageItem();
        ItemStack mh = p.getInventory().getItemInMainHand();
        if (mh == null || mh.getType() == Material.AIR) {
            p.getInventory().setItemInMainHand(item);
        } else {
            Map<Integer, ItemStack> left = p.getInventory().addItem(item);
            if (!left.isEmpty()) {
                p.getWorld().dropItemNaturally(p.getLocation(), left.values().iterator().next());
            }
        }
    }

    /* ================== Activation ================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onUseUlt(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        if (!isChronoMirageItem(p.getInventory().getItemInMainHand())) return;

        e.setCancelled(true);

        // Lobby guard: don't burn the ult item outside of an active game.
        if (!KitSession.isInGame(p)) return;

        // Already active -> do nothing
        if (p.getScoreboardTags().contains(TAG_MIRAGE) || activeTasks.containsKey(p.getUniqueId())) {
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.7f);
            p.sendMessage(Component.text("Chrono Mirage is already active.", NamedTextColor.RED));
            return;
        }

        // Consume the ult item to prevent spam; return after full cooldown
        if (!consumeOneChronoMirage(p)) return;

        startMirage(p);
    }

    private void startMirage(Player caster) {
        final UUID id = caster.getUniqueId();

        // Cancel any pending cooldown give-back from a prior activation so item
        // returns cannot stack; a fresh one is scheduled below.
        BukkitTask prevCooldown = cooldownTasks.remove(id);
        if (prevCooldown != null) prevCooldown.cancel();

        // FIXED anchor: a clone of the caster's current location. Domain does NOT move.
        final Location anchor = caster.getLocation().clone();
        final World world = anchor.getWorld();

        caster.addScoreboardTag(TAG_MIRAGE);
        applySelfBuff(caster);

        caster.getWorld().playSound(anchor, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.9f, 1.4f);
        caster.getWorld().playSound(anchor, Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.6f);
        caster.sendMessage(Component.text("Chrono Mirage deployed.", NamedTextColor.LIGHT_PURPLE));

        final Particle.DustOptions ringDust = new Particle.DustOptions(Color.fromRGB(40, 170, 255), 1.2f);
        final Particle.DustOptions cloneDust = new Particle.DustOptions(Color.fromRGB(90, 200, 255), 1.0f);

        BukkitTask task = new BukkitRunnable() {
            int elapsed = 0;
            double baseAngle = 0.0;

            @Override
            public void run() {
                Player online = Bukkit.getPlayer(id);

                // Early-end safety: caster gone / dead / changed world / left to lobby.
                if (online == null || !online.isOnline() || online.isDead()
                        || !online.getWorld().equals(world)
                        || !KitSession.isInGame(online)) {
                    endMirage(id, anchor, true);
                    cancel();
                    return;
                }

                // Refresh the self-buff so it persists for the whole duration.
                applySelfBuff(online);

                // Ground ring of particles at the FIXED anchor.
                drawGroundRing(world, anchor, ringDust);

                // Whirling clones (fast circular motion), rendered purely with particles.
                baseAngle += 0.55; // advances quickly each tick
                final double prevAngle = baseAngle - 0.55;
                for (int i = 0; i < cloneCount; i++) {
                    double slot = i * (2.0 * Math.PI / cloneCount);
                    double angle = baseAngle + slot;
                    Location pos = clonePosition(anchor, angle);

                    // Afterimage core: a few DUST + a soul/end-rod accent.
                    world.spawnParticle(Particle.DUST, pos, 3, 0.08, 0.20, 0.08, 0.0, cloneDust);
                    world.spawnParticle(Particle.SOUL, pos, 1, 0.02, 0.05, 0.02, 0.0);
                    world.spawnParticle(Particle.END_ROD, pos, 1, 0.01, 0.01, 0.01, 0.0);

                    // Short trailing segment toward the previous angle (the afterimage).
                    Location prev = clonePosition(anchor, prevAngle + slot);
                    Location mid = pos.clone().add(prev).multiply(0.5);
                    world.spawnParticle(Particle.DUST, mid, 1, 0.05, 0.10, 0.05, 0.0, cloneDust);
                }

                // Slash lines (the "рассечения"): every few ticks, a couple of clones
                // cut inward toward the anchor.
                if (elapsed % 4 == 0) {
                    int lines = Math.min(2, cloneCount);
                    for (int i = 0; i < lines; i++) {
                        double angle = baseAngle + i * (2.0 * Math.PI / cloneCount);
                        Location from = clonePosition(anchor, angle);
                        drawSlashLine(world, from, anchor.clone().add(0, 1.0, 0));
                    }
                }

                // Damage tick.
                if (elapsed % tickPeriod == 0) {
                    damageEnemiesInDomain(online, anchor);
                }

                elapsed++;
                if (elapsed >= durationTicks) {
                    endMirage(id, anchor, true);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);

        activeTasks.put(id, task);

        // Optional Citizens NPC clones: spawn, run around the (now huge) circle, and vanish.
        if (Bukkit.getPluginManager().getPlugin("Citizens") != null) {
            try {
                if (clones == null) clones = new TimeWalkerMirageClones(plugin);
                clones.start(caster, anchor, radius, cloneCount, durationTicks, cloneSkin);
            } catch (Throwable t) {
                plugin.getLogger().warning("[TimeWalker] Citizens clones unavailable: " + t.getMessage());
            }
        }

        // Return the item ONLY after the full cooldown, and ONLY if still in-game.
        BukkitTask cooldownTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            cooldownTasks.remove(id);
            Player online = Bukkit.getPlayer(id);
            if (online != null && online.isOnline() && KitSession.isInGame(online)) {
                giveBackChronoMirage(online);
                online.playSound(online.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.25f);
                online.sendMessage(Component.text("Chrono Mirage is ready again!", NamedTextColor.LIGHT_PURPLE));
            }
        }, cooldownTicks);
        cooldownTasks.put(id, cooldownTask);
    }

    /* ================== Geometry / visuals ================== */

    private Location clonePosition(Location anchor, double angle) {
        double x = anchor.getX() + Math.cos(angle) * radius;
        double z = anchor.getZ() + Math.sin(angle) * radius;
        return new Location(anchor.getWorld(), x, anchor.getY() + 1.0, z);
    }

    private void drawGroundRing(World w, Location anchor, Particle.DustOptions dust) {
        // Sample count scales with the radius so the (now large) circle still reads as a ring.
        final int samples = Math.max(24, (int) Math.round(radius * 2.0));
        for (int i = 0; i < samples; i++) {
            double angle = (2.0 * Math.PI / samples) * i;
            double x = anchor.getX() + Math.cos(angle) * radius;
            double z = anchor.getZ() + Math.sin(angle) * radius;
            Location pt = new Location(w, x, anchor.getY() + 0.15, z);
            w.spawnParticle(Particle.DUST, pt, 1, 0.0, 0.0, 0.0, 0.0, dust);
        }
    }

    private void drawSlashLine(World w, Location from, Location to) {
        final int steps = 6;
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            double x = from.getX() + (to.getX() - from.getX()) * t;
            double y = from.getY() + (to.getY() - from.getY()) * t;
            double z = from.getZ() + (to.getZ() - from.getZ()) * t;
            Location pt = new Location(w, x, y, z);
            if (s % 2 == 0) w.spawnParticle(Particle.SWEEP_ATTACK, pt, 1, 0.0, 0.0, 0.0, 0.0);
            else w.spawnParticle(Particle.CRIT, pt, 1, 0.02, 0.02, 0.02, 0.0);
        }
    }

    /* ================== Damage ================== */

    private void damageEnemiesInDomain(Player caster, Location anchor) {
        World w = anchor.getWorld();
        double r = radius;
        for (Entity ent : w.getNearbyEntities(anchor, r, r, r)) {
            if (!(ent instanceof Player target)) continue;
            if (!target.isOnline() || target.isDead()) continue;
            if (target.equals(caster)) continue;
            if (isFriendly(caster, target)) continue;
            // Confirm it is actually within the spherical radius of the anchor.
            if (target.getLocation().distanceSquared(anchor) > r * r) continue;

            target.damage(damagePerTick, caster);
            w.spawnParticle(Particle.CRIT, target.getLocation().add(0, 1.0, 0), 6, 0.2, 0.3, 0.2, 0.05);
            w.playSound(target.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 1.6f);
        }
    }

    /* ================== Self-buff ================== */

    private void applySelfBuff(Player p) {
        // Refreshed each tick; short duration buffer so it falls off promptly on end.
        int dur = 30;
        if (selfSpeedAmp >= 0) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, dur, selfSpeedAmp, false, false, false));
        }
        if (selfResistanceAmp >= 0) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, dur, selfResistanceAmp, false, false, false));
        }
    }

    private void removeSelfBuff(Player p) {
        p.removePotionEffect(PotionEffectType.SPEED);
        p.removePotionEffect(PotionEffectType.RESISTANCE);
    }

    /* ================== End / cleanup ================== */

    /**
     * End the ult and clean up everything. Idempotent.
     *
     * @param id      caster UUID
     * @param anchor  fixed anchor location (for the collapse burst); may be null
     * @param effects whether to play the collapse sound/particle burst
     */
    private void endMirage(UUID id, Location anchor, boolean effects) {
        BukkitTask task = activeTasks.remove(id);
        if (task != null) task.cancel();

        // Despawn this caster's Citizens clones (if any).
        if (clones != null) {
            try { clones.stop(id); } catch (Throwable ignored) {}
        }

        Player online = Bukkit.getPlayer(id);
        if (online != null && online.isOnline()) {
            online.removeScoreboardTag(TAG_MIRAGE);
            removeSelfBuff(online);
        }

        if (effects && anchor != null && anchor.getWorld() != null) {
            World w = anchor.getWorld();
            // Final implosion burst — all clones vanish at once.
            w.playSound(anchor, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.8f, 0.7f);
            w.playSound(anchor, Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 0.9f);
            Particle.DustOptions burst = new Particle.DustOptions(Color.fromRGB(40, 170, 255), 1.4f);
            w.spawnParticle(Particle.DUST, anchor.clone().add(0, 1.0, 0), 40, radius * 0.4, 0.8, radius * 0.4, 0.0, burst);
            w.spawnParticle(Particle.SOUL_FIRE_FLAME, anchor.clone().add(0, 1.0, 0), 20, 0.3, 0.6, 0.3, 0.05);
        }
    }

    /* ================== Team check ================== */

    private boolean isFriendly(Player a, Player b) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team ta = sb.getEntryTeam(a.getName());
        Team tb = sb.getEntryTeam(b.getName());
        if (ta == null || tb == null) return false;
        return ta.getName().equalsIgnoreCase(tb.getName());
    }

    /* ================== KitResettable / lifecycle ================== */

    @Override
    public void resetPlayer(Player player) {
        if (player == null) return;
        // Idempotent end + cleanup (no anchor-bound burst needed on reset, but harmless).
        endMirage(player.getUniqueId(), player.getLocation().clone(), false);
        cancelCooldownTask(player.getUniqueId());
        // Defensive: ensure tag/buffs are gone even if no task was tracked.
        player.removeScoreboardTag(TAG_MIRAGE);
        removeSelfBuff(player);
    }

    private void cancelCooldownTask(UUID id) {
        BukkitTask task = cooldownTasks.remove(id);
        if (task != null) task.cancel();
    }

    /** Destroy all Citizens clone NPCs + registry (called from Main.onDisable). */
    public void shutdownClones() {
        if (clones != null) {
            try { clones.shutdown(); } catch (Throwable ignored) {}
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        endMirage(p.getUniqueId(), null, false);
        cancelCooldownTask(p.getUniqueId());
        p.removeScoreboardTag(TAG_MIRAGE);
        removeSelfBuff(p);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        endMirage(p.getUniqueId(), null, false);
        cancelCooldownTask(p.getUniqueId());
        p.removeScoreboardTag(TAG_MIRAGE);
        removeSelfBuff(p);
    }
}
