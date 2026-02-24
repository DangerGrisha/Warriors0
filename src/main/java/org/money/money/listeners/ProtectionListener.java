// FILE: src/main/java/org/money/money/listeners/ProtectionListener.java
package org.money.money.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.money.money.session.PlayerState;
import org.money.money.session.SessionService;

/**
 * Basic protection rules for LOBBY & WAITING.
 */
public class ProtectionListener implements Listener {

    private boolean isProtected(Player p) {
        PlayerState st = SessionService.get().getState(p);
        return st == PlayerState.LOBBY || st == PlayerState.WAITING;
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (isProtected(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (isProtected(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onAnyDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (isProtected(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onPvP(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player damager && isProtected(damager)) {
            e.setCancelled(true);
        }
        if (e.getEntity() instanceof Player victim && isProtected(victim)) {
            e.setCancelled(true);
        }
    }
}
