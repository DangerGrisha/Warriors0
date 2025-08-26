package org.money.money.kits.ganyu.give;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import org.money.money.kits.dio.TimeStopListener;      // NEW
import org.money.money.kits.dio.VampireListener;      // NEW
import org.money.money.kits.ganyu.listeners.GanyuBudListener;
import org.money.money.kits.ganyu.listeners.GanyuUltListener;
import org.money.money.kits.hutao.HuTaoInvisListener;
import org.money.money.kits.hutao.HuTaoPyroListener;
import org.money.money.kits.hutao.HuTaoBoomListener;
import org.money.money.kits.uraraka.LevitationMarkListener;
import org.money.money.kits.uraraka.UrarakaGloveListener;
import org.money.money.kits.uraraka.UrarakaGravityListener;
import org.money.money.kits.uraraka.UrarakaHealPostListener;

import java.util.*;

public final class KitGiveCommand implements CommandExecutor, TabCompleter {

    private final Plugin plugin;

    private final GanyuBudListener budListener;
    private final GanyuUltListener ganyuUltListener;
    private final HuTaoInvisListener huTaoInvisListener;
    private final HuTaoPyroListener huTaoPyroListener;
    private final HuTaoBoomListener huTaoBoomListener;
    private final TimeStopListener  timeStopListener;            // NEW
    private final VampireListener   vampireListener;             // NEW
    private final UrarakaGloveListener   urarakaGloveListener;   // NEW
    private final UrarakaGravityListener urarakaGravityListener; // NEW
    private final UrarakaHealPostListener urarakaHealPostListener; // NEW
    private final LevitationMarkListener levitationMarkListener;   // <-- NEW

    private final NamespacedKey KEY_GANYU_BOW;
    private final NamespacedKey KEY_DIO_HAND;

    public KitGiveCommand(Plugin plugin,
                          GanyuBudListener budListener,
                          GanyuUltListener ganyuUltListener,
                          HuTaoInvisListener huTaoInvisListener,
                          HuTaoPyroListener huTaoPyroListener,
                          HuTaoBoomListener huTaoBoomListener,
                          TimeStopListener timeStopListener,
                          VampireListener vampireListener,
                          UrarakaGloveListener urarakaGloveListener,
                          UrarakaGravityListener urarakaGravityListener,
                          UrarakaHealPostListener urarakaHealPostListener,
                          LevitationMarkListener levitationMarkListener) { // <-- NEW

        this.plugin = Objects.requireNonNull(plugin);
        this.budListener = Objects.requireNonNull(budListener);
        this.ganyuUltListener = Objects.requireNonNull(ganyuUltListener);
        this.huTaoInvisListener = Objects.requireNonNull(huTaoInvisListener);
        this.huTaoPyroListener = Objects.requireNonNull(huTaoPyroListener);
        this.huTaoBoomListener = Objects.requireNonNull(huTaoBoomListener);
        this.timeStopListener  = Objects.requireNonNull(timeStopListener);
        this.vampireListener   = Objects.requireNonNull(vampireListener);
        this.urarakaGloveListener = Objects.requireNonNull(urarakaGloveListener);
        this.urarakaGravityListener = Objects.requireNonNull(urarakaGravityListener);
        this.urarakaHealPostListener = Objects.requireNonNull(urarakaHealPostListener);
        this.levitationMarkListener  = Objects.requireNonNull(levitationMarkListener); // <-- NEW

        this.KEY_GANYU_BOW = new NamespacedKey(plugin, "ganyu_bow");
        this.KEY_DIO_HAND  = new NamespacedKey(plugin, "dio_hand");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("kits.give")) {
            sender.sendMessage(ChatColor.RED + "Нет прав.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Использование:");
            sender.sendMessage(ChatColor.GRAY + " /" + label + " Ganyu <bow|bud|ult> [player]");
            sender.sendMessage(ChatColor.GRAY + " /" + label + " HuTao <homa|pyro|ult> [player]");
            sender.sendMessage(ChatColor.GRAY + " /" + label + " Dio <hand|timestop|vampire> [player]");
            sender.sendMessage(ChatColor.GRAY + " /" + label + " Uraraka <glove|gravity|healpost|levmark> [player]"); // <-- NEW
            return true;
        }

        String hero = args[0].toLowerCase(Locale.ROOT);
        String sub  = args[1].toLowerCase(Locale.ROOT);

        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Игрок оффлайн: " + args[2]);
                return true;
            }
        } else {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(ChatColor.RED + "Нужно указать игрока.");
                return true;
            }
            target = p;
        }

        ItemStack itemToGive;
        String pretty;

        switch (hero) {
            case "ganyu" -> {
                switch (sub) {
                    case "bow" -> { itemToGive = makeGanyuBow();                pretty = "Frostsong Bow"; }
                    case "bud" -> { itemToGive = budListener.makeFrostbudDye(); pretty = "Frostbud"; }
                    case "ult", "core", "ultimate" -> { itemToGive = ganyuUltListener.makeUltItem(); pretty = "Everfrost Core"; }
                    default -> { sender.sendMessage(ChatColor.RED + "Неизвестный предмет для Ganyu: " + sub); return true; }
                }
            }
            case "hutao", "hu", "tao" -> {
                switch (sub) {
                    case "homa" -> { itemToGive = huTaoInvisListener.makeHoma();        pretty = "Staff of Homa"; }
                    case "pyro" -> { itemToGive = huTaoPyroListener.makePyroStatusDye(); pretty = "Pyro Status"; }
                    case "ult"  -> { itemToGive = huTaoBoomListener.makeBoomDye();       pretty = "BOOM"; }
                    default -> { sender.sendMessage(ChatColor.RED + "Неизвестный предмет для HuTao: " + sub); return true; }
                }
            }
            case "dio" -> {
                switch (sub) {
                    case "hand" -> { itemToGive = makeDioHand();                     pretty = "hand"; }
                    case "timestop", "stop", "ts", "time" -> {
                        itemToGive = timeStopListener.makeTimeStopDye();             pretty = "TIME_STOP";
                    }
                    case "vampire" -> {
                        itemToGive = vampireListener.makeVampireDye();               pretty = "Vampire";
                    }
                    default -> { sender.sendMessage(ChatColor.RED + "Неизвестный предмет для Dio: " + sub); return true; }
                }
            }
            case "uraraka", "ochaco" -> {
                switch (sub) {
                    case "glove" -> {
                        itemToGive = urarakaGloveListener.makeGloveSword();          pretty = "Glove";
                    }
                    case "gravity" -> {
                        itemToGive = urarakaGravityListener.makeGravityDye();        pretty = "gravity";
                    }
                    case "healpost", "heal", "post" -> {
                        itemToGive = urarakaHealPostListener.makeHealPostItem();     pretty = "Heal Post";
                    }
                    case "levmark", "levitationmark", "lev" -> {                      // <-- NEW
                        itemToGive = levitationMarkListener.makeLevitationMarkDye();
                        pretty = "Levitation Mark";
                    }
                    default -> { sender.sendMessage(ChatColor.RED + "Неизвестный предмет для Uraraka: " + sub); return true; }
                }
            }
            default -> { sender.sendMessage(ChatColor.RED + "Неизвестный герой: " + args[0]); return true; }
        }

        giveOrDrop(target, itemToGive);
        target.playSound(target.getLocation(), Sound.UI_TOAST_IN, 0.6f, 1.6f);

        if (sender.equals(target)) {
            sender.sendMessage(ChatColor.AQUA + "Вы получили: " + ChatColor.WHITE + pretty);
        } else {
            sender.sendMessage(ChatColor.GREEN + "Выдал " + ChatColor.AQUA + pretty + ChatColor.GREEN
                    + " игроку " + ChatColor.WHITE + target.getName());
            target.sendMessage(ChatColor.AQUA + "Вы получили: " + ChatColor.WHITE + pretty
                    + ChatColor.GRAY + " (от " + sender.getName() + ")");
        }
        return true;
    }

    /** Ganyu bow с тегом. */
    private ItemStack makeGanyuBow() {
        ItemStack it = new ItemStack(Material.BOW);
        ItemMeta im = it.getItemMeta();
        im.setDisplayName("§bAmos' Bow");
        im.setLore(List.of(
                ChatColor.GRAY + "Hold to charge for 3s",
                ChatColor.GRAY + "Release to fire an icy arrow"
        ));
        im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
        im.setUnbreakable(true);
        im.getPersistentDataContainer().set(KEY_GANYU_BOW, PersistentDataType.BYTE, (byte)1);
        it.setItemMeta(im);
        return it;
    }

    /** DIO hand — алмазный меч с тегом. */
    private ItemStack makeDioHand() {
        ItemStack it = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("hand"));
        im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
        im.setUnbreakable(true);
        im.getPersistentDataContainer().set(KEY_DIO_HAND, PersistentDataType.BYTE, (byte)1);
        it.setItemMeta(im);
        return it;
    }

    private void giveOrDrop(Player p, ItemStack it) {
        Map<Integer, ItemStack> leftover = p.getInventory().addItem(it);
        leftover.values().forEach(rem -> p.getWorld().dropItemNaturally(p.getLocation(), rem));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission("kits.give")) return List.of();

        if (args.length == 1) return filter(List.of("Ganyu","HuTao","Dio","Uraraka"), args[0]);
        if (args.length == 2) {
            if ("ganyu".equalsIgnoreCase(args[0]))   return filter(Arrays.asList("bow","bud","ult"), args[1]);
            if ("hutao".equalsIgnoreCase(args[0]))   return filter(Arrays.asList("homa","pyro","ult"), args[1]);
            if ("dio".equalsIgnoreCase(args[0]))     return filter(Arrays.asList("hand","timestop","vampire"), args[1]);
            if ("uraraka".equalsIgnoreCase(args[0])) return filter(Arrays.asList("glove","gravity","healpost","levmark"), args[1]); // <-- NEW
        }
        if (args.length == 3) {
            List<String> names = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            return filter(names, args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> base, String token) {
        String t = token == null ? "" : token.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : base) if (s.toLowerCase(Locale.ROOT).startsWith(t)) out.add(s);
        return out;
    }
}
