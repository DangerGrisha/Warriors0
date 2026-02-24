package org.money.money.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.money.money.match.TeamKey;
import org.money.money.match.TeamService;

import java.util.ArrayList;
import java.util.List;

public final class TeamSelectorGUI {

    public static final String TITLE = "§eSelect Team";

    private static final int[] SLOTS = {10,11,12,13,14,15,16,22};

    private TeamSelectorGUI() {}

    public static void open(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, TITLE);

        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        if (fm != null) {
            fm.setDisplayName(" ");
            filler.setItemMeta(fm);
        }
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);

        String worldName = p.getWorld().getName();

        TeamKey[] keys = TeamKey.values();
        for (int i = 0; i < keys.length && i < SLOTS.length; i++) {
            TeamKey k = keys[i];

            ItemStack it = new ItemStack(k.wool);
            ItemMeta meta = it.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(k.chat + "Join " + k.id);

                List<String> lore = new ArrayList<>();
                lore.add("§7Members: §f" + TeamService.getTeamSize(worldName, k));
                List<String> roster = TeamService.getRoster(worldName, k);
                int cap = Math.min(8, roster.size());
                for (int r = 0; r < cap; r++) lore.add(" §f• " + roster.get(r));
                if (roster.size() > cap) lore.add(" §7... +" + (roster.size() - cap));

                meta.setLore(lore);
                it.setItemMeta(meta);
            }

            inv.setItem(SLOTS[i], it);
        }

        p.openInventory(inv);
    }

    public static boolean isThis(InventoryView view) {
        return view != null && view.getTitle() != null && view.getTitle().equals(TITLE);
    }
}
