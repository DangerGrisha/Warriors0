package org.money.money;

import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import org.money.money.combat.ElementalReactions;
import org.money.money.kits.airwalker.WindInvisListener;
import org.money.money.kits.airwalker.WindListener;
import org.money.money.kits.airwalker.WindSwordListener;
import org.money.money.kits.airwalker.WindUltListener;
import org.money.money.kits.burgerMaster.GardenPlatformListener;
import org.money.money.kits.burgerMaster.GrillManager;
import org.money.money.kits.burgerMaster.GrillPlaceListener;
import org.money.money.kits.burgerMaster.HungryMasterListener;
import org.money.money.kits.dio.DioHandListener;
import org.money.money.kits.dio.DioStandFollower;
import org.money.money.kits.dio.TimeStopListener;
import org.money.money.kits.dio.VampireListener;
import org.money.money.kits.ganyu.give.KitGiveCommand;
import org.money.money.kits.ganyu.listeners.GanyuBowListener;
import org.money.money.kits.ganyu.listeners.GanyuBudListener;
import org.money.money.kits.ganyu.listeners.GanyuUltListener;
import org.money.money.kits.haohao.SwordShield;
import org.money.money.kits.haohao.MaskAbility;
import org.money.money.kits.haohao.HaoHaoPerkListener;
import org.money.money.kits.opera.OperaTransformationListener;
import org.money.money.kits.opera.OperaAuraListener;
import org.money.money.kits.hutao.HuTaoBoomListener;
import org.money.money.kits.hutao.HuTaoInvisListener;
import org.money.money.kits.hutao.HuTaoPyroListener;
import org.money.money.kits.naruto.DisappearanceTechniqueListener;
import org.money.money.kits.naruto.NarutoClonesListener;
import org.money.money.kits.naruto.NarutoRasenganListener;
import org.money.money.kits.saberD.SaberDarkExcaliburListener;
import org.money.money.kits.saberD.SaberDarkUltimateListener;
import org.money.money.kits.saberL.SaberLightUltimateListener;
import org.money.money.kits.uraraka.LevitationMarkListener;
import org.money.money.kits.uraraka.UrarakaGloveListener;
import org.money.money.kits.uraraka.UrarakaGravityListener;
import org.money.money.kits.uraraka.UrarakaHealPostListener;
import org.money.money.kits.saberL.SaberLightExcaliburListener;
import org.money.money.kits.saberL.SaberLightSoulTradesListener;
import org.bukkit.entity.Player;

public final class Main extends JavaPlugin {
    private ElementalReactions elemental;
    private DioStandFollower dio;
    private GrillManager grillManager;

    @Override
    public void onEnable() {

        elemental = new ElementalReactions(this);
        grillManager = new GrillManager(this);        // сам регистрирует свои события

        var bow  = new GanyuBowListener(this,elemental);
        var bud  = new GanyuBudListener(this,elemental);
        var ult  = new GanyuUltListener(this,elemental);
        var homa = new HuTaoInvisListener(this);
        var pyro = new HuTaoPyroListener(this,elemental);
        var boom = new HuTaoBoomListener(this, elemental);
        //dio = new DioStandFollower(this);
        var hand = new DioHandListener(this);
        var timestop = new TimeStopListener(this);
        var vampire = new VampireListener(this);
        var glove = new UrarakaGloveListener(this);
        var gravity = new UrarakaGravityListener(this);
        var post = new UrarakaHealPostListener(this);
        var levitationMark = new LevitationMarkListener(this);
        var rassengan = new NarutoRasenganListener(this);
        var randomtp = new DisappearanceTechniqueListener(this);
        var clones = new NarutoClonesListener(this);
        var grill = new GrillPlaceListener(this,grillManager);
        var garden = new GardenPlatformListener(this);
        var hungry = new HungryMasterListener(this);
        var wind = new WindListener(this);
        var windult = new WindUltListener(this);
        var windsword = new WindSwordListener(this);
        var windinvis = new WindInvisListener(this);
        var haoh = new SwordShield(this);
        var mask = new MaskAbility(this);
        var haoPerk = new HaoHaoPerkListener(this);
        var opera = new OperaTransformationListener(this);
        var operaAura = new OperaAuraListener(this);
        var saberlightexcalibur = new SaberLightExcaliburListener(this);
        var saberlightrealese = new SaberLightUltimateListener(this, saberlightexcalibur);
        var saberdarkexcalibur = new SaberDarkExcaliburListener(this);
        var saberdarkrelease = new SaberDarkUltimateListener(this, saberdarkexcalibur);


        getServer().getPluginManager().registerEvents(bow,  this);
        getServer().getPluginManager().registerEvents(bud,  this);
        getServer().getPluginManager().registerEvents(ult,  this);
        getServer().getPluginManager().registerEvents(homa, this);
        getServer().getPluginManager().registerEvents(pyro, this);
        getServer().getPluginManager().registerEvents(boom, this);
        //getServer().getPluginManager().registerEvents(dio, this);
        getServer().getPluginManager().registerEvents(hand, this);
        getServer().getPluginManager().registerEvents(timestop, this);
        getServer().getPluginManager().registerEvents(vampire, this);
        getServer().getPluginManager().registerEvents(glove, this);
        getServer().getPluginManager().registerEvents(gravity, this);
        getServer().getPluginManager().registerEvents(post, this);
        getServer().getPluginManager().registerEvents(levitationMark, this);
        getServer().getPluginManager().registerEvents(rassengan, this);
        getServer().getPluginManager().registerEvents(randomtp, this);
        getServer().getPluginManager().registerEvents(clones, this);
        getServer().getPluginManager().registerEvents(grill, this);
        getServer().getPluginManager().registerEvents(garden, this);
        getServer().getPluginManager().registerEvents(hungry, this);
        getServer().getPluginManager().registerEvents(wind, this);
        getServer().getPluginManager().registerEvents(windult, this);
        getServer().getPluginManager().registerEvents(windsword, this);
        getServer().getPluginManager().registerEvents(windinvis, this);
        getServer().getPluginManager().registerEvents(haoh, this);
        getServer().getPluginManager().registerEvents(mask, this);
        getServer().getPluginManager().registerEvents(haoPerk, this);
        getServer().getPluginManager().registerEvents(opera, this);
        getServer().getPluginManager().registerEvents(operaAura, this);
        getServer().getPluginManager().registerEvents(saberlightrealese, this);
        getServer().getPluginManager().registerEvents(saberdarkrelease, this);

        var kit = new KitGiveCommand(this, bud, ult, homa, pyro, boom, timestop, vampire, glove, gravity, post,
                levitationMark, rassengan, randomtp, clones, grill, garden, hungry, wind, windult, windsword, windinvis,
                haoh, mask, opera, operaAura, saberlightexcalibur, saberlightrealese, saberdarkexcalibur, saberdarkrelease);
        getCommand("kitgive").setExecutor(kit);
        getCommand("kitgive").setTabCompleter(kit);

        //Saber Light

        // Register SoulTrades listener with hook into Excalibur souls
        SaberLightSoulTradesListener tradesListener = new SaberLightSoulTradesListener(this,
                new SaberLightSoulTradesListener.SaberLightExcaliburSoulHook() {

                    private ItemStack findExcalibur(Player player) {
                        for (ItemStack it : player.getInventory().getContents()) {
                            if (it == null) continue;
                            if (saberlightexcalibur.isExcalibur(it)) return it;
                            if (saberdarkexcalibur.isExcalibur(it)) return it;
                        }
                        return null;
                    }

                    @Override
                    public int getSouls(Player player) {
                        ItemStack excalibur = findExcalibur(player);
                        if (excalibur == null) return 0;
                        if (saberlightexcalibur.isExcalibur(excalibur)) {
                            return saberlightexcalibur.getSouls(excalibur);
                        }
                        if (saberdarkexcalibur.isExcalibur(excalibur)) {
                            return saberdarkexcalibur.getSouls(excalibur);
                        }
                        return 0;
                    }

                    @Override
                    public void setSouls(Player player, int souls) {
                        ItemStack excalibur = findExcalibur(player);
                        if (excalibur == null) return;

                        if (saberlightexcalibur.isExcalibur(excalibur)) {
                            saberlightexcalibur.updateExcaliburSouls(excalibur, souls);
                        } else if (saberdarkexcalibur.isExcalibur(excalibur)) {
                            saberdarkexcalibur.updateExcaliburSouls(excalibur, souls);
                        }

                        // Safety refresh (helps client sync on some versions)
                        player.updateInventory();
                    }
                });

        getServer().getPluginManager().registerEvents(tradesListener, this);
    }

    public ElementalReactions elemental() { return elemental; }

    @Override public void onDisable(){ if (dio != null) dio.shutdown();
    

    }

}
