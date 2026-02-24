package org.money.money.items;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.money.money.listeners.TeamSelectListener;

public final class WaitingItems {
    private WaitingItems() {}

    public static void giveTo(Player p) {
        ItemStack wool = new ItemStack(Material.WHITE_WOOL, 1);
        ItemMeta meta = wool.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(TeamSelectListener.TEAM_ITEM_NAME);
            wool.setItemMeta(meta);
        }

        p.getInventory().setItem(8, new ItemStack(Material.RED_CONCRETE));

        p.getInventory().setItem(4, wool);
        p.updateInventory();
    }
}
