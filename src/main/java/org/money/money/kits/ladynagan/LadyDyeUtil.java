package org.money.money.kits.ladynagan;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.money.money.util.ItemModels;

/** Перенос 1:1 из Last_Warriors (events/ladynagan/DyeUtil). */
public class LadyDyeUtil {

    public static ItemStack createDye(Material dyeMaterial, String displayName) {
        ItemStack dye = new ItemStack(dyeMaterial);
        ItemMeta meta = dye.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(displayName));
            if (dyeMaterial == Material.GREEN_DYE) {
                ItemModels.apply(meta, "ledynagan_boom_green");
            } else if (dyeMaterial == Material.RED_DYE) {
                ItemModels.apply(meta, "ledynagan_boom_red");
            } else if (dyeMaterial == Material.YELLOW_DYE) {
                ItemModels.apply(meta, "ledynagan_boom_yellow");
            }
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
