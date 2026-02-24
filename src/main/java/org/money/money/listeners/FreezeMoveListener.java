package org.money.money.listeners;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.money.money.match.SpawnService;

public class FreezeMoveListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();

        if (!SpawnService.isFrozen(p)) return;

        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;

        // allow head movement only (block-level movement is forbidden)
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        Location lock = SpawnService.getFrozenLocation(p);
        if (lock == null) return;

        // preserve camera yaw/pitch
        Location dest = lock.clone();
        dest.setYaw(to.getYaw());
        dest.setPitch(to.getPitch());

        e.setTo(dest);
    }
}
