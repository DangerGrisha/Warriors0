package org.money.money.kits.ladynagan;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.money.money.meta.ClassRegistry;
import org.money.money.util.ItemModels;

import java.util.List;
import java.util.Map;

/**
 * Перенос 1:1 из Last_Warriors (events/ladynagan/ExplosionListener).
 * Краситель «Self-Destruction»: ПКМ переключает red↔green; если на момент смерти зелёный —
 * взрыв (радиус 6, урон 26, ломает блоки), кд 60с. Детект по display-name.
 * Адаптация: предмет по кд возвращается в инвентарь (в оригинале — в фикс-слот 7).
 */
public class LadyNaganExplosionListener implements Listener {

    // числа 1:1 из LadyConstants (баланс читается из ClassRegistry при использовании)
    private static final boolean DAMAGE_TERRAIN_SE = true;
    private static final int    EXPLOSION_SLOT = 7;

    private static double explosionRadius() { return ClassRegistry.num("ladynagan", "explosion", "radius", 6.0); }
    private static double explosionDamage() { return ClassRegistry.num("ladynagan", "explosion", "damage", 26.0); }

    private final String dyeName = "Self-Destruction";

    private final Plugin plugin;
    private final LadyCooldownManager cooldownManager;
    private boolean isInteracted = false;

    public LadyNaganExplosionListener(Plugin plugin, LadyCooldownManager cooldownManager) {
        this.plugin = plugin;
        this.cooldownManager = cooldownManager;
    }

    /* ===================== Выдача ===================== */

    public ItemStack makeSelfDestructionDye() {
        ItemStack it = new ItemStack(Material.RED_DYE);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(dyeName));
        meta.setUnbreakable(true);
        meta.setLore(List.of("something"));
        ItemModels.apply(meta, "ledynagan_boom_red");
        it.setItemMeta(meta);
        return it;
    }

    /* ===================== Логика ===================== */

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        if (hasSelfDestructionItem(player)) {
            doExplosion(player);
            replaceGreenDyeWithYellow(player);
            delayForUlta(player, "ExplosionLadyNagan", EXPLOSION_SLOT, ClassRegistry.seconds("ladynagan", "explosion", 60));
        }
    }

    /**
     * Перк включён (зелёный краситель) и тотем спас леди от смерти — взрыв всё равно срабатывает.
     * Урон себе не наносится: в области урон получают только другие LivingEntity (сама леди исключена),
     * а сам createExplosion идёт с силой 0 (никакого AoE-урона/разрушения по самой леди).
     */
    @EventHandler
    public void onTotemResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.isCancelled()) return; // тотема нет — будет обычная смерть (onPlayerDeath)
        if (!hasSelfDestructionItem(player)) return;

        // взрыв на следующий тик, когда воскрешение уже завершилось
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) doExplosion(player);
        });
        replaceGreenDyeWithYellow(player);
        delayForUlta(player, "ExplosionLadyNagan", EXPLOSION_SLOT, ClassRegistry.seconds("ladynagan", "explosion", 60));
    }

    /** Взрыв вокруг леди: сама леди урон не получает. */
    private void doExplosion(Player player) {
        player.getWorld().createExplosion(player.getLocation(), 0, false, DAMAGE_TERRAIN_SE);

        double radius = explosionRadius();
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (entity instanceof LivingEntity target && !target.equals(player)) {
                target.damage(explosionDamage(), player);
            }
        }
    }

    private void delayForUlta(Player player, String nameOfAbilitySpecific, int inventorySlot, int delayInSeconds) {
        cooldownManager.startCooldown(player, nameOfAbilitySpecific, inventorySlot, delayInSeconds, false);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (cooldownManager.isCooldownComplete(player, nameOfAbilitySpecific)) {
                    giveOrDrop(player, makeSelfDestructionDye());
                }
            }
        }.runTaskLater(plugin, (delayInSeconds + 1) * 20L);
    }

    private void replaceGreenDyeWithYellow(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && item.getType() == Material.GREEN_DYE && item.hasItemMeta() &&
                    item.getItemMeta().hasDisplayName() && item.getItemMeta().getDisplayName().equals(dyeName)) {
                player.getInventory().setItem(i, LadyDyeUtil.createYellowDye(dyeName));
                break;
            }
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!isInteracted && checkEventForRightClick(event, player)) {
            ItemStack itemInHand = player.getInventory().getItemInMainHand();
            if (itemInHand.getType() == Material.RED_DYE || itemInHand.getType() == Material.GREEN_DYE) {
                isInteracted = true;
                new BukkitRunnable() {
                    @Override public void run() { isInteracted = false; }
                }.runTaskLater(plugin, 2);

                if (itemInHand.getType() == Material.RED_DYE) {
                    itemInHand.setType(Material.GREEN_DYE);
                    itemInHand.setItemMeta(LadyDyeUtil.createGreenDye(dyeName).getItemMeta());
                } else {
                    itemInHand.setType(Material.RED_DYE);
                    itemInHand.setItemMeta(LadyDyeUtil.createRedDye(dyeName).getItemMeta());
                }
                player.getInventory().setItemInMainHand(itemInHand);
            }
        }
    }

    private boolean hasSelfDestructionItem(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == Material.GREEN_DYE && item.hasItemMeta()
                    && item.getItemMeta().hasDisplayName() && item.getItemMeta().getDisplayName().equals(dyeName)) {
                return true;
            }
        }
        return false;
    }

    private boolean checkEventForRightClick(PlayerInteractEvent event, Player player) {
        return (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) &&
                player.getInventory().getItemInMainHand().hasItemMeta() &&
                player.getInventory().getItemInMainHand().getItemMeta().hasDisplayName() &&
                player.getInventory().getItemInMainHand().getItemMeta().getDisplayName().contains(dyeName);
    }

    private void giveOrDrop(Player p, ItemStack it) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(it);
        if (!left.isEmpty()) {
            for (ItemStack rem : left.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), rem);
            }
        }
    }
}
