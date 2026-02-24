package org.money.money.match;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.money.money.Main;
import org.money.money.config.MapDefinition;
import org.money.money.match.WorldService;
import org.money.money.session.ClassItemManager;
import org.money.money.session.PlayerState;
import org.money.money.session.SessionService;
import org.money.money.world.MatchWorld;
import org.money.money.world.MatchWorldService;
import org.money.money.world.WorldState;

import static org.money.money.items.InventoryUtil.wipeInventoryAll;
import static org.money.money.match.MobSpawnService.spawnPassiveMobsForMap;

public final class MatchOrchestrator {

    private static final int MATCH_SECONDS = 20 * 60;

    private MatchOrchestrator() {}

    public static void onCountdownFinished(String worldName) {
        ClassSelectionService.start(worldName);
    }

    public static void afterClassSelection(String worldName) {
        startMatch(worldName);
    }

    public static void startMatch(String worldName) {
        World w = Bukkit.getWorld(worldName);
        if (w == null) return;

        MatchWorld mw = MatchWorldService.getOrCreate(worldName);
        mw.setState(WorldState.RUNNING);

        // move players to IN_GAME
        for (Player p : w.getPlayers()) {
            SessionService.get().setState(p, PlayerState.IN_GAME);
        }

        // ✅ teleport + freeze 5 seconds
        SpawnService.teleportTeamsTogether(worldName);
        wipeInventoryAll(worldName);                     // очистка (если надо)
        Bukkit.getScheduler().runTaskLater(Main.getInstance(), () -> {
            for (Player p : Bukkit.getWorld(worldName).getPlayers()) {
                ClassItemManager.giveItemsForTaggedClass(p);
            }
        }, 1L);

        //give colors of teams in scoreboard and on nicknames
        TeamService.applyColorTeamsToScoreboard(w);

        // ✅ kits hook (temporary)
        for (Player p : w.getPlayers()) {
            String c = ClassSelectionService.getChosen(p.getUniqueId()).orElse("UNKNOWN");
            p.sendMessage("§a[KIT] class=" + c + " (hook later)");
        }

        //give totems
        ItemStack totems = new ItemStack(Material.TOTEM_OF_UNDYING, 5);

        for (Player p : w.getPlayers()) {
            var leftover = p.getInventory().addItem(totems.clone());
            if (!leftover.isEmpty()) {
                // inventory full -> drop leftovers at player
                leftover.values().forEach(it -> w.dropItemNaturally(p.getLocation(), it));
            }
            p.updateInventory();
        }

        //spawn mobs
        spawnPassiveMobsForMap(worldName);

        // ✅ start timer bossbar
        MatchTimer.start(worldName, MATCH_SECONDS);

        //boarder
        MapDefinition map = Main.getInstance().getMapRegistry().byWorld(worldName);
        BorderService.applyOnMatchStart(worldName, map);

        // и таймер игры тоже оттуда же:
        MatchTimer.start(worldName, map.border.totalSeconds);


        Bukkit.broadcastMessage("§a[Match] Started in " + worldName);
    }

    public static void endMatch(String worldName) {
        MatchWorld mw = MatchWorldService.getOrCreate(worldName);

        // prevent double end
        if (mw.getState() == WorldState.RESTARTING) return;

        mw.setState(WorldState.RESTARTING);

        // stop timer + stop queue countdown if running
        MatchTimer.stop(worldName);
        QueueService.stopCountdown(worldName);

        World w = Bukkit.getWorld(worldName);
        if (w != null) {
            for (Player p : w.getPlayers()) {
                // send back to lobby + give lobby items via SessionService
                SessionService.get().setState(p, PlayerState.LOBBY, "world");
                ClassSelectionService.clearPlayer(p.getUniqueId());
            }
        }
        //remove boarder to default
        BorderService.resetToDefault(worldName);
        MatchTimer.stop(worldName);

        Bukkit.broadcastMessage("§e[Match] Ended in " + worldName + ". Restarting world...");

        // reset world to template
        WorldService.resetWorld(worldName);
    }
}
