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

import org.money.money.kits.airwalker.WindInvisListener;
import org.money.money.kits.airwalker.WindListener;
import org.money.money.kits.airwalker.WindSwordListener;
import org.money.money.kits.airwalker.WindUltListener;
import org.money.money.kits.burgerMaster.GrillPlaceListener;
import org.money.money.kits.burgerMaster.GardenPlatformListener;
import org.money.money.kits.burgerMaster.HungryMasterListener;      // <<< NEW
import org.money.money.kits.dio.TimeStopListener;
import org.money.money.kits.dio.VampireListener;
import org.money.money.kits.ganyu.listeners.GanyuBudListener;
import org.money.money.kits.ganyu.listeners.GanyuUltListener;
import org.money.money.kits.haohao.MaskAbility;
import org.money.money.kits.haohao.SwordShield;
import org.money.money.kits.opera.OperaAuraListener;
import org.money.money.kits.opera.OperaTransformationListener;
import org.money.money.kits.hutao.HuTaoInvisListener;
import org.money.money.kits.hutao.HuTaoPyroListener;
import org.money.money.kits.hutao.HuTaoBoomListener;
import org.money.money.kits.naruto.DisappearanceTechniqueListener;
import org.money.money.kits.naruto.NarutoRasenganListener;
import org.money.money.kits.naruto.NarutoClonesListener;
import org.money.money.kits.saberD.SaberDarkExcaliburListener;
import org.money.money.kits.saberD.SaberDarkUltimateListener;
import org.money.money.kits.saberL.SaberLightUltimateListener;
import org.money.money.kits.uraraka.LevitationMarkListener;
import org.money.money.kits.uraraka.UrarakaGloveListener;
import org.money.money.kits.uraraka.UrarakaGravityListener;
import org.money.money.kits.uraraka.UrarakaHealPostListener;
import org.money.money.kits.saberL.SaberLightExcaliburListener;
import org.money.money.kits.saberL.SaberLightSoulTradesListener;
import org.bukkit.enchantments.Enchantment;

import java.util.*;

public final class KitGiveCommand implements CommandExecutor, TabCompleter {

    private final Plugin plugin;

    private final GanyuBudListener budListener;
    private final GanyuUltListener ganyuUltListener;
    private final HuTaoInvisListener huTaoInvisListener;
    private final HuTaoPyroListener huTaoPyroListener;
    private final HuTaoBoomListener huTaoBoomListener;
    private final TimeStopListener  timeStopListener;
    private final VampireListener   vampireListener;
    private final UrarakaGloveListener   urarakaGloveListener;
    private final UrarakaGravityListener urarakaGravityListener;
    private final UrarakaHealPostListener urarakaHealPostListener;
    private final LevitationMarkListener levitationMarkListener;
    private final NarutoRasenganListener narutoRasenganListener;
    private final DisappearanceTechniqueListener disappearListener;
    private final NarutoClonesListener narutoClonesListener;
    private final GrillPlaceListener grillPlacer;                     // BurgerMaster: grill
    private final GardenPlatformListener gardenPlatform;              // BurgerMaster: garden
    private final HungryMasterListener hungryMasterListener;          // BurgerMaster: hungry  <<< NEW
    private final WindListener windListener;
    private final WindUltListener windultListener;
    private final WindSwordListener windSwordListener;
    private final WindInvisListener windInvisListener;
    private final SwordShield swordShield;
    private final MaskAbility maskAbility;
    private final OperaTransformationListener operaTransformationListener;
    private final OperaAuraListener operaAuraListener;
    private final SaberLightExcaliburListener saberLightExcaliburListener;
    private final SaberLightUltimateListener saberLightUltimateListener;
    private final SaberDarkExcaliburListener saberDarkExcaliburListener;
    private final SaberDarkUltimateListener saberDarkUltimateListener;


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
                          LevitationMarkListener levitationMarkListener,
                          NarutoRasenganListener narutoRasenganListener,
                          DisappearanceTechniqueListener disappearListener,
                          NarutoClonesListener narutoClonesListener,
                          GrillPlaceListener grillPlacer,
                          GardenPlatformListener gardenPlatform,
                          HungryMasterListener hungryMasterListener,
                          WindListener windListener,
                          WindUltListener windUltListener,
                          WindSwordListener windSwordListener,
                          WindInvisListener windInvisListener,
                          SwordShield swordShield,
                          MaskAbility maskAbility,
                          OperaTransformationListener operaTransformationListener,
                          OperaAuraListener operaAuraListener,
                          SaberLightExcaliburListener saberLightExcaliburListener,
                          SaberLightUltimateListener saberLightUltimateListener,
                          SaberDarkExcaliburListener saberDarkExcaliburListener,
                          SaberDarkUltimateListener saberDarkUltimateListener) {

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
        this.levitationMarkListener  = Objects.requireNonNull(levitationMarkListener);
        this.narutoRasenganListener  = Objects.requireNonNull(narutoRasenganListener);
        this.disappearListener       = Objects.requireNonNull(disappearListener);
        this.narutoClonesListener    = Objects.requireNonNull(narutoClonesListener);
        this.grillPlacer             = Objects.requireNonNull(grillPlacer);
        this.gardenPlatform          = Objects.requireNonNull(gardenPlatform);
        this.hungryMasterListener    = Objects.requireNonNull(hungryMasterListener); // <<< NEW
        this.windListener    = Objects.requireNonNull(windListener);
        this.windultListener   = Objects.requireNonNull(windUltListener);
        this.windSwordListener   = Objects.requireNonNull(windSwordListener);
        this.windInvisListener   = Objects.requireNonNull(windInvisListener);
        this.swordShield = Objects.requireNonNull(swordShield);
        this.maskAbility = Objects.requireNonNull(maskAbility);
        this.operaTransformationListener = Objects.requireNonNull(operaTransformationListener);
        this.operaAuraListener = Objects.requireNonNull(operaAuraListener);
        this.saberLightExcaliburListener = Objects.requireNonNull(saberLightExcaliburListener);
        this.saberLightUltimateListener = Objects.requireNonNull(saberLightUltimateListener);
        this.saberDarkExcaliburListener = Objects.requireNonNull(saberDarkExcaliburListener);
        this.saberDarkUltimateListener = Objects.requireNonNull(saberDarkUltimateListener);

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
            sender.sendMessage(ChatColor.GRAY + " /" + label + " Uraraka <glove|gravity|healpost|levmark> [player]");
            sender.sendMessage(ChatColor.GRAY + " /" + label + " Naruto <rasengan|disappear|clones> [player]");
            sender.sendMessage(ChatColor.GRAY + " /" + label + " BurgerMaster <grill|garden|hungry> [player]"); // <<< UPDATED
            sender.sendMessage(ChatColor.GRAY + " /" + label + " BurgerMaster <grill|garden|hungry|sword> [player]");
            sender.sendMessage(ChatColor.GRAY + " /" + label + " AirWalker <wind|windult|windsword|windinvis> [player]");
            sender.sendMessage(ChatColor.GRAY + " /" + label + " HaoHao <swordshield> [player]");
            sender.sendMessage(ChatColor.GRAY + " /" + label + " HaoHao <mask> [player]");
            sender.sendMessage(ChatColor.GRAY + " /" + label + " Opera <transformation> [player]");
            sender.sendMessage(ChatColor.GRAY + " /" + label + " Opera <aura> [player]");
            sender.sendMessage(ChatColor.GRAY + " /" + label + " SaberLight <Excalibur|Ult|Trade> [player]");
            sender.sendMessage(ChatColor.GRAY + " /" + label + " SaberD <excaliburd|ultd> [player]");
            sender.sendMessage(ChatColor.GRAY + " /" + label + " SaberLight souladd [amount] [player]");
            return true;
        }

        String hero = args[0].toLowerCase(Locale.ROOT);
        String sub  = args[1].toLowerCase(Locale.ROOT);

        int soulAddAmount = 1;
        Player target;
        // Special parse for SaberLight souladd:
        // /kitgive SaberLight souladd
        // /kitgive SaberLight souladd <player>
        // /kitgive SaberLight souladd <amount>
        // /kitgive SaberLight souladd <amount> <player>
        if (("saberlight".equals(hero) || "saber".equals(hero) || "light".equals(hero)
                || "saberd".equals(hero) || "saberdark".equals(hero) || "darksaber".equals(hero))
                && ("souladd".equals(sub) || "addsoul".equals(sub) || "soulsadd".equals(sub))) {
            int idx = 2;
            if (args.length >= 3) {
                Integer parsedAmount = parsePositiveInt(args[2]);
                if (parsedAmount != null) {
                    soulAddAmount = parsedAmount;
                    idx = 3;
                }
            }

            if (args.length > idx) {
                target = Bukkit.getPlayerExact(args[idx]);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Игрок оффлайн: " + args[idx]);
                    return true;
                }
            } else {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(ChatColor.RED + "Нужно указать игрока.");
                    return true;
                }
                target = p;
            }
        } else if (args.length >= 3) {
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
                    case "timestop", "stop", "ts", "time" -> { itemToGive = timeStopListener.makeTimeStopDye(); pretty = "TIME_STOP"; }
                    case "vampire" -> { itemToGive = vampireListener.makeVampireDye(); pretty = "Vampire"; }
                    default -> { sender.sendMessage(ChatColor.RED + "Неизвестный предмет для Dio: " + sub); return true; }
                }
            }
            case "uraraka", "ochaco" -> {
                switch (sub) {
                    case "glove" -> { itemToGive = urarakaGloveListener.makeGloveSword();          pretty = "Glove"; }
                    case "gravity" -> { itemToGive = urarakaGravityListener.makeGravityDye();       pretty = "gravity"; }
                    case "healpost", "heal", "post" -> { itemToGive = urarakaHealPostListener.makeHealPostItem(); pretty = "Heal Post"; }
                    case "levmark", "levitationmark", "lev" -> { itemToGive = levitationMarkListener.makeLevitationMarkDye(); pretty = "Levitation Mark"; }
                    default -> { sender.sendMessage(ChatColor.RED + "Неизвестный предмет для Uraraka: " + sub); return true; }
                }
            }
            case "naruto" -> {
                switch (sub) {
                    case "rasengan" -> { itemToGive = narutoRasenganListener.makeRasenganDye();      pretty = "Rasengan"; }
                    case "disappear", "vanish", "teleport" -> { itemToGive = disappearListener.makeDisappearanceDye(); pretty = "Disappearance Technique"; }
                    case "clones", "clone", "shadowclones" -> { itemToGive = narutoClonesListener.makeClonesDye();     pretty = "Clones"; }
                    default -> { sender.sendMessage(ChatColor.RED + "Неизвестный предмет для Naruto: " + sub); return true; }
                }
            }
            case "burgermaster", "burger" -> {
                switch (sub) {
                    case "grill"   -> { itemToGive = grillPlacer.makeGrillBlock();             pretty = "grill"; }
                    case "garden"  -> { itemToGive = gardenPlatform.makeGardenPlatformBlock(); pretty = "Garden Platform"; }
                    case "hungry", "hungrymaster", "beast" -> {
                        itemToGive = hungryMasterListener.makeHungryDye();
                        pretty = "Hungry master";
                    }
                    case "sword" -> {                                      // <<< NEW
                        itemToGive = makeBurgerSword();                    // <<< NEW
                        pretty = "sword";                                  // <<< NEW
                    }
                    default -> { sender.sendMessage(ChatColor.RED + "Неизвестный предмет для BurgerMaster: " + sub); return true; }
                }
            }
            case "airwalker", "airwaler", "wind" -> {
                switch (sub) {
                    case "wind" -> {
                        itemToGive = windListener.makeWindDye();   // ВОТ ЭТО ГЛАВНОЕ
                        pretty = "Wind";
                    }
                    case "windult", "ult" -> {
                        itemToGive = windultListener.makeWindUltDye();
                        pretty = "WindUlt";
                    }
                    case "windsword", "sword" -> { itemToGive = windSwordListener.makeWindSword(); pretty = "Wind Sword"; }
                    case "invis", "windinvis" -> {
                        itemToGive = windInvisListener.makeInvisDye();
                        pretty = "Invis";
                    }
                    default -> {
                        sender.sendMessage(ChatColor.RED + "Неизвестный предмет для AirWalker: " + sub);
                        sender.sendMessage(ChatColor.GRAY + "Доступно: wind");
                        return true;
                    }
                }
            }
            case "haohao", "king" -> {
                switch (sub) {
                    case "swordshield", "shield", "sword" -> { itemToGive = swordShield.makeKingSword(); pretty = "King's Sword"; }
                    case "mask" -> { itemToGive = maskAbility.makeYellowMask(); pretty = "Mask"; }
                    default -> { sender.sendMessage(ChatColor.RED + "Неизвестный предмет для HaoHao: " + sub); return true; }
                }
            }
            case "opera" -> {
                switch (sub) {
                    case "transformation", "transform", "horse" -> { itemToGive = operaTransformationListener.makeTransformationDye(); pretty = "transformation"; }
                    case "aura" -> { itemToGive = operaAuraListener.makeAuraItem(); pretty = "Opera Aura"; }
                    default -> { sender.sendMessage(ChatColor.RED + "Неизвестный предмет для Opera: " + sub); return true; }
                }
            }
            case "saberlight", "saber", "light" -> {
                switch (sub) {
                    case "excalibur", "sword", "shield" -> {
                        itemToGive = saberLightExcaliburListener.makeExcalibur();
                        pretty = "Excalibur";
                    }
                    case "ult", "realese" -> {
                        itemToGive = saberLightUltimateListener.makeSoulReleaseCrossbow();
                        pretty = "Realese";
                    }
                    case "soultrades" -> {
                        if (!isSaberSoulClass(target)) {
                            sender.sendMessage("§cTarget is not LightSaber/DarkSaber.");
                            return true;
                        }

                        target.getInventory().addItem(SaberLightSoulTradesListener.makeSoulTradesItem());
                        sender.sendMessage("§aGave SoulTrades to " + target.getName());
                        target.sendMessage("§6You received §bSoulTrades§6.");
                        return true;
                    }
                    case "souladd", "addsoul", "soulsadd" -> {
                        ItemStack excalibur = findAnyExcalibur(target);

                        if (excalibur == null) {
                            sender.sendMessage(ChatColor.RED + "У игрока нет Excalibur/ExcaliburD.");
                            return true;
                        }

                        int currentSouls = getSoulsAny(excalibur);
                        int nextSouls = currentSouls + soulAddAmount;
                        setSoulsAny(excalibur, nextSouls);
                        target.updateInventory();

                        sender.sendMessage(ChatColor.GREEN + "Добавлено душ: " + ChatColor.AQUA + soulAddAmount
                                + ChatColor.GREEN + " -> " + ChatColor.WHITE + target.getName()
                                + ChatColor.GRAY + " (итого: " + nextSouls + ")");
                        if (!sender.equals(target)) {
                            target.sendMessage(ChatColor.AQUA + "Вам добавили души Excalibur: +" + soulAddAmount
                                    + ChatColor.GRAY + " (итого: " + nextSouls + ")");
                        }
                        return true;
                    }
                    default -> {
                        sender.sendMessage(ChatColor.RED + "Неизвестный предмет для SaberLight: " + sub);
                        return true;
                    }
                }
            }
            case "saberd", "saberdark", "darksaber" -> {
                switch (sub) {
                    case "excaliburd", "dexcalibur", "shield", "sword" -> {
                        itemToGive = saberDarkExcaliburListener.makeExcalibur();
                        pretty = "ExcaliburD";
                    }
                    case "ultd", "soulreleased", "ult", "release" -> {
                        itemToGive = saberDarkUltimateListener.makeSoulReleaseCrossbow();
                        pretty = "Soul ReleaseD";
                    }
                    case "soultrades" -> {
                        if (!isSaberSoulClass(target)) {
                            sender.sendMessage("§cTarget is not LightSaber/DarkSaber.");
                            return true;
                        }
                        target.getInventory().addItem(SaberLightSoulTradesListener.makeSoulTradesItem());
                        sender.sendMessage("§aGave SoulTrades to " + target.getName());
                        target.sendMessage("§6You received §bSoulTrades§6.");
                        return true;
                    }
                    case "souladd", "addsoul", "soulsadd" -> {
                        ItemStack excalibur = findAnyExcalibur(target);
                        if (excalibur == null) {
                            sender.sendMessage(ChatColor.RED + "У игрока нет Excalibur/ExcaliburD.");
                            return true;
                        }
                        int currentSouls = getSoulsAny(excalibur);
                        int nextSouls = currentSouls + soulAddAmount;
                        setSoulsAny(excalibur, nextSouls);
                        target.updateInventory();

                        sender.sendMessage(ChatColor.GREEN + "Добавлено душ: " + ChatColor.AQUA + soulAddAmount
                                + ChatColor.GREEN + " -> " + ChatColor.WHITE + target.getName()
                                + ChatColor.GRAY + " (итого: " + nextSouls + ")");
                        if (!sender.equals(target)) {
                            target.sendMessage(ChatColor.AQUA + "Вам добавили души Excalibur: +" + soulAddAmount
                                    + ChatColor.GRAY + " (итого: " + nextSouls + ")");
                        }
                        return true;
                    }
                    default -> {
                        sender.sendMessage(ChatColor.RED + "Неизвестный предмет для SaberD: " + sub);
                        return true;
                    }
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

    /** Ganyu bow с тегом + Infinity. */
    private ItemStack makeGanyuBow() {
        ItemStack it = new ItemStack(Material.BOW);
        ItemMeta im = it.getItemMeta();
        im.setDisplayName("§bAmos' Bow");
        im.setLore(List.of(
                ChatColor.GRAY + "Hold to charge for 3s",
                ChatColor.GRAY + "Release to fire an icy arrow"
        ));

        // чары
        im.addEnchant(Enchantment.INFINITY, 1, true); // «Бесконечность I»

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

        if (args.length == 1) return filter(List.of("Ganyu","HuTao","Dio","Uraraka","Naruto","BurgerMaster","AirWalker","HaoHao","LightSaber","DarkSaber"), args[0]);
        if (args.length == 2) {
            if ("ganyu".equalsIgnoreCase(args[0]))   return filter(Arrays.asList("bow","bud","ult"), args[1]);
            if ("hutao".equalsIgnoreCase(args[0]))   return filter(Arrays.asList("homa","pyro","ult"), args[1]);
            if ("dio".equalsIgnoreCase(args[0]))     return filter(Arrays.asList("hand","timestop","vampire"), args[1]);
            if ("uraraka".equalsIgnoreCase(args[0])) return filter(Arrays.asList("glove","gravity","healpost","levmark"), args[1]);
            if ("naruto".equalsIgnoreCase(args[0]))  return filter(Arrays.asList("rasengan","disappear","clones"), args[1]);
            if ("burgermaster".equalsIgnoreCase(args[0]) || "burger".equalsIgnoreCase(args[0]))
                return filter(Arrays.asList("grill","garden","hungry","sword"), args[1]);
            //i dont see airwalker here for some reason idk why
            if ("haohao".equalsIgnoreCase(args[0]) || "king".equalsIgnoreCase(args[0])) return filter(Arrays.asList("swordshield","shield","sword","mask"), args[1]);
            if ("opera".equalsIgnoreCase(args[0])) return filter(Arrays.asList("transformation","transform","horse","aura"), args[1]);
            if ("saberlight".equalsIgnoreCase(args[0]) || "saber".equalsIgnoreCase(args[0]) || "light".equalsIgnoreCase(args[0]))
                return filter(Arrays.asList("excalibur","ult","soultrades","souladd"), args[1]);
            if ("saberd".equalsIgnoreCase(args[0]) || "saberdark".equalsIgnoreCase(args[0]) || "darksaber".equalsIgnoreCase(args[0]))
                return filter(Arrays.asList("excaliburd","ultd","soultrades","souladd"), args[1]);
        }
        if (args.length == 3) {
            if (("saberlight".equalsIgnoreCase(args[0]) || "saber".equalsIgnoreCase(args[0]) || "light".equalsIgnoreCase(args[0]))
                    && ("souladd".equalsIgnoreCase(args[1]) || "addsoul".equalsIgnoreCase(args[1]) || "soulsadd".equalsIgnoreCase(args[1]))) {
                return filter(Arrays.asList("1","2","3","5","10"), args[2]);
            }
            List<String> names = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            return filter(names, args[2]);
        }
        if (args.length == 4) {
            if (("saberlight".equalsIgnoreCase(args[0]) || "saber".equalsIgnoreCase(args[0]) || "light".equalsIgnoreCase(args[0]))
                    && ("souladd".equalsIgnoreCase(args[1]) || "addsoul".equalsIgnoreCase(args[1]) || "soulsadd".equalsIgnoreCase(args[1]))) {
                List<String> names = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
                return filter(names, args[3]);
            }
        }
        return List.of();
    }

    private static Integer parsePositiveInt(String raw) {
        try {
            int n = Integer.parseInt(raw);
            return n > 0 ? n : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean isSaberSoulClass(Player p) {
        return p.getScoreboardTags().contains("LightSaber") || p.getScoreboardTags().contains("DarkSaber");
    }

    private ItemStack findAnyExcalibur(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null) continue;
            if (saberLightExcaliburListener.isExcalibur(it) || saberDarkExcaliburListener.isExcalibur(it)) return it;
        }
        return null;
    }

    private int getSoulsAny(ItemStack excalibur) {
        if (saberLightExcaliburListener.isExcalibur(excalibur)) {
            return saberLightExcaliburListener.getSouls(excalibur);
        }
        if (saberDarkExcaliburListener.isExcalibur(excalibur)) {
            return saberDarkExcaliburListener.getSouls(excalibur);
        }
        return 0;
    }

    private void setSoulsAny(ItemStack excalibur, int souls) {
        if (saberLightExcaliburListener.isExcalibur(excalibur)) {
            saberLightExcaliburListener.updateExcaliburSouls(excalibur, souls);
            return;
        }
        if (saberDarkExcaliburListener.isExcalibur(excalibur)) {
            saberDarkExcaliburListener.updateExcaliburSouls(excalibur, souls);
        }
    }

    private static List<String> filter(List<String> base, String token) {
        String t = token == null ? "" : token.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : base) if (s.toLowerCase(Locale.ROOT).startsWith(t)) out.add(s);
        return out;
    }
    /** BurgerMaster sword — iron sword, Knockback II, unbreakable. */
    private ItemStack makeBurgerSword() {
        ItemStack it = new ItemStack(Material.IRON_SWORD);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text("sword")); // простое имя "sword"
        im.addEnchant(Enchantment.KNOCKBACK, 2, true); // отдача II
        im.setUnbreakable(true);
        im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        it.setItemMeta(im);
        return it;
    }
}
