// FILE: src/main/java/org/money/money/listeners/GuiClickListener.java
package org.money.money.listeners;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.money.money.Main;
import org.money.money.gui.ServerSelectionGUI;
import org.money.money.session.PlayerState;
import org.money.money.session.SessionService;
import org.money.money.world.WorldState;
import org.money.money.world.WorldStateResolver;

public class GuiClickListener implements Listener {

    private NamespacedKey keyWorld() { return new NamespacedKey(Main.getInstance(), "map_world"); }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getView() == null) return;
        if (!ServerSelectionGUI.TITLE.equals(e.getView().getTitle())) return;

        e.setCancelled(true);

        // only top gui
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;

        ItemStack it = e.getCurrentItem();
        if (it == null || !it.hasItemMeta()) return;

        ItemMeta meta = it.getItemMeta();
        String worldName = meta.getPersistentDataContainer().get(keyWorld(), PersistentDataType.STRING);
        if (worldName == null || worldName.isEmpty()) return;

        WorldState st = WorldStateResolver.get(worldName);
        if (st == WorldState.RUNNING || st == WorldState.RESTARTING) {
            p.sendMessage("§cYou cannot join this world right now.");
            return;
        }

        World w = Bukkit.getWorld(worldName);
        if (w == null) {
            p.sendMessage("§cWorld is not loaded: " + worldName);
            return;
        }

        // ✅ move to WAITING state and teleport
        SessionService.get().setState(p, PlayerState.WAITING, worldName);
        p.sendMessage("§aJoined: §f" + worldName);
        p.closeInventory();
    }
}
