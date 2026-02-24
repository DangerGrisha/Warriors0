package org.money.money.listeners;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.money.money.gui.TeamSelectorGUI;
import org.money.money.match.TeamKey;
import org.money.money.match.TeamService;
import org.money.money.session.PlayerState;
import org.money.money.session.SessionService;

public class TeamSelectListener implements Listener {

    public static final String TEAM_ITEM_NAME = "§eTeam Selection";

    @EventHandler
    public void onRightClick(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;

        Player p = e.getPlayer();
        if (SessionService.get().getState(p) != PlayerState.WAITING) return;

        ItemStack it = p.getInventory().getItemInMainHand();
        if (it == null || it.getType() != Material.WHITE_WOOL) return;
        if (!it.hasItemMeta() || it.getItemMeta().getDisplayName() == null) return;

        if (!TEAM_ITEM_NAME.equals(it.getItemMeta().getDisplayName())) return;

        if (TeamService.isLocked(p.getWorld().getName())) {
            p.sendMessage("§cTeams are locked.");
            return;
        }

        TeamSelectorGUI.open(p);
        e.setCancelled(true);
    }

    @EventHandler
    public void onGuiClick(InventoryClickEvent e) {
        if (!TeamSelectorGUI.isThis(e.getView())) return;

        e.setCancelled(true);

        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;

        ItemStack clicked = e.getCurrentItem();
        if (clicked == null) return;

        TeamKey key = TeamKey.fromWool(clicked.getType());
        if (key == null) return;

        TeamService.chooseTeam(p, key);
        p.closeInventory();
    }
}
