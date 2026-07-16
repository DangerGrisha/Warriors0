package org.money.money.kits.bluerose;

import java.util.UUID;

/**
 * Одно «Семя Розы» (Rose Seed) на игроке.
 *
 * <ul>
 *   <li><b>Враг:</b> опасная метка — усиливает контроль, раскрывается в slow/root/шипы.</li>
 *   <li><b>Союзник:</b> защитная метка — усиливает лечение от роз (и ставится Heritage Bloom'ом).</li>
 * </ul>
 *
 * Хранит владельца-Guardian'а и время истечения (real-time, чтобы переживать лаги тиков).
 */
public final class RoseSeed {

    public final UUID owner;       // Guardian, поставивший семя
    public final boolean ally;     // true = защитное (на союзнике), false = на враге
    public long expiresAtMs;       // когда истекает (System.currentTimeMillis)

    public RoseSeed(UUID owner, boolean ally, long expiresAtMs) {
        this.owner = owner;
        this.ally = ally;
        this.expiresAtMs = expiresAtMs;
    }

    public boolean expired(long nowMs) {
        return nowMs >= expiresAtMs;
    }
}
