package org.money.money.kits.bluerose;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Учёт «Семян Розы» — центральная механика Blue Rose Guardian.
 *
 * <p>На цель приходится максимум одно вражеское и одно союзное семя (maxSeedsPerTarget=1):
 * повторное наложение обновляет владельца/длительность (и позволяет вызвавшему «раскрыть» семя).
 * Хранение по UUID цели; владелец запоминается, чтобы корректно чистить при выходе Guardian'а.
 *
 * <p>Никакого Bukkit-таска внутри нет — {@link #tick()} вызывает глобальный тикер менеджера,
 * там же чистятся истёкшие семена и рисуются метки. Союзники никогда не получают негативных
 * эффектов от семени, враги — лечения/щита.
 */
public final class RoseSeedService {

    // target UUID -> seed
    private final Map<UUID, RoseSeed> enemySeeds = new HashMap<>();
    private final Map<UUID, RoseSeed> allySeeds  = new HashMap<>();

    // анти-спам наложения семени зонами: "ownerUUID|targetUUID" -> untilMs
    private final Map<String, Long> applyCooldown = new HashMap<>();

    /* ===================== Враг ===================== */

    /** Есть ли у цели АКТИВНОЕ вражеское семя (с авто-истечением). */
    public boolean hasEnemySeed(UUID target) {
        RoseSeed s = enemySeeds.get(target);
        if (s == null) return false;
        if (s.expired(System.currentTimeMillis())) { enemySeeds.remove(target); return false; }
        return true;
    }

    /** Наложить/обновить вражеское семя. @return true, если семя УЖЕ было (повод «раскрыть»). */
    public boolean applyEnemySeed(Player owner, Player target, int durationTicks) {
        boolean already = hasEnemySeed(target.getUniqueId());
        long until = System.currentTimeMillis() + durationTicks * 50L;
        enemySeeds.put(target.getUniqueId(), new RoseSeed(owner.getUniqueId(), false, until));
        return already;
    }

    /** Снять вражеское семя (раскрытие). @return снятое семя или null. */
    public RoseSeed consumeEnemySeed(UUID target) {
        RoseSeed s = enemySeeds.remove(target);
        if (s == null || s.expired(System.currentTimeMillis())) return null;
        return s;
    }

    /* ===================== Союзник ===================== */

    public boolean hasAllySeed(UUID target) {
        RoseSeed s = allySeeds.get(target);
        if (s == null) return false;
        if (s.expired(System.currentTimeMillis())) { allySeeds.remove(target); return false; }
        return true;
    }

    public void applyAllySeed(Player owner, Player target, int durationTicks) {
        long until = System.currentTimeMillis() + durationTicks * 50L;
        allySeeds.put(target.getUniqueId(), new RoseSeed(owner.getUniqueId(), true, until));
    }

    /** Как {@link #applyAllySeed}, но без истечения по времени (снимается только вручную). */
    public void applyAllySeedPermanent(Player owner, Player target) {
        allySeeds.put(target.getUniqueId(), new RoseSeed(owner.getUniqueId(), true, Long.MAX_VALUE));
    }

    public void removeAllySeed(UUID target) {
        allySeeds.remove(target);
    }

    /* ===================== Анти-спам наложения ===================== */

    /** true (и ставит кд), если зоне разрешено наложить семя на эту цель сейчас. */
    public boolean tryApplyCooldown(UUID owner, UUID target, int cooldownTicks) {
        String key = owner + "|" + target;
        long now = System.currentTimeMillis();
        Long until = applyCooldown.get(key);
        if (until != null && now < until) return false;
        applyCooldown.put(key, now + cooldownTicks * 50L);
        return true;
    }

    /* ===================== Очистка ===================== */

    /** Снять все семена, относящиеся к цели (на выходе/смерти игрока). */
    public void clearTarget(UUID target) {
        enemySeeds.remove(target);
        allySeeds.remove(target);
    }

    /** Снять все семена и кд этого Guardian'а (когда он уходит/сбрасывается). */
    public void clearOwner(UUID owner) {
        enemySeeds.values().removeIf(s -> s.owner.equals(owner));
        allySeeds.values().removeIf(s -> s.owner.equals(owner));
        applyCooldown.keySet().removeIf(k -> k.startsWith(owner + "|"));
    }

    public void clearAll() {
        enemySeeds.clear();
        allySeeds.clear();
        applyCooldown.clear();
    }

    /* ===================== Тик: истечение + визуал ===================== */

    /** Чистит истёкшие семена и рисует метки над живыми целями. Вызывается глобальным тикером. */
    public void tick() {
        long now = System.currentTimeMillis();
        tickMap(enemySeeds, now, false);
        tickMap(allySeeds, now, true);
        applyCooldown.values().removeIf(until -> now >= until);
    }

    private void tickMap(Map<UUID, RoseSeed> map, long now, boolean ally) {
        Iterator<Map.Entry<UUID, RoseSeed>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, RoseSeed> e = it.next();
            RoseSeed s = e.getValue();
            if (s.expired(now)) { it.remove(); continue; }
            Player target = Bukkit.getPlayer(e.getKey());
            if (target == null || !target.isOnline() || target.isDead()) {
                // оффлайн/мёртвых не рисуем, но семя держим до истечения (вернётся — увидит)
                continue;
            }
            BlueRoseVisualUtil.seedMark(target.getEyeLocation(), ally);
            if (ally) BlueRoseVisualUtil.bodyPetals(target.getLocation());
        }
    }
}
