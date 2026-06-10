package org.money.money.kits.saske;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Перенос 1:1 из Last_Warriors (events/saske/ShurikenListener + ThrowListener) — сюрикены.
 * Снежок «Shuriken» (×3): попадание добавляет +7 урона; когда брошены все — перезарядка 25с
 * и возврат 3 штук. Детект по display-name.
 * Адаптация: кулдаун без фикс-слота 2 — возврат сюрикенов в инвентарь (в оригинале — в слот 2).
 */
public class SaskeShurikenListener implements Listener {

    private static final String SHURIKEN_NAME = "Shuriken";
    private static final int    SHURIKEN_DAMAGE = 7;
    private static final long   COOLDOWN_SECONDS = 25;

    private final Plugin plugin;
    private final Set<UUID> onCooldown = new HashSet<>();

    public SaskeShurikenListener(Plugin plugin) {
        this.plugin = plugin;
    }

    /* ===================== Выдача ===================== */

    public ItemStack makeShuriken() {
        ItemStack item = new ItemStack(Material.SNOWBALL, 3);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(SHURIKEN_NAME));
        meta.setUnbreakable(true);
        meta.lore(List.of(
                Component.text("Line 1").color(NamedTextColor.GRAY),
                Component.text("Line 2").color(NamedTextColor.DARK_PURPLE)
        ));
        item.setItemMeta(meta);
        return item;
    }

    /* ===================== Урон ===================== */

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Snowball && event.getEntity() instanceof LivingEntity) {
            final double totalDamage = event.getDamage() + SHURIKEN_DAMAGE;
            event.setDamage(totalDamage);
        }
    }

    /* ===================== Бросок / кулдаун ===================== */

    @EventHandler
    public void onThrow(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Snowball)) return;
        if (!(event.getEntity().getShooter() instanceof Player player)) return;

        // считаем бросок сюрикеном, если в руке снежок «Shuriken»
        if (!isShurikenInHand(player)) return;

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!hasShuriken(player) && !onCooldown.contains(player.getUniqueId())) {
                    coolDownShuriken(player);
                }
            }
        }.runTaskLater(plugin, 1L);
    }

    private void coolDownShuriken(Player player) {
        onCooldown.add(player.getUniqueId());
        new BukkitRunnable() {
            @Override
            public void run() {
                onCooldown.remove(player.getUniqueId());
                if (!player.isOnline()) return;
                ItemStack stack = makeShuriken();
                stack.setAmount(3);
                var leftover = player.getInventory().addItem(stack);
                leftover.values().forEach(rem -> player.getWorld().dropItemNaturally(player.getLocation(), rem));
            }
        }.runTaskLater(plugin, COOLDOWN_SECONDS * 20L);
    }

    private boolean isShurikenInHand(Player player) {
        return isShuriken(player.getInventory().getItemInMainHand())
                || isShuriken(player.getInventory().getItemInOffHand());
    }

    private boolean hasShuriken(Player player) {
        for (ItemStack it : player.getInventory().getContents()) {
            if (isShuriken(it)) return true;
        }
        return isShuriken(player.getInventory().getItemInOffHand());
    }

    private boolean isShuriken(ItemStack item) {
        if (item == null || item.getType() != Material.SNOWBALL) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return false;
        String name = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        return name.equals(SHURIKEN_NAME);
    }
}
