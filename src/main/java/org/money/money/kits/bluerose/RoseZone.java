package org.money.money.kits.bluerose;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

/**
 * Активная зона Blue Rose Guardian: Ward, Garden или Petal Trail.
 *
 * <p>Держит свой повторяющийся {@link BukkitTask} и (для Ward/Garden) сущность-якорь
 * (невидимый ArmorStand с моделью розы). {@link #cancel()} идемпотентно гасит и то, и другое,
 * чтобы менеджер мог разом снять всё при reset/death/quit/конце игры — без orphan-сущностей.
 */
final class RoseZone {

    enum Kind { WARD, GARDEN, TRAIL, ROSEBIND }

    final Kind kind;
    final UUID owner;
    final long createdAtMs;

    // Только для GARDEN (проверка членства при death-save) и валидности Ward.
    Location center;
    double radius;
    int deathSavesLeft;

    // Только для ROSEBIND в режиме «Поиск»: залоченная цель преследования.
    UUID huntTarget;

    private BukkitTask task;
    private Entity anchor;
    private boolean cancelled;

    RoseZone(Kind kind, UUID owner) {
        this.kind = kind;
        this.owner = owner;
        this.createdAtMs = System.currentTimeMillis();
    }

    void setTask(BukkitTask task) { this.task = task; }
    void setAnchor(Entity anchor) { this.anchor = anchor; }

    boolean isCancelled() { return cancelled; }

    /** Переместить зону: обновить центр и телепортнуть якорь (для управляемой Rosebind-розы). */
    void relocate(Location loc) {
        if (cancelled || loc == null) return;
        this.center = loc;
        if (anchor != null) {
            try { anchor.teleport(loc); } catch (Throwable ignored) {}
        }
    }

    /** Снять задачу + удалить якорь. Безопасно вызывать повторно. */
    void cancel() {
        if (cancelled) return;
        cancelled = true;
        if (task != null) {
            try { task.cancel(); } catch (Throwable ignored) {}
            task = null;
        }
        if (anchor != null) {
            try { anchor.remove(); } catch (Throwable ignored) {}
            anchor = null;
        }
    }
}
