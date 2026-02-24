package org.money.money.listeners;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.money.money.Main;
import org.money.money.gui.TeamSelectorGUI;
import org.money.money.items.SpectatorItems;
import org.money.money.match.TeamKey;
import org.money.money.match.TeamService;
import org.money.money.match.WinCheckService;
import org.money.money.session.PlayerState;
import org.money.money.session.SessionService;

public class OnDeathListener implements Listener {
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        World w = p.getWorld();
        String worldName = w.getName();

        // если это не игровой мир — игнор
        if (Main.getInstance().getMapRegistry().byWorld(worldName) == null) return;

        // через 2 тика, чтобы мир успел обновить состояния
        Bukkit.getScheduler().runTaskLater(Main.getInstance(),
                () -> WinCheckService.checkLastTeamAlive(worldName),
                2L
        );
        Bukkit.getScheduler().runTaskLater(Main.getInstance(), () -> {
            p.spigot().respawn(); // Paper обычно можно, но если ругается — убери
            setSpectator(p, worldName);
        }, 1L);
    }
    private void setSpectator(Player p, String gameWorldName) {
        SessionService.get().setState(p, PlayerState.SPECTATOR, gameWorldName);

        p.setGameMode(GameMode.SPECTATOR);
        p.getInventory().clear();

        // предметы спектатора
        SpectatorItems.give(p);
    }



}
