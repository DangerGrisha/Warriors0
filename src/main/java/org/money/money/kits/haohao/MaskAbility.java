package org.money.money.kits.haohao;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class MaskAbility implements Listener {

    public static final String TAG_MASK_GREEN = "MaskGreen";

    private static final long REFLECT_DELAY_TICKS = 20L; // 1 sec
    private static final long REFLECT_GUARD_TICKS = 2L; // skip only reflected events

    private final Plugin plugin;

    private final NamespacedKey KEY_MASK_YELLOW;
    private final NamespacedKey KEY_MASK_GREEN;

    private final Set<UUID> reflectGuard = new HashSet<>();

    public MaskAbility(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_MASK_YELLOW = new NamespacedKey(plugin, "mask_yellow");
        this.KEY_MASK_GREEN = new NamespacedKey(plugin, "mask_green");
    }

    /* =========================
       Public factory
       ========================= */

    public ItemStack makeYellowMask() {
        ItemStack it = new ItemStack(Material.YELLOW_DYE);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Mask", NamedTextColor.YELLOW));
        im.getPersistentDataContainer().set(KEY_MASK_YELLOW, PersistentDataType.BYTE, (byte) 1);
        im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(im);
        return it;
    }

    private ItemStack makeGreenMask() {
        ItemStack it = new ItemStack(Material.GREEN_DYE);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Mask", NamedTextColor.GREEN));
        im.getPersistentDataContainer().set(KEY_MASK_GREEN, PersistentDataType.BYTE, (byte) 1);
        im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(im);
        return it;
    }

    /* =========================
       Events
       ========================= */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onMaskUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;

        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();

        if (isYellowMask(hand)) {
            e.setCancelled(true);
            p.getInventory().setItemInMainHand(makeGreenMask());
            p.addScoreboardTag(TAG_MASK_GREEN);
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.3f);
            return;
        }

        if (isGreenMask(hand)) {
            e.setCancelled(true);
            p.getInventory().setItemInMainHand(makeYellowMask());
            p.removeScoreboardTag(TAG_MASK_GREEN);
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 0.9f);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker)) return;
        if (!(e.getEntity() instanceof Player victim)) return;

        if (!hasAnyMask(attacker) && !hasAnyMask(victim)) return;

        if (isReflectSkip(victim.getUniqueId())) return;
        if (isReflectSkip(attacker.getUniqueId())) return;
        if (!isEnemyOrNoTeams(attacker, victim)) return;
        if (willKill(victim, e.getFinalDamage())) return;

        double dmg = e.getFinalDamage();

        if (hasGreenMask(attacker) || hasGreenMask(victim)) {
            // Reflect damage back to the attacker after 1s
            scheduleReflect(attacker, victim, dmg);
        }
    }

    /* =========================
       Core
       ========================= */

    private void scheduleReflect(Player target, Player source, double dmg) {
        if (target == null || source == null) return;

        UUID targetId = target.getUniqueId();
        UUID sourceId = source.getUniqueId();

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!target.isOnline() || target.isDead()) return;
            if (!source.isOnline() || source.isDead()) return;
            guard(targetId);
            target.damage(dmg, source);
            Bukkit.getScheduler().runTaskLater(plugin, () -> unguard(targetId), REFLECT_GUARD_TICKS);
        }, REFLECT_DELAY_TICKS);
    }

    private void guard(UUID id) {
        reflectGuard.add(id);
    }

    private void unguard(UUID id) {
        reflectGuard.remove(id);
    }

    private boolean isGuarded(UUID id) {
        return reflectGuard.contains(id);
    }

    private boolean isReflectSkip(UUID id) {
        return isGuarded(id);
    }

    private boolean willKill(Player victim, double dmg) {
        return victim.getHealth() - dmg <= 0.0;
    }

    public static boolean hasGreenMask(Player p) {
        return p.getScoreboardTags().contains(TAG_MASK_GREEN);
    }

    /* =========================
       Item helpers
       ========================= */

    private boolean isYellowMask(ItemStack it) {
        return it != null
                && it.getType() == Material.YELLOW_DYE
                && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(KEY_MASK_YELLOW, PersistentDataType.BYTE);
    }

    private boolean isGreenMask(ItemStack it) {
        return it != null
                && it.getType() == Material.GREEN_DYE
                && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(KEY_MASK_GREEN, PersistentDataType.BYTE);
    }

    private boolean hasAnyMask(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (isYellowMask(it) || isGreenMask(it)) return true;
        }
        return false;
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
