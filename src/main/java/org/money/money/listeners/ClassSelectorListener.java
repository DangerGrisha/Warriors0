package org.money.money.listeners;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.money.money.Main;
import org.money.money.gui.ClassSelectionGUI;
import org.money.money.match.ClassSelectionService;

public class ClassSelectorListener implements Listener {

    private static final NamespacedKey KEY_CLASS = new NamespacedKey(Main.getInstance(), "class_id");

    @EventHandler
    public void onClassClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getView() == null) return;
        if (!ClassSelectionGUI.GUI_TITLE.equals(e.getView().getTitle())) return;

        e.setCancelled(true);

        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;

        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        ItemMeta meta = clicked.getItemMeta();
        String classId = meta.getPersistentDataContainer().get(KEY_CLASS, PersistentDataType.STRING);
        if (classId == null || classId.isBlank()) return;

        ClassSelectionService.choose(p, classId);
        ClassSelectionGUI.highlightSelectionInOpenMenu(p, classId);
    }

    // prevent closing during active selection
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;

        String world = p.getWorld().getName();
        if (ClassSelectionService.isActive(world) && ClassSelectionGUI.GUI_TITLE.equals(e.getView().getTitle())) {
            p.getServer().getScheduler().runTask(Main.getInstance(), () -> ClassSelectionGUI.open(p));
        }
    }
}
