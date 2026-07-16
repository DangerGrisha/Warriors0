package org.money.money.kits.fukuko;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;
import org.money.money.meta.ClassRegistry;
import org.money.money.session.KitSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fukuko — Shock Grenade (электрическая граната, «как у Бакуго»).
 *
 * <p>ПКМ — бросок снаряда (снежок с электро-трейлом, ванильная дуга). При ударе на месте
 * вырастает <b>невидимое</b> электрическое поле: круг радиусом 10, живёт 30с. Любой враг,
 * оказавшийся внутри, получает оглушение на 5с (полный стоп движения + Slowness/Blindness/
 * Weakness, нельзя бить) с электрическими эффектами; Fukuko получает сообщение о цели.
 * Поле и стан очищаются по времени; в лобби не работает (KitSession).
 */
public final class FukukoShockGrenadeListener implements Listener {

    private final Plugin plugin;
    private final NamespacedKey KEY_ITEM;
    private final NamespacedKey KEY_PROJECTILE;

    private static final Material GRENADE_MATERIAL = Material.LIGHTNING_ROD;

    // per-игрок состояние
    private final Map<UUID, Long> cooldownUntilMs = new HashMap<>();
    private final Map<UUID, Long> stunnedUntilMs  = new HashMap<>();

    public FukukoShockGrenadeListener(Plugin plugin) {
        this.plugin = plugin;
        this.KEY_ITEM = new NamespacedKey(plugin, "fukuko_shock_grenade");
        this.KEY_PROJECTILE = new NamespacedKey(plugin, "fukuko_shock_projectile");
    }

    /* ===================== Выдача ===================== */

    public ItemStack makeShockGrenade() {
        ItemStack it = new ItemStack(GRENADE_MATERIAL, 1);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Shock Grenade", NamedTextColor.AQUA));
        im.setUnbreakable(true);
        im.setLore(List.of("§7Бросок → невидимое электрическое поле (10, 30с)",
                "§7Враг внутри — стан 5с"));
        im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
        im.getPersistentDataContainer().set(KEY_ITEM, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(im);
        return it;
    }

    private boolean isShockGrenade(ItemStack it) {
        return it != null && it.getType() == GRENADE_MATERIAL && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(KEY_ITEM, PersistentDataType.BYTE);
    }

    /* ===================== Бросок ===================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onThrow(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!isShockGrenade(p.getInventory().getItemInMainHand())) return;
        e.setUseItemInHand(Event.Result.DENY);   // не ставим LIGHTNING_ROD как блок
        e.setCancelled(true);
        if (!KitSession.isInGame(p)) return;

        long now = System.currentTimeMillis();
        long until = cooldownUntilMs.getOrDefault(p.getUniqueId(), 0L);
        if (now < until) {
            long sec = (until - now + 999) / 1000;
            p.sendActionBar(Component.text("Shock Grenade: " + sec + "с", NamedTextColor.RED));
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.8f);
            return;
        }
        long cdMs = Math.max(0, ClassRegistry.ticks("fukuko", "shockgrenade", 400)) * 50L;
        cooldownUntilMs.put(p.getUniqueId(), now + cdMs);

        double speed = ClassRegistry.num("fukuko", "shockgrenade", "projectileSpeed", 1.6);
        Snowball sb = p.launchProjectile(Snowball.class, p.getEyeLocation().getDirection().multiply(speed));
        sb.setShooter(p);
        sb.getPersistentDataContainer().set(KEY_PROJECTILE, PersistentDataType.BYTE, (byte) 1);

        World w = p.getWorld();
        w.playSound(p.getLocation(), Sound.ENTITY_SNOWBALL_THROW, 1.0f, 1.2f);
        w.playSound(p.getLocation(), Sound.ITEM_TRIDENT_THUNDER, 0.4f, 1.7f);
        startTrail(sb);
    }

    private void startTrail(Snowball sb) {
        new BukkitRunnable() {
            @Override public void run() {
                if (!sb.isValid() || sb.isDead()) { cancel(); return; }
                Location loc = sb.getLocation();
                sb.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, loc, 3, 0.05, 0.05, 0.05, 0.02);
                sb.getWorld().spawnParticle(Particle.SMOKE, loc, 1, 0.02, 0.02, 0.02, 0.0);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /* ===================== Удар → поле ===================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof Snowball sb)) return;
        if (!sb.getPersistentDataContainer().has(KEY_PROJECTILE, PersistentDataType.BYTE)) return;

        Location at = e.getHitBlock() != null
                ? e.getHitBlock().getLocation().add(0.5, 1.0, 0.5)
                : sb.getLocation();
        Player shooter = (sb.getShooter() instanceof Player ps && ps.isOnline()) ? ps : null;
        sb.remove();
        if (shooter == null || !KitSession.isInGame(shooter)) return;
        spawnField(shooter, at);
    }

    /** Невидимое электрическое поле: круг radius, живёт duration, враг внутри → стан. */
    private void spawnField(Player owner, Location center) {
        final World w = center.getWorld();
        if (w == null) return;
        final UUID ownerId = owner.getUniqueId();
        final String ownerTeam = getTeam(owner);
        final double radius = ClassRegistry.num("fukuko", "shockgrenade", "fieldRadius", 10.0);
        final double vHalf  = ClassRegistry.num("fukuko", "shockgrenade", "fieldVerticalHalf", 5.0);
        final int duration  = ClassRegistry.numInt("fukuko", "shockgrenade", "fieldDurationTicks", 600);
        final int stunTicks = ClassRegistry.numInt("fukuko", "shockgrenade", "stunDurationTicks", 100);
        final int gapTicks  = ClassRegistry.numInt("fukuko", "shockgrenade", "reStunGapTicks", 40);
        final int scan      = Math.max(2, ClassRegistry.numInt("fukuko", "shockgrenade", "scanIntervalTicks", 5));

        w.playSound(center, Sound.ITEM_TRIDENT_THUNDER, 1.2f, 1.2f);
        w.spawnParticle(Particle.ELECTRIC_SPARK, center.clone().add(0, 0.4, 0), 30, radius * 0.3, 0.3, radius * 0.3, 0.1);

        final Map<UUID, Long> nextEligible = new HashMap<>();
        new BukkitRunnable() {
            int elapsed = 0;
            @Override public void run() {
                Player o = Bukkit.getPlayer(ownerId);
                if (elapsed >= duration || o == null || !o.isOnline() || !KitSession.isInGame(o)) {
                    w.spawnParticle(Particle.ELECTRIC_SPARK, center.clone().add(0, 0.4, 0), 10, radius * 0.3, 0.3, radius * 0.3, 0.05);
                    cancel();
                    return;
                }
                // «невидимое»: лишь редкая слабая искра внутри (без явной границы)
                if (elapsed % 20 == 0) {
                    double ang = Math.random() * Math.PI * 2, r = Math.sqrt(Math.random()) * radius;
                    w.spawnParticle(Particle.ELECTRIC_SPARK,
                            center.clone().add(Math.cos(ang) * r, 0.15, Math.sin(ang) * r), 1, 0, 0, 0, 0);
                }

                long now = System.currentTimeMillis();
                double r2 = radius * radius;
                for (Entity ent : w.getNearbyEntities(center, radius, Math.max(radius, vHalf), radius)) {
                    if (!(ent instanceof Player pl) || !pl.isOnline() || pl.isDead()) continue;
                    if (pl.getUniqueId().equals(ownerId)) continue;
                    if (getTeam(pl).equals(ownerTeam)) continue;          // только враги
                    Location pl_loc = pl.getLocation();
                    double dx = pl_loc.getX() - center.getX(), dz = pl_loc.getZ() - center.getZ();
                    if (dx * dx + dz * dz > r2) continue;                 // вне круга
                    if (Math.abs(pl_loc.getY() - center.getY()) > vHalf) continue;
                    Long elig = nextEligible.get(pl.getUniqueId());
                    if (elig != null && now < elig) continue;             // КД повторного стана
                    nextEligible.put(pl.getUniqueId(), now + (long) (stunTicks + gapTicks) * 50L);
                    stun(o, pl, stunTicks);
                }
                elapsed += scan;
            }
        }.runTaskTimer(plugin, 0L, scan);
    }

    /* ===================== Стан ===================== */

    private void stun(Player owner, Player victim, int ticks) {
        stunnedUntilMs.put(victim.getUniqueId(), System.currentTimeMillis() + ticks * 50L);
        victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 6, false, false, false));
        victim.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, ticks, 0, false, false, false));
        victim.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, ticks, 4, false, false, false));
        victim.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, ticks, 4, false, false, false));

        World w = victim.getWorld();
        Location l = victim.getLocation().add(0, 1, 0);
        w.spawnParticle(Particle.ELECTRIC_SPARK, l, 40, 0.4, 0.8, 0.4, 0.3);
        w.spawnParticle(Particle.CRIT, l, 12, 0.3, 0.6, 0.3, 0.2);
        w.playSound(victim.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.8f, 1.6f);
        w.playSound(victim.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.6f, 2.0f);

        int sec = Math.max(1, ticks / 20);
        victim.sendActionBar(Component.text("⚡ Электрическое поле Fukuko — стан " + sec + "с!", NamedTextColor.AQUA));
        owner.sendMessage(Component.text("⚡ Fukuko: поле оглушило " + victim.getName() + " на " + sec + "с!", NamedTextColor.AQUA));
    }

    private boolean isStunned(UUID id) {
        Long u = stunnedUntilMs.get(id);
        return u != null && System.currentTimeMillis() < u;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent e) {
        if (!isStunned(e.getPlayer().getUniqueId())) return;
        Location from = e.getFrom(), to = e.getTo();
        if (to != null && (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ())) {
            // полный стоп (XYZ, в т.ч. подвешивание), камеру оставляем
            e.setTo(new Location(from.getWorld(), from.getX(), from.getY(), from.getZ(), to.getYaw(), to.getPitch()));
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onStunnedAttack(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && isStunned(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onStunnedInteract(PlayerInteractEvent e) {
        if (isStunned(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    /* ===================== Очистка ===================== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        stunnedUntilMs.remove(id);
        cooldownUntilMs.remove(id);
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        stunnedUntilMs.remove(e.getEntity().getUniqueId());
    }

    /* ===================== Команды ===================== */

    private String getTeam(Player player) {
        Team t = getPlayerTeam(player);
        return t != null ? t.getName() : "DEFAULT";
    }

    private Team getPlayerTeam(Player player) {
        Scoreboard scoreboard = player.getScoreboard();
        for (Team team : scoreboard.getTeams()) {
            if (team.hasEntry(player.getName())) return team;
        }
        return null;
    }
}
