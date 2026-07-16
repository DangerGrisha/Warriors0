package org.money.money.util;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Кастомные текстуры предметов задаются ваниль-компонентом {@code minecraft:item_model}
 * (ресурспак LastWar, неймспейс {@code lastwar}). Переименование/имя в скобках больше не нужно
 * для игроков без OptiFine — текстура берётся из item_model. Имена предметов мы при этом
 * сохраняем (для OptiFine и для читаемости в чате/логах).
 *
 * <p>Использование в фабриках предметов: после настройки меты, до {@code it.setItemMeta(im)}:
 * <pre>ItemModels.apply(im, "ledynagan_bullet_gun");</pre>
 * Список id — в {@code docs/GIVE_COMMANDS.md}.
 */
public final class ItemModels {

    /** Неймспейс ресурспака. */
    public static final String NAMESPACE = "lastwar";

    private ItemModels() {}

    /**
     * Проставляет {@code minecraft:item_model = lastwar:<path>} на мету.
     *
     * @param meta мета предмета (может быть {@code null} — тогда ничего не делаем)
     * @param path путь модели без неймспейса, напр. {@code "abbility_amaterasu"}
     */
    public static void apply(ItemMeta meta, String path) {
        if (meta == null || path == null || path.isEmpty()) return;
        meta.setItemModel(new NamespacedKey(NAMESPACE, path));
    }
}
