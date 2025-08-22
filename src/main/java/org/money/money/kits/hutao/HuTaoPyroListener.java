package org.money.money.kits.hutao;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.money.money.combat.ElementalReactions;

import java.util.*;

public final class HuTaoPyroListener implements Listener {

    private final Plugin plugin;
    private final ElementalReactions elemental;

    // PDC keys
    private final NamespacedKey KEY_HOMA;       // меч «Staff of Homa» (тот же ключ, что и в твоём инвиз-классе!)
    private final NamespacedKey KEY_PYRO_ITEM;  // оранжевый краситель «Pyro Status»

    private static final String TAG_PYRO = "Pyro";

    // тайминги
    private static final int PYRO_DURATION_TICKS   = 20 * 10;  // 10c эффект вокруг игрока
    private static final int PYRO_COOLDOWN_TICKS   = 20 * 60;  // 60c вернуть краситель
    private static final int IGNITE_TICKS          = 20;       // ~1c поджигания
    private static final int MONITOR_STEP_TICKS    = 5;        // шаг мониторинга горения
    private static final int MONITOR_MAX_STEPS     = 40;       // максимум ~10c мониторинга (подстраховка)

    // активные статусы
    private final Set<UUID> pyroActive = new HashSet<>();
    private final Map<UUID, BukkitTask> particleTasks = new HashMap<>();

    public HuTaoPyroListener(Plugin plugin, ElementalReactions elemental) {
        this.plugin = plugin;
        this.elemental = elemental;
        this.KEY_HOMA      = new NamespacedKey(plugin, "hutao_homa");
        this.KEY_PYRO_ITEM = new NamespacedKey(plugin, "hutao_pyro_status");
    }

    /* ===================== Выдача предмета ===================== */

    /** Дай это из твоего /kitgive, например под сабом "hutao pyro". */
    public ItemStack makePyroStatusDye() {
        ItemStack it = new ItemStack(Material.ORANGE_DYE);
        ItemMeta im = it.getItemMeta();
        im.setDisplayName("§6Pyro Status");
        im.setLore(java.util.List.of("§7Right-click to empower Homa with fire"));
        im.getPersistentDataContainer().set(KEY_PYRO_ITEM, PersistentDataType.BYTE, (byte)1);
        im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(im);
        return it;
    }

    /* ===================== Активация ПКМ ===================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.MONITOR)
    public void onUsePyro(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!isPyroItem(hand)) return;

        // не даём ванилле что-то делать и гарантируем клик «в воздух»
        e.setUseItemInHand(Event.Result.DENY);
        e.setCancelled(true);

        // съедаем 1 краситель
        if (hand.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
        else hand.setAmount(hand.getAmount() - 1);

        // активируем «пиромод»
        enablePyro(p);

        // вернём предмет через 60с
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            p.getInventory().addItem(makePyroStatusDye());
            p.sendMessage("§6Pyro Status§7 is ready again!");
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.6f);
        }, PYRO_COOLDOWN_TICKS);
    }

    /* ===================== Бонус: поджог при ударе Хомой ===================== */

    @EventHandler(ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (!(e.getEntity() instanceof LivingEntity le)) return;
        if (!pyroActive.contains(p.getUniqueId())) return;
        if (!isHoma(p.getInventory().getItemInMainHand())) return;

        double vanilla = e.getDamage();          // что насчитал Minecraft
        double bonus   = 0.0;                    // если хочешь, добавляй плоский элементальный бонус
        double total   = vanilla + bonus;

        double fin = elemental.applyOnTotalDamage(le, total,
                ElementalReactions.Element.PYRO,
                40, true);

        e.setDamage(fin);                        // важно: не le.damage(...), иначе задвоишь
        le.setFireTicks(Math.max(le.getFireTicks(), 20));
    }


    /* ===================== Внутрянка ===================== */

    private boolean isPyroItem(ItemStack it) {
        return it != null && it.getType() == Material.ORANGE_DYE
                && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(KEY_PYRO_ITEM, PersistentDataType.BYTE);
    }

    private boolean isHoma(ItemStack it) {
        return it != null && it.getType() == Material.DIAMOND_SWORD
                && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(KEY_HOMA, PersistentDataType.BYTE);
    }

    private void enablePyro(Player p) {
        UUID id = p.getUniqueId();
        if (!pyroActive.add(id)) return; // уже активно

        // 10 секунд огненные/оранжевые партиклы вокруг
        startPyroParticles(p);

        // звук «включения»
        p.playSound(p.getLocation(), Sound.ITEM_FIRECHARGE_USE, 0.7f, 1.1f);

        // авто-выключение через 10с
        Bukkit.getScheduler().runTaskLater(plugin, () -> disablePyro(p), PYRO_DURATION_TICKS);
    }

    private void disablePyro(Player p) {
        UUID id = p.getUniqueId();
        if (!pyroActive.remove(id)) return;
        Optional.ofNullable(particleTasks.remove(id)).ifPresent(BukkitTask::cancel);
        // мягкий звук окончания
        if (p.isOnline()) p.playSound(p.getLocation(), Sound.BLOCK_CANDLE_EXTINGUISH, 0.5f, 1.2f);
    }

    private void startPyroParticles(Player p) {
        UUID id = p.getUniqueId();
        Optional.ofNullable(particleTasks.remove(id)).ifPresent(BukkitTask::cancel);

        Particle.DustOptions dust1 = new Particle.DustOptions(org.bukkit.Color.fromRGB(255, 120, 40), 1.0f);
        Particle.DustOptions dust2 = new Particle.DustOptions(org.bukkit.Color.fromRGB(255, 80, 10), 1.0f);

        BukkitTask t = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline() || !pyroActive.contains(id)) { Optional.ofNullable(particleTasks.remove(id)).ifPresent(BukkitTask::cancel); return; }
            Location base = p.getLocation().add(0, 1.1, 0);

            for (int i = 0; i < 10; i++) {
                double ang = Math.random() * Math.PI * 2;
                double r = 0.35 + Math.random() * 0.25;
                double y = (Math.random() - 0.5) * 0.35;
                Location spot = base.clone().add(Math.cos(ang) * r, y, Math.sin(ang) * r);

                World w = p.getWorld();
                w.spawnParticle(Particle.FLAME, spot, 1, 0,0,0, 0.01);
                w.spawnParticle(Particle.DUST, spot, 1, 0,0,0, 0, (Math.random() < 0.5 ? dust1 : dust2));
                if (Math.random() < 0.1) w.spawnParticle(Particle.LAVA, spot, 1, 0,0,0, 0.01);
            }
        }, 0L, 2L);

        particleTasks.put(id, t);
    }

    private void monitorPyroTagUntilExtinguished(LivingEntity le) {
        final UUID id = le.getUniqueId();
        final int[] steps = {0};
        BukkitTask[] holder = new BukkitTask[1];

        holder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            steps[0]++;
            if (!le.isValid() || le.isDead() || le.getFireTicks() <= 0 || steps[0] > MONITOR_MAX_STEPS) {
                le.removeScoreboardTag(TAG_PYRO);
                if (holder[0] != null) holder[0].cancel();
                return;
            }
        }, MONITOR_STEP_TICKS, MONITOR_STEP_TICKS);
    }

    /* ======= дружеский-огонь (команды) ======= */
    private boolean isTeammate(Player owner, LivingEntity target) {
        if (!(target instanceof Player other)) return false;
        Team tOwner = teamOf(owner, owner.getScoreboard());
        if (tOwner == null) tOwner = teamOf(owner, Bukkit.getScoreboardManager().getMainScoreboard());
        Team tOther = teamOf(other, other.getScoreboard());
        if (tOther == null) tOther = teamOf(other, Bukkit.getScoreboardManager().getMainScoreboard());
        return tOwner != null && tOwner.equals(tOther);
    }
    private Team teamOf(Player p, Scoreboard sb) {
        if (sb == null) return null;
        return sb.getEntryTeam(p.getName());
    }
}
