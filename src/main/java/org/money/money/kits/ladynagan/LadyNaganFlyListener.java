package org.money.money.kits.ladynagan;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.money.money.meta.ClassRegistry;
import org.money.money.session.KitSession;
import org.money.money.util.ItemModels;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * «Start Fly» (перо) — Air Walking.
 *
 * ПКМ по перу активирует 20-секундное окно полёта:
 *  • пока игрок ЗАЖИМАЕТ Shift — ему выдаётся левитация (подъём);
 *  • как только Shift отпущен — левитация принудительно снимается;
 *  • повторный ПКМ по перу в течение этих 20с — полностью останавливает полёт досрочно.
 * По завершении (истечение окна ИЛИ досрочная остановка) — кулдаун 60с, перо
 * убирается на время кулдауна и возвращается в руку по его окончании.
 *
 * Слушатель — синглтон на всех игроков, поэтому состояние окна храним по UUID.
 */
public class LadyNaganFlyListener implements Listener {

    private static final String ABILITY = "FlyLadyNagan";

    /** Длительность окна полёта в тиках (400т = 20с). */
    private static int flyDurationTicks() { return ClassRegistry.numInt("ladynagan", "fly", "durationTicks", 400); }
    /** Кулдаун в секундах (из cooldownTicks; 1600т = 80с). */
    private static int flyCooldownSeconds() { return ClassRegistry.seconds("ladynagan", "fly", 80); }
    /** Усиление левитации (amplifier; 2 = Левитация III). */
    private static int levitationAmplifier() { return ClassRegistry.numInt("ladynagan", "fly", "levitationAmplifier", 2); }

    private final Plugin plugin;
    private final LadyCooldownManager cooldownManager;

    /** Активные окна полёта по игроку. Наличие ключа == способность сейчас активна. */
    private final Map<UUID, ActiveFly> active = new HashMap<>();

    public LadyNaganFlyListener(Plugin plugin, LadyCooldownManager cooldownManager) {
        this.plugin = plugin;
        this.cooldownManager = cooldownManager;
    }

    /** Пара задач одного окна полёта: тик-проверка Shift и таймер завершения. */
    private record ActiveFly(BukkitTask ticker, BukkitTask expiry) {
        void cancel() {
            ticker.cancel();
            expiry.cancel();
        }
    }

    /* ===================== Выдача ===================== */

    public ItemStack makeStartFlyFeather() {
        ItemStack feather = new ItemStack(Material.FEATHER);
        ItemMeta meta = feather.getItemMeta();
        meta.displayName(Component.text("Start Fly"));
        meta.setUnbreakable(true);
        meta.setLore(List.of("something"));
        ItemModels.apply(meta, "ledynagan_startfly");
        feather.setItemMeta(meta);
        return feather;
    }

    /* ===================== Активация ===================== */

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return; // без дубля события от off-hand
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (!isStartFlyFeather(player.getInventory().getItemInMainHand())) return;

        UUID id = player.getUniqueId();
        if (active.containsKey(id)) {
            // Повторный ПКМ в течение окна — полностью останавливаем полёт (с кулдауном).
            endFly(player, true);
            return;
        }
        // Старт нового окна. В норме на кулдауне пера в руке нет, но перестрахуемся.
        if (!cooldownManager.isCooldownComplete(player, ABILITY)) return;
        startFly(player);
    }

    private void startFly(Player player) {
        final int amp = levitationAmplifier();

        // Тик-задача: держит Shift → левитация, отпустил → снимаем. Обновляем каждый тик,
        // чтобы отпускание Shift снимало подъём практически мгновенно.
        BukkitTask ticker = new BukkitRunnable() {
            @Override public void run() {
                if (!player.isOnline() || !KitSession.isInGame(player)) {
                    endFly(player, false); // лобби/оффлайн — тихо гасим, без кулдауна и возврата
                    return;
                }
                if (player.isSneaking()) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 20, amp, false, false, false));
                } else if (player.hasPotionEffect(PotionEffectType.LEVITATION)) {
                    player.removePotionEffect(PotionEffectType.LEVITATION);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);

        // Таймер окна: по истечении 20с завершаем полёт и уходим на кулдаун.
        BukkitTask expiry = new BukkitRunnable() {
            @Override public void run() { endFly(player, true); }
        }.runTaskLater(plugin, flyDurationTicks());

        active.put(player.getUniqueId(), new ActiveFly(ticker, expiry));

        try { player.playSound(player.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 1.0f, 1.4f); } catch (Throwable ignored) {}
        player.sendActionBar(Component.text("§bAir Walking §7активен — зажми §fShift §7для подъёма (" + (flyDurationTicks() / 20) + "с)"));
    }

    /**
     * Завершает окно полёта. Идемпотентно: если игрок не в полёте — no-op.
     *
     * @param triggerCooldown {@code true} — снять перо и запустить кулдаун 60с
     *                        (истечение окна / повторный ПКМ);
     *                        {@code false} — тихая уборка (лобби/оффлайн), без кулдауна.
     */
    private void endFly(Player player, boolean triggerCooldown) {
        ActiveFly af = active.remove(player.getUniqueId());
        if (af == null) return;
        af.cancel();

        if (player.hasPotionEffect(PotionEffectType.LEVITATION)) {
            player.removePotionEffect(PotionEffectType.LEVITATION);
        }

        if (triggerCooldown) {
            removeStartFlyFeathers(player); // чтобы возврат по кулдауну не задублировал перо
            cooldownManager.startCooldownAndReturn(player, ABILITY, flyCooldownSeconds(), makeStartFlyFeather(), true);
            if (player.isOnline()) {
                try { player.playSound(player.getLocation(), Sound.ENTITY_PHANTOM_HURT, 0.7f, 1.2f); } catch (Throwable ignored) {}
                player.sendActionBar(Component.text("§7Air Walking завершён — кулдаун " + flyCooldownSeconds() + "с"));
            }
        }
    }

    /* ===================== Утилиты ===================== */

    private boolean isStartFlyFeather(ItemStack item) {
        return item != null
                && item.getType() == Material.FEATHER
                && item.hasItemMeta()
                && item.getItemMeta().hasDisplayName()
                && item.getItemMeta().getDisplayName().equals("Start Fly");
    }

    /** Убирает из инвентаря все перья «Start Fly» (перед выдачей нового по кулдауну). */
    private void removeStartFlyFeathers(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isStartFlyFeather(contents[i])) {
                player.getInventory().setItem(i, null);
            }
        }
    }
}
