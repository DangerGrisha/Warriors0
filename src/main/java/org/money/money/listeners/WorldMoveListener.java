package org.money.money.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.money.money.match.QueueService;
import org.money.money.session.PlayerState;
import org.money.money.session.SessionService;

public class WorldMoveListener implements Listener {

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        var p = e.getPlayer();

        // If player is in WAITING state, re-check countdown in new world
        if (SessionService.get().getState(p) == PlayerState.WAITING) {
            QueueService.tryStartCountdown(p.getWorld().getName());
        }

        // If player left a world, we should also re-check that old world's queue
        String from = e.getFrom().getName();
        QueueService.tryStartCountdown(from);
    }
}
