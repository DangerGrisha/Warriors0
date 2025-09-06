package org.money.money.kits.dio;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.*;
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

    /* ====== DEBUG ====== */
    private static final boolean DEBUG = false;
    private void log(String s) { if (DEBUG) plugin.getLogger().info("[DIO] " + s); }
    private void warn(String s) { plugin.getLogger().warning("[DIO] " + s); }

    /* ====== геометрия и тайминги ====== */
    private static final double OFFSET_RIGHT = -0.75;
    private static final double OFFSET_BACK  =  0.90;
    private static final double OFFSET_UP    =  0.50;

    private static final double FOLLOW_TP_MAX_DIST = 10.0;
    private static final long   FOLLOW_PERIOD      = 2L;

    private static final double DASH_MAX_DIST      = 6.0;
    private static final double DASH_STEP          = 0.8;
    private static final long   DASH_PERIOD        = 1L;

    private static final long   ANCHOR_TIME_TICKS  = 20L * 3; // 3 сек
    private static final long   PUNCH_PERIOD       = 6L;      // 0.3 сек

    private static final double PUNCH_RANGE        = 1.3;
    private static final double PUNCH_DAMAGE       = 5; // половинка сердца

    // урон-фоллбэк
    private static final boolean TRUE_DAMAGE_FALLBACK = true;
    private static final double  PUNCH_FALLBACK       = 2.0;  // 1 сердце
    private static final boolean STRIP_ABSORPTION     = true;

    /* ====== поля ====== */

    private final Plugin plugin;

    private final NamespacedKey KEY_STAND;
    private final NamespacedKey KEY_OWNER;
    private final NamespacedKey KEY_HAND_ITEM;

    private final Map<UUID, ArmorStand> stands      = new HashMap<>();
    private final Map<UUID, BukkitTask> followLoops = new HashMap<>();
    private final Map<UUID, BukkitTask> dashLoops   = new HashMap<>();
    private final Map<UUID, Long>       anchorUntil = new HashMap<>();

    public DioHandListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_STAND     = new NamespacedKey(plugin, "dio_stand");
        this.KEY_OWNER     = new NamespacedKey(plugin, "dio_owner");
        this.KEY_HAND_ITEM = new NamespacedKey(plugin, "dio_hand_item");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /* ====== выдача меча ====== */

    public ItemStack makeHandSword() {
        ItemStack it = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text("hand"));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        meta.getPersistentDataContainer().set(KEY_HAND_ITEM, PersistentDataType.BYTE, (byte)1);
        it.setItemMeta(meta);
        return it;
    }

    private boolean isHandSword(ItemStack it) {
        if (it == null || it.getType() != Material.DIAMOND_SWORD || !it.hasItemMeta()) return false;
        var pdc = it.getItemMeta().getPersistentDataContainer();
        if (pdc.has(KEY_HAND_ITEM, PersistentDataType.BYTE)) return true;
        Component dn = it.getItemMeta().displayName();
        return dn != null && Component.text("hand").equals(dn);
    }

    private boolean isHoldingHand(Player p) {
        return isHandSword(p.getInventory().getItemInMainHand())
                || isHandSword(p.getInventory().getItemInOffHand());
    }

    /* ====== события ====== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onHeldSwitch(PlayerItemHeldEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            log("onHeldSwitch -> reevaluate " + e.getPlayer().getName());
            reevaluateStand(e.getPlayer());
        });
    }


    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            log("onSwapHands -> reevaluate " + e.getPlayer().getName());
            reevaluateStand(e.getPlayer());
        });
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        log("onJoin " + e.getPlayer().getName());
        reevaluateStand(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e)  {
        log("onQuit " + e.getPlayer().getName());
        despawnStand(e.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e){
        log("onDeath " + e.getEntity().getName());
        despawnStand(e.getEntity().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            log("onDrop -> reevaluate " + e.getPlayer().getName());
            reevaluateStand(e.getPlayer());
        });
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBreak(PlayerItemBreakEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            log("onBreak -> reevaluate " + e.getPlayer().getName());
            reevaluateStand(e.getPlayer());
        });
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onInvClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                log("onInvClose -> reevaluate " + p.getName());
                reevaluateStand(p);
            });
        }
    }

    // ПКМ мечом — dash
    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        if (!isHandSword(p.getInventory().getItemInMainHand())) return;

        ArmorStand st = stands.get(p.getUniqueId());
        if (st == null || !st.isValid()) {
            log("onUse: ensureStand (none present) for " + p.getName());
            ensureStand(p);
            st = stands.get(p.getUniqueId());
            if (st == null) { warn("onUse: stand still null after ensure"); return; }
        }
        if (dashLoops.containsKey(p.getUniqueId())) { log("onUse: dash already running"); return; }
        if (isAnchored(p)) { log("onUse: currently anchored, ignore"); return; }

        log("onUse: startDash by " + p.getName());
        startDash(p, st);
    }

    /* ====== логика стенда ====== */

    private void reevaluateStand(Player p) {
        UUID id = p.getUniqueId();

        if (dashLoops.containsKey(id) || isAnchored(p)) {
            log("reevaluate(" + p.getName() + "): skip (dash/anchor active)");
            return;
        }

        boolean holdingHand = isHoldingHand(p);
        log("reevaluate(" + p.getName() + "): holdingHand=" + holdingHand);

        if (holdingHand) {
            ensureStand(p);
            startFollowLoop(p);
        } else {
            despawnStand(id);
        }
    }

    private void ensureStand(Player p) {
        ArmorStand existing = stands.get(p.getUniqueId());
        if (existing != null && existing.isValid()) {
            return;
        }
        Location spawn = offsetBehindRight(p);
        ArmorStand as = spawnStand(spawn, p);
        stands.put(p.getUniqueId(), as);
        log("ensureStand: spawned at " + fmt(spawn) + " for " + p.getName());
    }

    private void startFollowLoop(Player p) {
        UUID id = p.getUniqueId();
        cancelTask(followLoops.remove(id));

        log("startFollowLoop for " + p.getName());
        BukkitTask t = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline() || p.isDead()) { log("follow: owner offline/dead -> despawn"); despawnStand(id); return; }
            if (dashLoops.containsKey(id) || isAnchored(p)) return;

            if (!isHoldingHand(p)) { log("follow: not holding hand anymore -> despawn"); despawnStand(id); return; }

            ArmorStand st = stands.get(id);
            if (st == null || !st.isValid()) {
                // стенд убит/исчез — создаём заново
                log("follow: stand missing/invalid -> ensure + continue");
                ensureStand(p);
                st = stands.get(id);
                if (st == null || !st.isValid()) return;
            }

            Location target = offsetBehindRight(p);
            if (st.getLocation().distanceSquared(target) > FOLLOW_TP_MAX_DIST * FOLLOW_TP_MAX_DIST) {
                log("follow: teleport (too far) to " + fmt(target));
                st.teleport(target);
            } else {
                st.teleport(target);
            }
        }, 0L, FOLLOW_PERIOD);

        followLoops.put(id, t);
    }

    private void startDash(Player owner, ArmorStand st) {
        UUID id = owner.getUniqueId();
        cancelTask(followLoops.remove(id));

        Vector dir = owner.getLocation().getDirection().normalize();
        final double[] travelled = {0.0};

        log("dash: START from " + fmt(st.getLocation()) + " dir=" + fmt(dir));
        BukkitTask dash = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!owner.isOnline() || owner.isDead()) { log("dash: abort (owner invalid)"); stopDash(id); return; }
            if (!st.isValid()) { log("dash: stand invalid"); stopDash(id); return; }

            Vector step = dir.clone().multiply(DASH_STEP);
            Location curr = st.getLocation();
            Location next = curr.clone().add(step);

            HitType hit = inspectCollision(next);
            if (hit == HitType.WALL) {
                log("dash: hit WALL at " + fmt(next));
                anchor(owner, st);
                stopDash(id);
                return;
            }
            if (hit == HitType.GROUND) {
                next.setY(curr.getY());
            }

            LivingEntity target = firstEnemyNear(next, owner, 0.6, st); // только для срабатывания анкера
            if (target != null) {
                log("dash: hit ENTITY " + target.getType() + " at " + fmt(target.getLocation()));
                anchor(owner, st);
                stopDash(id);
                return;
            }

            travelled[0] += DASH_STEP;
            st.teleport(next);

            if (travelled[0] >= DASH_MAX_DIST) {
                log("dash: reached MAX distance at " + fmt(next));
                anchor(owner, st);
                stopDash(id);
            }
        }, 0L, DASH_PERIOD);

        dashLoops.put(id, dash);
    }

    private void stopDash(UUID ownerId) {
        cancelTask(dashLoops.remove(ownerId));
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner == null) return;
        if (isAnchored(owner)) { log("stopDash: currently anchored -> wait anchor end"); return; }

        if (isHoldingHand(owner)) {
            log("stopDash: resume follow");
            ensureStand(owner); // вдруг стенд умер на якоре
            startFollowLoop(owner);
        } else {
            log("stopDash: not holding hand -> despawn");
            despawnStand(ownerId);
        }
    }

    private boolean isAnchored(Player p) {
        Long until = anchorUntil.get(p.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    private void anchor(Player owner, ArmorStand st) {
        anchorUntil.put(owner.getUniqueId(), System.currentTimeMillis() + (ANCHOR_TIME_TICKS * 50));

        Location loc = st.getLocation().clone();
        World w = loc.getWorld();
        log("anchor: START at " + fmt(loc) + " for " + owner.getName());

        try {
            w.playSound(loc, "minecraft:my_sounds.muda", SoundCategory.PLAYERS, 1.0f, 1.0f);
        } catch (Throwable ignored) {}

        // FX
        BukkitTask fx = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            w.spawnParticle(Particle.ANGRY_VILLAGER, st.getLocation().clone().add(0, 1.2, 0),
                    6, 0.6, 0.6, 0.6, 0.0);
            w.spawnParticle(Particle.CRIT, st.getLocation().clone().add(0, 1.0, 0),
                    8, 0.5, 0.5, 0.5, 0.01);
        }, 0L, 6L);

        // серия ударов (ищем ближайшего ВРАГА-ИГРОКА, игнорируя любые ArmorStand)
        BukkitTask punches = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!owner.isOnline() || owner.isDead() || !st.isValid()) { log("anchor: abort punches (owner/stand invalid)"); return; }

            LivingEntity target = nearestEnemy(st.getLocation(), owner, PUNCH_RANGE, st);
            if (target != null) {
                log("anchor: punch target=" + target.getType()
                        + " hp=" + String.format(Locale.US, "%.2f", target.getHealth())
                        + (target instanceof Player pl ? " abs=" + String.format(Locale.US, "%.2f", pl.getAbsorptionAmount()) : ""));
                applyPunch(owner, target, PUNCH_DAMAGE);
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 10, 0, false, false, false));
            } else {
                log("anchor: punch no player in range");
            }
        }, 0L, PUNCH_PERIOD);

        // снятие якоря
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            cancelTask(fx);
            cancelTask(punches);
            anchorUntil.remove(owner.getUniqueId());

            boolean hold = isHoldingHand(owner);
            log("anchor: END -> holdingHand=" + hold);
            if (hold) {
                ensureStand(owner);   // если стенд вдруг умер — создадим
                startFollowLoop(owner);
            } else {
                despawnStand(owner.getUniqueId());
            }
        }, ANCHOR_TIME_TICKS);
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.MONITOR)
    public void onAntiKnockback(EntityDamageByEntityEvent e) {
        // нас интересуют только удары владельца стенда во время ANCHOR вблизи стенда
        if (!(e.getDamager() instanceof Player owner)) return;

        UUID id = owner.getUniqueId();
        Long until = anchorUntil.get(id);
        if (until == null || until <= System.currentTimeMillis()) return;

        ArmorStand st = stands.get(id);
        if (st == null || !st.isValid()) return;
        if (!st.getWorld().equals(e.getEntity().getWorld())) return;
        if (st.getLocation().distanceSquared(e.getEntity().getLocation()) > 9.0) return; // ~3 блока

        // сбросим откидыш на следующем тике (когда vanilla уже толкнуло цель)
        Bukkit.getScheduler().runTask(plugin, () -> {
            Entity victim = e.getEntity();
            Vector v = victim.getVelocity();
            // убираем горизонтальный импульс, вертикаль по желанию: оставь v.getY() или обнули
            victim.setVelocity(new Vector(0.0, v.getY() * 0.0, 0.0));
        });
    }


    /* ====== урон ====== */

    private void applyPunch(Player owner, LivingEntity target, double amount) {
        try { target.setNoDamageTicks(0); } catch (Throwable ignored) {}

        double before = target.getHealth();
        target.damage(amount, owner); // норм. урон (событие может быть отменено другими плагинами)

        if (!TRUE_DAMAGE_FALLBACK) return;

        double after = target.getHealth();
        boolean changed = after < before - 0.001;
        log("applyPunch: normal damage res=" + changed + " before=" + fmt(before) + " after=" + fmt(after));
        if (changed) return;

        // фоллбэк — «истинный» урон
        double dmg = Math.max(amount, PUNCH_FALLBACK);
        double newHp = Math.max(0.0, before - dmg);

        if (STRIP_ABSORPTION && target instanceof Player pl) {
            try {
                double abs = pl.getAbsorptionAmount();
                if (abs > 0.0) {
                    double eat = Math.min(abs, dmg);
                    pl.setAbsorptionAmount(Math.max(0.0, abs - eat));
                    newHp = Math.max(0.0, before - (dmg - eat));
                    log("applyPunch: strip absorption eat=" + fmt(eat) + " newAbs=" + fmt(pl.getAbsorptionAmount()));
                }
            } catch (Throwable ignored) {}
        }

        try { target.setHealth(newHp); } catch (Throwable t) {
            warn("applyPunch: setHealth failed: " + t.getMessage());
        }
        log("applyPunch: TRUE-DMG applied -> hp " + fmt(before) + " -> " + fmt(newHp));

        target.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR,
                target.getLocation().add(0, target.getHeight()*0.7, 0),
                6, 0.25, 0.25, 0.25, 0.0);
        try {
            target.getWorld().playSound(target.getLocation(),
                    (target instanceof Player ? Sound.ENTITY_PLAYER_HURT : Sound.ENTITY_GENERIC_HURT),
                    0.7f, 1.1f);
        } catch (Throwable ignored) {}
    }

    // Для наглядности: лог входящих событий урона нашим владельцем
    @EventHandler(ignoreCancelled = false, priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        UUID aid = p.getUniqueId();
        if (!stands.containsKey(aid) && !anchorUntil.containsKey(aid)) return;

        String tgt = e.getEntity().getType().name();
        String msg = "onDamage: damager=" + p.getName() + " -> " + tgt
                + " base=" + fmt(e.getDamage()) + " final=" + fmt(e.getFinalDamage())
                + " cause=" + e.getCause()
                + " cancelled=" + e.isCancelled();
        log(msg);
    }

    /* ====== спавн/удаление стенда ====== */

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

            s.getEquipment().setHelmet(newNamed(Material.DIAMOND_HELMET));
            s.getEquipment().setChestplate(newNamed(Material.DIAMOND_CHESTPLATE));
            s.getEquipment().setLeggings(newNamed(Material.DIAMOND_LEGGINGS));
            s.getEquipment().setBoots(newNamed(Material.DIAMOND_BOOTS));

            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
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
        if (st != null && st.isValid()) { st.remove(); log("despawnStand: removed for " + owner); }
    }

    /* ====== утилиты ====== */

    private enum HitType { NONE, GROUND, WALL }

    private HitType inspectCollision(Location loc) {
        Block center = loc.getBlock();
        Block head   = loc.clone().add(0, 0.5, 0).getBlock();
        if (center.getType().isSolid() || head.getType().isSolid()) return HitType.WALL;

        Block below = loc.clone().add(0, -0.6, 0).getBlock();
        if (below.getType().isSolid()) return HitType.GROUND;

        return HitType.NONE;
    }

    private Location offsetBehindRight(Player p) {
        Location base = p.getLocation();
        Vector dir = base.getDirection().normalize();
        Vector right = dir.clone().crossProduct(new Vector(0, 1, 0)).normalize();
        return base.clone()
                .add(right.multiply(OFFSET_RIGHT))
                .add(dir.multiply(-OFFSET_BACK))
                .add(0, OFFSET_UP, 0);
    }

    // для срабатывания анкера во время полёта — можно бить и мобов, поэтому оставляем общий
    private LivingEntity firstEnemyNear(Location loc, Player owner, double r, ArmorStand self) {
        UUID ownerId = owner.getUniqueId();
        for (Entity e : loc.getWorld().getNearbyEntities(loc, r, r, r)) {
            if (!(e instanceof LivingEntity le) || le.isDead() || !le.isValid()) continue;
            if (le.getUniqueId().equals(ownerId)) continue;

            // не наш собственный стенд и вообще игнорируем любые ArmorStand
            if (le instanceof ArmorStand as) {
                String sOwner = as.getPersistentDataContainer().get(KEY_OWNER, PersistentDataType.STRING);
                if (sOwner != null && sOwner.equals(ownerId.toString())) continue;
                return null; // встречен чужой стенд — не якоримся о них
            }

            if (isTeammate(owner, le)) continue;
            return le;
        }
        return null;
    }

    // ВОТ ЗДЕСЬ — поиск цели для ударов: только ВРАГ-ИГРОК, игнорируя любые ArmorStand
    private LivingEntity nearestEnemy(Location loc, Player owner, double r, ArmorStand self) {
        double best = Double.MAX_VALUE;
        LivingEntity pick = null;
        UUID ownerId = owner.getUniqueId();

        for (Entity e : loc.getWorld().getNearbyEntities(loc, r, r, r)) {
            if (!(e instanceof LivingEntity le) || le.isDead() || !le.isValid()) continue;

            // только игроки
            if (!(le instanceof Player p2)) continue;

            // не владелец
            if (p2.getUniqueId().equals(ownerId)) continue;

            // не тиммейт
            if (isTeammate(owner, p2)) continue;

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

    private static String fmt(Location l) { return String.format(Locale.US, "%.2f,%.2f,%.2f", l.getX(), l.getY(), l.getZ()); }
    private static String fmt(Vector v)   { return String.format(Locale.US, "%.2f,%.2f,%.2f", v.getX(), v.getY(), v.getZ()); }
    private static String fmt(double d)   { return String.format(Locale.US, "%.2f", d); }
}
