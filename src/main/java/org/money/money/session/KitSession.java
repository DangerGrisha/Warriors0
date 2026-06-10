package org.money.money.session;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Определяет, находится ли игрок «в игре» или «в лобби».
 *
 * <p>Нужен, чтобы отложенные возвраты предметов способностей (кулдауны через
 * {@code runTaskLater}/{@code startCooldownAndReturn}) НЕ срабатывали после конца игры,
 * когда игрока уже перекинули в лобби — иначе шмотки способностей «всплывают» в лобби.
 *
 * <h3>Сигнал «в игре»</h3>
 * <ul>
 *   <li>Если игрок в одном из <b>лобби-миров</b> (из config) — он точно НЕ в игре.</li>
 *   <li>Если лобби-миры настроены и игрок не в них — считаем, что он в игре.</li>
 *   <li>Если лобби-миры НЕ настроены — fallback по scoreboard-команде: «в игре» = состоит
 *       в команде главного scoreboard (на сервере команды назначаются только на время матча).</li>
 * </ul>
 * Лобби-миры заполняются в {@code Main.onEnable()} из {@code config.yml}.
 */
public final class KitSession {

    private static final Set<String> LOBBY_WORLDS = ConcurrentHashMap.newKeySet();

    private KitSession() {}

    /** Заполнить список лобби-миров (имена мира, регистронезависимо). */
    public static void configureLobbyWorlds(Collection<String> names) {
        LOBBY_WORLDS.clear();
        if (names == null) return;
        for (String n : names) {
            if (n != null && !n.isBlank()) LOBBY_WORLDS.add(n.toLowerCase(Locale.ROOT));
        }
    }

    public static boolean isLobbyWorld(World world) {
        return world != null && LOBBY_WORLDS.contains(world.getName().toLowerCase(Locale.ROOT));
    }

    public static boolean lobbyWorldsConfigured() {
        return !LOBBY_WORLDS.isEmpty();
    }

    /** true, если игрок сейчас в активной игре (а не в лобби). null/offline → false. */
    public static boolean isInGame(Player player) {
        if (player == null || !player.isOnline()) return false;
        if (isLobbyWorld(player.getWorld())) return false;
        if (lobbyWorldsConfigured()) return true; // не в лобби-мире, а список настроен
        return hasTeam(player);                    // zero-config: сигналом служит команда
    }

    private static boolean hasTeam(Player player) {
        try {
            Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
            return sb.getEntryTeam(player.getName()) != null;
        } catch (Throwable t) {
            return true; // при сбое скорборда не ломаем выдачу предметов
        }
    }
}
