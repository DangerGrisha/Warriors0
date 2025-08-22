package org.money.money.kits.hutao;

import org.bukkit.*;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

// ▲ элементалки
import org.money.money.combat.ElementalReactions;
import org.money.money.combat.ElementalReactions.Element;

public final class HuTaoBoomListener implements Listener {

    private final Plugin plugin;
    private final NamespacedKey KEY_BOOM_ITEM;
    private final ElementalReactions reactions; // ← добавили

    // настройки
    private static final int COOLDOWN_TICKS         = 20 * 120; // 2 мин
    private static final int FUSE_TICKS             = 40;       // 2 сек «поджиг»
    private static final double RADIUS              = 10.0;     // радиус взрыва

    private static final int    EXPLOSION_IGNITE    = 20;       // ~1с горения на попадании

    public HuTaoBoomListener(Plugin plugin, ElementalReactions reactions) {
        this.plugin = plugin;
        this.reactions = reactions;
        this.KEY_BOOM_ITEM = new NamespacedKey(plugin, "hutao_boom_item");
    }

    /** Выдать красный краситель «BOOM». */
    public ItemStack makeBoomDye() {
        ItemStack it = new ItemStack(Material.RED_DYE);
        ItemMeta im = it.getItemMeta();
        im.setDisplayName("§cBOOM");
        im.setLore(java.util.List.of("§7Right-click to start a delayed blast"));
        im.getPersistentDataContainer().set(KEY_BOOM_ITEM, PersistentDataType.BYTE, (byte)1);
        im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(im);
        return it;
    }

    private boolean isBoom(ItemStack it) {
        return it != null && it.getType() == Material.RED_DYE
                && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(KEY_BOOM_ITEM, PersistentDataType.BYTE);
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onUseBoom(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!isBoom(hand)) return;

        // запретим дефолт
        e.setUseItemInHand(Event.Result.DENY);
        e.setCancelled(true);

        // забираем предмет
        if (hand.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
        else hand.setAmount(hand.getAmount() - 1);

        // вернём через 2 минуты
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) {
                p.getInventory().addItem(makeBoomDye());
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.6f);
                p.sendMessage("§cBOOM§7 is ready again!");
            }
        }, COOLDOWN_TICKS);

        // место взрыва фиксируем сейчас
        Location fuseLoc = p.getLocation().clone().add(0, 0.1, 0);

        // эффект «поджиг» — замедление + звук фитиля
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, FUSE_TICKS, 10, false, true, true));
        p.getWorld().playSound(fuseLoc, Sound.ENTITY_TNT_PRIMED, 0.9f, 1.0f);

        // искры во время фитиля (и корректно выключим)
        BukkitTask fuseFx = Bukkit.getScheduler().runTaskTimer(plugin, () ->
                        p.getWorld().spawnParticle(Particle.FLAME, p.getLocation().add(0, 1.0, 0),
                                4, 0.2, 0.2, 0.2, 0.01),
                0L, 4L);
        Bukkit.getScheduler().runTaskLater(plugin, fuseFx::cancel, FUSE_TICKS);

        // взрыв через 2 сек (без настоящего TNT)
        Bukkit.getScheduler().runTaskLater(plugin, () -> explode(p, fuseLoc), FUSE_TICKS);
    }

    private void explode(Player owner, Location center) {
        World w = center.getWorld();
        if (w == null) return;

        // звук взрыва
        w.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f);

        // оранжево-красные партиклы
        var orange = new Particle.DustOptions(org.bukkit.Color.fromRGB(255, 120, 40), 1.5f);
        var red    = new Particle.DustOptions(org.bukkit.Color.fromRGB(255, 40, 20), 1.5f);
        w.spawnParticle(Particle.EXPLOSION, center, 1, 0, 0, 0, 0);
        for (double r = 1.0; r <= RADIUS; r += 0.6) {
            int points = 32;
            for (int i = 0; i < points; i++) {
                double ang = (Math.PI * 2) * i / points;
                double x = Math.cos(ang) * r;
                double z = Math.sin(ang) * r;
                Location p = center.clone().add(x, 0.15, z);
                w.spawnParticle(Particle.DUST, p, 1, 0,0,0, 0, (i % 2 == 0 ? orange : red));
                if (r < 3.0 && (i % 4 == 0)) w.spawnParticle(Particle.FLAME, p, 1, 0,0,0, 0.01);
            }
        }

        // урон всем живым в радиусе (тиммейты тоже получают), кроме автора
        for (var ent : w.getNearbyEntities(center, RADIUS, RADIUS, RADIUS)) {
            if (!(ent instanceof LivingEntity le) || !le.isValid() || le.isDead()) continue;
            if (owner != null && le.getUniqueId().equals(owner.getUniqueId())) continue; // автор не получает

            // небольшое поджигание
            le.setFireTicks(Math.max(le.getFireTicks(), EXPLOSION_IGNITE));

            double total = 28.0; // твои 6 сердец
            double fin = reactions.applyOnTotalDamage(le, total,
                    ElementalReactions.Element.PYRO,
                    40,       // если реакции нет — повесим Pyro на ~2 c
                    true);    // если реакция была — «съедаем» обе ауры
            le.damage(fin, owner);
            le.setFireTicks(Math.max(le.getFireTicks(), 20));
        }
    }
}
