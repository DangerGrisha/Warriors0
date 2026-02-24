// FILE: src/main/java/org/money/money/listeners/InteractRouterListener.java
package org.money.money.listeners;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.money.money.Main;
import org.money.money.gui.ServerSelectionGUI;
import org.money.money.session.PlayerState;
import org.money.money.session.SessionService;

/**
 * Routes item interactions based on PlayerState.
 * Step 1: only compass and white wool.
 */
public class InteractRouterListener implements Listener {

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        ItemStack it = e.getItem();
        if (it == null) return;

        PlayerState state = SessionService.get().getState(p);

        // LOBBY: Compass -> open map selection GUI (Step 2)
        if (state == PlayerState.LOBBY && it.getType() == Material.COMPASS) {
            e.setCancelled(true);

            // If you already have ServerSelectionGUI:
            ServerSelectionGUI.open(p, Main.getInstance().getMapRegistry().getMaps());

            p.sendMessage("§e[TODO] ServerSelectionGUI.open(player) in Step 2");
            return;
        }

        // WAITING: White wool -> open team selection GUI
        if (state == PlayerState.WAITING && it.getType() == Material.WHITE_WOOL) {
            e.setCancelled(true);
            p.sendMessage("§eTeam selection will be added in Step 4 (TeamService/TeamSelectorGUI).");
        }
    }
}
