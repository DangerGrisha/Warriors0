// FILE: src/main/java/org/money/money/listeners/InventoryLockListener.java
package org.money.money.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.money.money.session.PlayerState;
import org.money.money.session.SessionService;

/**
 * Inventory & item interaction locks for LOBBY and WAITING.
 */
public class InventoryLockListener implements Listener {

    private boolean isLocked(Player p) {
        PlayerState st = SessionService.get().getState(p);
        return st == PlayerState.LOBBY || st == PlayerState.WAITING;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!isLocked(p)) return;

        // allow clicking inside top inventory (GUI), but block moving items
        if (e.getClickedInventory() != null && e.getClickedInventory().equals(e.getView().getTopInventory())) {
            return; // let GUI clicks pass (GuiClickListener will handle)
        }

        e.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!isLocked(p)) return;
        e.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (isLocked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (isLocked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (isLocked(p)) e.setCancelled(true);
    }
}
