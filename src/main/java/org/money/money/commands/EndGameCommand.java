package org.money.money.commands;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.money.money.Main;
import org.money.money.match.MatchOrchestrator;

public class EndGameCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        // allow console: /endgame <world>
        if (!(sender instanceof Player p)) {
            if (args.length < 1) {
                sender.sendMessage("Usage: /endgame <world>");
                return true;
            }
            String worldName = args[0];
            World w = Bukkit.getWorld(worldName);
            if (w == null) {
                sender.sendMessage("World not found: " + worldName);
                return true;
            }

            sender.sendMessage("Forcing end game in " + worldName);
            MatchOrchestrator.endMatch(worldName);
            return true;
        }

        // player: ends current world match
        String worldName = p.getWorld().getName();
        p.sendMessage("§eForcing end game in §f" + worldName);

        Main.getInstance().getLogger().info("[Command] /endgame by " + p.getName() + " in " + worldName);
        MatchOrchestrator.endMatch(worldName);
        return true;
    }
}
