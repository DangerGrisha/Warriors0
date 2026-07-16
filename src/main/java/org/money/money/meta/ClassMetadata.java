package org.money.money.meta;

import java.util.List;

/**
 * Описание одного класса из classes.json.
 * Чистый POJO без Bukkit-зависимостей — внешний плагин может скопировать
 * этот класс и парсить тот же файл тем же Gson.
 */
public final class ClassMetadata {

    public String id;            // стабильный ключ: "blastborn", "timewalker", ...
    public String displayName;   // имя для меню
    public String type;          // тип класса: Bruiser / Assassin / Support / ...
    public String rarity;        // COMMON / RARE / EPIC / LEGENDARY
    public int ceiling;          // сложность освоения 1-5
    public List<Ability> abilities;

    public static final class Ability {
        public String key;             // ключ как в /kitgive: "gloves", "run", "ult"
        public String name;            // отображаемое имя способности
        public int cooldownTicks;      // кд в тиках (20 тиков = 1 сек); 0 = нет кд
        public boolean passive;        // true = пассивка/тоггл без кд
        public List<String> description; // 2-3 строки описания
        public java.util.Map<String, Double> params; // урон/радиус/длительность и т.п.

        public double cooldownSeconds() {
            return cooldownTicks / 20.0;
        }

        public Double param(String name) {
            return params == null ? null : params.get(name);
        }
    }

    public Ability ability(String key) {
        if (abilities == null) return null;
        for (Ability a : abilities) {
            if (a.key != null && a.key.equalsIgnoreCase(key)) return a;
        }
        return null;
    }
}
