package org.money.money.combat;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ElementalReactions {

    public enum Element { PYRO, CRYO, HYDRO, ELECTRO }

    // сейчас пользуем только MULTIPLY
    public record Reaction(double mult) {}
    private record Key(Element atk, Element aura) {}

    private final Plugin plugin;

    private static final class Aura {
        final Element element; BukkitTask removal;
        Aura(Element e, BukkitTask t) { this.element = e; this.removal = t; }
    }
    private final Map<UUID, Aura> auras = new ConcurrentHashMap<>();
    private final Map<Key, Reaction> table = new HashMap<>();

    public ElementalReactions(Plugin plugin) {
        this.plugin = plugin;

        // === ПРАВИЛА (именно как ты описал: «кто был ПЕРВЫМ» важен) ===
        // Cryo -> (потом) Pyro = ×2 ; наоборот = ×1.5
        put(Element.PYRO,  Element.CRYO,  2.0);
        put(Element.CRYO,  Element.PYRO,  1.5);

        // Pyro -> (потом) Hydro = ×2 ; наоборот = ×1.5
        put(Element.HYDRO, Element.PYRO,  2.0);
        put(Element.PYRO,  Element.HYDRO, 1.5);

        // Electro -> (потом) Hydro/Pyro/Cryo = ×1.5 ; наоборот = ×1.3
        put(Element.PYRO,  Element.ELECTRO, 1.5);
        put(Element.CRYO,  Element.ELECTRO, 1.5);
        put(Element.HYDRO, Element.ELECTRO, 1.5);

        put(Element.ELECTRO, Element.PYRO,  1.3);
        put(Element.ELECTRO, Element.CRYO,  1.3);
        put(Element.ELECTRO, Element.HYDRO, 1.3);
    }
    private void put(Element attack, Element aura, double mult) {
        table.put(new Key(attack, aura), new Reaction(mult));
    }

    public Optional<Element> getAura(LivingEntity target) {
        Aura a = auras.get(target.getUniqueId());
        return a == null ? Optional.empty() : Optional.of(a.element);
    }

    /** Снять текущую ауру (и остановить её таймер) + подчистить теги. */
    public void clearAura(LivingEntity target) {
        Aura prev = auras.remove(target.getUniqueId());
        if (prev != null && prev.removal != null) prev.removal.cancel();
        target.removeScoreboardTag("Pyro");
        target.removeScoreboardTag("Cryo");
        target.removeScoreboardTag("Hydro");
        target.removeScoreboardTag("Electro");
    }

    /** Повесить ауру attackElement на ticks (с авто-снятием и scoreboard-тегом). */
    public void setAura(LivingEntity target, Element attackElement, int ticks) {
        clearAura(target);
        switch (attackElement) {
            case PYRO -> target.addScoreboardTag("Pyro");
            case CRYO -> target.addScoreboardTag("Cryo");
            case HYDRO -> target.addScoreboardTag("Hydro");
            case ELECTRO -> target.addScoreboardTag("Electro");
        }
        BukkitTask task = org.bukkit.Bukkit.getScheduler()
                .runTaskLater(plugin, () -> clearAura(target), ticks);
        auras.put(target.getUniqueId(), new Aura(attackElement, task));
    }

    /**
     * Универсальный хелпер:
     * — на вход подаёшь УЖЕ суммарный урон (ваниль + бонусы умения);
     * — если есть подходящая реакция (по текущей АУРЕ цели), умножаем его по таблице;
     * — если consumeOnReact=true — реакция «съедает» обе ауры (ничего нового не вешаем);
     * — иначе (или реакции нет) — вешаем ауру атаки на newAuraTicks.
     */
    public double applyOnTotalDamage(LivingEntity victim,
                                     double totalDamage,
                                     Element attackElement,
                                     int newAuraTicks,
                                     boolean consumeOnReact) {

        Optional<Element> aura = getAura(victim).filter(a -> a != attackElement);
        if (aura.isPresent()) {
            Reaction rx = table.get(new Key(attackElement, aura.get()));
            if (rx != null) {
                playReactionSfx(victim.getLocation().add(0, 1.0, 0), attackElement, aura.get());
                if (consumeOnReact) clearAura(victim);  // «съели» обе
                return totalDamage * rx.mult();
            }
        }
        // реакции нет — просто ставим ауру
        if (newAuraTicks > 0) setAura(victim, attackElement, newAuraTicks);
        return totalDamage;
    }

    /* ==== SFX по парам элементов (минималистично) ==== */
    private void playReactionSfx(Location at, Element atk, Element aura) {
        World w = at.getWorld(); if (w == null) return;

        // Pyro ↔ Cryo (потрескивание/лед)
        if ((atk == Element.PYRO && aura == Element.CRYO) || (atk == Element.CRYO && aura == Element.PYRO)) {
            w.playSound(at, Sound.BLOCK_GLASS_BREAK, 0.9f, 1.6f);
            w.playSound(at, Sound.ITEM_FIRECHARGE_USE, 0.6f, 1.8f);
            w.playSound(at, Sound.BLOCK_ANVIL_LAND, 0.3f, 1.8f);
            return;
        }
        // Pyro ↔ Hydro (шипение пара)
        if ((atk == Element.PYRO && aura == Element.HYDRO) || (atk == Element.HYDRO && aura == Element.PYRO)) {
            w.playSound(at, Sound.BLOCK_FIRE_EXTINGUISH, 0.9f, 1.2f);
            w.playSound(at, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, 0.6f, 1.9f);
            w.playSound(at, Sound.BLOCK_ANVIL_LAND, 0.3f, 1.8f);
            return;
        }
        // Electro ↔ (Pyro/Hydro/Cryo) (разряд/искры)
        if (atk == Element.ELECTRO || aura == Element.ELECTRO) {
            w.playSound(at, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.9f, 1.95f);
            w.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.6f, 1.95f);
            w.playSound(at, Sound.BLOCK_ANVIL_LAND, 0.3f, 1.8f);
        }
    }
}
