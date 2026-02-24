// FILE: src/main/java/org/money/money/gui/ServerSelectionGUI.java
package org.money.money.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.money.money.Main;
import org.money.money.config.MapDefinition;
import org.money.money.world.WorldState;
import org.money.money.world.WorldStateResolver;

import java.util.ArrayList;
import java.util.List;

public class ServerSelectionGUI {

    public static final String TITLE = "§eServer Selection";

    // ✅ DO NOT create NamespacedKey in static initializer via getInstance() (can be null)
    private static NamespacedKey keyWorld() { return new NamespacedKey(Main.getInstance(), "map_world"); }
    private static NamespacedKey keyId()    { return new NamespacedKey(Main.getInstance(), "map_id"); }

    public static void open(Player p, List<MapDefinition> maps) {
        Inventory inv = build(maps);
        p.openInventory(inv);
        Main.getInstance().getLogger().info("[GUI] open ServerSelectionGUI maps=" + maps.size());
    }

    public static Inventory build(List<MapDefinition> maps) {
        Inventory inv = Bukkit.createInventory(null, 27, TITLE);

        // filler
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        if (fm != null) {
            fm.setDisplayName(" ");
            filler.setItemMeta(fm);
        }
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);

        int slot = 10;
        for (MapDefinition map : maps) {
            if (!map.enabled) continue;

            WorldState st = WorldStateResolver.get(map.worldName);

            Material mat = switch (st) {
                case AVAILABLE -> Material.LIME_CONCRETE;
                case WAITING   -> Material.YELLOW_CONCRETE;
                case RUNNING   -> Material.RED_CONCRETE;
                case RESTARTING -> Material.BLACK_CONCRETE;
            };

            ItemStack item = new ItemStack(mat);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§f" + map.displayName);

                List<String> lore = new ArrayList<>();
                lore.add("§7World: §f" + map.worldName);
                lore.add("§7Players: §f" + map.minPlayers + " - " + map.maxPlayers);
                lore.add("§7State: §f" + st.name());
                lore.add("");

                boolean joinable = (st == WorldState.AVAILABLE || st == WorldState.WAITING);
                lore.add(joinable ? "§aClick to join" : "§cCannot join now");
                meta.setLore(lore);

                // ✅ Store identifiers in PDC
                meta.getPersistentDataContainer().set(keyWorld(), PersistentDataType.STRING, map.worldName);
                meta.getPersistentDataContainer().set(keyId(), PersistentDataType.STRING, map.id);

                item.setItemMeta(meta);
            }

            inv.setItem(slot, item);
            slot++;
            if (slot >= inv.getSize() - 1) break;
        }

        return inv;
    }
}
