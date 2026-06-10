package org.money.money.kits.haohao;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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

import org.money.money.session.KitSession;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class SwordShield implements Listener {

    /* =========================
       Config
       ========================= */
    private static final double DASH_SPEED = 1.35;
    private static final long DASH_CLEAVE_DELAY_TICKS = 3L;
    private static final int DASH_COOLDOWN_SECONDS = 7;
    private static final long DASH_COOLDOWN_MS = DASH_COOLDOWN_SECONDS * 1000L;

    private static final double CLEAVE_DAMAGE = 6.0; // 3 hearts
    private static final double CLEAVE_RADIUS = 2.4;
    private static final double CLEAVE_PUSH = 0.8;

    private static final double RETURN_HEAL_MULTIPLIER = 0.50;
    private static final long HANDG_WINDOW_TICKS = 40L; // 1 second
    private static final long HANDG_COOLDOWN_TICKS = 20L * 10; // 10 seconds

    private static final double GLOW_CHANCE = 0.25;
    private static final int GLOW_MIN_TICKS = 40; // 2 seconds
    private static final int GLOW_MAX_TICKS = 60; // 3 seconds

    private final Plugin plugin;

    private final NamespacedKey KEY_KING_SWORD;
    private final NamespacedKey KEY_HAND_R;
    private final NamespacedKey KEY_HAND_G;
    private final NamespacedKey KEY_HAND_Y;

    private record LastHit(UUID attackerId, double damage, long nonce) {}

    private final Map<UUID, LastHit> lastHits = new HashMap<>();
    private final Map<UUID, BukkitTask> handWindowTasks = new HashMap<>();
    private final Map<UUID, BukkitTask> handCooldownTasks = new HashMap<>();
    private final Map<UUID, Boolean> handCooldownOffhand = new HashMap<>();
    private final Map<UUID, Long> dashCooldowns = new HashMap<>();
    private final Map<UUID, BukkitTask> dashCdTasks = new HashMap<>();

    public SwordShield(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_KING_SWORD = new NamespacedKey(plugin, "haoh_king_sword");
        this.KEY_HAND_R = new NamespacedKey(plugin, "haoh_hand_r");
        this.KEY_HAND_G = new NamespacedKey(plugin, "haoh_hand_g");
        this.KEY_HAND_Y = new NamespacedKey(plugin, "haoh_hand_y");
    }

    /* =========================
       Public factory
       ========================= */

    public ItemStack makeKingSword() {
        ItemStack it = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("King's Sword", NamedTextColor.GOLD));
        im.setUnbreakable(true);
        im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
        im.getPersistentDataContainer().set(KEY_KING_SWORD, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(im);
        return it;
    }

    /* =========================
       Events
       ========================= */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onKingSwordHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker)) return;
        if (!(e.getEntity() instanceof LivingEntity victim)) return;

        ItemStack main = attacker.getInventory().getItemInMainHand();
        if (!isKingSword(main)) return;

        if (victim instanceof Player pv && !isEnemyOrNoTeams(attacker, pv)) return;

        if (ThreadLocalRandom.current().nextDouble() <= GLOW_CHANCE) {
            int dur = ThreadLocalRandom.current().nextInt(GLOW_MIN_TICKS, GLOW_MAX_TICKS + 1);
            victim.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, dur, 0, false, false, false));
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onKingHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        if (!(e.getDamager() instanceof Player attacker)) return;
        if (!isEnemyOrNoTeams(attacker, victim)) return;
        if (MaskAbility.hasGreenMask(victim)) return;

        ItemStack main = victim.getInventory().getItemInMainHand();
        ItemStack off = victim.getInventory().getItemInOffHand();

        boolean mainOk = isHandR(main) || isHandG(main);
        boolean offOk = isHandR(off) || isHandG(off);
        if (!mainOk && !offOk) return;

        if (isHandY(main) || isHandY(off)) return;

        armHandG(victim, attacker.getUniqueId(), e.getFinalDamage(), offOk);
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        EquipmentSlot slot = e.getHand();
        ItemStack hand = (slot == EquipmentSlot.OFF_HAND)
                ? p.getInventory().getItemInOffHand()
                : p.getInventory().getItemInMainHand();

        if (slot == EquipmentSlot.HAND && isKingSword(hand)) {
            e.setCancelled(true);

            if (p.isSneaking()) {
                p.getInventory().setItemInMainHand(makeHandR());
                return;
            }

            if (!canDash(p)) return;
            startDashCooldown(p);
            dashAndCleave(p);
            return;
        }

        if (isHandY(hand)) {
            if (p.isSneaking()) {
                p.sendMessage(Component.text("u cant switch back rn", NamedTextColor.RED));
            }
            e.setCancelled(true);
            return;
        }

        if (isHandG(hand)) {
            e.setCancelled(true);

            if (p.isSneaking()) {
                cancelHandWindow(p);
                p.getInventory().setItemInMainHand(makeKingSword());
                return;
            }

            returnDamage(p, slot == EquipmentSlot.OFF_HAND);
            return;
        }

        if (isHandR(hand)) {
            e.setCancelled(true);

            if (p.isSneaking()) {
                cancelHandWindow(p);
                p.getInventory().setItemInMainHand(makeKingSword());
            }
            return;
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { cleanup(e.getPlayer()); }
    @EventHandler public void onDeath(PlayerDeathEvent e) { cleanup(e.getEntity()); }

    /* =========================
       Core logic
       ========================= */

    private void dashAndCleave(Player p) {
        Vector dir = p.getEyeLocation().getDirection().normalize();
        p.setVelocity(dir.clone().multiply(DASH_SPEED));
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f, 1.2f);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || p.isDead()) return;
            dealAreaDamage(p);
        }, DASH_CLEAVE_DELAY_TICKS);
    }

    private boolean canDash(Player p) {
        long now = System.currentTimeMillis();
        Long last = dashCooldowns.get(p.getUniqueId());
        if (last == null) return true;
        long passed = now - last;
        if (passed >= DASH_COOLDOWN_MS) return true;

        long secLeft = (DASH_COOLDOWN_MS - passed + 999) / 1000;
        p.sendActionBar(Component.text(secLeft + " sec", NamedTextColor.RED));
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.8f);
        return false;
    }

    private void startDashCooldown(Player p) {
        dashCooldowns.put(p.getUniqueId(), System.currentTimeMillis());

        BukkitTask prev = dashCdTasks.remove(p.getUniqueId());
        if (prev != null) prev.cancel();

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int timeLeft = DASH_COOLDOWN_SECONDS;

            @Override
            public void run() {
                if (!p.isOnline()) {
                    cancel();
                    return;
                }
                if (timeLeft <= 0) {
                    p.sendActionBar(Component.text("Ready", NamedTextColor.GREEN));
                    cancel();
                    return;
                }
                p.sendActionBar(Component.text(timeLeft + " sec", NamedTextColor.YELLOW));
                timeLeft--;
            }

            private void cancel() {
                BukkitTask t = dashCdTasks.remove(p.getUniqueId());
                if (t != null) t.cancel();
            }
        }, 0L, 20L);

        dashCdTasks.put(p.getUniqueId(), task);
    }

    private void armHandG(Player victim, UUID attackerId, double damage, boolean swapOffhand) {
        long nonce = System.nanoTime();
        lastHits.put(victim.getUniqueId(), new LastHit(attackerId, damage, nonce));

        if (swapOffhand) {
            victim.getInventory().setItemInOffHand(makeHandG());
        } else {
            victim.getInventory().setItemInMainHand(makeHandG());
        }

        // подсказка над опытом: рука позеленела — можно отрикошетить удар
        victim.sendActionBar(Component.text("Можешь отрикошетить удар!", NamedTextColor.GREEN));

        BukkitTask prev = handWindowTasks.remove(victim.getUniqueId());
        if (prev != null) prev.cancel();

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!KitSession.isInGame(victim)) return; // игра кончилась — не выдаём в лобби
            LastHit lh = lastHits.get(victim.getUniqueId());
            if (lh == null || lh.nonce != nonce) return;

            ItemStack currentMain = victim.getInventory().getItemInMainHand();
            ItemStack currentOff = victim.getInventory().getItemInOffHand();
            if (isHandG(currentMain)) {
                victim.getInventory().setItemInMainHand(makeHandR());
            }
            if (isHandG(currentOff)) {
                victim.getInventory().setItemInOffHand(makeHandR());
            }
            lastHits.remove(victim.getUniqueId());
        }, HANDG_WINDOW_TICKS);

        handWindowTasks.put(victim.getUniqueId(), task);
    }

    private void returnDamage(Player p, boolean offhand) {
        if (MaskAbility.hasGreenMask(p)) {
            if (offhand) {
                p.getInventory().setItemInOffHand(makeHandR());
            } else {
                p.getInventory().setItemInMainHand(makeHandR());
            }
            return;
        }

        LastHit lh = lastHits.get(p.getUniqueId());
        if (lh == null) {
            p.sendMessage(Component.text("No damage to return.", NamedTextColor.RED));
            if (offhand) {
                p.getInventory().setItemInOffHand(makeHandR());
            } else {
                p.getInventory().setItemInMainHand(makeHandR());
            }
            return;
        }

        Player attacker = Bukkit.getPlayer(lh.attackerId());
        if (attacker != null && attacker.isOnline() && !attacker.isDead() && isEnemyOrNoTeams(p, attacker)) {
            attacker.damage(lh.damage(), p);
        }

        double heal = lh.damage() * RETURN_HEAL_MULTIPLIER;
        double max = Objects.requireNonNull(p.getAttribute(Attribute.MAX_HEALTH)).getValue();
        p.setHealth(Math.min(max, p.getHealth() + heal));

        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);

        cancelHandWindow(p);
        startHandCooldown(p, offhand);
    }

    private void startHandCooldown(Player p, boolean offhand) {
        handCooldownOffhand.put(p.getUniqueId(), offhand);
        if (offhand) {
            p.getInventory().setItemInOffHand(makeHandY());
        } else {
            p.getInventory().setItemInMainHand(makeHandY());
        }

        BukkitTask prev = handCooldownTasks.remove(p.getUniqueId());
        if (prev != null) prev.cancel();

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!KitSession.isInGame(p)) return; // игра кончилась — не выдаём в лобби
            boolean off = handCooldownOffhand.getOrDefault(p.getUniqueId(), false);
            if (off) {
                p.getInventory().setItemInOffHand(makeHandR());
            } else {
                p.getInventory().setItemInMainHand(makeHandR());
            }
            handCooldownOffhand.remove(p.getUniqueId());
        }, HANDG_COOLDOWN_TICKS);

        handCooldownTasks.put(p.getUniqueId(), task);
    }

    private void cancelHandWindow(Player p) {
        BukkitTask prev = handWindowTasks.remove(p.getUniqueId());
        if (prev != null) prev.cancel();
        lastHits.remove(p.getUniqueId());
    }

    private void cleanup(Player p) {
        cancelHandWindow(p);
        BukkitTask cd = handCooldownTasks.remove(p.getUniqueId());
        if (cd != null) cd.cancel();
        handCooldownOffhand.remove(p.getUniqueId());
        BukkitTask dash = dashCdTasks.remove(p.getUniqueId());
        if (dash != null) dash.cancel();
    }

    /* =========================
       Cleave helpers
       ========================= */

    private void dealAreaDamage(Player player) {
        World world = player.getWorld();
        Vector direction = player.getEyeLocation().getDirection().normalize();

        Vector sideDirection = new Vector(-direction.getZ(), 0, direction.getX()).normalize();
        Location frontBlock = player.getEyeLocation().add(direction);

        Set<UUID> hit = new HashSet<>();
        applyDamageAndPushEntities(world, frontBlock, player, direction, hit);
        applyDamageAndPushEntities(world, frontBlock.clone().add(sideDirection), player, direction, hit);
        applyDamageAndPushEntities(world, frontBlock.clone().subtract(sideDirection.clone().multiply(1.5)), player, direction, hit);
        spawnSwordCutParticles(world, frontBlock, direction, player);
    }

    private void applyDamageAndPushEntities(World world, Location center, Player attacker, Vector direction, Set<UUID> hit) {
        for (Entity ent : world.getNearbyEntities(center, CLEAVE_RADIUS, CLEAVE_RADIUS, CLEAVE_RADIUS)) {
            if (!(ent instanceof Player target)) continue;
            if (target.getUniqueId().equals(attacker.getUniqueId())) continue;
            if (!target.isOnline() || target.isDead()) continue;
            if (hit.contains(target.getUniqueId())) continue;
            if (!isEnemyOrNoTeams(attacker, target)) continue;

            hit.add(target.getUniqueId());

            target.damage(CLEAVE_DAMAGE, attacker);
            Vector push = direction.clone().normalize().multiply(CLEAVE_PUSH).setY(0.2);
            target.setVelocity(target.getVelocity().add(push));
        }
    }

    private void spawnSwordCutParticles(World world, Location startLocation, Vector direction, Player player) {
        world.spawnParticle(Particle.SWEEP_ATTACK, startLocation.add(0.1, 0.1, 0.1), 1);
        Vector sideDirection = new Vector(-direction.getZ(), 0, direction.getX()).normalize();
        double yOffset = -0.2;
        double lineLength = 6;
        int particleCount = 30;

        spawnParticleLine(world, startLocation, sideDirection, direction, yOffset, lineLength - 2, particleCount);
        spawnParticleLineSweepAttack(world, startLocation, sideDirection, direction, yOffset, lineLength - 2, particleCount - 27);

        Location oneBlockFurther = startLocation.clone().add(direction);
        spawnParticleLine(world, oneBlockFurther, sideDirection, direction, yOffset, lineLength, particleCount);
        spawnParticleLineSweepAttack(world, oneBlockFurther, sideDirection, direction, yOffset, lineLength, particleCount - 24);

        world.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 1.1f);
    }

    private void spawnParticleLine(World world, Location startLocation, Vector sideDirection, Vector forward, double yOffset,
                                   double lineLength, int particleCount) {
        Vector side = sideDirection.clone().normalize();
        Location base = startLocation.clone().add(forward.clone().normalize().multiply(0.2)).add(0, yOffset, 0);
        double step = lineLength / Math.max(1, particleCount - 1);

        for (int i = 0; i < particleCount; i++) {
            double t = (i * step) - (lineLength / 2.0);
            Location p = base.clone().add(side.clone().multiply(t));
            world.spawnParticle(Particle.CLOUD, p, 1, 0, 0, 0, 0.0);
        }
    }

    private void spawnParticleLineSweepAttack(World world, Location startLocation, Vector sideDirection, Vector forward,
                                              double yOffset, double lineLength, int particleCount) {
        if (particleCount <= 0) return;

        Vector side = sideDirection.clone().normalize();
        Location base = startLocation.clone().add(forward.clone().normalize().multiply(0.2)).add(0, yOffset, 0);
        double step = lineLength / Math.max(1, particleCount - 1);

        for (int i = 0; i < particleCount; i++) {
            double t = (i * step) - (lineLength / 2.0);
            Location p = base.clone().add(side.clone().multiply(t));
            world.spawnParticle(Particle.SWEEP_ATTACK, p, 1, 0, 0, 0, 0.0);
        }
    }

    /* =========================
       Item helpers
       ========================= */

    private ItemStack makeHandR() {
        return makeHand(Component.text("HandR", NamedTextColor.RED), KEY_HAND_R);
    }

    private ItemStack makeHandG() {
        return makeHand(Component.text("HandG", NamedTextColor.GREEN), KEY_HAND_G);
    }

    private ItemStack makeHandY() {
        return makeHand(Component.text("HandY", NamedTextColor.YELLOW), KEY_HAND_Y);
    }

    private ItemStack makeHand(Component name, NamespacedKey key) {
        ItemStack it = new ItemStack(Material.WOODEN_SWORD);
        ItemMeta im = it.getItemMeta();
        im.displayName(name);
        im.setUnbreakable(true);
        im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
        im.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(im);
        return it;
    }

    private boolean isKingSword(ItemStack it) {
        return it != null
                && it.getType() == Material.DIAMOND_SWORD
                && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(KEY_KING_SWORD, PersistentDataType.BYTE);
    }

    private boolean isHandR(ItemStack it) {
        return isHand(it, KEY_HAND_R, "HandR");
    }

    private boolean isHandG(ItemStack it) {
        return isHand(it, KEY_HAND_G, "HandG");
    }

    private boolean isHandY(ItemStack it) {
        return isHand(it, KEY_HAND_Y, "HandY");
    }

    private boolean isHand(ItemStack it, NamespacedKey key, String name) {
        if (it == null || it.getType() != Material.WOODEN_SWORD || !it.hasItemMeta()) return false;
        ItemMeta im = it.getItemMeta();
        if (im.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) return true;
        Component dn = im.displayName();
        return dn != null && Component.text(name).equals(dn);
    }

    /* =========================
       Team check
       ========================= */

    private boolean isEnemyOrNoTeams(Player a, Player b) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team ta = sb.getEntryTeam(a.getName());
        Team tb = sb.getEntryTeam(b.getName());
        if (ta == null || tb == null) return true;
        return !ta.getName().equalsIgnoreCase(tb.getName());
    }
}
