package org.money.money.kits.opera;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
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

public final class OperaAuraListener implements Listener {

    private enum AuraMode {
        OFF,
        ATTACK,
        FORMATION,
        MARCH
    }

    private static double AURA_RADIUS() { return org.money.money.meta.ClassRegistry.num("opera", "aura", "radius", 10.0); }
    private static final int AURA_TICK_PERIOD = 10; // 0.5s
    private static final int EFFECT_DURATION = 30; // 1.5s

    private final Plugin plugin;
    private final NamespacedKey KEY_AURA_ITEM;
    private final NamespacedKey KEY_UI_MARK;

    private final Map<UUID, AuraMode> modes = new HashMap<>();
    private final Map<UUID, BukkitTask> auraTasks = new HashMap<>();

    public OperaAuraListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_AURA_ITEM = new NamespacedKey(plugin, "opera_aura_item");
        this.KEY_UI_MARK = new NamespacedKey(plugin, "opera_aura_ui");
    }

    public ItemStack makeAuraItem() {
        ItemStack it = new ItemStack(Material.PURPLE_DYE);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Opera Aura", NamedTextColor.LIGHT_PURPLE));
        im.getPersistentDataContainer().set(KEY_AURA_ITEM, PersistentDataType.BYTE, (byte) 1);
        im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(im);
        return it;
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() == null) return;
        if (!e.getAction().isRightClick()) return;

        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!isAuraItem(hand)) return;

        e.setCancelled(true);
        openUi(p);
    }

    @EventHandler(ignoreCancelled = true)
    public void onUiClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory inv = e.getInventory();
        if (!isAuraUi(inv)) return;

        e.setCancelled(true);
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        ItemMeta meta = clicked.getItemMeta();
        if (!meta.getPersistentDataContainer().has(KEY_UI_MARK, PersistentDataType.STRING)) return;

        String id = meta.getPersistentDataContainer().get(KEY_UI_MARK, PersistentDataType.STRING);
        if (id == null) return;

        switch (id) {
            case "attack" -> setMode(p, AuraMode.ATTACK);
            case "formation" -> setMode(p, AuraMode.FORMATION);
            case "march" -> setMode(p, AuraMode.MARCH);
            case "off" -> setMode(p, AuraMode.OFF);
            default -> { return; }
        }

        p.closeInventory();
    }

    @EventHandler public void onUiClose(InventoryCloseEvent e) {
        // no-op, but reserved for future state cleanup
    }

    private void openUi(Player p) {
        Inventory inv = Bukkit.createInventory(p, 9, Component.text("Opera Aura"));

        inv.setItem(1, uiItem(Material.IRON_SWORD, "Attack", NamedTextColor.RED, "attack"));
        inv.setItem(3, uiItem(Material.SHIELD, "Formation", NamedTextColor.BLUE, "formation"));
        inv.setItem(5, uiItem(Material.FEATHER, "March", NamedTextColor.GREEN, "march"));
        inv.setItem(7, uiItem(Material.BARRIER, "Off", NamedTextColor.GRAY, "off"));
        inv.setItem(4, uiItem(Material.NETHER_STAR, "Select", NamedTextColor.LIGHT_PURPLE, "marker"));

        p.openInventory(inv);
    }

    private ItemStack uiItem(Material mat, String name, NamedTextColor color, String id) {
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text(name, color));
        im.getPersistentDataContainer().set(KEY_UI_MARK, PersistentDataType.STRING, id);
        it.setItemMeta(im);
        return it;
    }

    private void setMode(Player p, AuraMode mode) {
        modes.put(p.getUniqueId(), mode);
        if (mode == AuraMode.OFF) {
            stopAura(p);
            return;
        }
        startAura(p);
    }

    private void startAura(Player p) {
        UUID id = p.getUniqueId();
        BukkitTask prev = auraTasks.remove(id);
        if (prev != null) prev.cancel();

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline() || p.isDead()) {
                stopAura(p);
                return;
            }
            if (!hasAuraItem(p)) {
                setMode(p, AuraMode.OFF);
                stopAura(p);
                return;
            }
            AuraMode mode = modes.getOrDefault(id, AuraMode.OFF);
            if (mode == AuraMode.OFF) return;

            applyAura(p, mode);
            spawnAuraParticles(p.getLocation());
        }, 0L, AURA_TICK_PERIOD);

        auraTasks.put(id, task);
    }

    private void stopAura(Player p) {
        UUID id = p.getUniqueId();
        BukkitTask task = auraTasks.remove(id);
        if (task != null) task.cancel();
    }

    private void applyAura(Player center, AuraMode mode) {
        Team team = teamOf(center);
        if (team == null) {
            for (Player ally : Bukkit.getOnlinePlayers()) {
                if (ally == null || !ally.isOnline() || ally.isDead()) continue;
                if (ally.getUniqueId().equals(center.getUniqueId())) continue;
                if (!ally.getWorld().equals(center.getWorld())) continue;
                if (ally.getLocation().distanceSquared(center.getLocation()) > AURA_RADIUS() * AURA_RADIUS()) continue;
                applyModeEffect(ally, mode);
            }
            return;
        }

        for (String entry : team.getEntries()) {
            Player ally = Bukkit.getPlayerExact(entry);
            if (ally == null || !ally.isOnline() || ally.isDead()) continue;
            if (!ally.getWorld().equals(center.getWorld())) continue;
            if (ally.getLocation().distanceSquared(center.getLocation()) > AURA_RADIUS() * AURA_RADIUS()) continue;
            applyModeEffect(ally, mode);
        }
    }

    private void applyModeEffect(Player ally, AuraMode mode) {
        switch (mode) {
            case ATTACK -> ally.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, EFFECT_DURATION, org.money.money.meta.ClassRegistry.numInt("opera", "aura", "attackAmplifier", 0), false, false, true));
            case FORMATION -> ally.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, EFFECT_DURATION, org.money.money.meta.ClassRegistry.numInt("opera", "aura", "formationAmplifier", 0), false, false, true));
            case MARCH -> ally.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, EFFECT_DURATION, org.money.money.meta.ClassRegistry.numInt("opera", "aura", "marchSpeedAmplifier", 0), false, false, true));
            default -> { }
        }
    }

    private void spawnAuraParticles(Location center) {
        Particle.DustOptions purple = new Particle.DustOptions(Color.fromRGB(170, 90, 255), 1.2f);
        Particle.DustOptions gold = new Particle.DustOptions(Color.fromRGB(255, 215, 120), 0.9f);

        for (int i = 0; i < 12; i++) {
            double angle = (Math.PI * 2 * i) / 12;
            double x = Math.cos(angle) * AURA_RADIUS();
            double z = Math.sin(angle) * AURA_RADIUS();
            Location p = center.clone().add(x, 0.2, z);
            center.getWorld().spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, purple);
            if (i % 3 == 0) {
                center.getWorld().spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, gold);
            }
        }
    }

    private boolean isAuraItem(ItemStack it) {
        return it != null
                && it.getType() == Material.PURPLE_DYE
                && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(KEY_AURA_ITEM, PersistentDataType.BYTE);
    }

    private boolean hasAuraItem(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (isAuraItem(it)) return true;
        }
        return false;
    }

    private boolean isAuraUi(Inventory inv) {
        if (inv == null) return false;
        if (inv.getSize() != 9) return false;
        ItemStack it = inv.getItem(4);
        if (it == null || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(KEY_UI_MARK, PersistentDataType.STRING);
    }

    private Team teamOf(Player p) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        return sb.getEntryTeam(p.getName());
    }
}
