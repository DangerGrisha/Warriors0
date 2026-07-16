package org.money.money.kits.bluerose;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;

import org.money.money.session.KitSession;

import java.util.List;

/**
 * Ability 4 — Heritage Bloom / Наследственное Цветение.
 *
 * <p>ПКМ по союзнику (или по воздуху → ближайший союзник перед взглядом; иначе — на себя, если
 * разрешено и слабее) вешает защитную Голубую Розу: небольшой щит-абсорбция + Rose Seed на
 * союзнике. При падении его здоровья ниже порога роза раскрывается (heal/щит, отбрасывает и
 * замораживает врагов, краткая неуязвимость) — одна сильная страховка. Логика emergency — в
 * {@link BlueRoseGuardianManager#onDamage}.
 */
public final class HeritageBloomListener implements Listener {

    private final BlueRoseGuardianManager m;

    public HeritageBloomListener(BlueRoseGuardianManager m) { this.m = m; }

    /** ПКМ прямо по союзнику. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (!m.isHeritage(p.getInventory().getItemInMainHand())) return;
        if (!(e.getRightClicked() instanceof Player target)) return;
        e.setCancelled(true);
        if (!KitSession.isInGame(p)) return;
        if (m.isOnCooldown(p, "heritage", 1200, "Heritage")) return;

        if (target.getUniqueId().equals(p.getUniqueId())) { castSelfOrReject(p); return; }
        if (!m.isFriendly(p, target)) {
            p.sendActionBar(Component.text("Heritage Bloom only protects allies", NamedTextColor.RED));
            return;
        }
        cast(p, target, false);
    }

    /** ПКМ по воздуху/блоку — ближайший союзник перед взглядом, иначе self-cast (fallback). */
    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!m.isHeritage(p.getInventory().getItemInMainHand())) return;
        e.setCancelled(true);
        if (!KitSession.isInGame(p)) return;
        if (m.isOnCooldown(p, "heritage", 1200, "Heritage")) return;

        Player target = pickAllyInFront(p);
        if (target != null) { cast(p, target, false); return; }
        castSelfOrReject(p);
    }

    private void castSelfOrReject(Player p) {
        if (!m.selfCastEnabled()) {
            p.sendActionBar(Component.text("No ally found for Heritage Bloom", NamedTextColor.RED));
            return;
        }
        cast(p, p, true);
    }

    private Player pickAllyInFront(Player p) {
        double range = m.num("heritage", "targetRange", 10.0);
        Vector look = p.getEyeLocation().getDirection().normalize();
        Player best = null;
        double bestScore = -1;
        for (Player ally : m.alliesIn(p, p.getLocation(), range, false)) {
            if (!ally.isOnline() || ally.isDead()) continue;
            Vector to = ally.getLocation().toVector().subtract(p.getLocation().toVector());
            if (to.lengthSquared() < 1e-6) continue;
            double dot = look.dot(to.normalize());     // насколько «перед взглядом»
            if (dot < 0.5) continue;                   // ~60° конус
            double score = dot - ally.getLocation().distance(p.getLocation()) * 0.05;
            if (score > bestScore) { bestScore = score; best = ally; }
        }
        return best;
    }

    private void cast(Player owner, Player target, boolean selfCast) {
        if (!target.isOnline() || target.isDead()
                || target.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            owner.sendActionBar(Component.text("Invalid Heritage Bloom target", NamedTextColor.RED));
            return;
        }

        m.triggerCooldown(owner, "heritage", 1200, "Heritage");

        int shieldDur = m.numInt("heritage", "shieldDurationTicks", 100);
        double absorption = m.num("heritage", "initialAbsorption", 2.0);
        if (selfCast) absorption *= m.selfCastMultiplier();

        // Роза висит бессрочно; shieldDur — только длительность стартового щита-абсорбции при постановке.
        m.putHeritage(owner, target, selfCast);
        m.grantTempAbsorption(target, absorption, shieldDur);

        Location l = target.getLocation();
        BlueRoseVisualUtil.roseBloom(l.clone().add(0, 1.0, 0));
        BlueRoseVisualUtil.bodyPetals(l);
        BlueRoseVisualUtil.soundBloom(l);

        owner.sendActionBar(Component.text("Heritage Bloom on " + target.getName(), NamedTextColor.AQUA));
        if (!target.getUniqueId().equals(owner.getUniqueId())) {
            target.sendActionBar(Component.text("A Blue Rose guards you", NamedTextColor.AQUA));
        }
    }
}
