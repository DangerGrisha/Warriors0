// FILE: src/main/java/org/money/money/session/SessionService.java
package org.money.money.session;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.money.money.items.WaitingItems;

import java.util.*;

import static org.money.money.session.PlayerState.LOBBY;

/**
 * Central service: sets player state, handles teleports, gives items, and enforces "safety cleanup".
 * This is the ONLY place where state transitions should happen.
 */
public class SessionService {

    private static SessionService instance;

    public static SessionService get() {
        if (instance == null) throw new IllegalStateException("SessionService not initialized");
        return instance;
    }

    public static void init(JavaPlugin plugin) {
        instance = new SessionService(plugin);
    }

    private final JavaPlugin plugin;
    private final Map<UUID, PlayerSession> sessions = new HashMap<>();

    private SessionService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public PlayerState getState(Player p) {
        PlayerSession s = sessions.get(p.getUniqueId());
        return (s == null) ? LOBBY : s.getState();
    }

    public Optional<PlayerSession> getSession(UUID uuid) {
        return Optional.ofNullable(sessions.get(uuid));
    }

    public void clearSession(UUID uuid) {
        sessions.remove(uuid);
    }

    public void setState(Player p, PlayerState newState) {
        setState(p, newState, null);
    }

    /**
     * State transition with optional target world.
     * @param targetWorldName if not null: teleport player there (spawn location)
     */
    public void setState(Player p, PlayerState newState, String targetWorldName) {
        Objects.requireNonNull(p, "player");
        Objects.requireNonNull(newState, "newState");

        // 1) Safety cleanup that should happen for ANY transition
        closeAnyGui(p);
        p.setItemOnCursor(null);
        p.setCanPickupItems(true);
        p.setFireTicks(0);

        if (newState == LOBBY || newState == PlayerState.WAITING) {
            p.setFoodLevel(20);
            p.setSaturation(10f);
            p.setHealth(Math.min(p.getMaxHealth(), p.getHealth()));
        }

        // 2) Teleport if requested
        if (targetWorldName != null) {
            World w = Bukkit.getWorld(targetWorldName);
            if (w != null) {
                p.teleport(w.getSpawnLocation());
            }
        }

        // 3) Update / create session
        PlayerSession session = sessions.computeIfAbsent(
                p.getUniqueId(),
                id -> new PlayerSession(id, newState, safeWorldName(p))
        );
        session.setState(newState);
        session.setCurrentWorld(safeWorldName(p));

        // 4) Apply rules + inventory per state
        applyStateRules(p, newState);

        // 5) Hooks per state (queue start)
        if (newState == PlayerState.WAITING) {
            // run 1 tick later so world/players list is correct
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                org.money.money.match.QueueService.tryStartCountdown(p.getWorld().getName());
            }, 1L);
        }
    }

    private void applyStateRules(Player p, PlayerState state) {
        switch (state) {
            case LOBBY -> {
                p.setGameMode(GameMode.ADVENTURE);
                p.getInventory().clear();
                plugin.getLogger().info("[Session] LOBBY -> give items to " + p.getName());
                //LobbyItems.giveTo(p); // <-- your existing class
                p.getInventory().setItem(0, new org.bukkit.inventory.ItemStack(org.bukkit.Material.COMPASS));
            }
            case WAITING -> {
                p.setGameMode(GameMode.ADVENTURE);
                p.getInventory().clear();
                WaitingItems.giveTo(p);
            }
            case IN_GAME -> {
                p.setGameMode(GameMode.SURVIVAL);
                // Game items/kits are handled elsewhere
            }
        }
        p.updateInventory();
    }

    private void closeAnyGui(Player p) {
        try {
            if (p.getOpenInventory() != null) p.closeInventory();
        } catch (Throwable ignored) {}
    }

    private String safeWorldName(Player p) {
        return (p.getWorld() == null) ? "unknown" : p.getWorld().getName();
    }
}
