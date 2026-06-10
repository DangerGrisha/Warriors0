package org.money.money.session;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Единая точка очистки игрока от состояния способностей при выходе из игры.
 *
 * <h3>Два триггера</h3>
 * <ul>
 *   <li><b>Авто:</b> {@link PlayerChangedWorldEvent} — как только игрок зашёл в лобби-мир
 *       (из config), его чистим.</li>
 *   <li><b>Явно:</b> команда {@code /warriors reset [all|&lt;ник&gt;]} — её вызывает внешний
 *       минигейм-плагин в момент конца игры (по умолчанию — все онлайн).</li>
 * </ul>
 *
 * <p>Сама очистка делегируется зарегистрированным {@link KitResettable}-листенерам
 * (снайперка, Dio, SaberLight-баффы и т.п.), каждый снимает своё.
 */
public final class SessionManager implements Listener, CommandExecutor, TabCompleter {

    private final List<KitResettable> resettables;

    public SessionManager(List<KitResettable> resettables) {
        this.resettables = new ArrayList<>(resettables);
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player p = event.getPlayer();
        if (KitSession.isLobbyWorld(p.getWorld())) {
            cleanupPlayer(p);
        }
    }

    /** Снять с игрока всё состояние способностей. Идемпотентно. */
    public void cleanupPlayer(Player player) {
        for (KitResettable r : resettables) {
            try {
                r.resetPlayer(player);
            } catch (Throwable t) {
                Bukkit.getLogger().warning("[Warriors0] resetPlayer failed for "
                        + r.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    public void cleanupAll() {
        for (Player p : Bukkit.getOnlinePlayers()) cleanupPlayer(p);
    }

    /**
     * Снять состояние только с игроков, находящихся в указанном мире.
     * @return сколько игроков было очищено (-1 если мира с таким именем нет)
     */
    public int cleanupWorld(String worldName) {
        org.bukkit.World world = Bukkit.getWorld(worldName);
        if (world == null) return -1;
        int count = 0;
        for (Player p : world.getPlayers()) {
            cleanupPlayer(p);
            count++;
        }
        return count;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("reset")) {
            sender.sendMessage("§7/" + label + " reset [all|world <мир>|<ник>] §8— снять состояние способностей");
            return true;
        }

        // /warriors reset              -> все онлайн
        // /warriors reset all          -> все онлайн
        // /warriors reset world <мир>  -> только игроки в указанном мире
        // /warriors reset <ник>        -> конкретный игрок
        if (args.length == 1 || args[1].equalsIgnoreCase("all")) {
            cleanupAll();
            sender.sendMessage("§aСостояние способностей снято у всех онлайн-игроков.");
            return true;
        }

        if (args[1].equalsIgnoreCase("world")) {
            if (args.length < 3) {
                sender.sendMessage("§cУкажите имя мира: /" + label + " reset world <мир>");
                return true;
            }
            int n = cleanupWorld(args[2]);
            if (n < 0) {
                sender.sendMessage("§cМир не найден: " + args[2]);
            } else {
                sender.sendMessage("§aСостояние способностей снято у " + n + " игроков в мире " + args[2] + ".");
            }
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage("§cИгрок не найден: " + args[1]);
            return true;
        }
        cleanupPlayer(target);
        sender.sendMessage("§aСостояние способностей снято у " + target.getName() + ".");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("reset");
        if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            List<String> out = new ArrayList<>();
            out.add("all");
            out.add("world");
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            return out;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("reset") && args[1].equalsIgnoreCase("world")) {
            List<String> out = new ArrayList<>();
            for (org.bukkit.World w : Bukkit.getWorlds()) out.add(w.getName());
            return out;
        }
        return List.of();
    }
}
