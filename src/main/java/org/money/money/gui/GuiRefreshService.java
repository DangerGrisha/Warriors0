// FILE: src/main/java/org/money/money/gui/GuiRefreshService.java
package org.money.money.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.money.money.Main;
import org.money.money.config.MapDefinition;

import java.util.List;

public final class GuiRefreshService {
    private GuiRefreshService() {}

    public static void refreshServerSelectionForAllViewers() {
        List<MapDefinition> maps = Main.getInstance().getMapRegistry().getMaps();
        Inventory fresh = ServerSelectionGUI.build(maps);

        for (Player p : Bukkit.getOnlinePlayers()) {
            String title = p.getOpenInventory().getTitle();
            if (!ServerSelectionGUI.TITLE.equals(title)) continue;

            Inventory top = p.getOpenInventory().getTopInventory();
            top.setContents(fresh.getContents());
            p.updateInventory();
        }
    }
}
