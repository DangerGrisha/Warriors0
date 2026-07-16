package org.money.money.kits.burgerMaster;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.money.money.meta.ClassRegistry;

import java.util.Objects;

/**
 * BurgerMaster:
 * - предмет: GRAY_GLAZED_TERRACOTTA с именем "grill"
 * - при установке: блок сразу убирается и спавнится гриль через GrillManager
 * - ПКМ по костру (грилю) владельцем — открывает меню (сундук)
 */
public final class GrillPlaceListener implements Listener {

    private final Plugin plugin;
    private final GrillManager grillManager;
    private final NamespacedKey KEY_GRILL_ITEM;

    public GrillPlaceListener(Plugin plugin, GrillManager grillManager) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.grillManager = Objects.requireNonNull(grillManager, "grillManager");
        this.KEY_GRILL_ITEM = new NamespacedKey(plugin, "burger_grill_item");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** Выдать предмет установки гриля. */
    public ItemStack makeGrillBlock() {
        ItemStack it = new ItemStack(Material.GRAY_GLAZED_TERRACOTTA);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("grill"));
        im.getPersistentDataContainer().set(KEY_GRILL_ITEM, PersistentDataType.BYTE, (byte)1);
        it.setItemMeta(im);
        return it;
    }

    private boolean isOurGrillItem(ItemStack it) {
        if (it == null || it.getType() != Material.GRAY_GLAZED_TERRACOTTA || !it.hasItemMeta()) return false;
        var im = it.getItemMeta();
        if (im.getPersistentDataContainer().has(KEY_GRILL_ITEM, PersistentDataType.BYTE)) return true;
        return Component.text("grill").equals(im.displayName());
    }

    /** Игрок поставил наш “блок гриля”: убираем его и строим настоящий гриль. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent e) {
        if (!isOurGrillItem(e.getItemInHand())) return;

        final Player owner = e.getPlayer();
        final Block placed = e.getBlockPlaced();
        final Location baseLoc = placed.getLocation();

        // на следующий тик: убрать терракоту и создать гриль
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (placed.getType() == Material.GRAY_GLAZED_TERRACOTTA) {
                placed.setType(Material.AIR, false);
            }
            grillManager.spawnGrill(owner, baseLoc, true); // << КЛЮЧЕВОЕ

            World w = baseLoc.getWorld();
            w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, baseLoc.toCenterLocation().add(0, 0.7, 0), 6, 0.2, 0.2, 0.2, 0.01);
            w.playSound(baseLoc.toCenterLocation(), Sound.BLOCK_CAMPFIRE_CRACKLE, 0.9f, 1.0f);
        });

        // кулдаун: вернуть предмет гриля владельцу через 1 минуту (если у него его ещё нет)
        final long returnDelayMs = ClassRegistry.millis("burgermaster", "grill", 60_000L);
        final java.util.UUID id = owner.getUniqueId();
        Bukkit.getAsyncScheduler().runDelayed(
                plugin,
                t -> Bukkit.getScheduler().runTask(plugin, () -> giveBackGrillIfMissing(id)),
                returnDelayMs,
                java.util.concurrent.TimeUnit.MILLISECONDS
        );
    }

    /* ===================== ВОЗВРАТ ПРЕДМЕТА (кулдаун) ===================== */

    private void giveBackGrillIfMissing(java.util.UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p == null || !p.isOnline()) return;
        if (playerHasGrillItem(p)) return; // уже есть — второй не даём

        ItemStack item = makeGrillBlock();
        giveToHandOrInv(p, item);
        p.playSound(p.getLocation(), Sound.UI_TOAST_IN, 0.7f, 1.2f);
    }

    private boolean playerHasGrillItem(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (isOurGrillItem(it)) return true;
        }
        return false;
    }

    private void giveToHandOrInv(Player p, ItemStack it) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            p.getInventory().setItemInMainHand(it);
        } else {
            var left = p.getInventory().addItem(it);
            if (!left.isEmpty()) p.getWorld().dropItemNaturally(p.getLocation(), left.values().iterator().next());
        }
    }

    /** ПКМ по костру: если это наш гриль — открыть меню (только владельцу). */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onRightClickCampfire(PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        if (e.getClickedBlock() == null) return;
        var type = e.getClickedBlock().getType();
        if (type != Material.CAMPFIRE && type != Material.SOUL_CAMPFIRE) return;

        // попробуем открыть меню — если это гриль, отменяем событие
        boolean ours = grillManager.tryOpenMenu(e.getPlayer(), e.getClickedBlock());
        if (ours) e.setCancelled(true);
    }
}
