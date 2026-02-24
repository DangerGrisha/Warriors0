package org.money.money.world;

import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Runtime state for a single game world.
 */
public class MatchWorld {

    private final String worldName;

    private final Set<UUID> players = new HashSet<>();
    private WorldState state = WorldState.AVAILABLE;

    private BukkitRunnable countdownTask;

    public MatchWorld(String worldName) {
        this.worldName = worldName;
    }

    public String getWorldName() {
        return worldName;
    }

    public Set<UUID> getPlayers() {
        return players;
    }

    public WorldState getState() {
        return state;
    }

    public void setState(WorldState state) {
        this.state = state;
    }

    public BukkitRunnable getCountdownTask() {
        return countdownTask;
    }

    public void setCountdownTask(BukkitRunnable countdownTask) {
        this.countdownTask = countdownTask;
    }
}
