package org.money.money.session;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

public class ClassItemManager {
    private static final String[] ORDERED_CLASSES = {
            "LadyNagant","Saske","Hutao","Ganyu","Dio","Naruto","BurgerMaster","Uraraka","AirWalker"
    };

    private static final Set<String> ALL_CLASS_TAGS = new LinkedHashSet<>(Arrays.asList(
            "LadyNagant","LadyNagan", // алиас
            "Saske","Hutao","Ganyu","Dio","Naruto","BurgerMaster","Uraraka","AirWalker"
    ));

    public static void stripAllClassTags(Player p) {
        Set<String> copy = new LinkedHashSet<>(p.getScoreboardTags());
        for (String t : copy) if (ALL_CLASS_TAGS.contains(t)) p.removeScoreboardTag(t);
    }

    public static void giveItemsForTaggedClass(Player player) {
        if (player.getScoreboardTags().contains("KIT_GIVEN")) return;


        if (player.getScoreboardTags().contains("KIT_GIVEN")) {
            Bukkit.getLogger().info("[ClassItemManager] skip (KIT_GIVEN) " + player.getName());
            return;
        }
        Set<String> tags = player.getScoreboardTags();
        Bukkit.getLogger().info("[ClassItemManager] tags for " + player.getName() + " -> " + tags);


        // Определяем по приоритету
        String chosen = resolveChosenClassFromTags(player.getScoreboardTags());

        // Лог, чтобы увидеть исходные теги и что выбрали
        Bukkit.getLogger().info("[ClassItemManager] choose for " + player.getName()
                + " from=" + player.getScoreboardTags() + " -> " + chosen);

        // Нормализуем теги: оставляем один
        stripAllClassTags(player);
        if (chosen != null) player.addScoreboardTag(chosen);

        player.addScoreboardTag("KIT_GIVEN");

        if (chosen == null) {
            player.sendMessage("§cNo class tag found, giving default item.");
            player.getInventory().addItem(new ItemStack(Material.STICK));
            player.updateInventory();
            return;
        }

        switch (chosen) {
            case "LadyNagant" -> assignLadyNagant(player);
            case "Saske"      -> assignSaske(player);
            case "Hutao"      -> assignHutao(player);
            case "Ganyu"      -> assignGanyu(player);
            case "Dio"        -> assignDio(player);
            case "Naruto"     -> assignNaruto(player);
            case "BurgerMaster" -> assignBurgerMaster(player);
            case "Uraraka"    -> assignUraraka(player);
            case "AirWalker"    -> assignAirWalker(player);
            default -> {
                player.getInventory().addItem(new ItemStack(Material.STICK));
            }
        }
    }

    private static void cmd(String c){ Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c); }

    // ⚠ Исправь attribute: на большинстве билдов верный id — generic.armor без namespace
    private static final String ARMOR_ATTR = "generic.armor";

    /** Нормализуем алиасы (LadyNagan -> LadyNagant). */
    private static String normalizeClassTag(String tag) {
        if (tag == null) return null;
        if (tag.equalsIgnoreCase("LadyNagan")) return "LadyNagant";
        // остальные теги совпадают с каноническими
        for (String k : ORDERED_CLASSES) {
            if (k.equalsIgnoreCase(tag)) return k;
        }
        return null; // не классовый тег
    }
    // ClassItemManager.java (фрагменты)

    // вместо старого cmd(...)
    private static void cmdConsole(String command) {
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[ClassItemManager] console cmd failed: /" + command + " -> " + t);
        }
    }

    // запуск команды от имени игрока (для команд, где sender должен быть Player)
    private static void cmdAsPlayer(Player p, String command) {
        try {
            // performCommand не бросает эксепшены из плагина наружу,
            // но если внутри плагина что-то кинут — сервер залогирует.
            p.performCommand(command);
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[ClassItemManager] player cmd failed: " + p.getName() + " -> /" + command + " -> " + t);
        }
    }


    /** Определяем единый выбранный тег по фиксированному порядку. */
    private static String resolveChosenClassFromTags(Set<String> tags) {
        if (tags == null || tags.isEmpty()) return null;

        // если есть несколько тегов — берём первый по ORDERED_CLASSES
        for (String want : ORDERED_CLASSES) {
            if (tags.contains(want)) return want;
        }
        // проверяем алиас LadyNagan
        if (tags.contains("LadyNagan")) return "LadyNagant";

        return null;
    }

    /* -------------------- ВЫДАЧА НАБОРОВ -------------------- */
    // ВАЖНО: здесь НЕ меняем/добавляем теги классов. Только команды/предметы.

    private static void assignLadyNagant(Player player) {
        String name = player.getName();
        player.addScoreboardTag("LadyNagan");
        cmd("giveladynagan " + name);
        cmd("attribute " + name + " minecraft:armor base set 25");
        defaultStaffGive(player);
        cmd("skin set zelthew " + name);
    }

    private static void assignSaske(Player player) {
        String name = player.getName();
        cmd("givesaske " + name);
        cmd("attribute " + name + " minecraft:armor base set 25");
        defaultStaffGive(player);
        cmd("skin set Sasuke0 " + name);
    }

    private static void assignHutao(Player player) {
        String name = player.getName();
        cmd("kitgive HuTao homa " + name);
        cmd("kitgive Hutao pyro " + name);
        cmd("kitgive Hutao ult " + name);
        cmd("attribute " + name + " minecraft:armor base set 25");
        defaultStaffGive(player);
        cmd("skin set Poko1212 " + name);
    }

    private static void assignGanyu(Player player) {
        String name = player.getName();
        cmd("kitgive Ganyu bow " + name);
        cmd("kitgive Ganyu bud " + name);
        cmd("kitgive Ganyu ult " + name);
        cmd("attribute " + name + " minecraft:armor base set 25");
        defaultStaffGive(player);
        cmd("skin set Faantasia " + name);
        ItemStack arrows = new ItemStack(Material.ARROW, 64);
        player.getInventory().addItem(arrows);
        player.updateInventory();
    }

    private static void assignDio(Player player) {
        String name = player.getName();
        cmd("kitgive Dio hand " + name);
        cmd("kitgive Dio timestop " + name);
        cmd("kitgive Dio vampire " + name);
        cmd("attribute " + name + " minecraft:armor base set 25");
        defaultStaffGive(player);
        cmd("skin set ianx_xd " + name);
    }

    private static void assignUraraka(Player player) {
        String name = player.getName();
        cmd("kitgive Uraraka glove " + name);
        cmd("kitgive Uraraka gravity " + name);
        cmd("kitgive Uraraka healpost " + name);
        cmd("kitgive Uraraka levmark " + name);
        cmd("attribute " + name + " minecraft:armor base set 25");
        defaultStaffGive(player);
        cmd("skin set GH00ST1EE " + name);
    }

    private static void assignNaruto(Player player) {
        String name = player.getName();

        // Эта команда требует sender=Player (твой стек-трейс)
        cmdAsPlayer(player, "saskesword");

        // Эти, судя по синтаксису, принимают имя цели и нормально работают от консоли
        cmdConsole("kitgive Naruto clones " + name);
        cmdConsole("kitgive Naruto disappear " + name);
        cmdConsole("kitgive Naruto rasengan " + name);

        // В 1.21 у некоторых сборок атрибуты могут ругаться на id — это не критично,
        // но если надо, можно закомментировать.
        cmdConsole("attribute " + name + " minecraft:armor base set 25");

        defaultStaffGive(player);
        cmdConsole("skin set Naruto4 " + name);
    }


    private static void assignBurgerMaster(Player player) {
        String name = player.getName();

        // твои существующие киты/атрибуты
        cmd("kitgive BurgerMaster sword " + name);
        cmd("kitgive BurgerMaster garden " + name);
        cmd("kitgive BurgerMaster grill " + name);
        cmd("kitgive BurgerMaster hungry " + name);
        cmd("attribute " + name + " minecraft:armor base set 25");

        // стандартная палка/шмот (как у тебя было)
        defaultStaffGive(player);

        // скин
        cmd("skin set Jak6464 " + name);

        // ✅ добавить удочку с Быстрой ловлей II и Прочностью III
        ItemStack rod = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = rod.getItemMeta();
        if (meta != null) {
            meta.addEnchant(Enchantment.LURE, 2, true);        // Быстрая ловля II
            meta.addEnchant(Enchantment.MENDING, 3, true);  // Прочность III (Unbreaking III)
            meta.setDisplayName("§bBurgerMaster Rod");
            rod.setItemMeta(meta);
        }
        player.getInventory().addItem(rod);
        player.updateInventory();
    }
    private static void assignAirWalker(Player player) {
        String name = player.getName();

        // твои существующие киты/атрибуты
        cmd("kitgive AirWalker windsword " + name);
        cmd("kitgive AirWalker wind " + name);
        cmd("kitgive AirWalker wind " + name);
        cmd("kitgive AirWalker wind " + name);
        cmd("kitgive AirWalker windinvis " + name);
        cmd("kitgive AirWalker windult " + name);
        cmd("attribute " + name + " minecraft:armor base set 25");

        // стандартная палка/шмот (как у тебя было)
        defaultStaffGive(player);

        // скин
        cmd("skin set Jak6464 " + name);

        player.updateInventory();
    }

    public static void defaultStaffGive(Player player) {
        player.getInventory().addItem(new ItemStack(Material.GRAY_WOOL, 64));
        player.getInventory().addItem(new ItemStack(Material.SHEARS, 1));
        player.getInventory().addItem(new ItemStack(Material.PUMPKIN_PIE, 64));
        player.getInventory().addItem(new ItemStack(Material.WATER_BUCKET, 1));
        for (int i = 0; i < 4; i++) {
            player.getInventory().addItem(new ItemStack(Material.GRAY_WOOL, 64));
        }
        player.updateInventory();
    }
}
