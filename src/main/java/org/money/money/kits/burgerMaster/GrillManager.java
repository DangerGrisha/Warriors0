package org.money.money.kits.burgerMaster;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.EulerAngle;

import java.util.*;

/** Управляет грилями + GUI: слева выбор, центр прогресс, справа выход (до 4-х). */
public final class GrillManager implements Listener {

    /* --------------- Геометрия/тайминги/GUI --------------- */

    private static final double TRAPDOOR_Y_OFFSET = 0.42;          // чуть ниже центра
    private static final double HEAD_PITCH_DEG    = -90.0;         // крышка параллельно полу
    private static final long   GUARD_PERIOD      = 20L;           // 1 сек
    private static final long   COOK_TIME_TICKS   = 20L * 60;      // 60 секунд на готовку

    private static final Component GUI_TITLE = Component.text("Grill");
    private static final int GUI_SIZE = 27; // 3 ряда

    // слоты выбора (слева)
    private static final int[] SELECT_SLOTS = { 10, 11, 12 }; // MEAT, DIET, SEA
    // индикаторы прогресса (центр 2x2)
    private static final int[] PROG_SLOTS = { 13, 14, 22, 23 };
    // выходные слоты (справа 2x2)
    private static final int[] OUT_SLOTS  = { 15, 16, 24, 25 };

    /* --------------- Теги/ключи --------------- */

    private static final String SB_TAG = "BURGER_GRILL";

    private final Plugin plugin;
    private final NamespacedKey KEY_OWNER;          // owner uuid в PDC стенда
    private final NamespacedKey KEY_KIND;           // маркер “grill” на стенде
    private final NamespacedKey KEY_GUI_BURGER;     // тип бургера на превью предметах
    private final NamespacedKey KEY_EDIBLE_BURGER;  // тип готового бургера на pumpkin pie
    private final NamespacedKey KEY_GRILL_ITEM;     // предмет гриля для возврата владельцу

    /* --------------- Сущности гриля --------------- */

    public enum BurgerType { MEAT, DIET, SEA, LOCKED }

    /** Одно “место готовки” в гриле. */
    private static final class BurgerJob {
        BurgerType type;
        long finishAtMs;
        BukkitTask ticker;     // обновление UI
        boolean ready;         // true — готов, ждёт выдачи
    }

    /** Данные одного грила. */
    public static final class Grill {
        public final UUID ownerId;
        public final Location campfireBlock; // блок-локация костра
        public final UUID standId;
        public final Inventory inv;          // меню
        public final BurgerJob[] jobs = new BurgerJob[4]; // максимум 4 готовки
        Grill(UUID ownerId, Location campfireBlock, UUID standId, Inventory inv) {
            this.ownerId = ownerId; this.campfireBlock = campfireBlock; this.standId = standId; this.inv = inv;
        }
    }

    // campfireLoc -> grill
    private final Map<Location, Grill> grills = new HashMap<>();
    // инвентарь -> grill
    private final Map<Inventory, Grill> invToGrill = new IdentityHashMap<>();
    // активные GUI (быстрая проверка)
    private final Set<Inventory> grillInventories = Collections.newSetFromMap(new IdentityHashMap<>());
    // охрана костров
    private final Map<Location, BukkitTask> guards = new HashMap<>();

    /* --------------- Рецепты --------------- */

    private static final Set<Material> COOKED_MEATS = Set.of(
            Material.COOKED_PORKCHOP, Material.COOKED_BEEF, Material.COOKED_MUTTON,
            Material.COOKED_CHICKEN, Material.COOKED_RABBIT
    );
    private static final Set<Material> COOKED_FISH = Set.of(
            Material.COOKED_COD, Material.COOKED_SALMON
    );

    /* --------------- Конструктор --------------- */

    public GrillManager(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.KEY_OWNER          = new NamespacedKey(plugin, "grill_owner");
        this.KEY_KIND           = new NamespacedKey(plugin, "grill_kind");
        this.KEY_GUI_BURGER     = new NamespacedKey(plugin, "grill_burger_type");
        this.KEY_EDIBLE_BURGER  = new NamespacedKey(plugin, "burger_kind");
        this.KEY_GRILL_ITEM     = new NamespacedKey(plugin, "burger_grill_item");

        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /* ==================== Публичный API ==================== */

    /** Поставить гриль по блочной локации. */
    public void spawnGrill(Player owner, Location baseBlockLoc, boolean lit) {
        Location key = baseBlockLoc.toBlockLocation();
        World w = key.getWorld();

        // 1) костёр
        var data = (org.bukkit.block.data.type.Campfire) Bukkit.createBlockData(Material.CAMPFIRE);
        data.setLit(lit);
        data.setSignalFire(false);
        key.getBlock().setBlockData(data, false);

        // 2) крышка — арморстенд с Iron Trapdoor
        Location standLoc = key.toCenterLocation().add(0, TRAPDOOR_Y_OFFSET, 0);
        ArmorStand as = w.spawn(standLoc, ArmorStand.class, s -> {
            s.setInvisible(true);
            s.setMarker(true);
            s.setBasePlate(false);
            s.setSmall(false);
            s.setGravity(false);
            s.setPersistent(true);
            s.setInvulnerable(true);
            s.addScoreboardTag(SB_TAG);
            s.getPersistentDataContainer().set(KEY_KIND,  PersistentDataType.BYTE, (byte)1);
            s.getPersistentDataContainer().set(KEY_OWNER, PersistentDataType.STRING, owner.getUniqueId().toString());
            s.getEquipment().setHelmet(new ItemStack(Material.IRON_TRAPDOOR));
            s.setHeadPose(new EulerAngle(Math.toRadians(HEAD_PITCH_DEG), 0, 0));
        });

        // 3) GUI
        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, GUI_TITLE);
        decorate(inv);

        Grill g = new Grill(owner.getUniqueId(), key, as.getUniqueId(), inv);
        grills.put(key, g);
        invToGrill.put(inv, g);
        grillInventories.add(inv);

        // 4) охрана
        startGuard(g);
    }

    /** Владелец кликает ПКМ по костру — открыть меню. */
    public boolean tryOpenMenu(Player player, Block clicked) {
        Grill g = grills.get(clicked.getLocation().toBlockLocation());
        if (g == null) return false;

        if (!g.ownerId.equals(player.getUniqueId())) {
            player.sendMessage(Component.text("Это не ваш гриль.", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
            return true;
        }
        player.openInventory(g.inv);
        player.playSound(clicked.getLocation().toCenterLocation(), Sound.BLOCK_CHEST_OPEN, 0.85f, 1.0f);
        return true;
    }

    /** Полностью убрать гриль (стенд, таймеры, GUI) и вернуть предмет владельцу. */
    public void removeGrillAt(Location campfireBlockLoc) {
        Location key = campfireBlockLoc.toBlockLocation();
        stopGuard(key);
        Grill g = grills.remove(key);
        if (g == null) return;

        // остановить джобы
        for (int i = 0; i < g.jobs.length; i++) {
            BurgerJob job = g.jobs[i];
            if (job != null && job.ticker != null) job.ticker.cancel();
            g.jobs[i] = null;

            // если готовый бургер уже лежит справа — дропнем
            ItemStack out = g.inv.getItem(OUT_SLOTS[i]);
            if (out != null && isBurgerEdible(out)) {
                World w = key.getWorld();
                w.dropItemNaturally(key.toCenterLocation().add(0, 1, 0), out);
                g.inv.setItem(OUT_SLOTS[i], null);
            }
            // почистим центр
            g.inv.setItem(PROG_SLOTS[i], makeCenterIdle());
        }

        grillInventories.remove(g.inv);
        invToGrill.remove(g.inv);

        // удалить крышку по UUID
        Entity e = Bukkit.getEntity(g.standId);
        if (e instanceof ArmorStand as && as.isValid()) as.remove();

        // подчистить любые наши стенды рядом (страховка)
        World w = key.getWorld();
        Location center = key.toCenterLocation();
        for (Entity ent : w.getNearbyEntities(center, 1.2, 1.2, 1.2)) {
            if (ent instanceof ArmorStand as && as.getScoreboardTags().contains(SB_TAG)) {
                as.remove();
            }
        }

        // вернуть предмет владельцу
        returnGrillToOwner(g.ownerId, center);
    }

    public boolean isGrillCampfire(Block b) {
        return grills.containsKey(b.getLocation().toBlockLocation());
    }

    /* ==================== GUI построение ==================== */

    private void decorate(Inventory inv) {
        // всё — серыми панелями
        ItemStack pane = pane(" ");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);

        // СЛЕВА — выбор
        inv.setItem(SELECT_SLOTS[0], makeBurgerPreview(BurgerType.MEAT));
        inv.setItem(SELECT_SLOTS[1], makeBurgerPreview(BurgerType.DIET));
        inv.setItem(SELECT_SLOTS[2], makeBurgerPreview(BurgerType.SEA));

        // ЦЕНТР — 4 индикатора
        for (int i = 0; i < 4; i++) inv.setItem(PROG_SLOTS[i], makeCenterIdle());

        // СПРАВА — 4 “выхода”
        for (int i = 0; i < 4; i++) inv.setItem(OUT_SLOTS[i], makeOutputEmpty(i));
    }

    private ItemStack pane(String name) {
        ItemStack it = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text(name));
        it.setItemMeta(im);
        return it;
    }

    /** Превью-айтем для выбора рецепта. */
    private ItemStack makeBurgerPreview(BurgerType type) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta im = it.getItemMeta();
        switch (type) {
            case MEAT -> {
                im.displayName(Component.text("Meat Burger", NamedTextColor.GOLD));
                im.lore(List.of(
                        Component.text("Эффект: Strength I (30с)", NamedTextColor.GRAY),
                        Component.text("Рецепт: 2x Bread + 1x Cooked Meat", NamedTextColor.DARK_GRAY)
                ));
            }
            case DIET -> {
                im.displayName(Component.text("Diet Burger", NamedTextColor.GREEN));
                im.lore(List.of(
                        Component.text("Эффект: Regeneration I (30с)", NamedTextColor.GRAY),
                        Component.text("Рецепт: 2x Bread + 3x Carrot/3x Potato", NamedTextColor.DARK_GRAY)
                ));
            }
            case SEA -> {
                im.displayName(Component.text("Sea Burger", NamedTextColor.AQUA));
                im.lore(List.of(
                        Component.text("Эффект: Speed I (30с)", NamedTextColor.GRAY),
                        Component.text("Рецепт: 2x Bread + 1x Cooked Fish", NamedTextColor.DARK_GRAY)
                ));
            }
            default -> {
                im.displayName(Component.text("Chef's Special", NamedTextColor.DARK_GRAY));
                im.lore(List.of(Component.text("Скоро...", NamedTextColor.GRAY)));
            }
        }
        im.getPersistentDataContainer().set(KEY_GUI_BURGER, PersistentDataType.STRING, type.name());
        it.setItemMeta(im);
        return it;
    }

    /** Пустая ячейка “выхода”. */
    private ItemStack makeOutputEmpty(int idx) {
        ItemStack it = new ItemStack(Material.HOPPER);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Выход " + (idx+1) + ": пусто", NamedTextColor.GRAY));
        it.setItemMeta(im);
        return it;
    }

    /** Центр — “ничего не готовится”. */
    private ItemStack makeCenterIdle() {
        ItemStack it = new ItemStack(Material.CLOCK);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Нет активной готовки", NamedTextColor.DARK_GRAY));
        it.setItemMeta(im);
        return it;
    }

    /** Центр — прогресс конкретной готовки. */
    private ItemStack makeCenterProgress(BurgerType type, int secsLeft) {
        ItemStack it = new ItemStack(Material.CLOCK);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text(typeName(type) + " — " + secsLeft + "с", NamedTextColor.YELLOW));
        it.setItemMeta(im);
        return it;
    }

    /** Выход — “идёт готовка ... сек”. */
    private ItemStack makeOutputCooking(BurgerType type, int secsLeft) {
        ItemStack it = new ItemStack(Material.SMOKER);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("Готовится: " + typeName(type) + " (" + secsLeft + "с)", NamedTextColor.GOLD));
        it.setItemMeta(im);
        return it;
    }

    private String typeName(BurgerType t) {
        return switch (t) {
            case MEAT -> "Meat Burger";
            case DIET -> "Diet Burger";
            case SEA  -> "Sea Burger";
            default   -> "Burger";
        };
    }

    /** Готовый бургер — pumpkin pie с PDC. */
    private ItemStack craftActualBurger(BurgerType type) {
        ItemStack pie = new ItemStack(Material.PUMPKIN_PIE);
        ItemMeta im = pie.getItemMeta();
        switch (type) {
            case MEAT -> im.displayName(Component.text("Meat Burger", NamedTextColor.GOLD));
            case DIET -> im.displayName(Component.text("Diet Burger", NamedTextColor.GREEN));
            case SEA  -> im.displayName(Component.text("Sea Burger",  NamedTextColor.AQUA));
            default   -> im.displayName(Component.text("Burger", NamedTextColor.GRAY));
        }
        im.getPersistentDataContainer().set(KEY_EDIBLE_BURGER, PersistentDataType.STRING, type.name());
        pie.setItemMeta(im);
        return pie;
    }

    private boolean isBurgerEdible(ItemStack it) {
        if (it == null || it.getType() != Material.PUMPKIN_PIE || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(KEY_EDIBLE_BURGER, PersistentDataType.STRING);
    }

    /* ==================== Готовка / рецепты ==================== */

    private boolean tryConsumeRecipe(Player p, BurgerType type) {
        switch (type) {
            case MEAT -> {
                if (!hasItem(p, Material.BREAD, 2)) return false;
                Material meat = findFirstPresent(p, COOKED_MEATS);
                if (meat == null) return false;
                consume(p, Material.BREAD, 2);
                consume(p, meat, 1);
                return true;
            }
            case DIET -> {
                if (!hasItem(p, Material.BREAD, 2)) return false;
                boolean okCarrot = hasItem(p, Material.CARROT, 3);
                boolean okPotato = hasItem(p, Material.POTATO, 3);
                if (!okCarrot && !okPotato) return false;
                consume(p, Material.BREAD, 2);
                if (okCarrot) consume(p, Material.CARROT, 3);
                else consume(p, Material.POTATO, 3);
                return true;
            }
            case SEA -> {
                if (!hasItem(p, Material.BREAD, 2)) return false;
                Material fish = findFirstPresent(p, COOKED_FISH);
                if (fish == null) return false;
                consume(p, Material.BREAD, 2);
                consume(p, fish, 1);
                return true;
            }
            default -> { return false; }
        }
    }

    private boolean hasItem(Player p, Material mat, int amount) {
        int need = amount;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null || it.getType() != mat) continue;
            need -= it.getAmount();
            if (need <= 0) return true;
        }
        return false;
    }

    private Material findFirstPresent(Player p, Set<Material> allowed) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null) continue;
            if (allowed.contains(it.getType()) && it.getAmount() > 0) return it.getType();
        }
        return null;
    }

    private void consume(Player p, Material mat, int amount) {
        int left = amount;
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() != mat) continue;
            int take = Math.min(left, it.getAmount());
            it.setAmount(it.getAmount() - take);
            if (it.getAmount() <= 0) inv.setItem(i, null);
            left -= take;
            if (left <= 0) break;
        }
    }

    /* ==================== Охрана / снятие ==================== */

    private void startGuard(Grill g) {
        stopGuard(g.campfireBlock);
        BukkitTask t = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // стенд жив?
            Entity e = Bukkit.getEntity(g.standId);
            if (!(e instanceof ArmorStand as) || !as.isValid()) {
                removeGrillAt(g.campfireBlock);
                return;
            }
            // костёр на месте?
            Material m = g.campfireBlock.getBlock().getType();
            if (m != Material.CAMPFIRE && m != Material.SOUL_CAMPFIRE) {
                removeGrillAt(g.campfireBlock);
            }
        }, GUARD_PERIOD, GUARD_PERIOD);
        guards.put(g.campfireBlock, t);
    }

    private void stopGuard(Location key) {
        BukkitTask t = guards.remove(key);
        if (t != null) t.cancel();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onCampfireBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() != Material.CAMPFIRE && b.getType() != Material.SOUL_CAMPFIRE) return;
        Location key = b.getLocation().toBlockLocation();
        if (grills.containsKey(key)) removeGrillAt(key);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockExplode(BlockExplodeEvent e) {
        for (Block b : e.blockList()) {
            if (b.getType() == Material.CAMPFIRE || b.getType() == Material.SOUL_CAMPFIRE) {
                Location key = b.getLocation().toBlockLocation();
                if (grills.containsKey(key)) removeGrillAt(key);
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onEntityExplode(EntityExplodeEvent e) {
        for (Block b : e.blockList()) {
            if (b.getType() == Material.CAMPFIRE || b.getType() == Material.SOUL_CAMPFIRE) {
                Location key = b.getLocation().toBlockLocation();
                if (grills.containsKey(key)) removeGrillAt(key);
            }
        }
    }

    /* ==================== GUI обработка ==================== */

    private boolean isOurInventory(Inventory inv) {
        return grillInventories.contains(inv);
    }

    private int findFreeJobSlot(Grill g) {
        for (int i = 0; i < g.jobs.length; i++) if (g.jobs[i] == null) return i;
        return -1;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!isOurInventory(top)) return;

        e.setCancelled(true); // блокируем дефолтные перемещения

        Grill g = invToGrill.get(top);
        if (g == null) return;

        // клики только по верхнему
        if (e.getClickedInventory() != top) return;

        int slot = e.getSlot();
        ItemStack clicked = e.getCurrentItem();
        Player p = (Player) e.getWhoClicked();

        // 1) Клик по превью (слева) -> попытка стартовать готовку
        if (slot == SELECT_SLOTS[0] || slot == SELECT_SLOTS[1] || slot == SELECT_SLOTS[2]) {
            if (clicked == null || !clicked.hasItemMeta()) return;
            String tag = clicked.getItemMeta().getPersistentDataContainer().get(KEY_GUI_BURGER, PersistentDataType.STRING);
            if (tag == null) return;

            BurgerType type = BurgerType.valueOf(tag);
            int free = findFreeJobSlot(g);
            if (free < 0) {
                p.sendMessage(Component.text("Гриль занят: 4/4.", NamedTextColor.RED));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
                return;
            }
            if (!tryConsumeRecipe(p, type)) {
                p.sendMessage(Component.text("Не хватает ингредиентов.", NamedTextColor.RED));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 0.7f, 0.9f);
                return;
            }

            // создаём джоб
            BurgerJob job = new BurgerJob();
            job.type = type;
            job.finishAtMs = System.currentTimeMillis() + (COOK_TIME_TICKS * 50L);
            job.ready = false;

            // первичное UI
            top.setItem(PROG_SLOTS[free], makeCenterProgress(type, 60));
            top.setItem(OUT_SLOTS[free],  makeOutputCooking(type, 60));

            // тикер обновления
            job.ticker = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                long leftMs = job.finishAtMs - System.currentTimeMillis();
                if (leftMs <= 0) {
                    // готово
                    job.ready = true;
                    if (top.getViewers().contains(p)) {
                        p.playSound(p.getLocation(), Sound.BLOCK_SMITHING_TABLE_USE, 0.75f, 1.6f);
                        p.sendMessage(Component.text(typeName(type) + " готов!", NamedTextColor.GREEN));
                    }
                    // в выход кладём итоговый бургер
                    top.setItem(OUT_SLOTS[free], craftActualBurger(type));
                    // в центр — “готово, заберите”
                    ItemStack done = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
                    ItemMeta dm = done.getItemMeta();
                    dm.displayName(Component.text(typeName(type) + " — Готово! Заберите справа", NamedTextColor.GREEN));
                    done.setItemMeta(dm);
                    top.setItem(PROG_SLOTS[free], done);

                    // больше не обновляем
                    job.ticker.cancel();
                    job.ticker = null;
                } else {
                    int secs = (int)Math.ceil(leftMs / 1000.0);
                    top.setItem(PROG_SLOTS[free], makeCenterProgress(type, secs));
                    top.setItem(OUT_SLOTS[free],  makeOutputCooking(type, secs));
                }
            }, 0L, 20L);

            g.jobs[free] = job;
            return;
        }

        // 2) Клик по выходной ячейке (справа) -> забрать готовый бургер
        for (int i = 0; i < OUT_SLOTS.length; i++) {
            if (slot == OUT_SLOTS[i]) {
                BurgerJob job = g.jobs[i];
                if (job == null || !job.ready) {
                    // либо пусто, либо ещё готовится — игнор
                    return;
                }
                ItemStack out = top.getItem(OUT_SLOTS[i]);
                if (!isBurgerEdible(out)) return; // защита

                // забираем вручную
                Map<Integer, ItemStack> left = p.getInventory().addItem(out.clone());
                left.values().forEach(rem -> p.getWorld().dropItemNaturally(p.getLocation(), rem));
                top.setItem(OUT_SLOTS[i], makeOutputEmpty(i));
                top.setItem(PROG_SLOTS[i], makeCenterIdle());
                g.jobs[i] = null;

                p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.2f);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!isOurInventory(top)) return;
        e.setCancelled(true);
    }

    /* ==================== Еда: эффекты для pumpkin pie ==================== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onEatBurger(PlayerItemConsumeEvent e) {
        ItemStack it = e.getItem();
        if (it == null || it.getType() != Material.PUMPKIN_PIE || !it.hasItemMeta()) return;

        String kind = it.getItemMeta().getPersistentDataContainer().get(KEY_EDIBLE_BURGER, PersistentDataType.STRING);
        if (kind == null) return;

        Player p = e.getPlayer();
        switch (BurgerType.valueOf(kind)) {
            case MEAT -> p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,    20*30, 0, false, true, true));
            case DIET -> p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 20*30, 0, false, true, true));
            case SEA  -> p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,       20*30, 0, false, true, true));
            default -> {}
        }
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_BURP, 0.7f, 1.3f);
    }

    /* ==================== Возврат предмета гриля ==================== */

    private ItemStack makeGrillBlockItem() {
        ItemStack it = new ItemStack(Material.GRAY_GLAZED_TERRACOTTA);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("grill"));
        im.getPersistentDataContainer().set(KEY_GRILL_ITEM, PersistentDataType.BYTE, (byte)1);
        it.setItemMeta(im);
        return it;
    }

    private void giveToHandOrInv(Player p, ItemStack it) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            p.getInventory().setItemInMainHand(it);
        } else {
            var left = p.getInventory().addItem(it);
            if (!left.isEmpty()) {
                p.getWorld().dropItemNaturally(p.getLocation(), left.values().iterator().next());
            }
        }
    }

    private void returnGrillToOwner(UUID ownerId, Location where) {
        ItemStack item = makeGrillBlockItem();
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner != null && owner.isOnline()) {
            giveToHandOrInv(owner, item);
            owner.playSound(owner.getLocation(), Sound.UI_TOAST_IN, 0.8f, 1.2f);
            owner.sendMessage(Component.text("Ваш гриль возвращён.", NamedTextColor.GREEN));
        } else {
            // оффлайн — уронить на месте (или сделай свою систему отложенной выдачи)
            where.getWorld().dropItemNaturally(where, item);
        }
    }
}
