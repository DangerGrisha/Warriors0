// FILE: src/main/java/org/money/money/session/PlayerSession.java
package org.money.money.session;

import java.util.UUID;

/**
 * Runtime player session info stored in memory.
 */
public class PlayerSession {

    private final UUID playerId;
    private PlayerState state;
    private String currentWorld; // world name (optional, for debug / routing)

    public PlayerSession(UUID playerId, PlayerState state, String currentWorld) {
        this.playerId = playerId;
        this.state = state;
        this.currentWorld = currentWorld;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public PlayerState getState() {
        return state;
    }

    public void setState(PlayerState state) {
        this.state = state;
    }

    public String getCurrentWorld() {
        return currentWorld;
    }

    public void setCurrentWorld(String currentWorld) {
        this.currentWorld = currentWorld;
    }
}
