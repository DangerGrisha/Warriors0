package org.money.money.kits.ladynagan;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Перенос 1:1 из Last_Warriors (events/ladynagan/DyeUtil). */
public class LadyDyeUtil {

    public static ItemStack createDye(Material dyeMaterial, String displayName) {
        ItemStack dye = new ItemStack(dyeMaterial);
        ItemMeta meta = dye.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(displayName));
            dye.setItemMeta(meta);
        }
        return dye;
    }

    public static ItemStack createRedDye(String displayName) {
        return createDye(Material.RED_DYE, displayName);
    }

    public static ItemStack createGreenDye(String displayName) {
        return createDye(Material.GREEN_DYE, displayName);
    }

    public static ItemStack createYellowDye(String displayName) {
        return createDye(Material.YELLOW_DYE, displayName);
    }
}
