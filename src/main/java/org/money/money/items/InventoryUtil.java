package org.money.money.items;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

public final class InventoryUtil {

    private InventoryUtil() {}

    public static void wipeInventoryAll(String worldName) {
        World w = Bukkit.getWorld(worldName);
        if (w == null) return;

        for (Player p : w.getPlayers()) {
            wipePlayer(p);
        }
    }

    public static void wipePlayer(Player p) {
        if (p == null) return;

        // inventory + armor + offhand
        p.getInventory().clear();
        p.getInventory().setArmorContents(null);
        p.getInventory().setItemInOffHand(null);

        // cursor item (if they were dragging something)
        try { p.setItemOnCursor(null); } catch (Throwable ignored) {}

        // effects
        for (PotionEffect eff : p.getActivePotionEffects()) {
            p.removePotionEffect(eff.getType());
        }

        // misc reset
        p.setFireTicks(0);
        p.setFoodLevel(20);
        p.setSaturation(5f);
        p.setFallDistance(0f);
        p.setExp(0f);
        p.setLevel(0);
        p.setHealth(Math.min(p.getMaxHealth(), 20.0));

        p.updateInventory();
    }
}
