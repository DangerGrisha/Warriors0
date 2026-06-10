package org.money.money.kits.ladynagan;

import org.money.money.session.KitSession;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;

/**
 * Перенос 1:1 из Last_Warriors (events/ladynagan/CooldownManager).
 * Общий менеджер кулдаунов для способностей LadyNagan (снайпер/полёт/ловушки/взрыв).
 */
public class LadyCooldownManager {

    private final Map<Player, Map<String, Long>> cooldowns = new HashMap<>();
    private final Map<Player, Map<String, BukkitRunnable>> tasks = new HashMap<>();
    private final Material[] GLASS_COLORS = {
            Material.RED_STAINED_GLASS_PANE,
            Material.ORANGE_STAINED_GLASS_PANE,
            Material.YELLOW_STAINED_GLASS_PANE,
            Material.GREEN_STAINED_GLASS_PANE
    };
    public final Plugin plugin;

    public LadyCooldownManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public void startCooldown(Player player, String ability, int slot, long cooldownTime, boolean showUI) {
        cooldowns.putIfAbsent(player, new HashMap<>());
        tasks.putIfAbsent(player, new HashMap<>());

        if (cooldowns.get(player).containsKey(ability)) {
            return;
        }

        long endTime = System.currentTimeMillis() + (cooldownTime * 1000);
        cooldowns.get(player).put(ability, endTime);

        if (showUI) {
            BukkitRunnable task = new BukkitRunnable() {
                @Override
                public void run() {
                    long remainingTime = (endTime - System.currentTimeMillis()) / 1000;
                    if (remainingTime <= 0) {
                        cooldowns.get(player).remove(ability);
                        tasks.get(player).remove(ability);
                        this.cancel();
                        return;
                    }
                    double progress = (double) remainingTime / cooldownTime;
                    Material glassColor = getGlassColor(progress);
                    ItemStack glassPane = createCooldownGlass(remainingTime, glassColor, ability);
                    player.getInventory().setItem(slot, glassPane);
                }
            };
            task.runTaskTimer(plugin, 0L, 20L);
            tasks.get(player).put(ability, task);
        } else {
            // Без UI всё равно нужно снять кулдаун по истечении времени, иначе запись в
            // cooldowns остаётся навсегда и isCooldownComplete() всегда возвращает false →
            // способность «висит» в бесконечной перезарядке и больше не срабатывает.
            BukkitRunnable task = new BukkitRunnable() {
                @Override
                public void run() {
                    Map<String, Long> cd = cooldowns.get(player);
                    if (cd != null) cd.remove(ability);
                    Map<String, BukkitRunnable> t = tasks.get(player);
                    if (t != null) t.remove(ability);
                }
            };
            task.runTaskLater(plugin, cooldownTime * 20L);
            tasks.get(player).put(ability, task);
        }
    }

    /** Простой кд без UI: по окончании вернём предмет игроку и проиграем звук. */
    public void startCooldownAndReturn(Player player,
                                       String ability,
                                       long cooldownTimeSec,
                                       ItemStack itemToReturn,
                                       boolean preferMainHand,
                                       Sound sound,
                                       float volume,
                                       float pitch) {
        cooldowns.putIfAbsent(player, new HashMap<>());
        if (cooldowns.get(player).containsKey(ability)) return;

        long endTime = System.currentTimeMillis() + cooldownTimeSec * 1000L;
        cooldowns.get(player).put(ability, endTime);

        new BukkitRunnable() {
            @Override
            public void run() {
                Map<String, Long> map = cooldowns.get(player);
                if (map != null) map.remove(ability);

                if (!player.isOnline()) return;
                if (!KitSession.isInGame(player)) return; // игра кончилась — не выдаём в лобби

                ItemStack give = itemToReturn.clone();
                PlayerInventory inv = player.getInventory();
                if (preferMainHand && (inv.getItemInMainHand() == null || inv.getItemInMainHand().getType().isAir())) {
                    inv.setItemInMainHand(give);
                } else {
                    Map<Integer, ItemStack> left = inv.addItem(give);
                    if (!left.isEmpty()) {
                        World w = player.getWorld();
                        w.dropItemNaturally(player.getLocation(), left.values().iterator().next());
                    }
                }
                try { player.playSound(player.getLocation(), sound, volume, pitch); } catch (Throwable ignored) {}
            }
        }.runTaskLater(plugin, cooldownTimeSec * 20L);
    }

    public void startCooldownAndReturn(Player player,
                                       String ability,
                                       long cooldownTimeSec,
                                       ItemStack itemToReturn,
                                       boolean preferMainHand) {
        startCooldownAndReturn(player, ability, cooldownTimeSec, itemToReturn, preferMainHand,
                Sound.UI_TOAST_IN, 0.8f, 1.2f);
    }

    public boolean isCooldownComplete(Player player, String ability) {
        return !cooldowns.containsKey(player) || !cooldowns.get(player).containsKey(ability);
    }

    public void cancelCooldown(Player player, String ability) {
        if (tasks.containsKey(player) && tasks.get(player).containsKey(ability)) {
            tasks.get(player).get(ability).cancel();
            tasks.get(player).remove(ability);
        }
        if (cooldowns.containsKey(player)) {
            cooldowns.get(player).remove(ability);
        }
    }

    public long getRemainingCooldown(Player player, String ability) {
        if (!cooldowns.containsKey(player) || !cooldowns.get(player).containsKey(ability)) {
            return 0;
        }
        return Math.max((cooldowns.get(player).get(ability) - System.currentTimeMillis()) / 1000, 0);
    }

    private ItemStack createCooldownGlass(long remainingTime, Material glassColor, String ability) {
        ItemStack glassPane = new ItemStack(glassColor);
        ItemMeta meta = glassPane.getItemMeta();
        meta.displayName(Component.text("§eCooldown: " + remainingTime + " seconds remaining"));
        meta.setLore(java.util.Collections.singletonList("§6Ability: " + ability));
        glassPane.setItemMeta(meta);
        return glassPane;
    }

    private Material getGlassColor(double progress) {
        if (progress > 0.75) return GLASS_COLORS[0];
        else if (progress > 0.5) return GLASS_COLORS[1];
        else if (progress > 0.25) return GLASS_COLORS[2];
        else return GLASS_COLORS[3];
    }
}
