// FILE: src/main/java/org/money/money/listeners/JoinQuitListener.java
package org.money.money.listeners;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.money.money.Main;
import org.money.money.session.PlayerState;
import org.money.money.session.SessionService;

/**
 * Ensures every player always enters in LOBBY state.
 */
public class JoinQuitListener implements Listener {

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();

        // Force to lobby world "world"
        Bukkit.getScheduler().runTaskLater(
                Main.getInstance(),
                () -> SessionService.get().setState(p, PlayerState.LOBBY, "world"),
                1L
        );
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        SessionService.get().clearSession(e.getPlayer().getUniqueId());
    }
}
