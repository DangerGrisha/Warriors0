package org.money.money.listeners;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.money.money.gui.ServerSelectionGUI;
import org.money.money.Main;
import org.money.money.session.PlayerState;
import org.money.money.session.SessionService;

public class SpectatorInventoryMenuClickListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST) // important: run late
    public void onInvClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        // only handle clicks in player's own inventory (bottom)
        if (e.getClickedInventory() == null) return;
        if (e.getView().getBottomInventory() != e.getClickedInventory()) return;

        PlayerState st = SessionService.get().getState(p);
        if (st != PlayerState.WAITING && st != PlayerState.SPECTATOR && st != PlayerState.LOBBY) return;

        ItemStack item = e.getCurrentItem();
        if (item == null || item.getType().isAir()) return;

        // Example: compass opens ServerSelectionGUI
        if (item.getType() == Material.COMPASS) {
            e.setCancelled(true);
            ServerSelectionGUI.open(p, Main.getInstance().getMapRegistry().getMaps());
            return;
        }

        // Example: red concrete -> go to hub
        if (item.getType() == Material.RED_CONCRETE) {
            e.setCancelled(true);
            // choose ONE:
            // p.performCommand("server lobby"); // if Bungee
            p.teleport(p.getServer().getWorld("world").getSpawnLocation());
            return;
        }
    }
}
