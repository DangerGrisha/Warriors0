package org.money.money.match;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.money.money.Main;
import org.money.money.session.PlayerState;
import org.money.money.session.SessionService;

import java.util.*;

public class GameStartService {

    private static final Map<UUID, Location> frozen = new HashMap<>();
    private static final Random rnd = new Random();

    // ВРЕМЕННО: пока не вынесли в MapDefinition (потом заменим)
    private static final int SPAWN_X1 = -259, SPAWN_Z1 = 110;
    private static final int SPAWN_X2 =  -74, SPAWN_Z2 = 479;

    public static void startGame(String worldName) {
        World w = Bukkit.getWorld(worldName);
        if (w == null) return;

        // 1) IN_GAME state + survival
        for (Player p : w.getPlayers()) {
            SessionService.get().setState(p, PlayerState.IN_GAME);
            p.setGameMode(GameMode.SURVIVAL);
        }

        // 2) wipe inventories before kits
        for (Player p : w.getPlayers()) {
            wipeInventory(p);
            // очень важно: снять KIT_GIVEN, чтобы повторно выдалось
            p.removeScoreboardTag("KIT_GIVEN");
        }

        // 3) give kits based on scoreboard tag
        Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
            for (Player p : w.getPlayers()) {
                // !!! тут ты импортируешь свой ClassItemManager (или перенеси его в org.money.money)
                // ClassItemManager.giveItemsForTaggedClass(p);

                // временно debug если ещё не перенёс:
                p.sendMessage("§a[KITS] giving kit for your class tag...");
            }
        });

        // 4) teleport + freeze 5 sec
        teleportAndFreeze(w, 5);

        // 5) match timer (пока 20 минут можно потом)
        startMatchTimer(worldName, 20 * 60);
    }

    private static void teleportAndFreeze(World w, int freezeSeconds) {
        frozen.clear();

        for (Player p : w.getPlayers()) {
            Location loc = randomPointInRect(w);
            p.teleport(loc);
            frozen.put(p.getUniqueId(), loc);
        }

        new BukkitRunnable() {
            int left = freezeSeconds;
            @Override public void run() {
                if (left <= 0) {
                    frozen.clear();
                    for (Player p : w.getPlayers()) p.sendMessage("§aGo!");
                    cancel();
                    return;
                }
                for (Player p : w.getPlayers()) {
                    p.sendActionBar("§7Unfreeze in §e" + left + "§7...");
                }
                left--;
            }
        }.runTaskTimer(Main.getInstance(), 0L, 20L);
    }

    public static boolean isFrozen(UUID uuid) { return frozen.containsKey(uuid); }
    public static Location frozenLoc(UUID uuid) { return frozen.get(uuid); }

    private static Location randomPointInRect(World w) {
        int xmin = Math.min(SPAWN_X1, SPAWN_X2), xmax = Math.max(SPAWN_X1, SPAWN_X2);
        int zmin = Math.min(SPAWN_Z1, SPAWN_Z2), zmax = Math.max(SPAWN_Z1, SPAWN_Z2);

        double x = xmin + rnd.nextDouble() * (xmax - xmin);
        double z = zmin + rnd.nextDouble() * (zmax - zmin);
        int y = w.getHighestBlockYAt((int)Math.floor(x), (int)Math.floor(z)) + 1;
        return new Location(w, x + 0.5, y, z + 0.5);
    }

    private static void wipeInventory(Player p) {
        p.getInventory().clear();
        p.getInventory().setHelmet(null);
        p.getInventory().setChestplate(null);
        p.getInventory().setLeggings(null);
        p.getInventory().setBoots(null);
        p.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        p.updateInventory();
    }

    private static void startMatchTimer(String worldName, int seconds) {
        new BukkitRunnable() {
            int left = seconds;
            @Override public void run() {
                World w = Bukkit.getWorld(worldName);
                if (w == null) { cancel(); return; }

                if (left <= 0) {
                    Bukkit.broadcastMessage("§e[Match] time up, ending...");
                    MatchEndService.end(worldName);
                    cancel();
                    return;
                }

                // можешь сюда bossbar потом
                left--;
            }
        }.runTaskTimer(Main.getInstance(), 0L, 20L);
    }
}
