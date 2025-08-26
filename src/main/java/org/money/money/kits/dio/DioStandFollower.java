package org.money.money.kits.dio;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

public final class DioStandFollower implements Listener {

    private final Plugin plugin;
    private final NamespacedKey KEY_STAND;
    private final NamespacedKey KEY_OWNER;

    // каждые 5 сек — проверить наличие/нужность стенда
    private static final long VERIFY_PERIOD_TICKS = 20L * 5;
    // как часто подстраивать позицию стенда
    private static final long FOLLOW_PERIOD_TICKS = 2L;

    // смещение стенда относительно игрока: вправо и назад
    private static final double OFFSET_RIGHT = -0.75;
    private static final double OFFSET_BACK  = 0.9;
    private static final double OFFSET_UP    = 0.5;

    private final Map<UUID, ArmorStand> active = new HashMap<>();
    private BukkitTask verifyTask;
    private BukkitTask followTask;

    public DioStandFollower(Plugin plugin) {
        this.plugin = plugin;
        this.KEY_STAND = new NamespacedKey(plugin, "dio_stand");
        this.KEY_OWNER = new NamespacedKey(plugin, "dio_owner");
        startLoops();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private void startLoops() {
        stopLoops();

        // раз в 5 сек: убедиться что у «DIO» есть стенд, а у остальных — нет
        verifyTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                boolean needsStand = p.getScoreboardTags().contains("DIO") && p.getGameMode() != GameMode.SPECTATOR;

                if (needsStand) {
                    ArmorStand cur = active.get(p.getUniqueId());
                    if (cur == null || !cur.isValid()) {
                        spawnStandFor(p);
                    } else {
                        // защитим от телепорта в другой мир и т.п.
                        if (!Objects.equals(cur.getWorld(), p.getWorld())) {
                            cur.teleport(p.getLocation());
                        }
                    }
                } else {
                    removeStandFor(p.getUniqueId());
                }
            }

            // подчистить «висяки» на случай дисконнекта
            active.entrySet().removeIf(e -> {
                UUID id = e.getKey();
                Player p = Bukkit.getPlayer(id);
                if (p == null || !p.isOnline()
                        || !p.getScoreboardTags().contains("DIO")
                        || p.getGameMode() == GameMode.SPECTATOR) {
                    removeStandSilently(e.getValue());
                    return true;
                }
                return false;
            });
        }, 0L, VERIFY_PERIOD_TICKS);

        // плавное следование (каждые 2 тика)
        followTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (var entry : active.entrySet()) {
                Player p = Bukkit.getPlayer(entry.getKey());
                ArmorStand as = entry.getValue();
                if (p == null || !p.isOnline() || as == null || !as.isValid()) continue;

                // вектор «вперёд» и «вправо» от игрока по его yaw
                Vector fwd = p.getLocation().getDirection().setY(0).normalize();
                if (fwd.lengthSquared() < 1e-6) fwd = new Vector(0, 0, 1); // запаска
                Vector right = new Vector(0, 1, 0).crossProduct(fwd).normalize();

                Vector offset = right.multiply(OFFSET_RIGHT).subtract(fwd.multiply(OFFSET_BACK)).add(new Vector(0, OFFSET_UP, 0));

                Location base = p.getLocation().clone().add(offset);
                base.setYaw(p.getLocation().getYaw());
                base.setPitch(0f);

                as.teleport(base);
            }
        }, 0L, FOLLOW_PERIOD_TICKS);
    }

    private void stopLoops() {
        if (verifyTask != null) verifyTask.cancel();
        if (followTask != null) followTask.cancel();
    }

    private void spawnStandFor(Player owner) {
        removeStandFor(owner.getUniqueId()); // на всякий случай

        ArmorStand as = owner.getWorld().spawn(owner.getLocation(), ArmorStand.class, stand -> {
            stand.setInvisible(true);           // чтоб видна была только «броня-стенд»
            stand.setMarker(true);              // без хитбокса/коллизий
            stand.setInvulnerable(true);
            stand.setGravity(false);
            stand.setCanPickupItems(false);
            stand.setPersistent(true);

            var pdc = stand.getPersistentDataContainer();
            pdc.set(KEY_STAND, PersistentDataType.BYTE, (byte)1);
            pdc.set(KEY_OWNER, PersistentDataType.STRING, owner.getUniqueId().toString());

            stand.getEquipment().setHelmet(newNamed(Material.DIAMOND_HELMET));
            stand.getEquipment().setChestplate(newNamed(Material.DIAMOND_CHESTPLATE));
            stand.getEquipment().setLeggings(newNamed(Material.DIAMOND_LEGGINGS));
            stand.getEquipment().setBoots(newNamed(Material.DIAMOND_BOOTS));

            // обработаем только слоты брони, и аккуратно с meta
            for (EquipmentSlot slot : new EquipmentSlot[]{
                    EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {

                ItemStack it = stand.getEquipment().getItem(slot);
                if (it == null || it.getType() == Material.AIR) continue;

                ItemMeta im = it.getItemMeta();
                if (im == null) continue; // на всякий случай

                im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES,
                        ItemFlag.HIDE_UNBREAKABLE,
                        ItemFlag.HIDE_ENCHANTS,
                        ItemFlag.HIDE_DYE);
                im.setUnbreakable(true);
                it.setItemMeta(im);

                stand.getEquipment().setItem(slot, it);
            }
        });

        active.put(owner.getUniqueId(), as);
    }

    private ItemStack newNamed(Material type) {
        ItemStack it = new ItemStack(type);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text("za_warudo"));
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack named(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        // именно такой способ ты и просил
        meta.displayName(Component.text("za_warudo"));
        it.setItemMeta(meta);
        return it;
    }

    private void removeStandFor(UUID owner) {
        ArmorStand as = active.remove(owner);
        removeStandSilently(as);
    }

    private void removeStandSilently(ArmorStand as) {
        if (as != null && as.isValid()) as.remove();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        removeStandFor(e.getPlayer().getUniqueId());
    }

    // на выключении плагина вызови это из onDisable(), чтобы все стенды убрались
    public void shutdown() {
        stopLoops();
        for (ArmorStand as : active.values()) removeStandSilently(as);
        active.clear();
    }
}
