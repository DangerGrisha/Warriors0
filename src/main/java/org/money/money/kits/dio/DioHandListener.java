package org.money.money.kits.dio;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
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
import org.bukkit.util.Vector;

import java.util.*;

public final class DioHandListener implements Listener {

    // оффсеты позиции стенда: справа-сзади-вверх
    private static final double OFFSET_RIGHT = -0.75;
    private static final double OFFSET_BACK  =  0.90;
    private static final double OFFSET_UP    =  0.50;

    private static final double FOLLOW_TP_MAX_DIST = 10.0; // если дальше — просто тэпнуть
    private static final long   FOLLOW_PERIOD      = 2L;   // тики

    private static final double DASH_MAX_DIST      = 6.0;
    private static final double DASH_STEP          = 0.8;  // шаг полёта за тик
    private static final long   DASH_PERIOD        = 1L;

    private static final long   ANCHOR_TIME_TICKS  = 20L * 3; // 3 сек
    private static final int    PUNCHES_TOTAL      = 10;
    private static final long   PUNCH_PERIOD       = 6L; // 0.3 сек
    private static final double PUNCH_RANGE        = 1.0;
    private static final double PUNCH_DAMAGE       = 0.5; // приоритет 0.5 урона

    private final Plugin plugin;

    private final NamespacedKey KEY_STAND;
    private final NamespacedKey KEY_OWNER;

    // runtime-состояния
    private final Map<UUID, ArmorStand> stands = new HashMap<>();
    private final Map<UUID, BukkitTask> followLoops = new HashMap<>();
    private final Map<UUID, BukkitTask> dashLoops = new HashMap<>();
    private final Map<UUID, Long>       anchorUntil = new HashMap<>();

    public DioHandListener(Plugin plugin) {
        this.plugin = plugin;
        this.KEY_STAND = new NamespacedKey(plugin, "dio_stand");
        this.KEY_OWNER = new NamespacedKey(plugin, "dio_owner");
    }

    /* ================== KIT: меч "hand" ================== */

    public ItemStack makeHandSword() {
        ItemStack it = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text("hand"));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        it.setItemMeta(meta);
        return it;
    }
    // В твоём kit-giver просто: player.getInventory().addItem( makeHandSword() );

    /* ================== события ================== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onHeldSwitch(PlayerItemHeldEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> reevaluateStand(e.getPlayer())); // после смены слота
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> reevaluateStand(e.getPlayer()));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) { reevaluateStand(e.getPlayer()); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e)  { despawnStand(e.getPlayer().getUniqueId()); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e){ despawnStand(e.getEntity().getUniqueId()); }

    // ПКМ мечом — рывок вперёд
    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        if (!isHandSword(p.getInventory().getItemInMainHand())) return;

        ArmorStand st = stands.get(p.getUniqueId());
        if (st == null || !st.isValid()) { // при необходимости — создадим и сразу рванём
            ensureStand(p);
            st = stands.get(p.getUniqueId());
            if (st == null) return;
        }
        if (dashLoops.containsKey(p.getUniqueId())) return; // уже летит/якорится
        if (isAnchored(p)) return;

        startDash(p, st);
    }

    /* ================== логика ================== */

    private void reevaluateStand(Player p) {
        boolean holdingHand = isHandSword(p.getInventory().getItemInMainHand());
        UUID id = p.getUniqueId();

        if (holdingHand) {
            ensureStand(p);
            startFollowLoop(p);
            return;
        }

        // меч убран: удаляем стенд только если НЕ dash и НЕ anchor
        if (!dashLoops.containsKey(id) && !isAnchored(p)) {
            despawnStand(id);
        }
    }


    private boolean isHandSword(ItemStack it) {
        if (it == null || it.getType() != Material.DIAMOND_SWORD || !it.hasItemMeta()) return false;
        ItemMeta m = it.getItemMeta();
        // имя ровно "hand"
        Component dn = m.displayName();
        return dn != null && Component.text("hand").equals(dn);
    }

    private void ensureStand(Player p) {
        ArmorStand existing = stands.get(p.getUniqueId());
        if (existing != null && existing.isValid()) return;

        Location spawn = offsetBehindRight(p);
        ArmorStand as = spawnStand(spawn, p);
        stands.put(p.getUniqueId(), as);

        // сразу подхватим фоллоу
        startFollowLoop(p);
    }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent e) {
        // если дропнул из main hand – сразу переоценим
        Bukkit.getScheduler().runTask(plugin, () -> reevaluateStand(e.getPlayer()));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBreak(PlayerItemBreakEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> reevaluateStand(e.getPlayer()));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onInvClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) {
            // если переложил меч из главной руки через инвентарь – заметим при закрытии
            Bukkit.getScheduler().runTask(plugin, () -> reevaluateStand(p));
        }
    }


    private void startFollowLoop(Player p) {
        UUID id = p.getUniqueId();
        cancelTask(followLoops.remove(id));

        BukkitTask t = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            ArmorStand st = stands.get(id);
            if (st == null || !st.isValid()) { cancelTask(followLoops.remove(id)); return; }
            if (!p.isOnline() || p.isDead()) { despawnStand(id); return; }
            if (dashLoops.containsKey(id) || isAnchored(p)) return; // во время даша/якоря не тащим

            // стенд существует только пока меч в главной руке
            boolean holdingHand = isHandSword(p.getInventory().getItemInMainHand());
            if (!holdingHand) { despawnStand(id); return; }

            Location target = offsetBehindRight(p);
            if (st.getLocation().distanceSquared(target) > FOLLOW_TP_MAX_DIST * FOLLOW_TP_MAX_DIST) {
                st.teleport(target);
            } else {
                // мягко
                st.teleport(target);
            }
        }, 0L, FOLLOW_PERIOD);

        followLoops.put(id, t);
    }

    private void startDash(Player owner, ArmorStand st) {
        cancelTask(followLoops.remove(owner.getUniqueId())); // пауза фоллоу
        UUID id = owner.getUniqueId();

        // направление берём от взгляда игрока
        Vector dir = owner.getLocation().getDirection().normalize();
        final double[] travelled = {0.0};

        plugin.getLogger().info("[DIO] " + owner.getName() + " started dash from "
                + fmt(st.getLocation()) + " dir=" + fmt(dir));

        BukkitTask dash = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!owner.isOnline() || owner.isDead() || !st.isValid()) { stopDash(id); return; }

            // следующий шаг
            Vector step = dir.clone().multiply(DASH_STEP);
            Location curr = st.getLocation();
            Location next = curr.clone().add(step);

            // проверка блоков на позиции next
            HitType hit = inspectCollision(next);

            if (hit == HitType.WALL) {
                Block b1 = next.getBlock();
                Block b2 = next.clone().add(0, 0.5, 0).getBlock();
                plugin.getLogger().info("[DIO] " + owner.getName() + " dash hit WALL: "
                        + b1.getType() + "@" + b1.getX() + "," + b1.getY() + "," + b1.getZ()
                        + " or " + b2.getType() + "@" + b2.getX() + "," + b2.getY() + "," + b2.getZ());
                anchor(owner, st);
                stopDash(id);
                return;
            }

            if (hit == HitType.GROUND) {
                // скольжение по земле: не даём падать, движемся «прямо»
                next.setY(curr.getY());
                // (не логирую каждый тик, чтобы не заспамить; при желании можно раскомментить)
                // plugin.getLogger().fine("[DIO] sliding over ground at " + fmt(next));
            }

            // столкновение с существом? — игнорируем владельца, своего стенда и тиммейтов
            LivingEntity target = firstEnemyNear(next, owner, 0.6, st);
            if (target != null) {
                Location hl = target.getLocation();
                String who = (target instanceof Player pl) ? pl.getName()
                        : (target.getCustomName() != null ? target.getCustomName() : target.getType().name());
                plugin.getLogger().info("[DIO] " + owner.getName() + " dash hit ENTITY: "
                        + who + " (" + target.getType() + ") @" + fmt(hl));
                anchor(owner, st);
                stopDash(id);
                return;
            }

            // двигаемся вперёд
            travelled[0] += DASH_STEP;
            st.teleport(next);

            // лимит дистанции
            if (travelled[0] >= DASH_MAX_DIST) {
                plugin.getLogger().info("[DIO] " + owner.getName() + " dash reached max distance at " + fmt(next));
                anchor(owner, st);
                stopDash(id);
            }
        }, 0L, DASH_PERIOD);

        dashLoops.put(id, dash);
    }

    private static String fmt(Location l) {
        return String.format("%.2f,%.2f,%.2f", l.getX(), l.getY(), l.getZ());
    }
    private static String fmt(Vector v) {
        return String.format("%.2f,%.2f,%.2f", v.getX(), v.getY(), v.getZ());
    }
    private enum HitType { NONE, GROUND, WALL }

    private HitType inspectCollision(Location loc) {
        // центр и "голова": если тут солид — это стена
        Block center = loc.getBlock();
        Block head   = loc.clone().add(0, 0.5, 0).getBlock();
        if (center.getType().isSolid() || head.getType().isSolid()) return HitType.WALL;

        // ниже на ~полблока: это пол/ступень — считаем как землю (скользим)
        Block below = loc.clone().add(0, -0.6, 0).getBlock();
        if (below.getType().isSolid()) return HitType.GROUND;

        return HitType.NONE;
    }



    private void stopDash(UUID ownerId) {
        cancelTask(dashLoops.remove(ownerId));
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner == null) return;

        // если сейчас якорь — пусть anchor() сам разрулит финал
        if (isAnchored(owner)) return;

        if (isHandSword(owner.getInventory().getItemInMainHand())) {
            startFollowLoop(owner);
        } else {
            despawnStand(ownerId);
        }
    }


    private boolean isAnchored(Player p) {
        Long until = anchorUntil.get(p.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    private void anchor(Player owner, ArmorStand st) {
        // якорим на месте на 3 сек, эффекты и удары
        anchorUntil.put(owner.getUniqueId(), System.currentTimeMillis() + (ANCHOR_TIME_TICKS * 50));

        Location loc = st.getLocation().clone();
        World w = loc.getWorld();

        // звук
        try {
            w.playSound(loc, "minecraft:my_sounds.muda", SoundCategory.PLAYERS, 1.0f, 1.0f);
        } catch (Throwable t) {
            // если нет кастомного — тихо проигнорим
        }

        // визуал — злой житель, пару снежинок для драматизма
        BukkitTask fx = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            w.spawnParticle(Particle.ANGRY_VILLAGER, st.getLocation().clone().add(0, 1.2, 0),
                    6, 0.6, 0.6, 0.6, 0.0);
            w.spawnParticle(Particle.CRIT, st.getLocation().clone().add(0, 1.0, 0),
                    8, 0.5, 0.5, 0.5, 0.01);
        }, 0L, 6L);

        // серия ударов
        final int[] count = {0};
        BukkitTask punches = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!owner.isOnline() || owner.isDead() || !st.isValid()) return;

            LivingEntity target = nearestEnemy(st.getLocation(), owner, PUNCH_RANGE);
            if (target != null) {
                applyPunch(owner, target, PUNCH_DAMAGE); // учтёт i-frames и броню
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 10, 0, false, false, false));
            }
            count[0]++; // не выходим из anchor раньше времени — просто считаем
        }, 0L, PUNCH_PERIOD);

        // снять якорь через 3 сек, отменить эффекты/циклы и решить, что дальше
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            cancelTask(fx);
            cancelTask(punches);
            anchorUntil.remove(owner.getUniqueId());

            if (isHandSword(owner.getInventory().getItemInMainHand())) {
                startFollowLoop(owner);        // меч снова/всё ещё в руке — продолжаем следовать
            } else {
                despawnStand(owner.getUniqueId()); // меч убран — теперь можно удалить
            }
        }, ANCHOR_TIME_TICKS);
    }
    // добавь рядом с константами
    private static final boolean TRUE_DAMAGE_FALLBACK = true; // включить "истинный" урон, если обычный не прошёл
    private static final double  PUNCH_FALLBACK = 1.0;        // полсердца, если 0.5 не хватает

    // helper: наносит удар c обходом i-frames и фоллбэком на "истинный" урон
    private void applyPunch(Player owner, LivingEntity target, double amount) {
        // сбросить immunity-кадры, чтобы удар засчитался при частых тычках
        try { target.setNoDamageTicks(0); } catch (Throwable ignored) {}

        double before = target.getHealth();

        // обычный урон (с бронёй, ПвП и т.д.)
        target.damage(amount, owner);

        if (!TRUE_DAMAGE_FALLBACK) return;

        // на следующем тике проверяем: уменьшилось ли здоровье. Если нет — прожмём "истинный" урон.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!target.isValid() || target.isDead()) return;
            double after = target.getHealth();

            // если здоровье не уменьшилось заметно (броня/ПвП/регены), бьём напрямую
            if (after >= before - 0.001) {
                double dmg = Math.max(amount, PUNCH_FALLBACK);
                double newHp = Math.max(0.0, before - dmg);
                target.setHealth(newHp);

                // чуть косметики, чтобы видно было
                target.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR,
                        target.getLocation().add(0, target.getHeight() * 0.7, 0),
                        6, 0.25, 0.25, 0.25, 0.0);
                try {
                    target.getWorld().playSound(target.getLocation(),
                            (target instanceof Player ? Sound.ENTITY_PLAYER_HURT : Sound.ENTITY_GENERIC_HURT),
                            0.7f, 1.1f);
                } catch (Throwable ignored) {}
            }
        });
    }

    /* ================== Stand spawn/equip ================== */

    private ArmorStand spawnStand(Location loc, Player owner) {
        return loc.getWorld().spawn(loc, ArmorStand.class, s -> {
            s.setInvisible(true);
            s.setMarker(true);
            s.setInvulnerable(true);
            s.setGravity(false);
            s.setArms(true);
            s.setBasePlate(false);
            s.customName(Component.text("§eZa Warudo"));
            s.setCustomNameVisible(false);

            var pdc = s.getPersistentDataContainer();
            pdc.set(KEY_STAND, PersistentDataType.BYTE, (byte)1);
            pdc.set(KEY_OWNER, PersistentDataType.STRING, owner.getUniqueId().toString());

            // фулл алмазка, имя "za_warudo"
            s.getEquipment().setHelmet(newNamed(Material.DIAMOND_HELMET));
            s.getEquipment().setChestplate(newNamed(Material.DIAMOND_CHESTPLATE));
            s.getEquipment().setLeggings(newNamed(Material.DIAMOND_LEGGINGS));
            s.getEquipment().setBoots(newNamed(Material.DIAMOND_BOOTS));

            // спрятать лишнее
            for (EquipmentSlot slot : new EquipmentSlot[]{
                    EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                ItemStack it = s.getEquipment().getItem(slot);
                if (it == null || it.getType() == Material.AIR) continue;
                ItemMeta im = it.getItemMeta();
                if (im == null) continue;
                im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
                im.setUnbreakable(true);
                it.setItemMeta(im);
                s.getEquipment().setItem(slot, it);
            }
        });
    }

    private ItemStack newNamed(Material type) {
        ItemStack it = new ItemStack(type);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text("za_warudo"));
        it.setItemMeta(meta);
        return it;
    }

    private void despawnStand(UUID owner) {
        cancelTask(followLoops.remove(owner));
        cancelTask(dashLoops.remove(owner));
        anchorUntil.remove(owner);
        ArmorStand st = stands.remove(owner);
        if (st != null && st.isValid()) st.remove();
    }

    /* ================== геометрия/поиск ================== */

    private Location offsetBehindRight(Player p) {
        Location base = p.getLocation();
        Vector dir = base.getDirection().normalize();
        Vector right = dir.clone().crossProduct(new Vector(0, 1, 0)).normalize();

        return base.clone()
                .add(right.multiply(OFFSET_RIGHT))
                .add(dir.multiply(-OFFSET_BACK))
                .add(0, OFFSET_UP, 0);
    }

    private boolean isBlocked(Location loc) {
        // считаем заблокированным, если центр и "ноги" упираются в solid
        Material m1 = loc.getBlock().getType();
        Material m2 = loc.clone().add(0, -0.5, 0).getBlock().getType();
        return m1.isSolid() || m2.isSolid();
    }

    private LivingEntity firstEnemyNear(Location loc, Player owner, double r, ArmorStand self) {
        UUID ownerId = owner.getUniqueId();

        for (Entity e : loc.getWorld().getNearbyEntities(loc, r, r, r)) {
            if (!(e instanceof LivingEntity le) || le.isDead() || !le.isValid()) continue;

            // не владелец
            if (le.getUniqueId().equals(ownerId)) continue;

            // не наш собственный стенд
            if (self != null && le.getUniqueId().equals(self.getUniqueId())) continue;
            if (le instanceof ArmorStand as) {
                String sOwner = as.getPersistentDataContainer()
                        .get(KEY_OWNER, PersistentDataType.STRING);
                if (sOwner != null && sOwner.equals(ownerId.toString())) continue;
            }

            // не тиммейт
            if (isTeammate(owner, le)) continue;

            return le;
        }
        return null;
    }


    private LivingEntity nearestEnemy(Location loc, Player owner, double r) {
        double best = Double.MAX_VALUE;
        LivingEntity pick = null;
        for (Entity e : loc.getWorld().getNearbyEntities(loc, r, r, r)) {
            if (!(e instanceof LivingEntity le) || le.isDead() || !le.isValid()) continue;
            if (le.getUniqueId().equals(owner.getUniqueId())) continue;
            if (isTeammate(owner, le)) continue;
            double d = le.getLocation().distanceSquared(loc);
            if (d < best) { best = d; pick = le; }
        }
        return pick;
    }

    private boolean isTeammate(Player owner, LivingEntity other) {
        if (!(other instanceof Player p2)) return false;
        Team tOwner = teamOf(owner, owner.getScoreboard());
        if (tOwner == null) tOwner = teamOf(owner, Bukkit.getScoreboardManager().getMainScoreboard());
        Team tOther = teamOf(p2, p2.getScoreboard());
        if (tOther == null) tOther = teamOf(p2, Bukkit.getScoreboardManager().getMainScoreboard());
        return tOwner != null && tOwner.equals(tOther);
    }

    private Team teamOf(Player p, Scoreboard sb) {
        if (sb == null) return null;
        return sb.getEntryTeam(p.getName());
    }

    private static void cancelTask(BukkitTask t) { if (t != null) t.cancel(); }
}
