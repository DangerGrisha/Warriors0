package org.money.money.kits.airwalker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
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
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class WindListener implements Listener {

    // ===== Settings =====
    private static final double ANCHOR_OFFSET_FROM_BLOCK = 0.15;

    private static final double BLOW_RADIUS = 5.0;

    // Wind strength (x2)
    private static final double BLOW_STRENGTH = 2.5;
    private static final double EXTRA_UP = 0.15;

    private static final double ANCHOR_MAX_DISTANCE_NORMAL = 60.0;
    private static final double ANCHOR_MAX_DISTANCE_ULT = 120.0;

    // Levitations: apply for 1s, remove after 0.5s
    private static final int LEVITATION_TICKS = 20;
    private static final int REMOVE_LEVITATION_AFTER = 10;

    // Regen: +1 charge after 20s (only when a charge was consumed)
    private static final long REGEN_TICKS = 20L * 20L;

    // Internal anti-spam cooldown between casts
    private static final long INTERNAL_CD_TICKS_NORMAL = 10L; //
    private static final long INTERNAL_CD_TICKS_ULT    = 10L; // 0.5s

    // Special tag
    private static final String TAG_WIND_ULT = "WindUlt";

    private final Plugin plugin;

    // Item + anchor markers
    private final NamespacedKey KEY_WIND_ITEM;
    private final NamespacedKey KEY_WIND_ANCHOR;

    // One anchor per player
    private final Map<UUID, UUID> anchorByPlayer = new HashMap<>();

    // Last cast tick (internal cooldown)
    private final Map<UUID, Long> lastCastTick = new HashMap<>();

    public WindListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_WIND_ITEM = new NamespacedKey(plugin, "airwalker_wind_item");
        this.KEY_WIND_ANCHOR = new NamespacedKey(plugin, "airwalker_wind_anchor");
    }

    /* ================== Item ================== */

    public ItemStack makeWindDye() {
        ItemStack it = new ItemStack(Material.RED_DYE);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Wind", NamedTextColor.WHITE));
        im.getPersistentDataContainer().set(KEY_WIND_ITEM, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(im);
        return it;
    }

    private boolean isWindItem(ItemStack it) {
        if (it == null || it.getType() != Material.RED_DYE || !it.hasItemMeta()) return false;
        ItemMeta im = it.getItemMeta();
        if (im.getPersistentDataContainer().has(KEY_WIND_ITEM, PersistentDataType.BYTE)) return true;
        return Component.text("Wind").equals(im.displayName());
    }

    // Consume exactly 1 Wind item from main-hand or inventory
    private boolean consumeOneWind(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (isWindItem(hand)) {
            int amt = hand.getAmount();
            if (amt <= 1) p.getInventory().setItemInMainHand(null);
            else hand.setAmount(amt - 1);
            return true;
        }
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (isWindItem(it)) {
                if (it.getAmount() <= 1) p.getInventory().setItem(i, null);
                else it.setAmount(it.getAmount() - 1);
                return true;
            }
        }
        return false;
    }

    // Add +N wind charges (stack into existing if possible, otherwise add new stack)
    private void addWindCharges(Player p, int amount) {
        if (amount <= 0) return;

        // First try stacking into existing stacks
        for (int slot = 0; slot < p.getInventory().getSize() && amount > 0; slot++) {
            ItemStack it = p.getInventory().getItem(slot);
            if (!isWindItem(it)) continue;

            int max = it.getMaxStackSize();
            int cur = it.getAmount();
            if (cur >= max) continue;

            int add = Math.min(amount, max - cur);
            it.setAmount(cur + add);
            amount -= add;
        }

        // Then add new stacks if still remaining
        while (amount > 0) {
            int give = Math.min(amount, 64);
            ItemStack stack = makeWindDye();
            stack.setAmount(give);

            Map<Integer, ItemStack> left = p.getInventory().addItem(stack);
            if (!left.isEmpty()) {
                // Inventory full -> drop the leftover
                for (ItemStack rem : left.values()) {
                    p.getWorld().dropItemNaturally(p.getLocation(), rem);
                }
            }

            amount -= give;
        }
    }

    private boolean hasUltTag(Player p) {
        return p.getScoreboardTags().contains(TAG_WIND_ULT);
    }

    /* ================== Interactions ================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;

        Player p = e.getPlayer();
        ItemStack inHand = p.getInventory().getItemInMainHand();
        if (!isWindItem(inHand)) return;

        Action a = e.getAction();

        // Right click -> place/move anchor
        if (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) {
            e.setCancelled(true);
            placeOrMoveAnchor(p);
            return;
        }

        // Left click -> blow
        if (a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) {
            e.setCancelled(true);
            tryCastWind(p);
        }
    }

    /* ================== Casting rules ================== */

    private void tryCastWind(Player p) {
        long nowTick = Bukkit.getCurrentTick();

        boolean ult = hasUltTag(p);
        long internalCd = ult ? INTERNAL_CD_TICKS_ULT : INTERNAL_CD_TICKS_NORMAL;

        long last = lastCastTick.getOrDefault(p.getUniqueId(), -999999L);
        if (nowTick - last < internalCd) {
            // quiet fail (or show small msg)
            // p.sendMessage(Component.text("...", NamedTextColor.GRAY));
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.8f);
            return;
        }
        lastCastTick.put(p.getUniqueId(), nowTick);

        // Origin:
        // - if sneaking: origin = player
        // - else origin = anchor (required)
        final boolean sneaking = p.isSneaking();

        Location origin;
        if (sneaking) {
            origin = p.getLocation();
        } else {
            ArmorStand anchor = getAnchor(p);
            if (anchor == null) {
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.7f);
                p.sendMessage(Component.text("Сначала поставь якорь ПКМ.", NamedTextColor.GRAY));
                return;
            }
            origin = anchor.getLocation();
        }

        // Consume / regen logic:
        // - Normal: consume 1, schedule +1 after 20s
        // - Ult: do NOT consume, do NOT regen
        if (!ult) {
            if (!consumeOneWind(p)) {
                // No charges found (shouldn't happen if they hold the item, but just in case)
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.7f);
                return;
            }

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player online = Bukkit.getPlayer(p.getUniqueId());
                if (online != null && online.isOnline()) {
                    addWindCharges(online, 1); // +1 after 20s
                    online.playSound(online.getLocation(), Sound.UI_TOAST_IN, 0.4f, 1.4f);
                }
            }, REGEN_TICKS);
        }

        // Execute wind
        blowFromOrigin(p, origin);
    }

    /* ================== Anchor ================== */

    private void placeOrMoveAnchor(Player p) {
        removeAnchor(p);
        boolean ult = hasUltTag(p);
        double maxDist = ult ? ANCHOR_MAX_DISTANCE_ULT : ANCHOR_MAX_DISTANCE_NORMAL;

        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World w = p.getWorld();

        RayTraceResult hit = w.rayTraceBlocks(
                eye, dir, maxDist,
                FluidCollisionMode.NEVER, true
        );

        Location spawnLoc;
        if (hit != null && hit.getHitPosition() != null) {
            Vector hitPos = hit.getHitPosition();
            Vector back = dir.clone().multiply(ANCHOR_OFFSET_FROM_BLOCK);
            spawnLoc = hitPos.toLocation(w).subtract(back);
        } else {
            spawnLoc = eye.clone().add(dir.multiply(maxDist));
        }

        ArmorStand as = w.spawn(spawnLoc, ArmorStand.class, stand -> {
            stand.setInvisible(true);
            stand.setSilent(true);
            stand.setGravity(false);
            stand.setMarker(true);
            stand.setSmall(true);
            stand.setInvulnerable(true);
            stand.setPersistent(true);

            stand.customName(Component.text("WindAnchor"));
            stand.setCustomNameVisible(false);

            // PDC marker
            stand.getPersistentDataContainer().set(KEY_WIND_ANCHOR, PersistentDataType.BYTE, (byte) 1);
            // Vanilla scoreboard tag (backup)
            stand.addScoreboardTag("wind_anchor");
        });

        anchorByPlayer.put(p.getUniqueId(), as.getUniqueId());

        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.6f);
        w.spawnParticle(Particle.CLOUD, as.getLocation().add(0, 0.2, 0), 10, 0.05, 0.05, 0.05, 0.01);
    }

    private void removeAnchor(Player p) {
        UUID asId = anchorByPlayer.remove(p.getUniqueId());
        if (asId == null) return;

        Entity ent = p.getWorld().getEntity(asId);
        if (ent != null && ent.isValid()) ent.remove();
    }

    private ArmorStand getAnchor(Player p) {
        UUID asId = anchorByPlayer.get(p.getUniqueId());
        if (asId == null) return null;

        Entity ent = p.getWorld().getEntity(asId);
        if (!(ent instanceof ArmorStand as) || !as.isValid()) {
            anchorByPlayer.remove(p.getUniqueId());
            return null;
        }

        if (!as.getPersistentDataContainer().has(KEY_WIND_ANCHOR, PersistentDataType.BYTE)
                && !as.getScoreboardTags().contains("wind_anchor")) {
            return null;
        }

        return as;
    }

    /* ================== Wind logic ================== */

    private void blowFromOrigin(Player p, Location origin) {
        World w = p.getWorld();
        Vector dir = p.getLocation().getDirection().normalize();

        // Sound/visual at origin
        w.playSound(origin, Sound.ENTITY_BREEZE_IDLE_GROUND, 0.9f, 1.3f);

        // "Wall" particles across the whole lane
        spawnWindWallParticles(w, origin, dir);

        // Apply to nearby entities (fast)
        Collection<Entity> nearby = w.getNearbyEntities(origin, BLOW_RADIUS, BLOW_RADIUS, BLOW_RADIUS);

        for (Entity ent : nearby) {
            if (!(ent instanceof LivingEntity le)) continue;
            if (!ent.isValid()) continue;

            Vector v = dir.clone().multiply(BLOW_STRENGTH);
            v.setY(v.getY() + EXTRA_UP);

            le.setVelocity(v);

            PotionEffect lev = new PotionEffect(PotionEffectType.LEVITATION, LEVITATION_TICKS, 0, false, false, false);
            le.addPotionEffect(lev);

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (le.isValid()) le.removePotionEffect(PotionEffectType.LEVITATION);
            }, REMOVE_LEVITATION_AFTER);
        }
    }

    // Wide wind wall: particles distributed across the whole blowing lane (N/S/E/W snapped)
    private void spawnWindWallParticles(World w, Location from, Vector dir) {
        Vector f = new Vector(dir.getX(), 0, dir.getZ());
        if (f.lengthSquared() < 1e-6) return;

        double ax = Math.abs(f.getX());
        double az = Math.abs(f.getZ());

        // Snap to cardinal direction for clean sides (server north/south/east/west)
        Vector forward;
        if (ax >= az) {
            forward = new Vector(Math.signum(f.getX()), 0, 0); // East/West
        } else {
            forward = new Vector(0, 0, Math.signum(f.getZ())); // South/North (Minecraft: +Z south, -Z north)
        }

        // Right vector for width
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());

        // Tuning (moderate)
        final int samples = 26;         // total points (not too many)
        final double halfWidth = 3.2;   // ~6.4 blocks wide
        final double height = 1.6;      // vertical spread
        final double length = 9.0;      // lane length
        final double backOffset = 0.9;  // emitter plane behind origin

        // Plane center behind origin
        Location base = from.clone().add(0, 0.2, 0).subtract(forward.clone().multiply(backOffset));

        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        for (int i = 0; i < samples; i++) {
            double lateral = rnd.nextDouble(-halfWidth, halfWidth);
            double y = rnd.nextDouble(0.05, height);
            double along = rnd.nextDouble(0.0, length);

            Location pt = base.clone()
                    .add(right.clone().multiply(lateral))
                    .add(0, y, 0)
                    .add(forward.clone().multiply(along));

            try {
                w.spawnParticle(Particle.GUST, pt, 1, 0.12, 0.10, 0.12, 0.01);
            } catch (Throwable ignored) {
                w.spawnParticle(Particle.CLOUD, pt, 1, 0.12, 0.10, 0.12, 0.01);
            }
        }
    }

    /* ================== Cleanup ================== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        removeAnchor(e.getPlayer());
        lastCastTick.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        removeAnchor(e.getEntity());
        lastCastTick.remove(e.getEntity().getUniqueId());
    }
}
