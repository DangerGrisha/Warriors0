package org.money.money.items;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class SpectatorItems {
    private SpectatorItems() {}

    public static ItemStack serverMenuItem() {
        ItemStack it = new ItemStack(Material.COMPASS);
        ItemMeta im = it.getItemMeta();
        im.setDisplayName("§eServer Selection");
        it.setItemMeta(im);
        return it;
    }

    public static ItemStack hubItem() {
        ItemStack it = new ItemStack(Material.RED_CONCRETE);
        ItemMeta im = it.getItemMeta();
        im.setDisplayName("§cReturn to Hub");
        it.setItemMeta(im);
        return it;
    }

    public static void give(Player p) {
        p.getInventory().setItem(0, serverMenuItem());
        p.getInventory().setItem(8, hubItem()); // 9 слот = index 8
        p.updateInventory();
    }
}
