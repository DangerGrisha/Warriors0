package org.money.money.kits.opera;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class OperaTransformationListener implements Listener {

    private static final String TAG_OPERA = "Opera";

    private static final int SPEED_AURA_DURATION_TICKS = 60; // refresh every 3s
    private static final int SPEED_AURA_AMPLIFIER = 0; // Speed I

    private final Plugin plugin;
    private final NamespacedKey KEY_TRANSFORMATION;

    private final Map<UUID, Horse> activeHorses = new HashMap<>();
    private final Map<UUID, BukkitTask> followTasks = new HashMap<>();

    public OperaTransformationListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_TRANSFORMATION = new NamespacedKey(plugin, "opera_transformation");

        // Passive Speed I for Opera tag
        Bukkit.getScheduler().runTaskTimer(plugin, this::applyOperaSpeedAura, 0L, 40L);
    }

    /* =========================
       Public factory
       ========================= */

    public ItemStack makeTransformationDye() {
        ItemStack it = new ItemStack(Material.RED_DYE);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("transformation", NamedTextColor.RED));
        im.getPersistentDataContainer().set(KEY_TRANSFORMATION, PersistentDataType.BYTE, (byte) 1);
        im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(im);
        return it;
    }

    /* =========================
       Events
       ========================= */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        if (!isTransformationItem(p.getInventory().getItemInMainHand())) return;

        e.setCancelled(true);

        if (activeHorses.containsKey(p.getUniqueId())) {
            stopTransform(p);
        } else {
            startTransform(p);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onVehicleEnter(VehicleEnterEvent e) {
        if (!(e.getVehicle() instanceof Horse horse)) return;
        if (!(e.getEntered() instanceof Player rider)) return;

        UUID ownerId = getOwnerId(horse);
        if (ownerId == null) return;

        Player owner = Bukkit.getPlayer(ownerId);
        if (owner == null || !owner.isOnline()) {
            e.setCancelled(true);
            return;
        }

        if (rider.getUniqueId().equals(ownerId)) {
            e.setCancelled(true);
            return;
        }
        if (!isTeammate(owner, rider)) {
            e.setCancelled(true);
            return;
        }
        // Allow teammate to mount, but keep horse locked to owner
        Location loc = owner.getLocation().clone();
        var dir = loc.getDirection().normalize();
        var right = dir.clone().crossProduct(new org.bukkit.util.Vector(0, 1, 0)).normalize();
        loc.add(right.multiply(1.1));
        horse.teleport(loc);
        horse.setRotation(loc.getYaw(), loc.getPitch());
        horse.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        horse.setJumpStrength(0.0);
        horse.setAI(false);
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { stopTransform(e.getPlayer()); }
    @EventHandler public void onDeath(PlayerDeathEvent e) { stopTransform(e.getEntity()); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onHorseDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Horse horse)) return;
        UUID ownerId = getOwnerId(horse);
        if (ownerId == null) return;

        Player owner = Bukkit.getPlayer(ownerId);
        if (owner == null || !owner.isOnline() || owner.isDead()) return;

        // Horse can take damage, but owner also takes the same hit.
        owner.damage(e.getFinalDamage());
        // Prevent horse death while owner is alive
        double remaining = horse.getHealth() - e.getFinalDamage();
        if (remaining <= 0.0) {
            horse.setHealth(1.0);
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onHorseMove(VehicleMoveEvent e) {
        if (!(e.getVehicle() instanceof Horse horse)) return;
        UUID ownerId = getOwnerId(horse);
        if (ownerId == null) return;

        Player owner = Bukkit.getPlayer(ownerId);
        if (owner == null || !owner.isOnline() || owner.isDead()) return;

        syncHorseToOwner(owner, horse);
    }

    /* =========================
       Transform
       ========================= */

    private void startTransform(Player p) {
        UUID id = p.getUniqueId();
        if (activeHorses.containsKey(id)) return;

        Horse horse = p.getWorld().spawn(p.getLocation(), Horse.class, h -> {
            h.setAdult();
            h.setTamed(true);
            h.setOwner(p);
            h.setColor(Horse.Color.BROWN);
            if (h.getAttribute(Attribute.MAX_HEALTH) != null) {
                h.getAttribute(Attribute.MAX_HEALTH).setBaseValue(20.0);
                h.setHealth(20.0);
            }
            h.setSilent(true);
            h.setInvulnerable(false);
            h.setGravity(false);
            h.setCollidable(false);
            h.setAI(false);
            h.getInventory().setSaddle(new ItemStack(Material.SADDLE));
            h.getInventory().setArmor(new ItemStack(Material.GOLDEN_HORSE_ARMOR));
        });

        activeHorses.put(id, horse);
        setOwnerId(horse, id);

        applyTransformEffects(p);
        startFollowLoop(p, horse);
    }

    private void stopTransform(Player p) {
        UUID id = p.getUniqueId();

        BukkitTask t = followTasks.remove(id);
        if (t != null) t.cancel();

        Horse horse = activeHorses.remove(id);
        if (horse != null && horse.isValid()) horse.remove();

        clearTransformEffects(p);
    }

    private void startFollowLoop(Player p, Horse horse) {
        UUID id = p.getUniqueId();
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline() || p.isDead()) {
                stopTransform(p);
                return;
            }
            if (!hasTransformationItem(p)) {
                stopTransform(p);
                return;
            }
            if (!horse.isValid()) {
                stopTransform(p);
                return;
            }
            syncHorseToOwner(p, horse);
        }, 0L, 1L);

        followTasks.put(id, task);
    }

    private void syncHorseToOwner(Player owner, Horse horse) {
        Location loc = owner.getLocation().clone();
        var dir = loc.getDirection().normalize();
        var right = dir.clone().crossProduct(new org.bukkit.util.Vector(0, 1, 0)).normalize();
        loc.add(right.multiply(1.1));

        var passengers = horse.getPassengers();
        if (!passengers.isEmpty()) {
            horse.eject();
        }

        horse.teleport(loc);
        horse.setRotation(loc.getYaw(), loc.getPitch());
        horse.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        horse.setJumpStrength(0.0);

        if (!passengers.isEmpty()) {
            for (var ent : passengers) {
                if (!(ent instanceof Player rider)) continue;
                if (rider.getUniqueId().equals(owner.getUniqueId())) continue;
                if (!isTeammate(owner, rider)) continue;
                horse.addPassenger(rider);
            }
        }
    }

    private void applyTransformEffects(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, Integer.MAX_VALUE, 0, false, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, Integer.MAX_VALUE, 2, false, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 3, false, false, false));
    }

    private void clearTransformEffects(Player p) {
        p.removePotionEffect(PotionEffectType.INVISIBILITY);
        p.removePotionEffect(PotionEffectType.WEAKNESS);
        p.removePotionEffect(PotionEffectType.JUMP_BOOST);
        p.removePotionEffect(PotionEffectType.SPEED);
    }

    /* =========================
       Opera passive speed
       ========================= */

    private void applyOperaSpeedAura() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getScoreboardTags().contains(TAG_OPERA)) continue;
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, SPEED_AURA_DURATION_TICKS, SPEED_AURA_AMPLIFIER, false, false, true));
        }
    }

    /* =========================
       Helpers
       ========================= */

    private boolean isTransformationItem(ItemStack it) {
        return it != null
                && it.getType() == Material.RED_DYE
                && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(KEY_TRANSFORMATION, PersistentDataType.BYTE);
    }

    private boolean hasTransformationItem(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (isTransformationItem(it)) return true;
        }
        return false;
    }

    private boolean isTeammate(Player a, Player b) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team ta = sb.getEntryTeam(a.getName());
        Team tb = sb.getEntryTeam(b.getName());
        if (ta == null || tb == null) return false;
        return ta.getName().equalsIgnoreCase(tb.getName());
    }

    private void setOwnerId(Horse horse, UUID ownerId) {
        horse.getPersistentDataContainer().set(new NamespacedKey(plugin, "opera_owner"), PersistentDataType.STRING, ownerId.toString());
    }

    private UUID getOwnerId(Horse horse) {
        String raw = horse.getPersistentDataContainer().get(new NamespacedKey(plugin, "opera_owner"), PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
