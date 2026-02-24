package org.money.money.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.money.money.Main;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ClassSelectionGUI {

    public static final String GUI_TITLE = "Class Selection";

    // fixed order
    public static final String[] ORDER = {
            "LadyNagant","Saske","Hutao","Ganyu","Dio","Naruto","BurgerMaster","Uraraka","AirWalker"
    };

    // icons (all red dye)
    private static final Map<String, Material> ICONS = new LinkedHashMap<>();
    static {
        for (String id : ORDER) ICONS.put(id, Material.RED_DYE);
    }

    // nice slots in 27 menu
    private static final int[] SLOTS = {10,11,12,13,14,15,16,19,20,21,22};

    private static NamespacedKey keyClassId() { return new NamespacedKey(Main.getInstance(), "class_id"); }

    public static void open(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, GUI_TITLE);

        int placed = 0;
        for (int i = 0; i < ORDER.length && i < SLOTS.length; i++) {
            String classId = ORDER[i];
            int slot = SLOTS[i];
            inv.setItem(slot, buildItem(classId, ICONS.getOrDefault(classId, Material.RED_DYE)));
            placed++;
        }

        player.openInventory(inv);
        Main.getInstance().getLogger().info("[ClassGUI] opened for " + player.getName() + " placed=" + placed);
    }

    private static ItemStack buildItem(String classId, Material icon) {
        ItemStack item = new ItemStack(icon, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(classId));
            meta.lore(List.of(Component.text("§7Click to select")));

            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            meta.getPersistentDataContainer().set(keyClassId(), PersistentDataType.STRING, classId);

            item.setItemMeta(meta);
        }
        return item;
    }

    // highlight only for this player
    public static void highlightSelectionInOpenMenu(Player player, String classId) {
        if (player.getOpenInventory() == null) return;
        if (!GUI_TITLE.equals(player.getOpenInventory().getTitle())) return;

        Inventory inv = player.getOpenInventory().getTopInventory();
        for (ItemStack it : inv.getContents()) {
            if (it == null || !it.hasItemMeta()) continue;
            ItemMeta m = it.getItemMeta();

            // clear highlight
            m.removeEnchant(Enchantment.LURE);
            m.removeItemFlags(ItemFlag.HIDE_ENCHANTS);

            String stored = m.getPersistentDataContainer().get(keyClassId(), PersistentDataType.STRING);
            if (stored != null && stored.equalsIgnoreCase(classId)) {
                m.addEnchant(Enchantment.LURE, 1, true);
                m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            it.setItemMeta(m);
        }
        player.updateInventory();
    }
}
