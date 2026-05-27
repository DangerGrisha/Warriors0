package org.money.money.kits.naruto;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
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
import org.bukkit.util.Vector;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class NarutoRasenganListener implements Listener {

    /* =================== Константы =================== */

    // КД возврата синего красителя после активации (real-time)
    private static final long COOLDOWN_MS = 25_000L; // 1.5 мин

    // Урон от высоты падения (добавляется к урону ивента удара)
    private static final double BASE_DAMAGE   = 10.0;   // 2 сердца базой
    private static final double PER_BLOCK_DMG = 1.2;   // +1 сердца за 2 блок
    private static final double MAX_DAMAGE    = 70;  // хард-кап доп. урона

    // «Сила» удара (масштаб нокбэка) от высоты падения
    private static final float BASE_POWER      = 2.0f;
    private static final float POWER_PER_BLOCK = 0.2f;
    private static final float MAX_POWER       = 20.0f;

    // «Ядро» должно исчезнуть через 15 секунд + обратный отсчёт за 3/2/1
    private static final long CORE_TTL_TICKS = 20L * 15;
    // Jump Boost III during Rasengan active window
    private static final int RASENGAN_JUMP_TICKS = 20 * 15;
    private static final int RASENGAN_JUMP_AMPLIFIER = 2; // III (zero-based)

    /* =================== Поля =================== */

    // Гард от повторного входа во «взрыв» (на 2 тика)
    private final Set<UUID> blastGuard = new HashSet<>();

    private final Plugin plugin;
    private final NamespacedKey KEY_RASENGAN;       // blue_dye
    private final NamespacedKey KEY_RASENGAN_CORE;  // heart_of_the_sea
    // кто и до какого времени не получает урон от падения (после успешного удара Rasengan'ом)
    private final Map<UUID, Long> noFallUntilMs = new HashMap<>();

    // когда игроку можно вернуть краситель (для onJoin)
    private final Map<UUID, Long> cooldownUntilMs = new HashMap<>();

    // активные таймеры для «ядра»: 3с/2с/1с и финальное удаление
    private final Map<UUID, List<Integer>> coreTimerTasks = new HashMap<>();

    public NarutoRasenganListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_RASENGAN      = new NamespacedKey(plugin, "naruto_rasengan");
        this.KEY_RASENGAN_CORE = new NamespacedKey(plugin, "naruto_rasengan_core");
    }

    /* =================== Items =================== */

    public ItemStack makeRasenganDye() {
        ItemStack it = new ItemStack(Material.BLUE_DYE);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Rasengan"));
        im.getPersistentDataContainer().set(KEY_RASENGAN, PersistentDataType.BYTE, (byte)1);
        it.setItemMeta(im);
        return it;
    }

    private ItemStack makeRasenganCore() {
        ItemStack it = new ItemStack(Material.HEART_OF_THE_SEA);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Rasengan (charged)"));
        im.getPersistentDataContainer().set(KEY_RASENGAN_CORE, PersistentDataType.BYTE, (byte)1);
        it.setItemMeta(im);
        return it;
    }

    private boolean isRasenganDye(ItemStack it) {
        if (it == null || it.getType() != Material.BLUE_DYE || !it.hasItemMeta()) return false;
        ItemMeta im = it.getItemMeta();
        if (im.getPersistentDataContainer().has(KEY_RASENGAN, PersistentDataType.BYTE)) return true;
        return Component.text("Rasengan").equals(im.displayName());
    }

    private boolean isRasenganCore(ItemStack it) {
        if (it == null || it.getType() != Material.HEART_OF_THE_SEA || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(KEY_RASENGAN_CORE, PersistentDataType.BYTE);
    }

    /* =================== Активация (ПКМ по синему красителю) =================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!isRasenganDye(hand)) return;

        // звук «заряда»
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 0.9f, 1.6f);

        // съесть краситель и положить «ядро» в руку
        int amt = hand.getAmount();
        if (amt <= 1) p.getInventory().setItemInMainHand(makeRasenganCore());
        else {
            hand.setAmount(amt - 1);
            p.getInventory().setItemInMainHand(makeRasenganCore());
        }

        // Give mobility window while Rasengan core is active (15s).
        p.addPotionEffect(new PotionEffect(
                PotionEffectType.JUMP_BOOST,
                RASENGAN_JUMP_TICKS,
                RASENGAN_JUMP_AMPLIFIER,
                false, true, true
        ));

        // возврат красителя через 1.5 мин (real-time)
        long backAt = System.currentTimeMillis() + COOLDOWN_MS;
        cooldownUntilMs.put(p.getUniqueId(), backAt);
        Bukkit.getAsyncScheduler().runDelayed(
                plugin,
                task -> Bukkit.getScheduler().runTask(plugin, () -> giveBackIfMissing(p.getUniqueId())),
                COOLDOWN_MS, TimeUnit.MILLISECONDS
        );

        // TTL ядра 10с + обратный отсчёт 3/2/1
        scheduleCoreTTL(p.getUniqueId());
    }

    /* =================== Промах по блоку (удар по блоку ядром) =================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onHitBlock(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.LEFT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        if (!isRasenganCore(p.getInventory().getItemInMainHand())) return;

        Location where = e.getInteractionPoint() != null
                ? e.getInteractionPoint().toLocation(p.getWorld())
                : e.getClickedBlock().getLocation().add(0.5, 0.5, 0.5);

        finishCore(p.getUniqueId());   // убрать ядро сразу
        doRasenganBlast(p, where);     // кастомный нокбэк + эффекты
    }

    /* =================== Попадание по сущности ядром =================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onHitEntity(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (!isRasenganCore(p.getInventory().getItemInMainHand())) return;
        if (!(e.getEntity() instanceof LivingEntity target)) return;

        double extra = computeDamageFromFall(p);
        e.setDamage(Math.max(e.getDamage(), 0.0) + extra);

        // 👉 отменяем будущий урон от падения
        p.setFallDistance(0f); // сброс сразу, чтобы база от текущего падения не применилась
        noFallUntilMs.put(p.getUniqueId(), System.currentTimeMillis() + 2000L); // 2 c «иммуна»

        Location center = target.getLocation().add(0, target.getHeight() * 0.5, 0);

        finishCore(p.getUniqueId());
        doRasenganBlast(p, center);
    }


    /* =================== Механика удара/нокбэка =================== */

    private double computeDamageFromFall(Player p) {
        double h = Math.max(0.0, p.getFallDistance()); // блоки падения с последнего onGround
        double dmg = BASE_DAMAGE + PER_BLOCK_DMG * h;
        return Math.min(dmg, MAX_DAMAGE);
    }

    private float computePowerFromFall(Player p) {
        float h = (float) Math.max(0.0, p.getFallDistance());
        float pow = BASE_POWER + POWER_PER_BLOCK * h;
        return Math.min(pow, MAX_POWER);
    }

    private void doRasenganBlast(Player owner, Location center) {
        UUID id = owner.getUniqueId();
        if (!blastGuard.add(id)) return; // защита от повторного входа на пару тиков

        World w = center.getWorld();

        // визуал/звук (легче, чем у TNT, без реального server explosion)
        try {
            w.spawnParticle(Particle.CLOUD, center, 40, 0.9, 0.9, 0.9, 0.02);
            w.spawnParticle(Particle.END_ROD, center, 30, 0.5, 0.8, 0.5, 0.01);
            w.playSound(center, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f, 1.35f);
            w.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.25f);
        } catch (Throwable ignored) {}

        float power = computePowerFromFall(owner);
        applyKnockbackArea(owner, center, power);

        // снять guard через 2 тика
        Bukkit.getScheduler().runTaskLater(plugin, () -> blastGuard.remove(id), 2L);
    }

    /** Мощный кастомный нокбэк, теперь включает и владельца. */
    private void applyKnockbackArea(Player owner, Location center, float power) {
        double radius  = 2.5 + power * 1.25;   // ~6..10 блоков
        double radius2 = radius * radius;

        for (var e : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(e instanceof LivingEntity le) || le.isDead() || !le.isValid()) continue;

            Vector toEntity = le.getLocation().toVector().subtract(center.toVector());
            double d2 = toEntity.lengthSquared();
            if (d2 > radius2) continue;

            double dist    = Math.sqrt(Math.max(1.0e-6, d2));
            double falloff = Math.max(0.0, 1.0 - dist / radius);

            double horizontal = 0.6 + 0.18 * power;
            double vertical   = 0.35 + 0.10 * power;

            Vector dir;
            if (d2 <= 1.0e-4) {
                if (le.getUniqueId().equals(owner.getUniqueId())) {
                    dir = owner.getLocation().getDirection().clone().multiply(-1);
                    dir.setY(0);
                    if (dir.lengthSquared() < 1.0e-6) dir = new Vector(-1, 0, 0);
                    dir.normalize();
                } else {
                    dir = new Vector(Math.random() - 0.5, 0, Math.random() - 0.5).normalize();
                }
            } else {
                dir = toEntity.normalize();
            }

            Vector push = dir.clone().multiply(horizontal * falloff);
            push.setY(push.getY() + vertical * (0.65 + 0.35 * falloff));

            Vector newVel = le.getVelocity().clone().add(push);
            if (newVel.length() > 2.8) { newVel.normalize().multiply(2.8); }
            le.setVelocity(newVel);

            le.getWorld().spawnParticle(
                    Particle.SWEEP_ATTACK,
                    le.getLocation().add(0, le.getHeight() * 0.6, 0),
                    2, 0.2, 0.2, 0.2, 0.0
            );
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onOwnerFall(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(e.getEntity() instanceof Player p)) return;

        long until = noFallUntilMs.getOrDefault(p.getUniqueId(), 0L);
        if (System.currentTimeMillis() < until) {
            e.setCancelled(true);
        }
    }


    /* =================== Управление «ядром» =================== */

    private void finishCore(UUID playerId) {
        // убрать «ядро» из руки/инвентаря и зачистить таймеры TTL
        removeCoreIfPresent(playerId);
        cancelCoreTimers(playerId);
        // возврат синего красителя происходит по плану (COOLDOWN_MS)
    }

    private void removeCoreIfPresent(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p == null) return;

        // main hand
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (isRasenganCore(hand)) {
            p.getInventory().setItemInMainHand(null);
            return;
        }
        // поиск по инвентарю
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (isRasenganCore(it)) {
                p.getInventory().setItem(i, null);
                return;
            }
        }
    }

    /* ===== TTL ядра: обратный отсчёт 3/2/1 и удаление на 10-й секунде ===== */

    private void scheduleCoreTTL(UUID ownerId) {
        cancelCoreTimers(ownerId);

        List<Integer> ids = new ArrayList<>(4);
        coreTimerTasks.put(ownerId, ids);

        // 7с -> «через 3с»
        ids.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(ownerId);
            if (p != null && p.isOnline() && hasCoreAnywhere(p))
                p.sendMessage(Component.text("Rasengan исчезнет через 3с", NamedTextColor.YELLOW));
        }, CORE_TTL_TICKS - 60).getTaskId());

        // 8с -> «через 2с»
        ids.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(ownerId);
            if (p != null && p.isOnline() && hasCoreAnywhere(p))
                p.sendMessage(Component.text("Rasengan исчезнет через 2с", NamedTextColor.YELLOW));
        }, CORE_TTL_TICKS - 40).getTaskId());

        // 9с -> «через 1с»
        ids.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(ownerId);
            if (p != null && p.isOnline() && hasCoreAnywhere(p))
                p.sendMessage(Component.text("Rasengan исчезнет через 1с", NamedTextColor.YELLOW));
        }, CORE_TTL_TICKS - 20).getTaskId());

        // 10с -> удалить ядро где бы оно ни лежало
        ids.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
            removeCoreIfPresent(ownerId);
            cancelCoreTimers(ownerId);
        }, CORE_TTL_TICKS).getTaskId());
    }

    private boolean hasCoreAnywhere(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (isRasenganCore(hand)) return true;
        for (ItemStack it : p.getInventory().getContents()) if (isRasenganCore(it)) return true;
        return false;
    }

    private void cancelCoreTimers(UUID ownerId) {
        List<Integer> ids = coreTimerTasks.remove(ownerId);
        if (ids != null) for (int id : ids) Bukkit.getScheduler().cancelTask(id);
    }

    /* =================== Возврат красителя =================== */

    private void giveBackIfMissing(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p == null || !p.isOnline()) return;

        // уже есть краситель? — второй не выдаём
        if (playerHasRasengan(p)) return;

        ItemStack dye = makeRasenganDye();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) p.getInventory().setItemInMainHand(dye);
        else {
            Map<Integer, ItemStack> left = p.getInventory().addItem(dye);
            if (!left.isEmpty())
                p.getWorld().dropItemNaturally(p.getLocation(), left.values().iterator().next());
        }
        p.playSound(p.getLocation(), Sound.UI_TOAST_IN, 0.7f, 1.2f);
        p.sendMessage(Component.text("Rasengan восстановлен.", NamedTextColor.GREEN));
    }

    private boolean playerHasRasengan(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (isRasenganDye(it)) return true;
        }
        return false;
    }

    /* =================== Cleanup / Join =================== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        noFallUntilMs.remove(e.getPlayer().getUniqueId());
    }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        removeCoreIfPresent(e.getEntity().getUniqueId());
        noFallUntilMs.remove(e.getEntity().getUniqueId());
    }
}
