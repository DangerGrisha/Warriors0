package org.money.money.kits.valkyrie;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.money.money.meta.ClassRegistry;
import org.money.money.session.KitResettable;
import org.money.money.session.KitSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Valkyrie — Teammate Arsenal.
 *
 * <p>RMB the arsenal item to open a menu of your <b>teammates' main weapons</b> and take one.
 * The mode plugin tags the Valkyrie with a scoreboard tag per teammate = that teammate's class
 * name (the /kitgive class id); we read those tags, look up each class's main weapon and show them.
 * Picking a weapon grants it (removing whichever weapon you took last — one at a time), and you can
 * swap to a different one every {@code changeCooldownSeconds} (60s by default).
 *
 * <p>Weapons are produced by {@link #weaponSource} (wired to {@code KitGiveCommand::mainWeaponFor}),
 * so a stolen weapon is a real, fully-functional kit item.
 */
public final class ValkyrieWeaponListener implements Listener, KitResettable {

    private final Plugin plugin;
    private final NamespacedKey KEY_SELECTOR;   // the menu-opener item
    private final NamespacedKey KEY_GRANTED;    // marks a weapon handed out by this ability
    private final NamespacedKey KEY_GRANTED_CLASS;

    /** class id -> weapon item; injected after construction to avoid a ctor cycle with KitGiveCommand. */
    private Function<String, ItemStack> weaponSource = id -> null;

    private final Map<UUID, Long> changeCdMap = new HashMap<>();

    public ValkyrieWeaponListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_SELECTOR = new NamespacedKey(plugin, "valkyrie_arsenal");
        this.KEY_GRANTED = new NamespacedKey(plugin, "valkyrie_granted");
        this.KEY_GRANTED_CLASS = new NamespacedKey(plugin, "valkyrie_granted_class");
    }

    /** Wire the weapon provider (KitGiveCommand::mainWeaponFor). Called once from Main after both exist. */
    public void setWeaponSource(Function<String, ItemStack> src) {
        if (src != null) this.weaponSource = src;
    }

    private static int changeCooldownSeconds() {
        return Math.max(0, ClassRegistry.numInt("valkyrie", "arsenal", "changeCooldownSeconds", 60));
    }

    /* ================== Item ================== */

    /** Create the arsenal selector. /kitgive Valkyrie arsenal */
    public ItemStack makeSelector() {
        ItemStack it = new ItemStack(Material.ECHO_SHARD);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Valkyrie's Arsenal", NamedTextColor.AQUA));
        im.lore(List.of(Component.text("ПКМ — выбрать оружие союзника", NamedTextColor.GRAY)));
        im.getPersistentDataContainer().set(KEY_SELECTOR, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(im);
        return it;
    }

    private boolean isSelector(ItemStack it) {
        if (it == null || it.getType() != Material.ECHO_SHARD || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(KEY_SELECTOR, PersistentDataType.BYTE);
    }

    /* ================== Open the menu ================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!isSelector(p.getInventory().getItemInMainHand())) return;
        e.setCancelled(true);
        if (!KitSession.isInGame(p)) return;
        openArsenal(p);
    }

    private void openArsenal(Player p) {
        // teammate classes = the Valkyrie's scoreboard tags that resolve to a known class with a weapon
        LinkedHashSet<String> classes = new LinkedHashSet<>();
        for (String tag : p.getScoreboardTags()) {
            String id = canonicalClass(tag);
            if (id == null || id.equals("valkyrie")) continue;
            if (weaponSource.apply(id) != null) classes.add(id);
        }

        if (classes.isEmpty()) {
            p.sendActionBar(Component.text("Нет оружия союзников (нет тегов классов)", NamedTextColor.RED));
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 0.7f);
            return;
        }

        List<String> ids = new ArrayList<>(classes);
        int rows = Math.min(6, Math.max(1, (ids.size() + 8) / 9));
        int size = rows * 9;

        ArsenalHolder holder = new ArsenalHolder();
        Inventory inv = Bukkit.createInventory(holder, size,
                Component.text("Оружие союзников", NamedTextColor.DARK_AQUA));
        holder.inv = inv;

        for (int i = 0; i < ids.size() && i < size; i++) {
            String id = ids.get(i);
            ItemStack weapon = weaponSource.apply(id);
            if (weapon == null) continue;
            ItemStack icon = weapon.clone();
            ItemMeta im = icon.getItemMeta();
            if (im != null) {
                List<Component> lore = im.hasLore() && im.lore() != null ? new ArrayList<>(im.lore()) : new ArrayList<>();
                lore.add(Component.text("Союзник: " + classDisplay(id), NamedTextColor.LIGHT_PURPLE));
                lore.add(Component.text("Клик — взять это оружие", NamedTextColor.GRAY));
                im.lore(lore);
                icon.setItemMeta(im);
            }
            inv.setItem(i, icon);
            holder.slotClass.put(i, id);
        }

        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.7f, 1.4f);
    }

    /* ================== Handle selection ================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof ArsenalHolder holder)) return;
        e.setCancelled(true); // lock the menu — no taking/moving items
        if (e.getClickedInventory() != holder.getInventory()) return; // only handle clicks on the menu itself
        if (!(e.getWhoClicked() instanceof Player p)) return;

        String classId = holder.slotClass.get(e.getSlot());
        if (classId == null) return;

        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        long cdMs = changeCooldownSeconds() * 1000L;
        Long last = changeCdMap.get(id);
        if (last != null && now - last < cdMs) {
            long secLeft = (cdMs - (now - last) + 999) / 1000;
            p.sendActionBar(Component.text("Сменить оружие можно через " + secLeft + "с", NamedTextColor.RED));
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.8f);
            return;
        }

        ItemStack weapon = weaponSource.apply(classId);
        if (weapon == null) return;

        removeGrantedFrom(p);                      // swap: drop the previously-taken weapon
        ItemStack granted = weapon.clone();
        stampGranted(granted, classId);
        giveToHandOrInv(p, granted);
        changeCdMap.put(id, now);

        // Blastborn gloves: mark her so the Self-Destruction meter charges an extra 2× (BlastbornManager).
        if ("blastborn".equals(classId)) p.addScoreboardTag("ValkyrieBlast");
        else p.removeScoreboardTag("ValkyrieBlast");

        p.closeInventory();
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_NETHERITE, 0.8f, 1.2f);
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.4f, 1.6f);
        p.sendActionBar(Component.text("Взято оружие: " + classDisplay(classId), NamedTextColor.GREEN));
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof ArsenalHolder) e.setCancelled(true);
    }

    /* ================== Granted-weapon bookkeeping ================== */

    private void stampGranted(ItemStack it, String classId) {
        ItemMeta im = it.getItemMeta();
        if (im == null) return;
        im.getPersistentDataContainer().set(KEY_GRANTED, PersistentDataType.BYTE, (byte) 1);
        im.getPersistentDataContainer().set(KEY_GRANTED_CLASS, PersistentDataType.STRING, classId);
        it.setItemMeta(im);
    }

    private boolean isGranted(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(KEY_GRANTED, PersistentDataType.BYTE);
    }

    private void removeGrantedFrom(Player p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (isGranted(inv.getItem(i))) inv.setItem(i, null);
        }
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

    /* ================== Class-tag resolution ================== */

    /** Normalize a scoreboard tag to a canonical /kitgive class id, or null if it isn't a class tag. */
    private static String canonicalClass(String tag) {
        if (tag == null) return null;
        String s = tag.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return switch (s) {
            case "ganyu" -> "ganyu";
            case "hutao", "hu", "tao" -> "hutao";
            case "dio" -> "dio";
            case "uraraka", "ochaco" -> "uraraka";
            case "naruto" -> "naruto";
            case "burgermaster", "burger" -> "burgermaster";
            case "airwalker", "airwaler", "wind" -> "airwalker";
            case "haohao", "king" -> "haohao";
            case "opera" -> "opera";
            case "lightsaber", "saberlight" -> "lightsaber";
            case "darksaber", "saberdark", "saberd" -> "darksaber";
            case "fukuko", "fuku" -> "fukuko";
            case "ladynagan", "lady", "nagan" -> "ladynagan";
            case "saske", "sasuke" -> "saske";
            case "ishigava", "ishigawa", "ishi" -> "ishigava";
            case "timewalker", "tw" -> "timewalker";
            case "blastborn", "blast" -> "blastborn";
            case "bluerose", "blueroseguardian", "guardian", "brg" -> "bluerose";
            default -> null;
        };
    }

    private static String classDisplay(String id) {
        return switch (id) {
            case "ganyu" -> "Ganyu";
            case "hutao" -> "HuTao";
            case "dio" -> "Dio";
            case "uraraka" -> "Uraraka";
            case "naruto" -> "Naruto";
            case "burgermaster" -> "BurgerMaster";
            case "airwalker" -> "AirWalker";
            case "haohao" -> "HaoHao";
            case "opera" -> "Opera";
            case "lightsaber" -> "LightSaber";
            case "darksaber" -> "DarkSaber";
            case "fukuko" -> "Fukuko";
            case "ladynagan" -> "LadyNagan";
            case "saske" -> "Saske";
            case "ishigava" -> "Ishigava";
            case "timewalker" -> "TimeWalker";
            case "blastborn" -> "Blastborn";
            case "bluerose" -> "BlueRose";
            default -> id;
        };
    }

    /* ================== Cleanup ================== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        changeCdMap.remove(e.getPlayer().getUniqueId());
        e.getPlayer().removeScoreboardTag("ValkyrieBlast");
    }

    @Override
    public void resetPlayer(Player player) {
        if (player == null) return;
        changeCdMap.remove(player.getUniqueId());
        player.removeScoreboardTag("ValkyrieBlast");
        removeGrantedFrom(player); // don't leak a stolen weapon into the lobby
    }

    /** GUI marker so clicks are unambiguous. */
    public static final class ArsenalHolder implements InventoryHolder {
        private Inventory inv;
        private final Map<Integer, String> slotClass = new HashMap<>();
        @Override public Inventory getInventory() { return inv; }
    }
}
