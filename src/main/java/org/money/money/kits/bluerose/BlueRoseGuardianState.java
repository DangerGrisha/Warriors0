package org.money.money.kits.bluerose;

import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-Guardian состояние: только кулдауны способностей и их actionbar-таймеры.
 *
 * <p>Всё «мировое» (семена, активные зоны, Heritage-розы, root/invuln) живёт в
 * {@link BlueRoseGuardianManager}, чтобы очистка была в одной точке и без дублирования.
 */
final class BlueRoseGuardianState {

    /** abilityKey -> момент последнего использования (System.currentTimeMillis). */
    final Map<String, Long> lastUseMs = new HashMap<>();

    /** abilityKey -> задача обратного отсчёта в actionbar. */
    final Map<String, BukkitTask> cdTasks = new HashMap<>();
}
