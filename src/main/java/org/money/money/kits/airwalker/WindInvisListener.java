package org.money.money.kits.airwalker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

public final class WindInvisListener implements Listener {

    // ===== Settings =====
    private static int INVIS_TICKS() { return org.money.money.meta.ClassRegistry.numInt("airwalker", "invis", "invisDurationTicks", 600); }           // 30 seconds
    private static final long RETURN_AFTER_TICKS = 3L * 60L * 20L; // 3 minutes

    private final Plugin plugin;

    private final NamespacedKey KEY_INVIS_ITEM;

    // Prevent double spending while the ability is "waiting to return"
    private final Set<UUID> waitingReturn = new HashSet<>();

    public WindInvisListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_INVIS_ITEM = new NamespacedKey(plugin, "airwalker_invis_item");
    }

    /* ================== Item ================== */

    public ItemStack makeInvisDye() {
        ItemStack it = new ItemStack(Material.RED_DYE);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Invis", NamedTextColor.WHITE));
        im.getPersistentDataContainer().set(KEY_INVIS_ITEM, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(im);
        return it;
    }

    private boolean isInvisItem(ItemStack it) {
        if (it == null || it.getType() != Material.RED_DYE || !it.hasItemMeta()) return false;
        ItemMeta im = it.getItemMeta();
        if (im.getPersistentDataContainer().has(KEY_INVIS_ITEM, PersistentDataType.BYTE)) return true;
        return Component.text("Invis").equals(im.displayName());
    }

    private boolean playerHasInvisItem(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (isInvisItem(it)) return true;
        }
        return false;
    }

    // Consume exactly 1 Invis item from main-hand or inventory
    private boolean consumeOneInvis(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (isInvisItem(hand)) {
            int amt = hand.getAmount();
            if (amt <= 1) p.getInventory().setItemInMainHand(null);
            else hand.setAmount(amt - 1);
            return true;
        }
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (isInvisItem(it)) {
                if (it.getAmount() <= 1) p.getInventory().setItem(i, null);
                else it.setAmount(it.getAmount() - 1);
                return true;
            }
        }
        return false;
    }

    // Return +1 Invis item if player doesn't have it
    private void giveBackInvis(Player p) {
        if (playerHasInvisItem(p)) return;

        ItemStack dye = makeInvisDye();
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
        p.sendMessage(Component.text("Invis is ready again.", NamedTextColor.GREEN));
    }

    /* ================== Use ================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;

        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        if (!isInvisItem(p.getInventory().getItemInMainHand())) return;

        e.setCancelled(true);

        UUID id = p.getUniqueId();

        // If already in "waiting return" (item spent), don't allow spam
        if (waitingReturn.contains(id)) {
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 0.7f);
            p.sendActionBar(Component.text("Cooldown...", NamedTextColor.RED));
            return;
        }

        // If already invisible, don't spend again (optional safety)
        if (p.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 0.7f);
            p.sendActionBar(Component.text("Already invisible.", NamedTextColor.GRAY));
            return;
        }

        // Spend item (remove -1)
        if (!consumeOneInvis(p)) return;

        waitingReturn.add(id);

        // Give invis
        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, INVIS_TICKS(), 0, false, false, false));
        p.playSound(p.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 0.8f, 1.6f);
        p.sendMessage(Component.text("You became wind (Invisible) for 30s.", NamedTextColor.AQUA));

        // Return item after 3 minutes (+1)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player online = Bukkit.getPlayer(id);
            waitingReturn.remove(id);
            if (online != null && online.isOnline()) {
                giveBackInvis(online);
            }
        }, RETURN_AFTER_TICKS);
    }

    /* ================== Cleanup ================== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        // we keep waitingReturn as-is (cooldown continues even offline),
        // but you can remove it if you want cooldown to reset on quit.
    }
}
