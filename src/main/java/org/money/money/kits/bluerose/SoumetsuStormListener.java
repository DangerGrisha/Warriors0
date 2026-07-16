package org.money.money.kits.bluerose;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import org.money.money.session.KitSession;

/**
 * Ability — «Kamisato Art: Soumetsu» (морозная буря Аяки), ОТДЕЛЬНАЯ способность.
 *
 * <p>ПКМ предметом-бурей → вперёд летит буря (см. {@link BlueRoseFrostStorm}): морозит блоки в
 * синий лёд, наносит урон врагам; лёд держится 60с и откатывается, стоящих на нём замедляет.
 * Кулдаун 5 минут (soumetsu.cooldownTicks). Гарден-ульта осталась отдельной способностью.
 */
public final class SoumetsuStormListener implements Listener {

    private final BlueRoseGuardianManager m;

    public SoumetsuStormListener(BlueRoseGuardianManager m) { this.m = m; }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!m.isStorm(p.getInventory().getItemInMainHand())) return;
        e.setCancelled(true);
        if (!KitSession.isInGame(p)) return;
        if (m.isOnCooldown(p, "soumetsu", 6000, "Soumetsu")) return; // 6000т = 5 мин

        m.triggerCooldown(p, "soumetsu", 6000, "Soumetsu");
        m.castFrostStorm(p);
    }
}
