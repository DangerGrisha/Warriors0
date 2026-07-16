package org.money.money.kits.ganyu.listeners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.EulerAngle;
import org.money.money.session.KitSession;
import org.money.money.util.ItemModels;

import java.util.*;

/**
 * Ganyu — способность «Meteor» (ледяной метеоритный дождь).
 *
 * <p>ПКМ предметом → каст ~3.5с (звуки/частицы, будто читаем ледяную ульту), затем с ОЧЕНЬ большой
 * высоты в радиусе {@code meteorRadius} рандомно падают {@code meteorCount} больших ледяных шаров
 * (модель как у сферы ульты) за {@code meteorDurationTicks}. При касании с блоком метеорит устраивает
 * НАСТОЯЩИЙ взрыв (рушит карту) + ледяные эффекты. Ванильный взрыв бьёт всех; дополнительный ЛЕДЯНОЙ
 * урон + заморозку получают только ВРАГИ (создатель и тиммейты ледяной урон не получают). Кд 5 минут.
 */
public final class GanyuMeteorListener implements Listener {

    private final Plugin plugin;
    private final NamespacedKey KEY_METEOR_ITEM;

    private final Map<UUID, BukkitTask> meteorTasks = new HashMap<>();

    public GanyuMeteorListener(Plugin plugin) {
        this.plugin = plugin;
        this.KEY_METEOR_ITEM = new NamespacedKey(plugin, "ganyu_meteor_item");
    }

    /* ===================== Config ===================== */

    private static int    castTicks()          { return num("castTicks", 70); }
    private static int    meteorCount()        { return num("meteorCount", 10); }
    private static int    meteorDurationTicks(){ return num("meteorDurationTicks", 600); }
    private static double meteorRadius()       { return numD("meteorRadius", 70.0); }
    private static int    meteorHeight()       { return num("meteorHeight", 120); }
    private static double meteorFallSpeed()    { return numD("meteorFallSpeed", 4.0); }
    private static float  explosionPower()     { return (float) numD("explosionPower", 4.0); }
    private static boolean breakBlocks()       { return num("breakBlocks", 1) != 0; }
    private static double impactRadius()       { return numD("impactRadius", 6.0); }
    private static double iceDamage()          { return numD("iceDamage", 6.0); }
    private static int    freezeAddTicks()     { return num("freezeAddTicks", 100); }
    private static int    slowTicks()          { return num("slowDurationTicks", 80); }
    private static int    slowAmplifier()      { return num("slowAmplifier", 1); }

    private static int num(String k, int def)     { return org.money.money.meta.ClassRegistry.numInt("ganyu", "meteor", k, def); }
    private static double numD(String k, double d){ return org.money.money.meta.ClassRegistry.num("ganyu", "meteor", k, d); }

    /* ===================== Item ===================== */

    public ItemStack makeMeteorItem() {
        ItemStack it = new ItemStack(Material.PRISMARINE_CRYSTALS);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Frostfall", NamedTextColor.AQUA));
        im.setLore(List.of("§7Right-click: ice meteor storm"));
        im.getPersistentDataContainer().set(KEY_METEOR_ITEM, PersistentDataType.BYTE, (byte) 1);
        im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(im);
        return it;
    }

    private boolean isMeteorItem(ItemStack it) {
        return it != null && it.getType() == Material.PRISMARINE_CRYSTALS && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(KEY_METEOR_ITEM, PersistentDataType.BYTE);
    }

    /* ===================== Активация ===================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!isMeteorItem(hand)) return;

        e.setUseItemInHand(Event.Result.DENY);
        e.setCancelled(true);
        if (!KitSession.isInGame(p)) return;

        // тратим предмет, ставим кд, вернём по кулдауну
        if (hand.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
        else hand.setAmount(hand.getAmount() - 1);

        final int cooldownTicks = org.money.money.meta.ClassRegistry.ticks("ganyu", "meteor", 6000);
        final UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player on = Bukkit.getPlayer(id);
            if (on == null || !on.isOnline() || !KitSession.isInGame(on)) return;
            on.getInventory().addItem(makeMeteorItem());
            on.sendMessage(Component.text("Frostfall", NamedTextColor.AQUA)
                    .append(Component.text(" is ready again!", NamedTextColor.GRAY)));
            on.playSound(on.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.45f, 1.7f);
        }, cooldownTicks);

        startCast(p);
    }

    /* ===================== Каст ===================== */

    private void startCast(Player p) {
        final int castTicks = Math.max(10, castTicks());
        final UUID id = p.getUniqueId();

        p.getWorld().playSound(p.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 1.0f, 0.55f);
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 0.5f);
        p.sendActionBar(Component.text("Frostfall — casting…", NamedTextColor.AQUA));

        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                Player owner = Bukkit.getPlayer(id);
                if (owner == null || !owner.isOnline() || owner.isDead() || !KitSession.isInGame(owner)) { cancel(); return; }
                t++;
                castParticles(owner.getLocation());
                if (t % 10 == 0) {
                    float pitch = 0.5f + 0.8f * ((float) t / castTicks);
                    owner.getWorld().playSound(owner.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, pitch);
                }
                if (t >= castTicks) {
                    cancel();
                    owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.7f, 0.7f);
                    owner.getWorld().playSound(owner.getLocation(), Sound.ITEM_TRIDENT_THUNDER, 0.8f, 1.2f);
                    startStorm(owner, owner.getLocation().clone());
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void castParticles(Location loc) {
        World w = loc.getWorld();
        Particle.DustOptions ice = new Particle.DustOptions(Color.fromRGB(120, 180, 255), 1.4f);
        for (int i = 0; i < 20; i++) {
            double ang = Math.random() * Math.PI * 2;
            double r = 1.5 + Math.random() * 1.5;
            Location up = loc.clone().add(Math.cos(ang) * r, 2.2 + Math.random() * 1.5, Math.sin(ang) * r);
            w.spawnParticle(Particle.SNOWFLAKE, up, 1, 0.05, 0.05, 0.05, 0.01);
            if (i % 3 == 0) w.spawnParticle(Particle.DUST, up, 1, 0, 0, 0, 0, ice);
        }
    }

    /* ===================== Метеоритный дождь ===================== */

    private void startStorm(Player caster, Location center) {
        final int count = Math.max(1, meteorCount());
        final int total = Math.max(count, meteorDurationTicks());
        final int interval = Math.max(1, total / count);
        final UUID id = caster.getUniqueId();

        new BukkitRunnable() {
            int spawned = 0;
            @Override public void run() {
                Player owner = Bukkit.getPlayer(id);
                if (spawned >= count || owner == null || !owner.isOnline() || !KitSession.isInGame(owner)) { cancel(); return; }
                spawnMeteor(center, owner);
                spawned++;
            }
        }.runTaskTimer(plugin, 0L, interval);
    }

    private void spawnMeteor(Location center, Player caster) {
        World w = center.getWorld();
        double r = meteorRadius() * Math.sqrt(Math.random());
        double ang = Math.random() * Math.PI * 2;
        double x = center.getX() + Math.cos(ang) * r;
        double z = center.getZ() + Math.sin(ang) * r;
        double y = Math.min(w.getMaxHeight() - 2, center.getY() + meteorHeight());
        Location spawn = new Location(w, x, y, z);

        ArmorStand meteor = w.spawn(spawn, ArmorStand.class, s -> {
            s.setInvisible(true);
            s.setMarker(true);
            s.setInvulnerable(true);
            s.setGravity(false);          // падение контролируем сами (быстро и надёжно)
            s.setBasePlate(false);
            s.setPersistent(false);
            s.setArms(true);
            s.setRightArmPose(new EulerAngle(0, 0, 0));
            s.setLeftArmPose(new EulerAngle(0, 0, 0));

            ItemStack ball = new ItemStack(Material.HEART_OF_THE_SEA);
            ItemMeta im = ball.getItemMeta();
            im.displayName(Component.text("IceMeteor"));
            ItemModels.apply(im, "ganyu_smok1"); // большой ледяной шар (как у сферы ульты)
            ball.setItemMeta(im);
            s.getEquipment().setItemInMainHand(ball);
        });

        try { w.playSound(spawn, Sound.ENTITY_BREEZE_SHOOT, 1.2f, 0.6f); } catch (Throwable ignored) {}
        monitorMeteor(meteor, caster);
    }

    private void monitorMeteor(ArmorStand meteor, Player caster) {
        final UUID id = meteor.getUniqueId();
        final double fall = Math.max(0.5, meteorFallSpeed());
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!meteor.isValid() || meteor.isDead()) { stopMeteor(id); return; }
            World w = meteor.getWorld();
            Location loc = meteor.getLocation();

            // хвост метеорита
            w.spawnParticle(Particle.SNOWFLAKE, loc.clone().add(0, 0.6, 0), 10, 0.35, 0.4, 0.35, 0.03);
            w.spawnParticle(Particle.CLOUD, loc.clone().add(0, 0.6, 0), 4, 0.25, 0.25, 0.25, 0.01);

            int fromY = (int) Math.floor(loc.getY());
            int toY   = (int) Math.floor(loc.getY() - fall);
            for (int yy = fromY; yy >= toY; yy--) {
                Block b = w.getBlockAt(loc.getBlockX(), yy, loc.getBlockZ());
                if (b.getType().isSolid()) {
                    meteorImpact(new Location(w, loc.getX(), yy + 1.0, loc.getZ()), caster);
                    stopMeteor(id);
                    meteor.remove();
                    return;
                }
            }
            if (loc.getY() - fall <= w.getMinHeight() + 1) { // ушёл в бездну
                stopMeteor(id);
                meteor.remove();
                return;
            }
            meteor.teleport(loc.clone().subtract(0, fall, 0));
        }, 0L, 1L);
        meteorTasks.put(id, task);
    }

    private void stopMeteor(UUID id) {
        BukkitTask t = meteorTasks.remove(id);
        if (t != null) t.cancel();
    }

    /* ===================== Взрыв метеорита ===================== */

    private void meteorImpact(Location loc, Player caster) {
        World w = loc.getWorld();

        // НАСТОЯЩИЙ взрыв: рушит карту + бьёт всех (без источника → урон средовой, по всем).
        w.createExplosion(loc, explosionPower(), false, breakBlocks());

        // ледяные эффекты взрыва
        Particle.DustOptions ice = new Particle.DustOptions(Color.fromRGB(120, 180, 255), 1.6f);
        w.spawnParticle(Particle.SNOWFLAKE, loc, 140, 2.2, 1.0, 2.2, 0.06);
        w.spawnParticle(Particle.CLOUD, loc, 50, 1.8, 0.6, 1.8, 0.04);
        double vr = impactRadius();
        for (double rr = 0.6; rr <= vr; rr += 0.8) {
            for (int i = 0; i < 26; i++) {
                double a = 2 * Math.PI * i / 26.0;
                w.spawnParticle(Particle.DUST, loc.clone().add(Math.cos(a) * rr, 0.3, Math.sin(a) * rr), 1, 0, 0, 0, 0, ice);
            }
        }
        try {
            w.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.3f, 0.8f);
            w.playSound(loc, Sound.BLOCK_GLASS_BREAK, 1.0f, 1.4f);
            w.playSound(loc, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 1.7f);
        } catch (Throwable ignored) {}

        // ЛЕДЯНОЙ урон + заморозка ТОЛЬКО по врагам (создатель и тиммейты ледяной урон не получают)
        int freezeAdd = freezeAddTicks();
        for (var ent : w.getNearbyEntities(loc, vr, vr, vr)) {
            if (!(ent instanceof LivingEntity le) || le.isDead() || !le.isValid()) continue;
            if (caster != null && (le.getUniqueId().equals(caster.getUniqueId()) || isTeammate(caster, le))) continue;
            if (caster != null) le.damage(iceDamage(), caster); else le.damage(iceDamage());
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slowTicks(), slowAmplifier(), false, true, true));
            le.setFreezeTicks(Math.min(le.getMaxFreezeTicks(), le.getFreezeTicks() + freezeAdd));
        }
    }

    /* ===================== Утилиты ===================== */

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
