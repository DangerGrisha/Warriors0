package org.money.money.meta;

import com.google.gson.Gson;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Реестр классов из classes.json (источник истины по кулдаунам).
 *
 * <p>Файл лежит в plugins/LastWarriors0/classes.json. При первом запуске
 * копируется из jar; дальше НЕ перезаписывается — правки админа сохраняются.
 * Внешний плагин читает тот же файл:
 * {@code new File(Bukkit.getPluginManager().getPlugin("LastWarriors0").getDataFolder(), "classes.json")}
 *
 * <p>Кулдауны листенеры берут через статические {@link #ticks}/{@link #millis}/{@link #seconds}
 * в момент использования способности — поэтому {@code /warriors reload} меняет кд без рестарта.
 */
public final class ClassRegistry {

    private static volatile ClassRegistry instance;

    private final Map<String, ClassMetadata> byId = new LinkedHashMap<>();

    private ClassRegistry() {
    }

    /** Текущий реестр (null до init). */
    public static ClassRegistry get() {
        return instance;
    }

    /** Загрузить при старте плагина. Не фатально: при ошибке остаются дефолты из кода. */
    public static void init(JavaPlugin plugin) {
        // Источник истины — classes.json, собранный в jar. Раньше здесь был saveResource(false):
        // файл распаковывался в дата-папку ОДИН раз и навсегда «замерзал», из-за чего правки
        // конфига в проекте после первого запуска на сервер уже НЕ попадали. Теперь при каждом
        // старте перезаписываем дата-папочный файл версией из jar. Живые правки на сервере
        // (через /warriors reload) продолжают работать — но действуют до следующего рестарта.
        plugin.saveResource("classes.json", true);
        File file = new File(plugin.getDataFolder(), "classes.json");
        instance = parse(plugin, file);
    }

    /** Перечитать файл (для /warriors reload). @return true если успешно. */
    public static boolean reload(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "classes.json");
        ClassRegistry fresh = parse(plugin, file);
        if (fresh == null) return false;
        instance = fresh;
        return true;
    }

    private static ClassRegistry parse(JavaPlugin plugin, File file) {
        try (Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            ClassesFile parsed = new Gson().fromJson(r, ClassesFile.class);
            ClassRegistry reg = new ClassRegistry();
            if (parsed != null && parsed.classes != null) {
                for (ClassMetadata c : parsed.classes) {
                    if (c != null && c.id != null) reg.byId.put(c.id.toLowerCase(), c);
                }
            }
            plugin.getLogger().info("[classes.json] загружено классов: " + reg.byId.size());
            return reg;
        } catch (Exception e) {
            plugin.getLogger().warning("[classes.json] не удалось загрузить: " + e.getMessage());
            return null;
        }
    }

    public Collection<ClassMetadata> all() {
        return byId.values();
    }

    public ClassMetadata byId(String id) {
        return id == null ? null : byId.get(id.toLowerCase());
    }

    // ---- статические хелперы для листенеров (безопасны при отсутствии файла) ----

    /** Кд в тиках; defTicks — если файла/записи нет. */
    public static int ticks(String classId, String abilityKey, int defTicks) {
        ClassMetadata.Ability a = find(classId, abilityKey);
        return a != null ? a.cooldownTicks : defTicks;
    }

    /** Кд в миллисекундах; defMillis — если файла/записи нет. */
    public static long millis(String classId, String abilityKey, long defMillis) {
        ClassMetadata.Ability a = find(classId, abilityKey);
        return a != null ? a.cooldownTicks * 50L : defMillis;
    }

    /** Кд в секундах (целых); defSeconds — если файла/записи нет. */
    public static int seconds(String classId, String abilityKey, int defSeconds) {
        ClassMetadata.Ability a = find(classId, abilityKey);
        return a != null ? a.cooldownTicks / 20 : defSeconds;
    }

    /** Числовой параметр способности (урон, радиус, длительность...); def — если записи нет. */
    public static double num(String classId, String abilityKey, String param, double def) {
        ClassMetadata.Ability a = find(classId, abilityKey);
        if (a == null) return def;
        Double v = a.param(param);
        return v != null ? v : def;
    }

    /** То же, но целое (округление). */
    public static int numInt(String classId, String abilityKey, String param, int def) {
        return (int) Math.round(num(classId, abilityKey, param, def));
    }

    private static ClassMetadata.Ability find(String classId, String abilityKey) {
        ClassRegistry reg = instance;
        if (reg == null) return null;
        ClassMetadata c = reg.byId(classId);
        return c == null ? null : c.ability(abilityKey);
    }

    /** Корневая структура classes.json. */
    public static final class ClassesFile {
        public int schemaVersion;
        public List<ClassMetadata> classes;
    }
}
