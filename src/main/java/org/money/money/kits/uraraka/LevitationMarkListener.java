package org.money.money.kits.uraraka;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.TimeUnit;

public final class LevitationMarkListener implements Listener {

    // тайминги / уровни
    private static final long  COOLDOWN_MS          = 120_000L;   // 2 мин – возврат предмета
    private static final long  MARK_DURATION_MS     = 60_000L;    // 1 мин метка
    private static final int   CURSE_TOTAL_TICKS    = 20 * 10;    // ~10с общее время «проклятия»
    private static final int   FIRST_BURST_TICKS    = 20 * 2;     // 2с левитации
    private static final int   GAP_TICKS            = 6;          // 0.3с пауза
    private static final int   LOOP_BURST_TICKS     = 20;         // далее по 1с
    private static final int   CURSE_AMPLIFIER      = 1;          // Levitation II (amp=1)
    private static final int   OWNER_HARD_LIFT_AMP  = 29;         // «30 ур.» (amp=29)
    private static final int   OWNER_HARD_LIFT_TK   = 20;         // 1с

    private final Plugin plugin;

    // предмет
    private final NamespacedKey KEY_ITEM;

    // «заряженные» владельцы (ПКМ → ждём их следующий удар по цели, время не ограничиваем)
    private final Set<UUID> armed = new HashSet<>();

    // кулдауны (когда вернётся предмет)
    private final Map<UUID, Long> cooldownUntilMs = new HashMap<>();

    public LevitationMarkListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_ITEM = new NamespacedKey(plugin, "levitation_mark");
    }

    /* ======================= Item ======================= */

    public ItemStack makeLevitationMarkDye() {
        ItemStack it = new ItemStack(Material.RED_DYE);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Levitation Mark"));
        im.getPersistentDataContainer().set(KEY_ITEM, PersistentDataType.BYTE, (byte)1);
        it.setItemMeta(im);
        return it;
    }
    // Владелец выбрал цель ударом — ставим метку на 60с
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onOwnerPickTarget(EntityDamageByEntityEvent e) {
        // кого ударили — хотим только игроков (или поставь LivingEntity, если нужно на всех)
        if (!(e.getEntity() instanceof Player victim)) return;

        // кто ударил
        Player owner = damagerAsPlayer(e.getDamager());
        if (owner == null) return;

        UUID ownerId = owner.getUniqueId();
        if (!armed.contains(ownerId)) return; // не «заряжён» => ничего не делаем

        // один раз из «armed» выходим
        armed.remove(ownerId);

        // ставим метку на минуту (1200 тиков)
        putMark(ownerId, victim.getUniqueId(), 20L * 60);

        // немного фидбэка
        owner.playSound(owner.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.4f);
        owner.sendMessage(Component.text(
                "Levitation Mark: цель " + victim.getName() + " отмечена на 60с.", NamedTextColor.GREEN));
    }

    private boolean isMarkItem(ItemStack it) {
        if (it == null || it.getType() != Material.RED_DYE || !it.hasItemMeta()) return false;
        var im = it.getItemMeta();
        if (im.getPersistentDataContainer().has(KEY_ITEM, PersistentDataType.BYTE)) return true;
        return Component.text("Levitation Mark").equals(im.displayName());
    }

    /* ======================= Use (ПКМ) ======================= */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!isMarkItem(hand)) return;

        // убрать один предмет сразу (без блокировки «кд»)
        int amt = hand.getAmount();
        if (amt <= 1) p.getInventory().setItemInMainHand(null);
        else hand.setAmount(amt - 1);

        armed.add(p.getUniqueId());
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 1.2f);
        p.sendMessage(Component.text("Levitation Mark: выбери цель ударом.", NamedTextColor.AQUA));

        // планируем возврат предмета через 2 мин (real-time)
        long backAt = System.currentTimeMillis() + COOLDOWN_MS;
        cooldownUntilMs.put(p.getUniqueId(), backAt);

        Bukkit.getAsyncScheduler().runDelayed(plugin, task ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player pl = Bukkit.getPlayer(p.getUniqueId());
                    if (pl != null && pl.isOnline()) {
                        // не выдаём второй, если уже где-то появился
                        if (!playerHas(pl)) {
                            giveToHandOrInv(pl, makeLevitationMarkDye());
                            pl.playSound(pl.getLocation(), Sound.UI_TOAST_IN, 0.7f, 1.2f);
                            pl.sendMessage(Component.text("Levitation Mark восстановлена.", NamedTextColor.GREEN));
                        }
                    }
                }), COOLDOWN_MS, TimeUnit.MILLISECONDS);
    }

    /* ======================= Combat hooks ======================= */

    // === В onDamage по отмеченной цели ДО/ВМЕСТО обычного применения "проклятия" ===
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onDamageMarked(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof LivingEntity victim)) return;

        LivingEntity attacker = null;
        if (e.getDamager() instanceof LivingEntity le) attacker = le;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof LivingEntity le) attacker = le;
        if (attacker == null) return;

        UUID markedId = victim.getUniqueId();
        MarkData md = marks.get(markedId);
        if (md == null || md.isExpired()) { marks.remove(markedId); return; }

        // метка срабатывает один раз — снимаем её
        clearMark(markedId);

        UUID ownerId = md.ownerId;

        // Если проклятие прилетает владельцу (он сам ударил отмеченную цель) —
        // даём ОДИН РАЗ левитацию 1с уровнем 30 и не запускаем цикл.
        if (attacker.getUniqueId().equals(ownerId)) {
            applyOwnerOneShotLevitation(attacker);
            return;
        }

        // Иначе — обычное «проклятие» на атакующего (2с, затем 0.3с пауза и 1с) ~10с суммарно
        startLevitationCurse(attacker);
    }
    private void applyOwnerOneShotLevitation(LivingEntity owner) {
        owner.addPotionEffect(new PotionEffect(
                PotionEffectType.LEVITATION,
                20,           // 1 сек
                29,           // amplifier 29 = 30 уровень
                false, true, true
        ));
        try {
            var l = owner.getLocation().add(0, 1.0, 0);
            owner.getWorld().spawnParticle(Particle.END_ROD, l, 12, 0.4, 0.5, 0.4, 0.01);
            owner.getWorld().playSound(l, Sound.ITEM_TOTEM_USE, 0.7f, 1.8f);
        } catch (Throwable ignored) {}
    }
    // ====== ХРАНИЛИЩЕ МЕТОК ======
    private static final class MarkData {
        final UUID ownerId;      // кто поставил метку
        final long expireAtMs;   // когда истекает (real-time)
        MarkData(UUID ownerId, long expireAtMs) {
            this.ownerId = ownerId; this.expireAtMs = expireAtMs;
        }
        boolean isExpired() { return System.currentTimeMillis() >= expireAtMs; }
    }

    // targetId (у кого метка) -> данные метки
    private final Map<UUID, MarkData> marks = new HashMap<>();

    private boolean hasActiveMark(UUID targetId) {
        MarkData md = marks.get(targetId);
        if (md == null) return false;
        if (md.isExpired()) { marks.remove(targetId); return false; }
        return true;
    }

    private void clearMark(UUID targetId) {
        marks.remove(targetId);
    }

    /** Поставить метку на target на durationTicks (тик-время). */
    private void putMark(UUID ownerId, UUID targetId, long durationTicks) {
        long expire = System.currentTimeMillis() + durationTicks * 50L;
        marks.put(targetId, new MarkData(ownerId, expire));

        // авто-снятие после истечения
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            MarkData md = marks.get(targetId);
            if (md != null && md.ownerId.equals(ownerId) && md.isExpired()) {
                marks.remove(targetId);
            }
        }, durationTicks + 1L);
    }
    private void startLevitationCurse(LivingEntity target) {
        final int FIRST_DUR = 40;  // 2.0s
        final int NEXT_DUR  = 20;  // 1.0s
        final int GAP       = 6;   // 0.3s
        final int TOTAL     = 200; // 10s суммарно (с учётом пауз)

        chainLevitation(target, TOTAL, true, FIRST_DUR, NEXT_DUR, GAP);
    }

    private void chainLevitation(LivingEntity t, int remain, boolean first, int firstDur, int nextDur, int gap) {
        if (remain <= 0 || t == null || t.isDead() || !t.isValid()) return;

        int dur = first ? firstDur : nextDur;
        int amp = 1; // Levitation II (уровень 2) => amplifier 1
        t.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, dur, amp, false, true, true));

        int nextRemain = remain - dur - gap;
        if (nextRemain <= 0) return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (t.isDead() || !t.isValid()) return;
            chainLevitation(t, nextRemain, false, firstDur, nextDur, gap);
        }, dur + gap);
    }


    private Player damagerAsPlayer(Entity d) {
        if (d instanceof Player p) return p;
        if (d instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }
    private LivingEntity damagerAsLiving(Entity d) {
        if (d instanceof LivingEntity le) return le;
        if (d instanceof Projectile proj && proj.getShooter() instanceof LivingEntity le) return le;
        return null;
    }



    /* ======================= Utils / inv ======================= */

    private boolean playerHas(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (isMarkItem(it)) return true;
        }
        return false;
    }
    private void giveToHandOrInv(Player p, ItemStack it) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) p.getInventory().setItemInMainHand(it);
        else {
            Map<Integer, ItemStack> left = p.getInventory().addItem(it);
            if (!left.isEmpty()) p.getWorld().dropItemNaturally(p.getLocation(), left.values().iterator().next());
        }
    }

    /* ======================= Cleanup / Join ======================= */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        armed.remove(e.getPlayer().getUniqueId());
        // метки сами истекают по времени; можно подчистить: marked.remove(id) — но оставим, чтобы другой игрок всё ещё был помечен
    }
}
