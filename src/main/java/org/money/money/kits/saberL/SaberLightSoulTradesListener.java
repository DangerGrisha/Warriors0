package org.money.money.kits.saberL;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.money.money.session.KitResettable;

import java.util.*;

public class SaberLightSoulTradesListener implements Listener, KitResettable {

    private final JavaPlugin plugin;

    // Hook to your Excalibur / soul system
    private final SaberLightExcaliburSoulHook soulHook;

    // GUI constants
    private static final String GUI_TITLE = "§6Soul Contracts";
    private static final int GUI_SIZE = 27;

    // Item name for opening GUI
    private static final String SOUL_TRADES_NAME = "SoulTrades";

    // Slot layout
    private static final int SLOT_INFO = 4;
    private static final int SLOT_TOTEM = 10;
    private static final int SLOT_SPEED_PERM = 11;
    private static final int SLOT_JUMP_TEMP = 12;
    private static final int SLOT_JUMP_PERM = 13;
    private static final int SLOT_REFRESH = 15;
    private static final int SLOT_CLOSE = 16;

    // Costs (read from ClassRegistry at use time)
    private static int COST_SPEED_PERM() { return org.money.money.meta.ClassRegistry.numInt("saberlight", "soultrades", "speedPermSoulCost", 3); }
    private static int COST_JUMP_TEMP() { return org.money.money.meta.ClassRegistry.numInt("saberlight", "soultrades", "jumpTempSoulCost", 1); }
    private static int COST_JUMP_PERM() { return org.money.money.meta.ClassRegistry.numInt("saberlight", "soultrades", "jumpPermSoulCost", 2); }

    // Temp jump
    private static int TEMP_JUMP_SECONDS() { return org.money.money.meta.ClassRegistry.numInt("saberlight", "soultrades", "tempJumpDurationSeconds", 10); }
    // "Jump 10" in Minecraft effect terms means amplifier 9 (because amplifier is zero-based)
    private static int TEMP_JUMP_AMPLIFIER() { return org.money.money.meta.ClassRegistry.numInt("saberlight", "soultrades", "tempJumpAmplifier", 9); }

    // Permanent buff level caps (optional safety; raise/remove if you want)
    private static int MAX_PERM_SPEED_LEVEL() { return org.money.money.meta.ClassRegistry.numInt("saberlight", "soultrades", "maxPermSpeedLevel", 3); } // Speed I..III
    private static int MAX_PERM_JUMP_LEVEL() { return org.money.money.meta.ClassRegistry.numInt("saberlight", "soultrades", "maxPermJumpLevel", 3); }  // Jump I..III

    // Per-player match state
    private final Map<UUID, Integer> permanentSpeedLevel = new HashMap<>();
    private final Map<UUID, Integer> permanentJumpLevel = new HashMap<>();
    private final Map<UUID, Integer> totemsBought = new HashMap<>();
    private final Set<UUID> tempJumpCredit = new HashSet<>(); // полцены temp-jump: оплаченный даёт ещё один бесплатный

    public SaberLightSoulTradesListener(JavaPlugin plugin, SaberLightExcaliburSoulHook soulHook) {
        this.plugin = plugin;
        this.soulHook = soulHook;
    }

    // =========================================================
    // Public helper: create SoulTrades item (Blue Dye)
    // =========================================================
    public static ItemStack makeSoulTradesItem() {
        ItemStack it = new ItemStack(Material.BLUE_DYE);
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;

        meta.displayName(Component.text("SoulTrades"));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("§7Right click to open Soul Contracts"));
        lore.add(Component.text("§7Spend souls on upgrades"));
        meta.lore(lore);

        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }

    // =========================================================
    // Open GUI on right click with Blue Dye SoulTrades
    // =========================================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onSoulTradesInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();

        Action action = e.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return; // avoid offhand duplicate event

        ItemStack hand = e.getItem();
        if (!isSoulTradesItem(hand)) return;

        // Allow SaberLight and SaberD users (by tag)
        if (!p.getScoreboardTags().contains("LightSaber") && !p.getScoreboardTags().contains("DarkSaber")) {
            p.sendMessage("§cOnly LightSaber/DarkSaber can use SoulTrades.");
            return;
        }

        e.setCancelled(true);
        openSoulTradesMenu(p);
    }

    // =========================================================
    // GUI click handling
    // =========================================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onSoulTradesClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getView() == null) return;
        if (!GUI_TITLE.equals(e.getView().getTitle())) return;

        e.setCancelled(true);

        // Prevent taking items from own inventory while menu open (simple version)
        if (e.getClickedInventory() == null) return;
        if (e.getClickedInventory().getType() != InventoryType.CHEST) return;

        int slot = e.getRawSlot();

        switch (slot) {
            case SLOT_TOTEM -> handleBuyTotem(p);
            case SLOT_SPEED_PERM -> handleBuyPermanentSpeed(p);
            case SLOT_JUMP_TEMP -> handleBuyTempJump(p);
            case SLOT_JUMP_PERM -> handleBuyPermanentJump(p);
            case SLOT_REFRESH -> openSoulTradesMenu(p);
            case SLOT_CLOSE -> p.closeInventory();
            default -> {
            }
        }
    }

    // =========================================================
    // Re-apply permanent buffs on respawn (whole match feel)
    // =========================================================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();

        // Delay 1 tick so player fully respawns before effects apply
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            applyPermanentEffects(p, id);
        }, 1L);
    }

    // =========================================================
    // GUI building
    // =========================================================
    private void openSoulTradesMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, GUI_TITLE);

        // Fill background
        ItemStack filler = makeGuiItem(Material.GRAY_STAINED_GLASS_PANE, "§8", List.of());
        for (int i = 0; i < GUI_SIZE; i++) inv.setItem(i, filler);

        // Info panel
        int souls = getSoulsSafe(p);
        UUID id = p.getUniqueId();
        int speedLvl = permanentSpeedLevel.getOrDefault(id, 0);
        int jumpLvl = permanentJumpLevel.getOrDefault(id, 0);
        int totemCost = getNextTotemCost(p);

        inv.setItem(SLOT_INFO, makeGuiItem(
                Material.NETHER_STAR,
                "§6Soul Contracts",
                List.of(
                        "§7Souls: §e" + souls,
                        "§7Permanent Speed: §b" + roman(speedLvl),
                        "§7Permanent Jump: §a" + roman(jumpLvl),
                        "§7Next Totem Cost: §6" + totemCost,
                        "§8Choose a contract..."
                )
        ));

        // Totem (3 first, then 4 forever)
        inv.setItem(SLOT_TOTEM, makeGuiItem(
                Material.TOTEM_OF_UNDYING,
                "§6Sacred Totem",
                List.of(
                        "§7Buy a Totem of Undying",
                        "§7Cost: §e" + totemCost + " soul(s)",
                        "§8First buy = 3, then 4 forever",
                        canAffordLine(souls, totemCost)
                )
        ));

        // Permanent Speed +1
        inv.setItem(SLOT_SPEED_PERM, makeGuiItem(
                Material.SUGAR,
                "§bRoyal Haste §7(+1 Speed)",
                List.of(
                        "§7Permanent for the match",
                        "§7Cost: §e" + COST_SPEED_PERM() + " soul(s)",
                        "§7Current: §b" + roman(speedLvl),
                        "§7Max: §b" + roman(MAX_PERM_SPEED_LEVEL()),
                        canAffordLine(souls, COST_SPEED_PERM())
                )
        ));

        // Temporary Jump 10s
        inv.setItem(SLOT_JUMP_TEMP, makeGuiItem(
                Material.RABBIT_FOOT,
                "§eSky Burst §7(Jump X / 10s)",
                List.of(
                        "§7Jump Boost X for §e" + TEMP_JUMP_SECONDS() + "s",
                        "§7Cost: §e" + COST_JUMP_TEMP() + " soul(s)",
                        "§8Great for engage / escape",
                        canAffordLine(souls, COST_JUMP_TEMP())
                )
        ));

        // Permanent Jump +1
        inv.setItem(SLOT_JUMP_PERM, makeGuiItem(
                Material.SLIME_BALL,
                "§aSkybound Oath §7(+1 Jump)",
                List.of(
                        "§7Permanent for the match",
                        "§7Cost: §e" + COST_JUMP_PERM() + " soul(s)",
                        "§7Current: §a" + roman(jumpLvl),
                        "§7Max: §a" + roman(MAX_PERM_JUMP_LEVEL()),
                        canAffordLine(souls, COST_JUMP_PERM())
                )
        ));

        // Refresh
        inv.setItem(SLOT_REFRESH, makeGuiItem(
                Material.CLOCK,
                "§eRefresh",
                List.of("§7Reload current souls and upgrade state")
        ));

        // Close
        inv.setItem(SLOT_CLOSE, makeGuiItem(
                Material.BARRIER,
                "§cClose",
                List.of("§7Close Soul Contracts")
        ));

        p.openInventory(inv);
    }

    // =========================================================
    // Purchase handlers
    // =========================================================
    private void handleBuyTotem(Player p) {
        int cost = getNextTotemCost(p);
        if (!trySpendSouls(p, cost)) {
            p.sendMessage("§cNot enough souls for a Totem. Need §e" + cost + "§c.");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 0.9f);
            openSoulTradesMenu(p);
            return;
        }

        p.getInventory().addItem(new ItemStack(Material.TOTEM_OF_UNDYING, 1));

        UUID id = p.getUniqueId();
        totemsBought.put(id, totemsBought.getOrDefault(id, 0) + 1);

        p.sendMessage("§6Soul Contract: §fYou bought a §eTotem§f for §e" + cost + "§f soul(s).");
        p.playSound(p.getLocation(), Sound.ITEM_TOTEM_USE, 0.6f, 1.4f);
        openSoulTradesMenu(p);
    }

    private void handleBuyPermanentSpeed(Player p) {
        UUID id = p.getUniqueId();
        int current = permanentSpeedLevel.getOrDefault(id, 0);

        if (current >= MAX_PERM_SPEED_LEVEL()) {
            p.sendMessage("§cPermanent Speed is already at max (§b" + roman(MAX_PERM_SPEED_LEVEL()) + "§c).");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 0.9f);
            openSoulTradesMenu(p);
            return;
        }

        if (!trySpendSouls(p, COST_SPEED_PERM())) {
            p.sendMessage("§cNot enough souls for Permanent Speed. Need §e" + COST_SPEED_PERM() + "§c.");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 0.9f);
            openSoulTradesMenu(p);
            return;
        }

        int next = current + 1;
        permanentSpeedLevel.put(id, next);
        applyPermanentEffects(p, id);

        p.sendMessage("§6Soul Contract: §fPermanent §bSpeed " + roman(next) + " §funlocked.");
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
        openSoulTradesMenu(p);
    }

    private void handleBuyTempJump(Player p) {
        UUID id = p.getUniqueId();
        // Полцены: 1 душа = 2 прыжка (эффективно 0.5 души за прыжок).
        boolean free = tempJumpCredit.remove(id);
        if (!free) {
            if (!trySpendSouls(p, COST_JUMP_TEMP())) {
                p.sendMessage("§cNot enough souls for Jump Burst. Need §e" + COST_JUMP_TEMP() + "§c.");
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 0.9f);
                openSoulTradesMenu(p);
                return;
            }
            tempJumpCredit.add(id); // оплатил 1 душу → следующий прыжок бесплатный
        }

        // Add temporary Jump X (10s). Ambient/particles hidden for cleaner look.
        p.addPotionEffect(new PotionEffect(
                PotionEffectType.JUMP_BOOST,
                TEMP_JUMP_SECONDS() * 20,
                TEMP_JUMP_AMPLIFIER(),
                false,
                false,
                true
        ));

        p.sendMessage("§6Soul Contract: §fJump Burst activated (§e10s§f)." + (free ? " §8(бесплатно ½)" : ""));
        p.playSound(p.getLocation(), Sound.ENTITY_BREEZE_JUMP, 0.8f, 1.15f);
        openSoulTradesMenu(p);
    }

    private void handleBuyPermanentJump(Player p) {
        UUID id = p.getUniqueId();
        int current = permanentJumpLevel.getOrDefault(id, 0);

        if (current >= MAX_PERM_JUMP_LEVEL()) {
            p.sendMessage("§cPermanent Jump is already at max (§a" + roman(MAX_PERM_JUMP_LEVEL()) + "§c).");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 0.9f);
            openSoulTradesMenu(p);
            return;
        }

        if (!trySpendSouls(p, COST_JUMP_PERM())) {
            p.sendMessage("§cNot enough souls for Permanent Jump. Need §e" + COST_JUMP_PERM() + "§c.");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 0.9f);
            openSoulTradesMenu(p);
            return;
        }

        int next = current + 1;
        permanentJumpLevel.put(id, next);
        applyPermanentEffects(p, id);

        p.sendMessage("§6Soul Contract: §fPermanent §aJump " + roman(next) + " §funlocked.");
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.35f);
        openSoulTradesMenu(p);
    }

    // =========================================================
    // Effect application
    // =========================================================
    private void applyPermanentEffects(Player p, UUID id) {
        int speedLvl = permanentSpeedLevel.getOrDefault(id, 0);
        int jumpLvl = permanentJumpLevel.getOrDefault(id, 0);

        // Minecraft amplifier is zero-based:
        // Speed I -> amp 0, Speed II -> amp 1, etc.
        if (speedLvl > 0) {
            p.addPotionEffect(new PotionEffect(
                    PotionEffectType.SPEED,
                    Integer.MAX_VALUE,
                    speedLvl - 1,
                    false,
                    false,
                    true
            ));
        }

        if (jumpLvl > 0) {
            p.addPotionEffect(new PotionEffect(
                    PotionEffectType.JUMP_BOOST,
                    Integer.MAX_VALUE,
                    jumpLvl - 1,
                    false,
                    false,
                    true
            ));
        }
    }

    // =========================================================
    // Soul economy helpers (hook to your Excalibur system)
    // =========================================================
    private int getSoulsSafe(Player p) {
        try {
            return Math.max(0, soulHook.getSouls(p));
        } catch (Exception ex) {
            p.sendMessage("§cSoul hook error (getSouls). Check console.");
            ex.printStackTrace();
            return 0;
        }
    }

    private boolean trySpendSouls(Player p, int amount) {
        if (amount <= 0) return true;

        int souls = getSoulsSafe(p);
        if (souls < amount) return false;

        int newSouls = souls - amount;
        try {
            soulHook.setSouls(p, newSouls); // This should also rebuild Excalibur meta/attributes in your existing system
            p.sendActionBar(Component.text("§6Souls: §e" + newSouls));
            return true;
        } catch (Exception ex) {
            p.sendMessage("§cSoul hook error (setSouls). Check console.");
            ex.printStackTrace();
            return false;
        }
    }

    // =========================================================
    // Totem pricing: first 3, then 4 forever
    // =========================================================
    private int getNextTotemCost(Player p) {
        int bought = totemsBought.getOrDefault(p.getUniqueId(), 0);
        return bought <= 0
                ? org.money.money.meta.ClassRegistry.numInt("saberlight", "soultrades", "totemFirstSoulCost", 3)
                : org.money.money.meta.ClassRegistry.numInt("saberlight", "soultrades", "totemNextSoulCost", 4);
    }

    // =========================================================
    // Item / GUI utils
    // =========================================================
    private boolean isSoulTradesItem(ItemStack it) {
        if (it == null || it.getType() != Material.BLUE_DYE || !it.hasItemMeta()) return false;

        ItemMeta meta = it.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return false;

        String plain = ChatColor.stripColor(meta.getDisplayName());
        return plain != null && plain.equalsIgnoreCase(SOUL_TRADES_NAME);
    }

    private ItemStack makeGuiItem(Material mat, String name, List<String> loreLines) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;

        meta.displayName(Component.text(name));
        if (loreLines != null && !loreLines.isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String s : loreLines) lore.add(Component.text(s));
            meta.lore(lore);
        }

        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        it.setItemMeta(meta);
        return it;
    }

    private String canAffordLine(int souls, int cost) {
        return (souls >= cost) ? "§aClick to purchase" : "§cNot enough souls";
    }

    private String roman(int level) {
        if (level <= 0) return "0";
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(level);
        };
    }

    // =========================================================
    // Optional reset API (call when match ends / class removed)
    // =========================================================
    public void resetSaberLightTrades(Player p) {
        UUID id = p.getUniqueId();
        permanentSpeedLevel.remove(id);
        permanentJumpLevel.remove(id);
        totemsBought.remove(id);
        tempJumpCredit.remove(id);

        // Remove only if you want to fully clean buffs on reset:
        p.removePotionEffect(PotionEffectType.SPEED);
        p.removePotionEffect(PotionEffectType.JUMP_BOOST);
    }

    /** Конец игры / вход в лобби — снять перманентные баффы соул-трейдов. */
    @Override
    public void resetPlayer(Player player) {
        resetSaberLightTrades(player);
    }

    // =========================================================
    // Hook interface to plug into your current Excalibur souls system
    // =========================================================
    public interface SaberLightExcaliburSoulHook {
        int getSouls(Player player);
        void setSouls(Player player, int souls);
    }
}
