package org.money.money.kits.blastborn;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.money.money.meta.ClassRegistry;
import org.money.money.session.KitResettable;
import org.money.money.session.KitSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Blastborn — Blast Gloves.
 *
 * <p>A two-mode mobility/burst tool. The held item carries an INTEGER PDC mode flag
 * (0 = Wall Blast, 1 = Air Burst) that the player toggles with the off-hand swap key (F).
 *
 * <ul>
 *   <li><b>Wall Blast</b> (mode 0): ray-trace to a surface ahead and detonate there. Pushes
 *       <em>everyone</em> in range (including the caster) — pure mobility, no damage.</li>
 *   <li><b>Air Burst</b> (mode 1): detonate at a point in front of the player along a clear air
 *       path. Pushes everyone (incl. self for the launch) and damages enemies + the caster
 *       (reduced self-damage). The self-damage is the cost of the mobility.</li>
 * </ul>
 *
 * <p>Each glove explosion feeds the Self-Destruction meter via {@link BlastbornManager#addGloveGain}
 * (which no-ops while the ult is active). The gloves hold no long-lived tasks beyond the per-mode
 * cooldown action-bar timers, which are cancelled on quit/death/reset.
 */
public final class BlastGlovesListener implements Listener, KitResettable {

    private static final int MODE_WALL = 0;
    private static final int MODE_AIR = 1;

    private final Plugin plugin;
    private final BlastbornManager manager;

    private final NamespacedKey KEY_GLOVES;
    private final NamespacedKey KEY_MODE;

    // ===== Config (read once; non-balance toggles only — balance numbers come from ClassRegistry at use time) =====
    private final boolean switchWithF;
    private final boolean wallBreakBlocks;
    private final boolean airBreakBlocks;
    private final boolean airRequireClearPath;

    // ===== Per-player cooldown state (one shared cooldown per player) =====
    private final Map<UUID, Long> cooldownMap = new HashMap<>();
    private final Map<UUID, BukkitTask> cdTasks = new HashMap<>();

    public BlastGlovesListener(Plugin plugin, BlastbornManager manager) {
        this.plugin = Objects.requireNonNull(plugin);
        this.manager = Objects.requireNonNull(manager);

        this.KEY_GLOVES = new NamespacedKey(plugin, "blastborn_gloves");
        this.KEY_MODE = new NamespacedKey(plugin, "blastborn_glove_mode");

        this.switchWithF = plugin.getConfig().getBoolean("classes.blastborn.gloves.switchWithF", true);
        this.wallBreakBlocks = plugin.getConfig().getBoolean("classes.blastborn.gloves.wallBlast.breakBlocks", false);
        this.airBreakBlocks = plugin.getConfig().getBoolean("classes.blastborn.gloves.airBurst.breakBlocks", false);
        this.airRequireClearPath = plugin.getConfig().getBoolean("classes.blastborn.gloves.airBurst.requireClearAirPath", true);
    }

    /** Caster knockback multiplier (class-wide passive value, hot-reloadable). */
    private static double selfKnockbackMultiplier() {
        return ClassRegistry.num("blastborn", "selfdestruction", "selfKnockbackMultiplier", 2.25);
    }

    /* ================== Item ================== */

    /** Create the Blast Gloves item (starts in Wall Blast mode). */
    public ItemStack makeBlastGloves() {
        ItemStack it = new ItemStack(Material.LEATHER_HORSE_ARMOR);
        ItemMeta im = it.getItemMeta();
        im.getPersistentDataContainer().set(KEY_GLOVES, PersistentDataType.BYTE, (byte) 1);
        im.getPersistentDataContainer().set(KEY_MODE, PersistentDataType.INTEGER, MODE_WALL);
        im.setUnbreakable(true);
        im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
        it.setItemMeta(im);
        applyModeVisual(it, MODE_WALL);
        return it;
    }

    /** True if this stack is a pair of Blast Gloves (PDC BYTE marker present). */
    public boolean isBlastGloves(ItemStack it) {
        if (it == null || it.getType() != Material.LEATHER_HORSE_ARMOR || !it.hasItemMeta()) return false;
        ItemMeta im = it.getItemMeta();
        return im.getPersistentDataContainer().has(KEY_GLOVES, PersistentDataType.BYTE);
    }

    private int getMode(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return MODE_WALL;
        Integer mode = it.getItemMeta().getPersistentDataContainer().get(KEY_MODE, PersistentDataType.INTEGER);
        return mode == null ? MODE_WALL : mode;
    }

    private void setMode(ItemStack it, int mode) {
        if (it == null || !it.hasItemMeta()) return;
        ItemMeta im = it.getItemMeta();
        im.getPersistentDataContainer().set(KEY_MODE, PersistentDataType.INTEGER, mode);
        it.setItemMeta(im);
    }

    /** Update the held stack's display name + lore + model data to reflect {@code mode}. */
    private void applyModeVisual(ItemStack it, int mode) {
        if (it == null || !it.hasItemMeta()) return;
        ItemMeta im = it.getItemMeta();

        if (mode == MODE_AIR) {
            im.displayName(Component.text("Blast Gloves [Air Burst]", NamedTextColor.AQUA));
            im.lore(List.of(Component.text("Detonate in front of you — launches + damages", NamedTextColor.GRAY)));
        } else {
            im.displayName(Component.text("Blast Gloves [Wall Blast]", NamedTextColor.GOLD));
            im.lore(List.of(Component.text("Blast off a surface to launch yourself", NamedTextColor.GRAY)));
        }
        im.setCustomModelData(mode);

        it.setItemMeta(im);
    }

    /* ================== F-switch (toggle mode) ================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        if (!switchWithF) return;

        Player p = e.getPlayer();
        ItemStack main = p.getInventory().getItemInMainHand();
        if (!isBlastGloves(main)) return;
        if (!KitSession.isInGame(p)) return;

        e.setCancelled(true);

        int next = getMode(main) == MODE_WALL ? MODE_AIR : MODE_WALL;
        setMode(main, next);
        applyModeVisual(main, next);
        p.getInventory().setItemInMainHand(main);

        if (next == MODE_AIR) {
            p.sendActionBar(Component.text("Mode: Air Burst", NamedTextColor.AQUA));
        } else {
            p.sendActionBar(Component.text("Mode: Wall Blast", NamedTextColor.GOLD));
        }
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
    }

    /* ================== Interact (fire) ================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;

        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        ItemStack main = p.getInventory().getItemInMainHand();
        if (!isBlastGloves(main)) return;

        e.setCancelled(true);

        if (!KitSession.isInGame(p)) return;

        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();

        int mode = getMode(main);
        // Both modes share the single "gloves" cooldown key; read at use time so /warriors reload applies.
        int cooldownTicks = Math.max(0, ClassRegistry.ticks("blastborn", "gloves", 8));
        long cooldownMs = cooldownTicks * 50L;

        if (cooldownMap.containsKey(id)) {
            long last = cooldownMap.get(id);
            long passed = now - last;
            if (passed < cooldownMs) {
                long secLeft = (cooldownMs - passed + 999) / 1000;
                p.sendActionBar(Component.text(secLeft + " sec", NamedTextColor.RED));
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.8f);
                return;
            }
        }

        // Each ability returns true on a successful cast (so a failed cast costs no cooldown).
        boolean fired = mode == MODE_AIR ? airBurst(p) : wallBlast(p);
        if (!fired) return;

        // Air Burst self-damage can kill the caster synchronously (death cleanup already ran);
        // don't re-add a stale cooldown / meter gain for a just-dead player.
        if (!p.isOnline() || p.isDead()) return;

        manager.addGloveGain(p);

        cooldownMap.put(id, now);
        if (cooldownTicks > 0) {
            startCooldownTimer(p, cooldownTicks);
        }
    }

    /* ================== Wall Blast (mode 0) ================== */

    private boolean wallBlast(Player p) {
        // Balance numbers read at use time so /warriors reload applies without restart.
        final double wallRange = ClassRegistry.num("blastborn", "gloves", "wallRange", 4.0);
        final double wallRadius = ClassRegistry.num("blastborn", "gloves", "wallRadius", 3.0);
        final double wallDamage = ClassRegistry.num("blastborn", "gloves", "wallDamage", 0.0);
        final double wallKnockback = ClassRegistry.num("blastborn", "gloves", "wallKnockback", 1.6);

        RayTraceResult rr = ExplosionUtil.rayTrace(p, wallRange);
        if (rr == null || rr.getHitBlock() == null) {
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.6f);
            return false;
        }

        World w = p.getWorld();
        Location center = rr.getHitPosition().toLocation(w);

        ExplosionUtil.visualExplosion(center, 2.5f, false);
        // Push EVERYONE including the caster — this is the mobility tool, and the caster is flung
        // several times harder (selfKnockbackMultiplier) so he can ricochet off walls.
        ExplosionUtil.knockbackPlayers(center, wallRadius, wallKnockback, 0.4, p, true, false, selfKnockbackMultiplier());
        if (wallDamage > 0) {
            ExplosionUtil.damagePlayers(center, wallRadius, wallDamage, p, false, 1.0, false);
        }
        if (wallBreakBlocks) {
            ExplosionUtil.breakBlocksSafely(center, wallRadius);
        }
        return true;
    }

    /* ================== Air Burst (mode 1) ================== */

    private boolean airBurst(Player p) {
        // Balance numbers read at use time so /warriors reload applies without restart.
        final double airDistance = ClassRegistry.num("blastborn", "gloves", "airDistance", 3.0);
        final double airRadius = ClassRegistry.num("blastborn", "gloves", "airRadius", 3.2);
        final double airDamage = ClassRegistry.num("blastborn", "gloves", "airDamage", 6.0);
        final double airSelfDamageMult = ClassRegistry.num("blastborn", "gloves", "airSelfDamageMultiplier", 0.75);
        final double airKnockback = ClassRegistry.num("blastborn", "gloves", "airKnockback", 1.8);

        if (airRequireClearPath && !ExplosionUtil.hasClearPath(p, airDistance)) {
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.6f);
            return false;
        }

        Vector dir = p.getEyeLocation().getDirection().normalize();
        Location center = p.getEyeLocation().add(dir.multiply(airDistance));
        if (center.getBlock().getType().isSolid()) {
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.6f);
            return false;
        }

        ExplosionUtil.visualExplosion(center, 3f, true);
        // Push everyone incl. self — the caster is launched several times harder for flight.
        ExplosionUtil.knockbackPlayers(center, airRadius, airKnockback, 0.5, p, true, false, selfKnockbackMultiplier());
        // Damage enemies + the caster (reduced); never hit allies.
        ExplosionUtil.damagePlayers(center, airRadius, airDamage, p, true, airSelfDamageMult, false);
        if (airBreakBlocks) {
            ExplosionUtil.breakBlocksSafely(center, airRadius);
        }
        return true;
    }

    /* ================== Cooldown actionbar timer ================== */

    private void startCooldownTimer(Player player, int ticks) {
        UUID id = player.getUniqueId();

        BukkitTask prev = cdTasks.remove(id);
        if (prev != null) prev.cancel();

        final int seconds = (ticks + 19) / 20; // round up to whole seconds for the countdown

        BukkitTask task = new BukkitRunnable() {
            int timeLeft = seconds;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cdTasks.remove(id);
                    cancel();
                    return;
                }

                if (timeLeft <= 0) {
                    player.sendActionBar(Component.text("Ready", NamedTextColor.GREEN));
                    cdTasks.remove(id);
                    cancel();
                    return;
                }

                player.sendActionBar(Component.text(timeLeft + " sec", NamedTextColor.YELLOW));
                timeLeft--;
            }
        }.runTaskTimer(plugin, 0L, 20L);

        cdTasks.put(id, task);
    }

    /* ================== Cleanup ================== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        cleanup(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        cleanup(e.getEntity());
    }

    private void cleanup(Player p) {
        if (p == null) return;
        UUID id = p.getUniqueId();
        BukkitTask t = cdTasks.remove(id);
        if (t != null) t.cancel();
        cooldownMap.remove(id);
    }

    /**
     * Idempotent per-player reset so glove cooldown timers don't leak into the lobby.
     * Cancels the cooldown action-bar task and drops the cooldown state.
     */
    @Override
    public void resetPlayer(Player player) {
        cleanup(player);
    }
}
