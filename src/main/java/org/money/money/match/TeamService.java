package org.money.money.match;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.money.money.match.TeamKey;

import java.util.*;

public final class TeamService {

    // world -> player -> team
    private static final Map<String, Map<UUID, TeamKey>> worldTeams = new HashMap<>();

    // world lock flag
    private static final Set<String> lockedWorlds = new HashSet<>();

    private static final Random rnd = new Random();

    private static final Scoreboard LW_BOARD =
            Bukkit.getScoreboardManager().getNewScoreboard();


    private TeamService() {}

    public static boolean isLocked(String worldName) {
        return lockedWorlds.contains(worldName);
    }

    public static void setLocked(String worldName, boolean locked) {
        if (locked) lockedWorlds.add(worldName);
        else lockedWorlds.remove(worldName);
    }

    public static Optional<TeamKey> getTeam(Player p) {
        String w = p.getWorld().getName();
        Map<UUID, TeamKey> map = worldTeams.get(w);
        if (map == null) return Optional.empty();
        return Optional.ofNullable(map.get(p.getUniqueId()));
    }

    private static Team ensureColorTeam(TeamKey key) {
        String name = "CLR_" + key.name(); // CLR_RED / CLR_BLUE etc
        Team t = LW_BOARD.getTeam(name);
        if (t == null) t = LW_BOARD.registerNewTeam(name);

        // Paper/Spigot: setColor + prefix gives tab + name color
        t.color(key.namedColor());
        t.setPrefix(key.prefixLegacy()); // optional "§c" etc

        // important: make names colored in tab
        t.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
        return t;
    }

    public static void applyColorTeamsToScoreboard(World world) {
        for (Player p : world.getPlayers()) {
            p.setScoreboard(LW_BOARD);

            TeamKey key = TeamService.getTeam(p).orElse(null);
            if (key == null) continue;

            // remove from old CLR teams
            for (Team t : LW_BOARD.getTeams()) {
                if (t.getName().startsWith("CLR_")) t.removeEntry(p.getName());
            }
            ensureColorTeam(key).addEntry(p.getName());
        }
    }

    public static void clearPlayer(UUID uuid) {
        for (Map<UUID, TeamKey> map : worldTeams.values()) map.remove(uuid);
    }

    public static void clearWorld(String worldName) {
        worldTeams.remove(worldName);
        lockedWorlds.remove(worldName);
    }

    public static void chooseTeam(Player p, TeamKey team) {
        String worldName = p.getWorld().getName();
        if (isLocked(worldName)) {
            p.sendMessage("§cTeams are locked.");
            return;
        }

        worldTeams.computeIfAbsent(worldName, k -> new HashMap<>())
                .put(p.getUniqueId(), team);

        p.sendMessage("§aYou joined team " + team.chat + team.id + "§a.");
    }

    /**
     * Assign players without a team using BALANCE rule:
     * - always pick among MIN size teams (random tie-break)
     */
    public static void assignMissingBalanced(String worldName) {
        World w = Bukkit.getWorld(worldName);
        if (w == null) return;

        Map<UUID, TeamKey> map = worldTeams.computeIfAbsent(worldName, k -> new HashMap<>());

        for (Player p : w.getPlayers()) {
            if (map.containsKey(p.getUniqueId())) continue;

            TeamKey picked = pickMinTeam(worldName);
            map.put(p.getUniqueId(), picked);
            p.sendMessage("§eAuto-assigned to " + picked.chat + picked.id + "§e (balance).");
        }
    }

    public static int getTeamSize(String worldName, TeamKey k) {
        Map<UUID, TeamKey> map = worldTeams.get(worldName);
        if (map == null) return 0;
        int c = 0;
        for (TeamKey v : map.values()) if (v == k) c++;
        return c;
    }

    public static List<String> getRoster(String worldName, TeamKey k) {
        World w = Bukkit.getWorld(worldName);
        if (w == null) return List.of();

        Map<UUID, TeamKey> map = worldTeams.get(worldName);
        if (map == null) return List.of();

        List<String> names = new ArrayList<>();
        for (Player p : w.getPlayers()) {
            TeamKey t = map.get(p.getUniqueId());
            if (t == k) names.add(p.getName());
        }
        names.sort(String::compareToIgnoreCase);
        return names;
    }

    private static TeamKey pickMinTeam(String worldName) {
        int min = Integer.MAX_VALUE;
        List<TeamKey> candidates = new ArrayList<>();

        for (TeamKey k : TeamKey.values()) {
            int size = getTeamSize(worldName, k);

            if (size < min) {
                min = size;
                candidates.clear();
                candidates.add(k);
            } else if (size == min) {
                candidates.add(k);
            }
        }

        // if all equal -> random
        return candidates.get(rnd.nextInt(candidates.size()));
    }
}
