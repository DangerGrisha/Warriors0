package org.money.money.match;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.ChatColor;
import org.bukkit.Material;

public enum TeamKey {
    RED("RED", ChatColor.RED, Material.RED_WOOL, NamedTextColor.RED),
    BLUE("BLUE", ChatColor.BLUE, Material.BLUE_WOOL, NamedTextColor.BLUE),
    GREEN("GREEN", ChatColor.GREEN, Material.GREEN_WOOL, NamedTextColor.GREEN),
    YELLOW("YELLOW", ChatColor.YELLOW, Material.YELLOW_WOOL, NamedTextColor.YELLOW),
    AQUA("AQUA", ChatColor.AQUA, Material.CYAN_WOOL, NamedTextColor.AQUA),
    PURPLE("PURPLE", ChatColor.LIGHT_PURPLE, Material.PURPLE_WOOL, NamedTextColor.LIGHT_PURPLE),
    ORANGE("ORANGE", ChatColor.GOLD, Material.ORANGE_WOOL, NamedTextColor.GOLD),
    WHITE("WHITE", ChatColor.WHITE, Material.WHITE_WOOL, NamedTextColor.WHITE);

    public final String id;
    public final ChatColor chat;        // legacy (можно оставить)
    public final Material wool;
    private final NamedTextColor named; // modern (для Team#color)

    TeamKey(String id, ChatColor chat, Material wool, NamedTextColor named) {
        this.id = id;
        this.chat = chat;
        this.wool = wool;
        this.named = named;
    }

    public NamedTextColor namedColor() {
        return named;
    }

    /** Для prefix в scoreboard team (если используешь §) */
    public String prefixLegacy() {
        return chat.toString();
    }

    public static TeamKey fromWool(Material mat) {
        for (TeamKey k : values()) if (k.wool == mat) return k;
        return null;
    }
}
