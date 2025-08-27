package org.money.money.kits.naruto;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.TimeUnit;

public final class NarutoClonesListener implements Listener {

    private static final int   COUNT            = 15;
    private static final long  COOLDOWN_MS      = 90_000L;        // 1.5 мин
    private static final int   RANGE_BLOCKS     = 20;
    private static final int   SLOW_TICKS       = 20;              // 1 c
    private static final int   ZOMBIE_HEALTH_HP = 1;

    private final Plugin plugin;

    private final NamespacedKey KEY_ITEM;    // предмет "clones"
    private final NamespacedKey KEY_CLONE;   // маркер зомби-клона
    private final NamespacedKey KEY_OWNER;   // владелец клона (UUID-строкой)

    // для возврата предмета
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();

    // активные клоны по владельцу
    private final Map<UUID, Set<UUID>> clonesByOwner = new HashMap<>();
    private final Map<UUID, Integer>   despawnTaskId  = new HashMap<>();

    public NarutoClonesListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_ITEM  = new NamespacedKey(plugin, "naruto_clones");
        this.KEY_CLONE = new NamespacedKey(plugin, "naruto_clone");
        this.KEY_OWNER = new NamespacedKey(plugin, "naruto_clone_owner");
    }

    /* -------------------- Item -------------------- */

    public ItemStack makeClonesDye() {
        ItemStack it = new ItemStack(Material.YELLOW_DYE);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("clones"));
        im.getPersistentDataContainer().set(KEY_ITEM, PersistentDataType.BYTE, (byte)1);
        it.setItemMeta(im);
        return it;
    }

    private boolean isClonesItem(ItemStack it) {
        if (it == null || it.getType() != Material.YELLOW_DYE || !it.hasItemMeta()) return false;
        if (it.getItemMeta().getPersistentDataContainer().has(KEY_ITEM, PersistentDataType.BYTE)) return true;
        return Component.text("clones").equals(it.getItemMeta().displayName());
    }

    /* -------------------- Use -------------------- */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!isClonesItem(hand)) return;

        // звук/дым
        p.playSound(p.getLocation(), Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 0.9f, 1.2f);

        // точка по взгляду (до 20 блоков)
        Location target = lookPoint(p, RANGE_BLOCKS);
        if (target == null) target = p.getLocation().add(p.getLocation().getDirection().normalize().multiply(6));

        // съесть предмет
        int amt = hand.getAmount();
        if (amt <= 1) p.getInventory().setItemInMainHand(null);
        else hand.setAmount(amt - 1);

        // убрать старых клонов, если ещё висят
        removeClones(p.getUniqueId());

        // заспавнить новых
        spawnClones(p, target);

        // план возврата предмета + автодиспавн клонов
        long backAt = System.currentTimeMillis() + COOLDOWN_MS;
        cooldownUntil.put(p.getUniqueId(), backAt);

        Bukkit.getAsyncScheduler().runDelayed(plugin, task ->
                        Bukkit.getScheduler().runTask(plugin, () -> giveBackIfMissing(p.getUniqueId())),
                COOLDOWN_MS, TimeUnit.MILLISECONDS);

        // на всякий — если игрок умрёт/выйдет раньше, в их событиях мы тоже чистим
        int tid = Bukkit.getScheduler().runTaskLater(plugin, () -> removeClones(p.getUniqueId()),
                COOLDOWN_MS / 50L).getTaskId();
        despawnTaskId.put(p.getUniqueId(), tid);
    }

    /* -------------------- Combat hooks -------------------- */

    // Клон выбрал цель: не даём таргетить тиммейтов владельца
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (!(e.getEntity() instanceof Zombie z)) return;
        var pdc = z.getPersistentDataContainer();
        if (!pdc.has(KEY_CLONE, PersistentDataType.BYTE)) return;

        String ownerStr = pdc.get(KEY_OWNER, PersistentDataType.STRING);
        if (ownerStr == null) return;
        UUID ownerId = UUID.fromString(ownerStr);

        LivingEntity tgt = e.getTarget();
        if (tgt == null) return;

        // не трогаем владельца и его тиммейтов
        if (tgt.getUniqueId().equals(ownerId) || isTeammate(ownerId, tgt)) {
            e.setCancelled(true);
            e.setTarget(null);
        }
    }

    // Удар клона → наложить Slowness I на 1c на игрока-жертву
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onCloneHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Zombie z)) return;
        var pdc = z.getPersistentDataContainer();
        if (!pdc.has(KEY_CLONE, PersistentDataType.BYTE)) return;

        if (e.getEntity() instanceof Player victim) {
            victim.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS, SLOW_TICKS, 3, false, true, true
            ));
        }
    }

    /* -------------------- Spawn helpers -------------------- */

    private void spawnClones(Player owner, Location center) {
        World w = center.getWorld();

        // чуть приподнимем, если внутри блока
        if (center.getBlock().getType().isSolid()) center = center.clone().add(0, 1.2, 0);

        // визуал
        w.spawnParticle(Particle.CLOUD, center, 40, 1.2, 0.6, 1.2, 0.01);
        w.playSound(center, Sound.ENTITY_ZOMBIE_INFECT, 0.9f, 0.8f);

        Set<UUID> set = new HashSet<>(COUNT);
        clonesByOwner.put(owner.getUniqueId(), set);

        // окружность + случайный разброс
        double radius = 3.0;
        for (int i = 0; i < COUNT; i++) {
            double ang = (2 * Math.PI * i) / COUNT;
            double dx = Math.cos(ang) * radius + (Math.random() - 0.5) * 1.2;
            double dz = Math.sin(ang) * radius + (Math.random() - 0.5) * 1.2;
            Location spot = center.clone().add(dx, 0, dz);

            Zombie z = w.spawn(spot, Zombie.class, this::setupCloneZombie);
            // помечаем владельца
            z.getPersistentDataContainer().set(KEY_CLONE, PersistentDataType.BYTE, (byte)1);
            z.getPersistentDataContainer().set(KEY_OWNER, PersistentDataType.STRING, owner.getUniqueId().toString());

            // экип
            equipGold(z);

            // 1 HP
            try {
                z.getAttribute(Attribute.MAX_HEALTH).setBaseValue(ZOMBIE_HEALTH_HP);
                z.setHealth(ZOMBIE_HEALTH_HP);
            } catch (Throwable ignored) { z.setHealth(1.0); }

            set.add(z.getUniqueId());
        }
    }

    private void setupCloneZombie(Zombie z) {
        z.setAdult();
        z.setCanPickupItems(false);
        z.setRemoveWhenFarAway(false);
        z.setSilent(false);
        // не горим днём, если метод есть
        try { z.setShouldBurnInDay(false); } catch (Throwable ignored) {}
        // слегка толкаем, чтобы они «проснулись»
        z.setVelocity(new Vector((Math.random()-0.5)*0.2, 0.1, (Math.random()-0.5)*0.2));
    }

    private void equipGold(Zombie z) {
        ItemStack[] gear = new ItemStack[] {
                namedGold(Material.GOLDEN_HELMET),
                namedGold(Material.GOLDEN_CHESTPLATE),
                namedGold(Material.GOLDEN_LEGGINGS),
                namedGold(Material.GOLDEN_BOOTS)
        };
        z.getEquipment().setHelmet(gear[0]);
        z.getEquipment().setChestplate(gear[1]);
        z.getEquipment().setLeggings(gear[2]);
        z.getEquipment().setBoots(gear[3]);
        z.getEquipment().setHelmetDropChance(0f);
        z.getEquipment().setChestplateDropChance(0f);
        z.getEquipment().setLeggingsDropChance(0f);
        z.getEquipment().setBootsDropChance(0f);
    }

    private ItemStack namedGold(Material m) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Naruto", NamedTextColor.GOLD));
        im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ENCHANTS);
        im.setUnbreakable(true);
        it.setItemMeta(im);
        return it;
    }

    /* -------------------- Utils -------------------- */

    private Location lookPoint(Player p, int range) {
        // Paper API: лучше rayTrace, иначе fallback на getTargetBlockExact
        RayTraceResult rt = p.rayTraceBlocks(range);
        if (rt != null && rt.getHitPosition() != null) {
            return rt.getHitPosition().toLocation(p.getWorld());
        }
        Block b = p.getTargetBlockExact(range);
        if (b != null) return b.getLocation().add(0.5, 1.0, 0.5);
        // конец луча в воздухе
        return p.getLocation().add(p.getLocation().getDirection().normalize().multiply(range));
    }

    private boolean isTeammate(UUID ownerId, LivingEntity other) {
        if (!(other instanceof Player p2)) return false;
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner == null) return false;

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

    private void giveBackIfMissing(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p == null || !p.isOnline()) return;
        // уже есть? — не дублируем
        for (ItemStack it : p.getInventory().getContents())
            if (isClonesItem(it)) return;

        ItemStack dye = makeClonesDye();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) p.getInventory().setItemInMainHand(dye);
        else {
            Map<Integer, ItemStack> left = p.getInventory().addItem(dye);
            if (!left.isEmpty()) p.getWorld().dropItemNaturally(p.getLocation(), left.values().iterator().next());
        }
        p.playSound(p.getLocation(), Sound.UI_TOAST_IN, 0.7f, 1.2f);
        p.sendMessage(Component.text("Clones восстановлены.", NamedTextColor.GREEN));
    }

    private void removeClones(UUID ownerId) {
        // отменить плановый диспавн
        Integer tid = despawnTaskId.remove(ownerId);
        if (tid != null) Bukkit.getScheduler().cancelTask(tid);

        Set<UUID> set = clonesByOwner.remove(ownerId);
        if (set == null || set.isEmpty()) return;
        for (UUID zid : set) {
            Entity e = Bukkit.getEntity(zid);
            if (e != null && e.isValid()) e.remove();
        }
    }

    /* -------------------- Cleanup -------------------- */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        removeClones(e.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        removeClones(e.getEntity().getUniqueId());
    }
}
