package org.money.money.kits.naruto;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.TimeUnit;

public final class DisappearanceTechniqueListener implements Listener {

    private static final long TELEPORT_DELAY_TICKS = 20L * 2; // 2 seconds delay
    private static final double LOOK_TELEPORT_RANGE = 25.0;
    private static final long RETURN_AFTER_MS = 60_000L; // вернуть предмет через 60 сек

    private final Plugin plugin;

    private final NamespacedKey KEY_ITEM;

    // когда игроку вернётся предмет
    private final Map<UUID, Long> cooldownUntilMs = new HashMap<>();
    private final Map<UUID, Integer> pendingTpTaskId = new HashMap<>();

    public DisappearanceTechniqueListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_ITEM = new NamespacedKey(plugin, "disappearance_technique");
    }

    /* ---------- item ---------- */

    /** Red dye "Disappearance Technique". */
    public ItemStack makeDisappearanceDye() {
        ItemStack it = new ItemStack(Material.RED_DYE);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Disappearance Technique"));
        im.getPersistentDataContainer().set(KEY_ITEM, PersistentDataType.BYTE, (byte)1);
        it.setItemMeta(im);
        return it;
    }
    private boolean isDisappearance(ItemStack it) {
        if (it == null || it.getType() != Material.RED_DYE || !it.hasItemMeta()) return false;
        var im = it.getItemMeta();
        if (im.getPersistentDataContainer().has(KEY_ITEM, PersistentDataType.BYTE)) return true;
        return Component.text("Disappearance Technique").equals(im.displayName());
    }

    /* ---------- use ---------- */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!isDisappearance(hand)) return;

        // 1) убрать ОДИН предмет сразу
        if (hand.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
        else hand.setAmount(hand.getAmount() - 1);

        // 2) fx + звук на исходной точке
        Location from = p.getLocation();
        try {
            from.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, from.add(0, 0.2, 0), 20, 0.6, 0.3, 0.6, 0.01);
            from.getWorld().playSound(from, Sound.ITEM_CHORUS_FRUIT_TELEPORT, 0.9f, 1.35f);
        } catch (Throwable ignored) {}

        // 3) remember exact raycast point NOW and teleport there after 2s (including air/sky)
        Location lookTarget = resolveLookTarget(p);
        scheduleDelayedTeleport(p.getUniqueId(), lookTarget);

        // 4) планируем возврат предмета через 60 сек (real-time)
        long backAt = System.currentTimeMillis() + RETURN_AFTER_MS;
        cooldownUntilMs.put(p.getUniqueId(), backAt);

        Bukkit.getAsyncScheduler().runDelayed(plugin,
                task -> Bukkit.getScheduler().runTask(plugin, () -> giveBackIfMissing(p.getUniqueId())),
                RETURN_AFTER_MS, TimeUnit.MILLISECONDS);
    }

    /* ---------- delayed look TP ---------- */

    private void scheduleDelayedTeleport(UUID playerId, Location dest) {
        Integer prev = pendingTpTaskId.remove(playerId);
        if (prev != null) Bukkit.getScheduler().cancelTask(prev);

        int tid = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingTpTaskId.remove(playerId);
            Player p = Bukkit.getPlayer(playerId);
            if (p == null || !p.isOnline()) return;

            p.teleportAsync(dest);
            try {
                dest.getWorld().spawnParticle(Particle.CLOUD, dest.clone().add(0, 0.1, 0), 16, 0.5, 0.3, 0.5, 0.02);
                dest.getWorld().playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.2f);
            } catch (Throwable ignored) {}
        }, TELEPORT_DELAY_TICKS).getTaskId();

        pendingTpTaskId.put(playerId, tid);
    }

    private Location resolveLookTarget(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World w = p.getWorld();

        RayTraceResult hit = w.rayTraceBlocks(eye, dir, LOOK_TELEPORT_RANGE, FluidCollisionMode.NEVER, true);
        if (hit != null && hit.getHitPosition() != null) {
            Vector v = hit.getHitPosition();
            return new Location(w, v.getX(), v.getY(), v.getZ());
        }
        return eye.clone().add(dir.multiply(LOOK_TELEPORT_RANGE));
    }

    /* ---------- return item / join ---------- */

    private void giveBackIfMissing(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p == null || !p.isOnline()) return;
        if (playerHasItem(p)) return; // второй не выдаём

        ItemStack it = makeDisappearanceDye();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) p.getInventory().setItemInMainHand(it);
        else {
            Map<Integer, ItemStack> left = p.getInventory().addItem(it);
            if (!left.isEmpty()) p.getWorld().dropItemNaturally(p.getLocation(), left.values().iterator().next());
        }
        p.playSound(p.getLocation(), Sound.UI_TOAST_IN, 0.7f, 1.2f);
        p.sendMessage(Component.text("Disappearance Technique восстановлена.", NamedTextColor.GREEN));
    }

    private boolean playerHasItem(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (isDisappearance(it)) return true;
        }
        return false;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Integer tid = pendingTpTaskId.remove(e.getPlayer().getUniqueId());
        if (tid != null) Bukkit.getScheduler().cancelTask(tid);
    }

    private void giveToHandOrInv(Player p, ItemStack it) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) p.getInventory().setItemInMainHand(it);
        else {
            Map<Integer, ItemStack> left = p.getInventory().addItem(it);
            if (!left.isEmpty()) p.getWorld().dropItemNaturally(p.getLocation(), left.values().iterator().next());
        }
    }
}
