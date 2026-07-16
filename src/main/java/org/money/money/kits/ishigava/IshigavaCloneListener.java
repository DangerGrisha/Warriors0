package org.money.money.kits.ishigava;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.money.money.meta.ClassRegistry;
import org.money.money.session.KitResettable;
import org.money.money.session.KitSession;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Ishigava — Mirror Clones.
 *
 * <p>RMB the item to deploy two Citizens clones that flank you (left + right) and mirror your
 * movement, facing and held item one-to-one — three identical figures moving as one. While active,
 * press <b>F</b> to cycle a target slot (1·2·3, left→right) shown on the action bar, then press
 * <b>RMB</b> (the Mirror Clones item) to confirm — you swap places with the clone in that slot
 * (teleport-swap). Confirming on your own slot just plays a feint (no real move). Whoever you control is the "centre" the formation
 * builds around, and slot 2 is always the formation middle — the Aura ult emanates from there.
 *
 * <p>Clones have ≈3 hearts; a fallen clone's slot can't be swapped into. Lasts 20s.
 *
 * <p>Requires the Citizens plugin (the NPC handling is isolated in {@link IshigavaCloneNpcs}); if
 * Citizens isn't installed, activation is denied with a message.
 */
public final class IshigavaCloneListener implements Listener, KitResettable {

    private static final String TAG_CLONES = "IshigavaClones";

    private final Plugin plugin;
    private final NamespacedKey KEY_CLONES;

    // ===== Config =====
    private final String cloneSkin;

    private static int durationTicks() {
        return Math.max(1, ClassRegistry.numInt("ishigava", "clones", "durationSeconds", 40)) * 20;
    }

    private static double cloneHealth() {
        return ClassRegistry.num("ishigava", "clones", "cloneHealth", 6.0);
    }

    private static double spacing() {
        return ClassRegistry.num("ishigava", "clones", "spacingBlocks", 2.6);
    }

    // Citizens controller (lazy; only created when Citizens is installed).
    private IshigavaCloneNpcs npcs;

    // Static accessor so the Aura ult can centre on the formation middle (slot 2).
    private static IshigavaCloneListener INSTANCE;

    private static final class State {
        int playerSlot = 2;            // 1..3, which slot the player occupies
        int targetSel = 2;             // 1..3, current F highlight
        final int[] cloneIndexBySlot = {-1, -1, -1, -1}; // [1..3] -> clone index, or -1 (player/empty/dead)
        final boolean[] aliveBySlot = {false, false, false, false};
        Location centerAnchor;         // slot-2 world position (aura centre)
        BukkitTask tickTask;
        ItemStack lastItem;
        int elapsed;
    }

    private final Map<UUID, State> active = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();

    public IshigavaCloneListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_CLONES = new NamespacedKey(plugin, "ishigava_clones");

        this.cloneSkin = plugin.getConfig().getString("ishigava.clones.skinName", "_Heugo");

        INSTANCE = this;
    }

    /** Slot-2 (formation middle) world location for an active player, else null. For the Aura ult. */
    public static Location auraCenter(Player p) {
        if (INSTANCE == null || p == null) return null;
        State s = INSTANCE.active.get(p.getUniqueId());
        return (s != null && s.centerAnchor != null) ? s.centerAnchor.clone() : null;
    }

    /* ================== Item ================== */

    public ItemStack makeClonesItem() {
        ItemStack it = new ItemStack(Material.LIGHT_GRAY_DYE);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Mirror Clones", NamedTextColor.WHITE));
        im.getPersistentDataContainer().set(KEY_CLONES, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(im);
        return it;
    }

    private boolean isClonesItem(ItemStack it) {
        if (it == null || it.getType() != Material.LIGHT_GRAY_DYE || !it.hasItemMeta()) return false;
        ItemMeta im = it.getItemMeta();
        if (im.getPersistentDataContainer().has(KEY_CLONES, PersistentDataType.BYTE)) return true;
        return Component.text("Mirror Clones", NamedTextColor.WHITE).equals(im.displayName());
    }

    private boolean citizensPresent() {
        return Bukkit.getPluginManager().getPlugin("Citizens") != null;
    }

    /* ================== Activation (RMB) ================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        if (!isClonesItem(p.getInventory().getItemInMainHand())) return;

        e.setCancelled(true);
        if (!KitSession.isInGame(p)) return;

        UUID id = p.getUniqueId();
        if (active.containsKey(id)) { // active -> RMB confirms the F-selected swap
            commitSwap(id);
            return;
        }

        if (!citizensPresent()) {
            p.sendActionBar(Component.text("Mirror Clones requires the Citizens plugin.", NamedTextColor.RED));
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.7f);
            return;
        }

        long now = System.currentTimeMillis();
        if (cooldown.containsKey(id)) {
            long cooldownMs = ClassRegistry.millis("ishigava", "clones", 120_000L);
            long passed = now - cooldown.get(id);
            if (passed < cooldownMs) {
                long secLeft = (cooldownMs - passed + 999) / 1000;
                p.sendActionBar(Component.text(secLeft + " sec", NamedTextColor.RED));
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.8f);
                return;
            }
        }
        cooldown.put(id, now);
        activate(p);
    }

    private void activate(Player p) {
        final UUID id = p.getUniqueId();
        try {
            if (npcs == null) npcs = new IshigavaCloneNpcs(plugin);
        } catch (Throwable t) {
            p.sendActionBar(Component.text("Mirror Clones unavailable.", NamedTextColor.RED));
            plugin.getLogger().warning("[Ishigava] Citizens clones unavailable: " + t.getMessage());
            cooldown.remove(id);
            return;
        }

        State s = new State();
        s.playerSlot = 2;
        s.targetSel = 2;

        Vector right = rightVector(p);
        Location anchor = p.getLocation().clone().subtract(right.clone().multiply(slotOffset(s.playerSlot)));
        s.centerAnchor = anchor;

        // Two clones flank the centre: slot 1 (left) -> index 0, slot 3 (right) -> index 1.
        Location loc1 = anchor.clone().add(right.clone().multiply(slotOffset(1)));
        Location loc3 = anchor.clone().add(right.clone().multiply(slotOffset(3)));
        s.cloneIndexBySlot[1] = 0;
        s.cloneIndexBySlot[3] = 1;
        s.aliveBySlot[1] = true;
        s.aliveBySlot[3] = true;

        ItemStack held = p.getInventory().getItemInMainHand();
        s.lastItem = held == null ? null : held.clone();
        npcs.spawn(p, 2, new Location[]{loc1, loc3}, cloneHealth(), held, cloneSkin);

        p.addScoreboardTag(TAG_CLONES);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 1.0f);
        p.sendActionBar(Component.text("Mirror Clones deployed — F to swap (1·2·3)", NamedTextColor.AQUA));

        active.put(id, s);
        s.tickTask = new BukkitRunnable() {
            @Override
            public void run() {
                tick(id);
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /* ================== Formation tick ================== */

    private void tick(UUID id) {
        State s = active.get(id);
        if (s == null) return;
        Player p = Bukkit.getPlayer(id);
        if (p == null || !p.isOnline() || p.isDead()
                || p.getGameMode() == GameMode.SPECTATOR || !KitSession.isInGame(p)) {
            end(id, false);
            return;
        }

        if (s.elapsed >= durationTicks()) {
            end(id, true);
            return;
        }
        s.elapsed++;

        Vector right = rightVector(p);
        Location anchor = p.getLocation().clone().subtract(right.clone().multiply(slotOffset(s.playerSlot)));
        s.centerAnchor = anchor;

        float yaw = p.getLocation().getYaw();
        float pitch = p.getLocation().getPitch();

        // Only push an equipment update when the held item actually changed (avoid flicker/cost).
        ItemStack held = p.getInventory().getItemInMainHand();
        ItemStack itemUpdate = null;
        if (!sameItem(s.lastItem, held)) {
            s.lastItem = held == null ? null : held.clone();
            itemUpdate = held;
        }

        for (int slot = 1; slot <= 3; slot++) {
            if (slot == s.playerSlot) continue;
            int idx = s.cloneIndexBySlot[slot];
            if (idx < 0) continue; // empty / fallen

            if (!npcs.isAlive(p, idx)) {
                onCloneFell(p, s, slot, idx);
                continue;
            }

            Location pos = anchor.clone().add(right.clone().multiply(slotOffset(slot)));
            pos.setY(p.getLocation().getY());
            npcs.setTransform(p, idx, pos, yaw, pitch, itemUpdate);
        }
    }

    private void onCloneFell(Player p, State s, int slot, int idx) {
        s.aliveBySlot[slot] = false;
        s.cloneIndexBySlot[slot] = -1;
        Location at = npcs.getLocation(p, idx);
        npcs.despawn(p, idx);
        if (at != null && at.getWorld() != null) {
            at.getWorld().playSound(at, Sound.ENTITY_ILLUSIONER_DEATH, 0.8f, 1.2f);
            at.getWorld().spawnParticle(Particle.SMOKE, at.clone().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.03);
        }
    }

    /* ================== F: select target slot, debounced swap ================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        State s = active.get(p.getUniqueId());
        if (s == null) return;

        e.setCancelled(true);

        s.targetSel = s.targetSel % 3 + 1; // 1 -> 2 -> 3 -> 1
        showSelection(p, s);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
        // Selection only — press RMB (Mirror Clones item) to confirm the swap.
    }

    private void showSelection(Player p, State s) {
        StringBuilder sb = new StringBuilder("Swap: ");
        for (int slot = 1; slot <= 3; slot++) {
            String mark = (slot == s.targetSel) ? "[" + slot + "]" : " " + slot + " ";
            if (slot == s.playerSlot) mark = "*" + mark.trim() + "*"; // your current slot
            sb.append(mark).append(' ');
        }
        p.sendActionBar(Component.text(sb.toString().trim(), NamedTextColor.AQUA));
    }

    private void commitSwap(UUID id) {
        State s = active.get(id);
        if (s == null) return;
        Player p = Bukkit.getPlayer(id);
        if (p == null || !p.isOnline() || p.isDead() || !KitSession.isInGame(p)) return;

        int target = s.targetSel;

        if (target == s.playerSlot) {
            // Feint: looks like a swap, but you stay put.
            swapFx(p.getLocation());
            p.sendActionBar(Component.text("...", NamedTextColor.GRAY));
            return;
        }

        int idx = s.cloneIndexBySlot[target];
        if (idx < 0 || !s.aliveBySlot[target]) {
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.6f);
            p.sendActionBar(Component.text("That clone has fallen.", NamedTextColor.RED));
            return;
        }

        Location cloneLoc = npcs.getLocation(p, idx);
        if (cloneLoc == null || !npcs.isAlive(p, idx)) {
            // Clone died inside the debounce window — treat as fallen.
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.6f);
            p.sendActionBar(Component.text("That clone has fallen.", NamedTextColor.RED));
            return;
        }

        Location playerOld = p.getLocation().clone();
        swapFx(playerOld);
        swapFx(cloneLoc);

        // The clone takes the player's old spot; the player takes the clone's spot.
        npcs.teleport(p, idx, playerOld);
        p.teleport(cloneLoc);

        s.cloneIndexBySlot[s.playerSlot] = idx;
        s.aliveBySlot[s.playerSlot] = true;
        s.cloneIndexBySlot[target] = -1;
        s.playerSlot = target;

        p.getWorld().playSound(cloneLoc, Sound.ENTITY_ENDERMAN_TELEPORT, 0.9f, 1.4f);
    }

    private void swapFx(Location loc) {
        if (loc.getWorld() == null) return;
        loc.getWorld().spawnParticle(Particle.DUST, loc.clone().add(0, 1, 0), 24, 0.3, 0.8, 0.3, 0.0,
                new Particle.DustOptions(Color.fromRGB(180, 180, 200), 1.2f));
        loc.getWorld().spawnParticle(Particle.CLOUD, loc.clone().add(0, 1, 0), 10, 0.2, 0.5, 0.2, 0.02);
    }

    /* ================== Geometry ================== */

    /** Horizontal "right" unit vector relative to the player's facing. */
    private Vector rightVector(Player p) {
        Vector dir = p.getLocation().getDirection();
        dir.setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 0, 1);
        dir.normalize();
        Vector right = new Vector(-dir.getZ(), 0, dir.getX());
        if (right.lengthSquared() < 1e-6) return new Vector(1, 0, 0);
        return right.normalize();
    }

    /** Offset of a slot from the formation centre, along the right vector. slot1=left, slot3=right. */
    private double slotOffset(int slot) {
        return (slot - 2) * spacing();
    }

    private boolean sameItem(ItemStack a, ItemStack b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.isSimilar(b);
    }

    /* ================== End / cleanup ================== */

    private void end(UUID id, boolean effects) {
        State s = active.remove(id);
        if (s == null) {
            // Still make sure no clones/tag linger for this player.
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.removeScoreboardTag(TAG_CLONES);
                if (npcs != null) try { npcs.despawnAll(p); } catch (Throwable ignored) {}
            }
            return;
        }
        if (s.tickTask != null) s.tickTask.cancel();

        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            if (npcs != null) try { npcs.despawnAll(p); } catch (Throwable ignored) {}
            p.removeScoreboardTag(TAG_CLONES);
            if (effects && p.isOnline()) {
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.8f, 0.8f);
                p.sendActionBar(Component.text("Mirror Clones ended.", NamedTextColor.GRAY));
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        end(e.getPlayer().getUniqueId(), false);
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        end(e.getEntity().getUniqueId(), false);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onGameModeChange(PlayerGameModeChangeEvent e) {
        if (e.getNewGameMode() == GameMode.SPECTATOR) end(e.getPlayer().getUniqueId(), false);
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent e) {
        if (KitSession.isLobbyWorld(e.getPlayer().getWorld())) end(e.getPlayer().getUniqueId(), false);
    }

    @Override
    public void resetPlayer(Player player) {
        if (player == null) return;
        end(player.getUniqueId(), false);
    }

    /** End all active formations + destroy all clone NPCs (Main.onDisable). */
    public void shutdown() {
        for (UUID id : new java.util.HashSet<>(active.keySet())) {
            end(id, false);
        }
        if (npcs != null) try { npcs.shutdown(); } catch (Throwable ignored) {}
        if (INSTANCE == this) INSTANCE = null; // reload-safe
    }
}
