package org.money.money.kits.timewalker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.money.money.meta.ClassRegistry;
import org.money.money.session.KitResettable;
import org.money.money.session.KitSession;

import java.util.*;

/**
 * TimeWalker ABILITY — ReRun / GoBack («раздвоение времени»).
 *
 * <p>ПКМ по <b>ReRun</b>: сохраняем её позицию, её HP и открываем окно {@code windowTicks} (20с);
 * предмет превращается в <b>GoBack</b>. Пока окно открыто — запоминаем, сколько урона и кому она
 * нанесла.
 *
 * <ul>
 *   <li><b>ПКМ по GoBack</b> (в течение окна) → откат: телепорт в точку сохранения, её HP
 *       восстанавливается до сохранённого, а всем, кому она успела нанести урон, возвращаем HP.</li>
 *   <li><b>Смертельный урон в течение окна</b> → мы НЕ даём ей умереть (отменяем удар, чтобы
 *       плагин режима не забрал её в спектаторы) и делаем тот же откат автоматически.</li>
 *   <li><b>Окно истекло</b> (GoBack не нажат) → сохранения стираются, отката нет — «она
 *       закоммитила» действия; предупреждаем и уводим способность в полный кулдаун.</li>
 * </ul>
 * Во всех случаях после завершения способность уходит в КД и предмет ReRun возвращается позже
 * (с проверкой {@link KitSession#isInGame}).
 */
public final class TimeWalkerReRunListener implements Listener, KitResettable {

    private final Plugin plugin;
    private final NamespacedKey KEY_KIND;

    private static final String KIND_RERUN  = "rerun";
    private static final String KIND_GOBACK = "goback";
    private static final String NAME_RERUN  = "ReRun";
    private static final String NAME_GOBACK = "GoBack";

    private final Map<UUID, ReRunSave> saves = new HashMap<>();
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();
    private final Map<UUID, Long> noFallUntil = new HashMap<>(); // grace right after a rewind teleport

    public TimeWalkerReRunListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_KIND = new NamespacedKey(plugin, "timewalker_rerun");
    }

    /** Snapshot taken when ReRun is pressed. */
    private static final class ReRunSave {
        final Location loc;
        final double health;
        final Map<UUID, Double> dealt = new HashMap<>(); // enemy -> total damage she dealt
        BukkitTask timeout;
        boolean rewindPending; // a death-triggered rewind is already queued for next tick
        ReRunSave(Location loc, double health) { this.loc = loc; this.health = health; }
    }

    /** Why the rewind fired (drives the feedback message). */
    private enum Cause { MANUAL, DEATH, KILL }

    /* ================== Item ================== */

    /** Base ability item — «ReRun». /kitgive TimeWalker rerun */
    public ItemStack makeReRunItem()  { return item(KIND_RERUN,  NAME_RERUN,  NamedTextColor.AQUA); }
    private ItemStack makeGoBackItem() { return item(KIND_GOBACK, NAME_GOBACK, NamedTextColor.LIGHT_PURPLE); }

    private ItemStack item(String kind, String name, NamedTextColor color) {
        ItemStack it = new ItemStack(Material.RECOVERY_COMPASS);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text(name, color));
        im.getPersistentDataContainer().set(KEY_KIND, PersistentDataType.STRING, kind);
        it.setItemMeta(im);
        return it;
    }

    private String kindOf(ItemStack it) {
        if (it == null || it.getType() != Material.RECOVERY_COMPASS || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(KEY_KIND, PersistentDataType.STRING);
    }

    /* ================== Interact ================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        String kind = kindOf(p.getInventory().getItemInMainHand());
        if (kind == null) return;
        e.setCancelled(true);
        if (!KitSession.isInGame(p)) return;

        if (KIND_RERUN.equals(kind)) {
            activate(p);
        } else if (KIND_GOBACK.equals(kind)) {
            ReRunSave save = saves.remove(p.getUniqueId());
            if (save == null) { // desynced GoBack item without a live window — just reset it
                removeKindItem(p, KIND_GOBACK);
                giveToHandOrInv(p, makeReRunItem());
                return;
            }
            if (save.timeout != null) save.timeout.cancel();
            performGoBack(p, save, Cause.MANUAL);
        }
    }

    private void activate(Player p) {
        UUID id = p.getUniqueId();
        if (saves.containsKey(id)) return; // already splitting time
        long now = System.currentTimeMillis();
        Long until = cooldownUntil.get(id);
        if (until != null && now < until) {
            long left = (until - now + 999) / 1000;
            p.sendActionBar(Component.text("ReRun: " + left + " sec", NamedTextColor.RED));
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.8f);
            return;
        }

        int slot = p.getInventory().getHeldItemSlot();
        ReRunSave save = new ReRunSave(p.getLocation().clone(), p.getHealth());
        saves.put(id, save);
        p.getInventory().setItem(slot, makeGoBackItem());

        int windowTicks = ClassRegistry.numInt("timewalker", "rerun", "windowTicks", 400);
        save.timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> expire(id), Math.max(20L, windowTicks));

        // "time splits into two"
        World w = p.getWorld();
        Location o = p.getLocation();
        w.playSound(o, Sound.BLOCK_BEACON_POWER_SELECT, 0.9f, 1.7f);
        w.playSound(o, Sound.BLOCK_CONDUIT_ACTIVATE, 0.7f, 1.5f);
        w.spawnParticle(Particle.REVERSE_PORTAL, o.clone().add(0, 1.0, 0), 40, 0.4, 0.7, 0.4, 0.06);
        w.spawnParticle(Particle.FLASH, o.clone().add(0, 1.0, 0), 1, 0, 0, 0, 0);
        int sec = Math.max(1, Math.round(windowTicks / 20f));
        p.sendActionBar(Component.text("Time split — GoBack within " + sec + "s", NamedTextColor.AQUA));
    }

    /* ================== Recording her outgoing damage ================== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onDeal(EntityDamageByEntityEvent e) {
        if (saves.isEmpty()) return;
        UUID attackerId = attackerUuid(e.getDamager());
        if (attackerId == null) return;
        ReRunSave save = saves.get(attackerId);
        if (save == null) return;
        if (!(e.getEntity() instanceof Player victim)) return; // HP restore only makes sense for players
        if (victim.getUniqueId().equals(attackerId)) return;
        double dmg = e.getFinalDamage();
        if (dmg <= 0) return;
        save.dealt.merge(victim.getUniqueId(), dmg, Double::sum);
    }

    /* ================== Death prevention -> auto rewind ================== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onLethal(EntityDamageEvent e) {
        if (saves.isEmpty()) return;
        if (!(e.getEntity() instanceof Player p)) return;
        ReRunSave save = saves.get(p.getUniqueId());
        if (save == null) return;
        // Would this blow drop her to 0? Then never let her die — the mode plugin must not spec her.
        // Cancel EVERY fatal hit (e.g. repeated void ticks) so she can't slip through before the rewind
        // lands, but queue the actual rewind only once, next tick (safer than teleporting mid-event).
        if (e.getFinalDamage() >= p.getHealth()) {
            e.setCancelled(true);
            if (!save.rewindPending) {
                save.rewindPending = true;
                if (save.timeout != null) save.timeout.cancel();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    ReRunSave cur = saves.remove(p.getUniqueId());
                    if (cur != null && p.isOnline()) performGoBack(p, cur, Cause.DEATH);
                });
            }
        }
    }

    /* ================== Core: go back / expire ================== */

    private void performGoBack(Player p, ReRunSave save, Cause cause) {
        // 1) teleport her back to the saved spot — kill any fall so the drop she was in doesn't splat her
        p.setFireTicks(0);
        p.setFallDistance(0f);
        if (save.loc.getWorld() != null) p.teleport(save.loc);
        p.setFallDistance(0f);
        noFallUntil.put(p.getUniqueId(), System.currentTimeMillis() + 1500L);

        // 2) restore HER hp to the saved value
        double max = maxHealth(p);
        p.setHealth(Math.max(0.5, Math.min(max, save.health)));

        // 3) give back the HP she took from others
        for (Map.Entry<UUID, Double> en : save.dealt.entrySet()) {
            Player victim = Bukkit.getPlayer(en.getKey());
            if (victim == null || !victim.isOnline() || victim.isDead()
                    || victim.getGameMode() == GameMode.SPECTATOR) continue; // don't heal a corpse/spectator (e.g. the one she killed)
            double vmax = maxHealth(victim);
            victim.setHealth(Math.min(vmax, victim.getHealth() + Math.max(0.0, en.getValue())));
            Location vl = victim.getLocation().add(0, 1.0, 0);
            victim.getWorld().spawnParticle(Particle.HEART, vl, 6, 0.3, 0.4, 0.3, 0.0);
            victim.getWorld().spawnParticle(Particle.REVERSE_PORTAL, vl, 10, 0.3, 0.4, 0.3, 0.03);
            victim.getWorld().playSound(victim.getLocation(), Sound.BLOCK_BELL_RESONATE, 0.6f, 1.4f);
        }

        // 4) consume GoBack, start cooldown + queued ReRun return
        removeKindItem(p, KIND_GOBACK);
        startCooldownAndReturn(p);

        // 5) fx + message
        World w = p.getWorld();
        Location o = p.getLocation();
        w.playSound(o, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.2f);
        w.playSound(o, Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 1.4f);
        w.playSound(o, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 1.1f);
        w.spawnParticle(Particle.REVERSE_PORTAL, o.clone().add(0, 1.0, 0), 46, 0.35, 0.7, 0.35, 0.08);
        w.spawnParticle(Particle.FLASH, o.clone().add(0, 1.0, 0), 1, 0, 0, 0, 0);
        p.sendActionBar(switch (cause) {
            case DEATH  -> Component.text("Death rewound — you never happened", NamedTextColor.LIGHT_PURPLE);
            case KILL   -> Component.text("Kill secured — snapped back", NamedTextColor.LIGHT_PURPLE);
            case MANUAL -> Component.text("Rewound to the split", NamedTextColor.LIGHT_PURPLE);
        });
    }

    private void expire(UUID id) {
        ReRunSave save = saves.remove(id);
        if (save == null) return;
        // committed: no teleport, no HP restore — reality stays
        Player p = Bukkit.getPlayer(id);
        if (p != null && p.isOnline()) {
            removeKindItem(p, KIND_GOBACK);
            startCooldownAndReturn(p);
            p.sendActionBar(Component.text("Time committed — no going back", NamedTextColor.YELLOW));
            p.playSound(p.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.7f, 0.8f);
        }
    }

    private void startCooldownAndReturn(Player p) {
        UUID id = p.getUniqueId();
        int cdSec = ClassRegistry.seconds("timewalker", "rerun", 45);
        cooldownUntil.put(id, System.currentTimeMillis() + cdSec * 1000L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            cooldownUntil.remove(id);
            Player pl = Bukkit.getPlayer(id);
            if (pl == null || !pl.isOnline() || !KitSession.isInGame(pl)) return;
            if (hasKind(pl, KIND_RERUN, KIND_GOBACK)) return; // already holds one somewhere
            giveToHandOrInv(pl, makeReRunItem());
            pl.playSound(pl.getLocation(), Sound.UI_TOAST_IN, 0.7f, 1.2f);
            pl.sendActionBar(Component.text("ReRun ready", NamedTextColor.GREEN));
        }, cdSec * 20L);
    }

    /* ================== Helpers ================== */

    private UUID attackerUuid(Entity damager) {
        if (damager instanceof Player p) return p.getUniqueId();
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player sp) return sp.getUniqueId();
        return null;
    }

    private double maxHealth(Player p) {
        var attr = p.getAttribute(Attribute.MAX_HEALTH);
        return attr != null ? attr.getValue() : 20.0;
    }

    private void removeKindItem(Player p, String kind) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (kind.equals(kindOf(inv.getItem(i)))) { inv.setItem(i, null); return; }
        }
    }

    private boolean hasKind(Player p, String... kinds) {
        Set<String> set = Set.of(kinds);
        for (ItemStack it : p.getInventory().getContents()) {
            String k = kindOf(it);
            if (k != null && set.contains(k)) return true;
        }
        return false;
    }

    private void giveToHandOrInv(Player p, ItemStack it) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            p.getInventory().setItemInMainHand(it);
        } else {
            Map<Integer, ItemStack> left = p.getInventory().addItem(it);
            if (!left.isEmpty()) p.getWorld().dropItemNaturally(p.getLocation(), left.values().iterator().next());
        }
    }

    /* ================== Cleanup ================== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        clear(e.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player dead = e.getEntity();
        // If she somehow died mid-window (untracked source), just drop her save — nothing to rewind.
        clear(dead.getUniqueId());

        // If the KILLER is mid-ReRun, her kill is now secured → snap her back to the split at once.
        // The killed victim stays dead (not revived; skipped in the heal loop); other hurt victims
        // still get healed by the GoBack. She returns to the split with her saved HP.
        Player killer = dead.getKiller();
        if (killer == null || killer.getUniqueId().equals(dead.getUniqueId())) return;
        ReRunSave save = saves.remove(killer.getUniqueId());
        if (save == null) return;
        if (save.timeout != null) save.timeout.cancel();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (killer.isOnline()) performGoBack(killer, save, Cause.KILL);
        });
    }

    /** Swallow the fall damage from the drop she was yanked out of by a rewind (short grace). */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFallGrace(EntityDamageEvent e) {
        if (noFallUntil.isEmpty()) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(e.getEntity() instanceof Player p)) return;
        Long until = noFallUntil.get(p.getUniqueId());
        if (until != null && System.currentTimeMillis() < until) e.setCancelled(true);
    }

    private void clear(UUID id) {
        ReRunSave save = saves.remove(id);
        if (save != null && save.timeout != null) save.timeout.cancel();
        cooldownUntil.remove(id);
        noFallUntil.remove(id);
    }

    @Override
    public void resetPlayer(Player player) {
        if (player == null) return;
        clear(player.getUniqueId());
    }
}
