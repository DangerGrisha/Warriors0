package org.money.money.kits.airwalker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.money.money.session.KitSession;

import java.util.*;

public final class WindUltListener implements Listener {

    // ===== Ult settings =====
    private static long ULT_DURATION_TICKS() { return org.money.money.meta.ClassRegistry.numInt("airwalker", "windult", "durationTicks", 600); }  // длительность эффекта — 30с
    private static final long ULT_COOLDOWN_TICKS = 300L * 20L; // кд до возврата кнопки — 5 минут (от активации)
    private static final String TAG_WIND_ULT = "WindUlt";
    // Внешняя игра: пока Airwalker в ульте — ей нельзя подбирать флаг. Тот же scoreboard-тег,
    // что вешает TimeWalker Future Run; его читает внешний плагин.
    private static final String TAG_CANT_PICKUP_FLAG = "cantpickupflag";

    // Slow Falling behavior while airborne (only during ult)
    // Strong/weak difference is basically amplifier; duration is refreshed constantly.
    private static final int SF_REFRESH_TICKS = 30;  // refresh buffer
    private static int SF_STRONG_AMP() { return org.money.money.meta.ClassRegistry.numInt("airwalker", "windult", "slowFallStrongAmplifier", 15); }      // "very strong" slow falling (amplifier doesn't change much, but ok)
    private static int SF_WEAK_AMP() { return org.money.money.meta.ClassRegistry.numInt("airwalker", "windult", "slowFallWeakAmplifier", 5); }      // weak slow falling

    private final Plugin plugin;

    // Item marker
    private final NamespacedKey KEY_WIND_ULT_ITEM;

    // Track ult return item to avoid duplicates / prevent spam
    private final Set<UUID> ultActive = new HashSet<>();

    public WindUltListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_WIND_ULT_ITEM = new NamespacedKey(plugin, "airwalker_wind_ult_item");
    }

    /* ================== Item ================== */

    public ItemStack makeWindUltDye() {
        ItemStack it = new ItemStack(Material.RED_DYE);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("WindUlt", NamedTextColor.WHITE));
        im.getPersistentDataContainer().set(KEY_WIND_ULT_ITEM, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(im);
        return it;
    }

    private boolean isWindUltItem(ItemStack it) {
        if (it == null || it.getType() != Material.RED_DYE || !it.hasItemMeta()) return false;
        ItemMeta im = it.getItemMeta();
        if (im.getPersistentDataContainer().has(KEY_WIND_ULT_ITEM, PersistentDataType.BYTE)) return true;
        return Component.text("WindUlt").equals(im.displayName());
    }

    private boolean playerHasWindUltItem(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (isWindUltItem(it)) return true;
        }
        return false;
    }

    // Consume exactly 1 WindUlt item from main-hand or inventory
    private boolean consumeOneWindUlt(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (isWindUltItem(hand)) {
            int amt = hand.getAmount();
            if (amt <= 1) p.getInventory().setItemInMainHand(null);
            else hand.setAmount(amt - 1);
            return true;
        }
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (isWindUltItem(it)) {
                if (it.getAmount() <= 1) p.getInventory().setItem(i, null);
                else it.setAmount(it.getAmount() - 1);
                return true;
            }
        }
        return false;
    }

    // Return ult item (only if player doesn't already have one)
    private void giveBackWindUlt(Player p) {
        if (playerHasWindUltItem(p)) return;

        ItemStack dye = makeWindUltDye();
        ItemStack mh = p.getInventory().getItemInMainHand();
        if (mh == null || mh.getType() == Material.AIR) {
            p.getInventory().setItemInMainHand(dye);
        } else {
            Map<Integer, ItemStack> left = p.getInventory().addItem(dye);
            if (!left.isEmpty()) {
                p.getWorld().dropItemNaturally(p.getLocation(), left.values().iterator().next());
            }
        }
        p.playSound(p.getLocation(), Sound.UI_TOAST_IN, 0.6f, 1.2f);
        p.sendMessage(Component.text("WindUlt is ready again.", NamedTextColor.GREEN));
    }

    /* ================== Ult activation ================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onUseUlt(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        if (!isWindUltItem(p.getInventory().getItemInMainHand())) return;

        e.setCancelled(true);

        // Already active -> do nothing
        if (p.getScoreboardTags().contains(TAG_WIND_ULT) || ultActive.contains(p.getUniqueId())) {
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.7f);
            p.sendMessage(Component.text("WindUlt is already active.", NamedTextColor.RED));
            return;
        }

        // Consume the ult item to prevent spam; return after duration
        if (!consumeOneWindUlt(p)) return;

        ultActive.add(p.getUniqueId());
        p.addScoreboardTag(TAG_WIND_ULT);
        p.addScoreboardTag(TAG_CANT_PICKUP_FLAG); // пока в ульте — нельзя подбирать флаг

        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.8f);
        p.sendMessage(Component.text("WindUlt activated (30s, cooldown 5m).", NamedTextColor.AQUA));

        // Конец действия ульты (через 45с): снимаем эффекты, но предмет НЕ возвращаем —
        // он на кулдауне.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player online = Bukkit.getPlayer(p.getUniqueId());
            ultActive.remove(p.getUniqueId());

            if (online != null && online.isOnline()) {
                online.removeScoreboardTag(TAG_WIND_ULT);
                online.removeScoreboardTag(TAG_CANT_PICKUP_FLAG);
                online.removePotionEffect(PotionEffectType.SLOW_FALLING);
                online.playSound(online.getLocation(), Sound.UI_TOAST_OUT, 0.7f, 1.2f);
                online.sendMessage(Component.text("WindUlt ended.", NamedTextColor.GRAY));
            }
        }, ULT_DURATION_TICKS());

        // Возврат кнопки ульты только после полного кулдауна (3 минуты от активации).
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player online = Bukkit.getPlayer(p.getUniqueId());
            if (online != null && online.isOnline() && KitSession.isInGame(online)) {
                giveBackWindUlt(online);
                online.playSound(online.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.25f);
                online.sendMessage(Component.text("WindUlt is ready again!", NamedTextColor.AQUA));
            }
        }, ULT_COOLDOWN_TICKS);
    }

    /* ================== Slow Falling while airborne ================== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onMove(org.bukkit.event.player.PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!p.getScoreboardTags().contains(TAG_WIND_ULT)) return;

        updateSlowFalling(p);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onSneak(PlayerToggleSneakEvent e) {
        Player p = e.getPlayer();
        if (!p.getScoreboardTags().contains(TAG_WIND_ULT)) return;

        // Update instantly when shift toggled
        updateSlowFalling(p);
    }

    private void updateSlowFalling(Player p) {
        // Airborne check: block directly below feet is air
        Block below = p.getLocation().getBlock().getRelative(BlockFace.DOWN);
        boolean airborne = below.getType().isAir() && !p.isOnGround();

        if (!airborne) {
            // Landed -> remove
            p.removePotionEffect(PotionEffectType.SLOW_FALLING);
            return;
        }

        int amp = p.isSneaking() ? SF_WEAK_AMP() : SF_STRONG_AMP();

        // Refresh effect continuously so it persists until landing
        PotionEffect eff = new PotionEffect(
                PotionEffectType.SLOW_FALLING,
                SF_REFRESH_TICKS,
                amp,
                false, false, false
        );
        p.addPotionEffect(eff);
    }

    /* ================== Cleanup ================== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        ultActive.remove(p.getUniqueId());
        // scoreboard-теги хранятся в NBT игрока: снимаем при выходе, иначе «нельзя подбирать
        // флаг» (и состояние ульты) залипнет после переподключения.
        p.removeScoreboardTag(TAG_WIND_ULT);
        p.removeScoreboardTag(TAG_CANT_PICKUP_FLAG);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        ultActive.remove(p.getUniqueId());
        p.removeScoreboardTag(TAG_WIND_ULT);
        p.removeScoreboardTag(TAG_CANT_PICKUP_FLAG);
        p.removePotionEffect(PotionEffectType.SLOW_FALLING);
    }
}
