package org.money.money.kits.blastborn;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
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
import org.money.money.meta.ClassRegistry;
import org.money.money.session.KitResettable;
import org.money.money.session.KitSession;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Blastborn — Ultimate: Phoenix Detonator (VARIANT A).
 *
 * <p>RMB the Phoenix Detonator (Nether Star) to begin charging. The PLAYER themselves becomes
 * the phoenix — there is no separate controllable clone. The caster's <em>original</em> standing
 * spot is marked with a purely particle-drawn flame silhouette + a shrinking countdown ring
 * (no real entity is spawned, so nothing can leak), and a red boss bar counts the charge down.
 *
 * <p>While charging the player roams freely with flame/smoke trailing from their hands; gloves
 * still work (the {@link BlastbornManager} ult flag already suppresses glove self-destruction
 * gain). When the countdown ends a large explosion goes off at the player's CURRENT location
 * (manual knockback/damage + safe block break), and — if configured — the player is teleported
 * back to a safe spot near their original location.
 *
 * <p>The Phoenix Detonator item is consumed on cast and returned only after the ultimate cooldown,
 * and only while the player is still in-game (WindUlt / TimeWalkerUlt style).
 */
public final class PhoenixDetonatorListener implements Listener, KitResettable {

    private static final String TAG_ULT = "BlastbornUlt";

    private final Plugin plugin;
    private final BlastbornManager manager;

    // Item marker
    private final org.bukkit.NamespacedKey KEY_PHOENIX;

    // ===== Config (read once; non-balance toggles only — balance numbers come from ClassRegistry at use time) =====
    private final boolean returnToOriginalLocation;

    // ===== Active state =====
    private final Map<UUID, BukkitTask> activeTasks = new HashMap<>();
    private final Map<UUID, BukkitTask> markerTasks = new HashMap<>();
    private final Map<UUID, BukkitTask> cooldownTasks = new HashMap<>();
    private final Map<UUID, BossBar> bossBars = new HashMap<>();
    private final Map<UUID, Location> originalLocations = new HashMap<>();

    public PhoenixDetonatorListener(Plugin plugin, BlastbornManager manager) {
        this.plugin = Objects.requireNonNull(plugin);
        this.manager = Objects.requireNonNull(manager);
        this.KEY_PHOENIX = new org.bukkit.NamespacedKey(plugin, "blastborn_phoenix");

        this.returnToOriginalLocation = plugin.getConfig().getBoolean("classes.blastborn.ultimate.returnToOriginalLocation", true);
    }

    /** Charge-up duration (ticks), read at use time so /warriors reload applies. */
    private static int durationTicks() {
        return Math.max(1, ClassRegistry.numInt("blastborn", "ult", "durationTicks", 200));
    }

    /* ================== Item ================== */

    public ItemStack makePhoenixDetonator() {
        ItemStack it = new ItemStack(Material.NETHER_STAR);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Phoenix Detonator", NamedTextColor.GOLD));
        im.getPersistentDataContainer().set(KEY_PHOENIX, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(im);
        return it;
    }

    public boolean isPhoenix(ItemStack it) {
        if (it == null || it.getType() != Material.NETHER_STAR || !it.hasItemMeta()) return false;
        ItemMeta im = it.getItemMeta();
        if (im.getPersistentDataContainer().has(KEY_PHOENIX, PersistentDataType.BYTE)) return true;
        return Component.text("Phoenix Detonator", NamedTextColor.GOLD).equals(im.displayName());
    }

    private boolean playerHasPhoenix(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (isPhoenix(it)) return true;
        }
        return false;
    }

    // Consume exactly 1 Phoenix Detonator from main-hand or inventory
    private boolean consumeOnePhoenix(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (isPhoenix(hand)) {
            int amt = hand.getAmount();
            if (amt <= 1) p.getInventory().setItemInMainHand(null);
            else hand.setAmount(amt - 1);
            return true;
        }
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (isPhoenix(it)) {
                if (it.getAmount() <= 1) p.getInventory().setItem(i, null);
                else it.setAmount(it.getAmount() - 1);
                return true;
            }
        }
        return false;
    }

    // Return the ult item (only if player doesn't already have one)
    private void giveBackPhoenix(Player p) {
        if (playerHasPhoenix(p)) return;

        ItemStack item = makePhoenixDetonator();
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
        if (!isPhoenix(p.getInventory().getItemInMainHand())) return;

        e.setCancelled(true);

        // Lobby guard: don't burn the ult item outside of an active game.
        if (!KitSession.isInGame(p)) return;

        // Already active -> deny.
        if (manager.isUltActive(p) || activeTasks.containsKey(p.getUniqueId())) {
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.7f);
            p.sendMessage(Component.text("Phoenix Detonator is already charging.", NamedTextColor.RED));
            return;
        }

        // Consume the ult item to prevent spam; return after the full cooldown.
        if (!consumeOnePhoenix(p)) return;

        startUltimate(p);
    }

    private void startUltimate(Player caster) {
        final UUID id = caster.getUniqueId();

        // Cancel any pending cooldown give-back from a prior activation so item returns
        // cannot stack; a fresh one is scheduled below.
        BukkitTask prevCooldown = cooldownTasks.remove(id);
        if (prevCooldown != null) prevCooldown.cancel();

        // Save the ORIGINAL standing spot (+world via the location itself).
        final Location originalLocation = caster.getLocation().clone();
        originalLocations.put(id, originalLocation);
        final World originalWorld = originalLocation.getWorld();

        manager.setUltActive(caster, true);
        caster.addScoreboardTag(TAG_ULT);

        caster.getWorld().playSound(caster.getLocation(), Sound.ITEM_TOTEM_USE, 1.0f, 1.3f);
        caster.getWorld().playSound(caster.getLocation(), Sound.ENTITY_BLAZE_AMBIENT, 1.0f, 0.8f);
        caster.sendMessage(Component.text("Phoenix Detonator charging!", NamedTextColor.GOLD));

        // ===== Boss bar (red, counts the charge down) =====
        BossBar bar = Bukkit.createBossBar(
                "Phoenix Detonator", BarColor.RED, BarStyle.SOLID);
        bar.setProgress(1.0);
        bar.addPlayer(caster);
        bar.setVisible(true);
        bossBars.put(id, bar);

        // ===== Marker particle loop at the ORIGINAL location (no entity) =====
        startMarkerTask(id, originalLocation, originalWorld);

        // ===== Countdown task =====
        final Particle.DustOptions warnDust = new Particle.DustOptions(Color.fromRGB(255, 60, 0), 1.6f);

        BukkitTask task = new BukkitRunnable() {
            int elapsed = 0;

            @Override
            public void run() {
                Player online = Bukkit.getPlayer(id);

                // Early-end safety: caster gone / dead / changed world / left to lobby.
                if (online == null || !online.isOnline() || online.isDead()
                        || online.getWorld() == null || originalWorld == null
                        || !online.getWorld().equals(originalWorld)
                        || !KitSession.isInGame(online)) {
                    endUlt(id, false);
                    cancel();
                    return;
                }

                // Duration read each tick so /warriors reload applies without restart.
                int durationTicks = durationTicks();

                if (elapsed >= durationTicks) {
                    detonate(online);
                    endUlt(id, false);
                    cancel();
                    return;
                }

                int remaining = durationTicks - elapsed;

                // Boss bar progress.
                BossBar b = bossBars.get(id);
                if (b != null) {
                    b.setProgress(Math.max(0.0, Math.min(1.0, remaining / (double) durationTicks)));
                }

                // Flame/smoke around the player's hands each tick.
                Location hands = online.getLocation().add(0, 1.0, 0);
                online.getWorld().spawnParticle(Particle.FLAME, hands, 6, 0.4, 0.4, 0.4, 0.02);
                online.getWorld().spawnParticle(Particle.SMOKE, hands, 4, 0.4, 0.4, 0.4, 0.01);

                // Per-second charging cue.
                if (elapsed % 20 == 0) {
                    online.getWorld().playSound(online.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 0.8f);
                }

                // Last 2 seconds: louder ticking + red particles + a ground warning ring.
                if (remaining <= 40) {
                    if (elapsed % 4 == 0) {
                        online.getWorld().playSound(online.getLocation(), Sound.ENTITY_TNT_PRIMED, 1.2f, 1.6f);
                    }
                    online.getWorld().spawnParticle(Particle.LARGE_SMOKE, hands, 4, 0.5, 0.5, 0.5, 0.02);
                    drawGroundRing(online.getWorld(), online.getLocation(), 2.0, warnDust);
                }

                elapsed++;
            }
        }.runTaskTimer(plugin, 0L, 1L);

        activeTasks.put(id, task);

        // Return the item ONLY after the full cooldown, and ONLY if still in-game.
        // Cooldown read at use time so /warriors reload applies without restart.
        final int cooldownTicks = Math.max(1, ClassRegistry.ticks("blastborn", "ult", 2400));
        BukkitTask cooldownTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            cooldownTasks.remove(id);
            Player online = Bukkit.getPlayer(id);
            if (online != null && online.isOnline() && KitSession.isInGame(online)) {
                giveBackPhoenix(online);
                online.playSound(online.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.25f);
                online.sendMessage(Component.text("Phoenix Detonator is ready again!", NamedTextColor.GOLD));
            }
        }, cooldownTicks);
        cooldownTasks.put(id, cooldownTask);
    }

    /* ================== Original-location marker ================== */

    /**
     * Purely particle-drawn flame silhouette + shrinking countdown ring at the original spot.
     * No entity is spawned, so nothing can leak. Tracked so it is cancelled on end/cleanup.
     */
    private void startMarkerTask(final UUID id, final Location origin, final World originWorld) {
        if (origin == null || originWorld == null) return;

        final Particle.DustOptions silhouette = new Particle.DustOptions(Color.fromRGB(255, 140, 0), 1.3f);

        BukkitTask marker = new BukkitRunnable() {
            int elapsed = 0;

            @Override
            public void run() {
                // Stop if the ult is no longer active for this player.
                if (!activeTasks.containsKey(id)) {
                    cancel();
                    return;
                }
                if (originWorld.getPlayers().isEmpty() && Bukkit.getPlayer(id) == null) {
                    // Nothing to render to / player gone; the countdown task handles real cleanup.
                    cancel();
                    return;
                }

                // Standing flame silhouette: a thin vertical column of flame/dust.
                for (int s = 0; s <= 4; s++) {
                    double y = origin.getY() + s * 0.45;
                    Location pt = new Location(originWorld, origin.getX(), y, origin.getZ());
                    originWorld.spawnParticle(Particle.FLAME, pt, 2, 0.12, 0.12, 0.12, 0.0);
                    originWorld.spawnParticle(Particle.DUST, pt, 1, 0.12, 0.12, 0.12, 0.0, silhouette);
                }

                // Shrinking countdown ring on the ground.
                double frac = Math.max(0.0, 1.0 - (elapsed / (double) durationTicks()));
                double r = 0.3 + 1.8 * frac;
                drawGroundRing(originWorld, origin, r, silhouette);

                elapsed++;
            }
        }.runTaskTimer(plugin, 0L, 2L);

        markerTasks.put(id, marker);
    }

    private void drawGroundRing(World w, Location center, double radius, Particle.DustOptions dust) {
        if (w == null || radius <= 0) return;
        final int samples = Math.max(12, (int) Math.round(radius * 12.0));
        for (int i = 0; i < samples; i++) {
            double angle = (2.0 * Math.PI / samples) * i;
            double x = center.getX() + Math.cos(angle) * radius;
            double z = center.getZ() + Math.sin(angle) * radius;
            Location pt = new Location(w, x, center.getY() + 0.15, z);
            w.spawnParticle(Particle.DUST, pt, 1, 0.0, 0.0, 0.0, 0.0, dust);
        }
    }

    /* ================== Detonation ================== */

    private void detonate(Player p) {
        Location loc = p.getLocation();
        World w = loc.getWorld();

        // Balance numbers read at use time so /warriors reload applies without restart.
        final double finalExplosionRadius = ClassRegistry.num("blastborn", "ult", "finalExplosionRadius", 8.0);
        final double blockBreakRadius = ClassRegistry.num("blastborn", "ult", "blockBreakRadius", 5.0);
        final double damage = ClassRegistry.num("blastborn", "ult", "damage", 24.0);
        final double knockback = ClassRegistry.num("blastborn", "ult", "knockback", 3.5);
        final double selfKnockbackMultiplier = ClassRegistry.num("blastborn", "selfdestruction", "selfKnockbackMultiplier", 2.25);

        // Big blast at the player's CURRENT location — stronger blast + harder knockback,
        // with the caster flung the hardest of all.
        ExplosionUtil.visualExplosion(loc, 9f, true);
        ExplosionUtil.knockbackPlayers(loc, finalExplosionRadius, knockback, 0.7, p, true, false, selfKnockbackMultiplier);
        ExplosionUtil.damagePlayers(loc, finalExplosionRadius, damage, p, false, 1.0, false);
        ExplosionUtil.breakBlocksSafely(loc, blockBreakRadius);

        // Shockwave ring + dragon/firework cue.
        if (w != null) {
            Particle.DustOptions shock = new Particle.DustOptions(Color.fromRGB(255, 90, 0), 1.8f);
            drawGroundRing(w, loc, finalExplosionRadius * 0.6, shock);
            drawGroundRing(w, loc, finalExplosionRadius, shock);
            w.playSound(loc, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 1.2f);
            w.playSound(loc, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.2f, 1.0f);
        }

        // Return to the original location (safe spot only).
        if (returnToOriginalLocation) {
            Location origin = originalLocations.get(p.getUniqueId());
            if (origin != null && origin.getWorld() != null) {
                Location dest = ExplosionUtil.safeTeleport(origin);
                if (dest != null) {
                    p.teleport(dest);
                    if (dest.getWorld() != null) {
                        dest.getWorld().spawnParticle(Particle.FLAME, dest.clone().add(0, 1.0, 0), 30, 0.4, 0.8, 0.4, 0.05);
                        dest.getWorld().playSound(dest, Sound.ENTITY_BLAZE_SHOOT, 1.0f, 1.4f);
                    }
                }
            }
        }
    }

    /* ================== End / cleanup ================== */

    /**
     * End the ult and clean up everything for this player. Idempotent.
     *
     * @param id      caster UUID
     * @param effects whether to play a small fizzle cue (purely cosmetic)
     */
    private void endUlt(UUID id, boolean effects) {
        BukkitTask task = activeTasks.remove(id);
        if (task != null) task.cancel();

        BukkitTask marker = markerTasks.remove(id);
        if (marker != null) marker.cancel();

        BossBar bar = bossBars.remove(id);
        if (bar != null) {
            bar.removeAll();
            bar.setVisible(false);
        }

        originalLocations.remove(id);

        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            manager.setUltActive(online, false);
            online.removeScoreboardTag(TAG_ULT);
            online.removePotionEffect(PotionEffectType.RESISTANCE);
            if (effects && online.isOnline()) {
                online.getWorld().playSound(online.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.8f, 1.0f);
            }
        }
    }

    private void cancelCooldownTask(UUID id) {
        BukkitTask task = cooldownTasks.remove(id);
        if (task != null) task.cancel();
    }

    /* ================== KitResettable / lifecycle ================== */

    @Override
    public void resetPlayer(Player player) {
        if (player == null) return;
        UUID id = player.getUniqueId();
        endUlt(id, false);
        cancelCooldownTask(id);
        // Defensive: ensure flag/tag are gone even if no task was tracked.
        manager.setUltActive(player, false);
        player.removeScoreboardTag(TAG_ULT);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        endUlt(p.getUniqueId(), false);
        cancelCooldownTask(p.getUniqueId());
        manager.setUltActive(p, false);
        p.removeScoreboardTag(TAG_ULT);
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        // No return teleport on death — endUlt does not detonate, just cleans up.
        endUlt(p.getUniqueId(), false);
        cancelCooldownTask(p.getUniqueId());
        manager.setUltActive(p, false);
        p.removeScoreboardTag(TAG_ULT);
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (KitSession.isLobbyWorld(p.getWorld())) {
            resetPlayer(p);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onGameModeChange(org.bukkit.event.player.PlayerGameModeChangeEvent e) {
        // Going spectator mid-ult (common elimination handling) must end it — otherwise the
        // countdown keeps ticking and would detonate/teleport the spectator and leave the
        // bossbar, the ult-active flag and the tag stuck.
        if (e.getNewGameMode() == org.bukkit.GameMode.SPECTATOR) {
            resetPlayer(e.getPlayer());
        }
    }

    /** End all active ults + remove all boss bars (Main.onDisable). */
    public void shutdown() {
        for (UUID id : new java.util.HashSet<>(activeTasks.keySet())) {
            endUlt(id, false);
        }
        for (UUID id : new java.util.HashSet<>(markerTasks.keySet())) {
            BukkitTask marker = markerTasks.remove(id);
            if (marker != null) marker.cancel();
        }
        for (UUID id : new java.util.HashSet<>(cooldownTasks.keySet())) {
            cancelCooldownTask(id);
        }
        for (BossBar bar : bossBars.values()) {
            bar.removeAll();
            bar.setVisible(false);
        }
        bossBars.clear();
        originalLocations.clear();
    }
}
