package org.money.money.kits.burgerMaster;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.type.Slab;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Orange Glazed Terracotta "Garden Platform":
 * при установке — удаляет себя и строит платформу-ферму 5x5.
 * Через 2.5 минуты предмет возвращается владельцу (если у него его нет).
 */
public final class GardenPlatformListener implements Listener {

    private static final long RETURN_DELAY_MS = 150_000L; // 2.5 мин real-time

    private final Plugin plugin;
    private final NamespacedKey KEY_ITEM; // маркер нашего блока-предмета

    // когда игроку вернуть предмет
    private final Map<UUID, Long> cooldownUntilMs = new HashMap<>();

    public GardenPlatformListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_ITEM = new NamespacedKey(plugin, "garden_platform_item");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** Выдать предмет установки платформы. */
    public ItemStack makeGardenPlatformBlock() {
        ItemStack it = new ItemStack(Material.ORANGE_GLAZED_TERRACOTTA);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Garden Platform"));
        im.getPersistentDataContainer().set(KEY_ITEM, PersistentDataType.BYTE, (byte)1);
        it.setItemMeta(im);
        return it;
    }

    private boolean isOurItem(ItemStack it) {
        if (it == null || it.getType() != Material.ORANGE_GLAZED_TERRACOTTA || !it.hasItemMeta()) return false;
        var pdc = it.getItemMeta().getPersistentDataContainer();
        return pdc.has(KEY_ITEM, PersistentDataType.BYTE)
                || Component.text("Garden Platform").equals(it.getItemMeta().displayName());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent e) {
        if (!isOurItem(e.getItemInHand())) return;

        final Player p = e.getPlayer();
        final Block placed = e.getBlockPlaced();
        final Location base = placed.getLocation(); // по центру этой клетки строим

        // строим на следующий тик (чтобы не драться с логикой установки блока)
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (placed.getType() == Material.ORANGE_GLAZED_TERRACOTTA) {
                placed.setType(Material.AIR, false); // убрать сам блок
            }
            buildPlatform(base);

            // эффекты
            World w = base.getWorld();
            w.playSound(base.toCenterLocation(), Sound.BLOCK_FENCE_GATE_OPEN, 0.8f, 1.2f);
            w.spawnParticle(Particle.CLOUD, base.toCenterLocation().add(0, 0.5, 0), 18, 0.6, 0.3, 0.6, 0.02);
        });

        // планируем возврат предмета через 2.5 мин (real-time)
        UUID id = p.getUniqueId();
        long backAt = System.currentTimeMillis() + RETURN_DELAY_MS;
        cooldownUntilMs.put(id, backAt);
        Bukkit.getAsyncScheduler().runDelayed(
                plugin,
                task -> Bukkit.getScheduler().runTask(plugin, () -> giveBackIfMissing(id)),
                RETURN_DELAY_MS,
                TimeUnit.MILLISECONDS
        );
    }

    /* ===================== СТРОИТЕЛЬ ===================== */

    private void buildPlatform(Location centerBlockLoc) {
        World w = centerBlockLoc.getWorld();
        int cx = centerBlockLoc.getBlockX();
        int cy = centerBlockLoc.getBlockY();
        int cz = centerBlockLoc.getBlockZ();

        // 1) 5×5 farmland, центр — вода. Засеять пшеницу (age 0).
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = cx + dx, y = cy, z = cz + dz;

                if (dx == 0 && dz == 0) {
                    // центр — вода-источник
                    setIfAir(w, x, y, z, Bukkit.createBlockData(Material.WATER, data -> {
                        ((Levelled) data).setLevel(0);
                    }));
                    // полублок под водой (y-1), нижний
                    setIfAir(w, x, y - 1, z, Bukkit.createBlockData(Material.OAK_SLAB, data -> {
                        Slab slab = (Slab) data;
                        slab.setType(Slab.Type.BOTTOM);
                    }));
                    continue;
                }

                // блок — farmland (только если воздух)
                setIfAir(w, x, y, z, Material.FARMLAND);

                // сверху посев — пшеница age=0 (только если воздух)
                Block above = w.getBlockAt(x, y + 1, z);
                if (isAir(above.getType())) {
                    var crop = Bukkit.createBlockData(Material.WHEAT);
                    ((Ageable) crop).setAge(0);
                    above.setBlockData(crop, false);
                }
            }
        }

        // 2) Забор вокруг (кольцо 7×7 по периметру), ставим только в воздух.
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (Math.abs(dx) != 3 && Math.abs(dz) != 3) continue; // только рамка
                int x = cx + dx, y = cy, z = cz + dz;
                setIfAir(w, x, y+1, z, Material.OAK_FENCE);
            }
        }

        // 3) Колонна над центром: верстак -> сундук -> smoker.
        setIfAir(w, cx, cy + 1, cz, Material.CRAFTING_TABLE);
        setIfAir(w, cx, cy + 2, cz, Material.CRAFTING_TABLE);
        setIfAir(w, cx, cy + 3, cz, Material.SMOKER);
    }

    /* ===================== ВОЗВРАТ ПРЕДМЕТА ===================== */

    private void giveBackIfMissing(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p == null || !p.isOnline()) return;

        // уже есть наш предмет? — второй не даём
        if (playerHasItem(p)) return;

        ItemStack item = makeGardenPlatformBlock();
        giveToHandOrInv(p, item);
        p.playSound(p.getLocation(), Sound.UI_TOAST_IN, 0.7f, 1.2f);
        p.sendMessage(Component.text("Garden Platform восстановлена."));
    }

    private boolean playerHasItem(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && it.getType() == Material.ORANGE_GLAZED_TERRACOTTA && it.hasItemMeta()
                    && it.getItemMeta().getPersistentDataContainer().has(KEY_ITEM, PersistentDataType.BYTE)) {
                return true;
            }
        }
        return false;
    }

    private void giveToHandOrInv(Player p, ItemStack it) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            p.getInventory().setItemInMainHand(it);
        } else {
            Map<Integer, ItemStack> left = p.getInventory().addItem(it);
            if (!left.isEmpty()) p.getWorld().dropItemNaturally(p.getLocation(), left.values().iterator().next());
        }
    }

    /* ===================== ХЕЛПЕРЫ ===================== */

    private boolean isAir(Material m) {
        return m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR;
    }

    private void setIfAir(World w, int x, int y, int z, Material mat) {
        Block b = w.getBlockAt(x, y, z);
        if (isAir(b.getType())) b.setType(mat, false);
    }

    private void setIfAir(World w, int x, int y, int z, org.bukkit.block.data.BlockData data) {
        Block b = w.getBlockAt(x, y, z);
        if (isAir(b.getType())) b.setBlockData(data, false);
    }
}
