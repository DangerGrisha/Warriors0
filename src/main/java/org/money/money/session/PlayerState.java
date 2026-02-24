// FILE: src/main/java/org/money/money/session/PlayerState.java
package org.money.money.session;

/**
 * High-level player lifecycle states.
 * Source of truth for what the player is allowed to do.
 */
public enum PlayerState {
    LOBBY,
    WAITING,
    IN_GAME,
    SPECTATOR
}
