package org.money.money.listeners;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.money.money.Main;
import org.money.money.gui.ServerSelectionGUI;
import org.money.money.session.PlayerState;
import org.money.money.session.SessionService;

public class InteractHubListener implements Listener {
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getItem() == null) return;
        Player p = e.getPlayer();

        PlayerState st = SessionService.get().getState(p); // как у тебя сделано
        if (st != PlayerState.WAITING && st != PlayerState.SPECTATOR) return;

        Material m = e.getItem().getType();

        // hub
        if (m == Material.RED_CONCRETE) {
            e.setCancelled(true);
            SessionService.get().setState(p, PlayerState.LOBBY, "world");
            return;
        }

        // server menu
        if (m == Material.COMPASS) {
            e.setCancelled(true);
            var maps = Main.getInstance().getMapRegistry().getMaps();
            ServerSelectionGUI.open(p, maps);
        }
    }

}
