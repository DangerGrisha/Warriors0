package org.money.money.session;

import org.bukkit.entity.Player;

/**
 * Листенер кита, у которого есть per-player состояние/эффекты/сущности, реализует этот
 * интерфейс, чтобы {@link SessionManager} мог разом всё снять при возврате игрока в лобби
 * или по команде {@code /warriors reset}.
 *
 * <p>{@code resetPlayer} должен быть идемпотентным и безопасным к вызову вне игры.
 */
public interface KitResettable {
    void resetPlayer(Player player);
}
