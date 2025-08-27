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

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public final class DisappearanceTechniqueListener implements Listener {

    private static final int  RADIUS = 15;           // радиус поиска точки
    private static final int  TRIES  = 32;           // кол-во попыток подобрать безопасную точку
    private static final long RETURN_AFTER_MS = 60_000L; // вернуть предмет через 60 сек

    private final Plugin plugin;

    private final NamespacedKey KEY_ITEM;

    // когда игроку вернётся предмет
    private final Map<UUID, Long> cooldownUntilMs = new HashMap<>();

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

        // 3) телепорт
        Location dest = pickSafeRandomLocation(p, RADIUS, TRIES);
        if (dest == null) dest = fallbackSameSpot(p); // на всякий случай
        p.teleportAsync(dest);
        try {
            dest.getWorld().spawnParticle(Particle.CLOUD, dest.clone().add(0, 0.1, 0), 16, 0.5, 0.3, 0.5, 0.02);
            dest.getWorld().playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.2f);
        } catch (Throwable ignored) {}

        // 4) планируем возврат предмета через 60 сек (real-time)
        long backAt = System.currentTimeMillis() + RETURN_AFTER_MS;
        cooldownUntilMs.put(p.getUniqueId(), backAt);

        Bukkit.getAsyncScheduler().runDelayed(plugin,
                task -> Bukkit.getScheduler().runTask(plugin, () -> giveBackIfMissing(p.getUniqueId())),
                RETURN_AFTER_MS, TimeUnit.MILLISECONDS);
    }

    /* ---------- safe random TP ---------- */

    private Location pickSafeRandomLocation(Player p, int radius, int tries) {
        World w = p.getWorld();
        Random rnd = ThreadLocalRandom.current();

        int minY = w.getMinHeight() + 1;
        int maxY = w.getMaxHeight() - 2;

        Location base = p.getLocation();

        for (int i = 0; i < tries; i++) {
            int dx = rnd.nextInt(-radius, radius + 1);
            int dy = rnd.nextInt(-radius, radius + 1);
            int dz = rnd.nextInt(-radius, radius + 1);

            int x = base.getBlockX() + dx;
            int y = clamp(base.getBlockY() + dy, minY, maxY);
            int z = base.getBlockZ() + dz;

            Location cand = new Location(w, x + 0.5, y, z + 0.5);

            Location safe = searchNearbyStandable(cand, 24);
            if (safe != null) return safe;
        }
        return null;
    }

    /** Ищем рядом по вертикали место, где можно стоять: блок под ногами – solid, ноги/голова – воздух. */
    private Location searchNearbyStandable(Location around, int maxVertical) {
        World w = around.getWorld();
        int x = around.getBlockX();
        int y = around.getBlockY();
        int z = around.getBlockZ();

        // вниз
        for (int dy = 0; dy <= maxVertical; dy++) {
            int yy = y - dy;
            if (isStandable(w, x, yy, z)) return new Location(w, x + 0.5, yy, z + 0.5);
        }
        // вверх
        for (int dy = 1; dy <= maxVertical; dy++) {
            int yy = y + dy;
            if (isStandable(w, x, yy, z)) return new Location(w, x + 0.5, yy, z + 0.5);
        }
        return null;
    }

    private boolean isStandable(World w, int x, int y, int z) {
        Block feet = w.getBlockAt(x, y, z);
        Block head = w.getBlockAt(x, y + 1, z);
        Block below = w.getBlockAt(x, y - 1, z);
        // «ноги» и «голова» проходимы, блок под ногами – твёрдый и не жидкость
        return feet.isPassable() && head.isPassable() && below.getType().isSolid() && !below.isLiquid();
    }

    private int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private Location fallbackSameSpot(Player p) {
        // если ничего не нашли, вернуть на то же место (но всё равно эффекты уже проиграны)
        return p.getLocation();
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
        // ничего особого не требуется; возврат по плану real-time
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
