package org.money.money;

import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import org.money.money.combat.ElementalReactions;
import org.money.money.kits.airwalker.WindInvisListener;
import org.money.money.kits.airwalker.WindListener;
import org.money.money.kits.airwalker.WindSwordListener;
import org.money.money.kits.airwalker.WindUltListener;
import org.money.money.kits.airwalker.WindTornadoListener;
import org.money.money.kits.valkyrie.ValkyrieWeaponListener;
import org.money.money.kits.burgerMaster.GardenPlatformListener;
import org.money.money.kits.burgerMaster.GrillManager;
import org.money.money.kits.burgerMaster.GrillPlaceListener;
import org.money.money.kits.burgerMaster.HungryMasterListener;
import org.money.money.kits.dio.DioHandListener;
import org.money.money.kits.dio.DioStandFollower;
import org.money.money.kits.dio.TimeStopListener;
import org.money.money.kits.dio.VampireListener;
import org.money.money.kits.fukuko.FukukoPistolListener;
import org.money.money.kits.fukuko.FukukoMortiraListener;
import org.money.money.kits.fukuko.FukukoBombZoneListener;
import org.money.money.kits.fukuko.FukukoShockGrenadeListener;
import org.money.money.kits.ladynagan.LadyCooldownManager;
import org.money.money.kits.ladynagan.LadyNaganSniperListener;
import org.money.money.kits.ladynagan.LadyNaganFlyListener;
import org.money.money.kits.ladynagan.LadyNaganTrapsListener;
import org.money.money.kits.ladynagan.LadyNaganExplosionListener;
import org.money.money.kits.saske.SaskeSwordListener;
import org.money.money.kits.saske.SaskeShurikenListener;
import org.money.money.kits.saske.SaskeBodyReplacementListener;
import org.money.money.kits.saske.SaskeChidoriListener;
import org.money.money.kits.saske.SaskeAttractionListener;
import org.money.money.kits.ishigava.IshigavaWaterShieldListener;
import org.money.money.kits.ishigava.IshigavaWaterBridgesListener;
import org.money.money.kits.ishigava.IshigavaLastWaterWallListener;
import org.money.money.kits.ishigava.IshigavaAuraListener;
import org.money.money.kits.ishigava.IshigavaKunaiBowListener;
import org.money.money.kits.ishigava.IshigavaCloneListener;
import org.money.money.kits.ganyu.give.KitGiveCommand;
import org.money.money.kits.ganyu.listeners.GanyuBowListener;
import org.money.money.kits.ganyu.listeners.GanyuBudListener;
import org.money.money.kits.ganyu.listeners.GanyuUltListener;
import org.money.money.kits.ganyu.listeners.GanyuMeteorListener;
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
import org.money.money.kits.timewalker.TimeWalkerRunListener;
import org.money.money.kits.timewalker.TimeWalkerSlashListener;
import org.money.money.kits.timewalker.TimeWalkerMomentumListener;
import org.money.money.kits.timewalker.TimeWalkerUltListener;
import org.money.money.kits.timewalker.TimeWalkerReRunListener;
import org.money.money.kits.blastborn.BlastbornManager;
import org.money.money.kits.blastborn.BlastGlovesListener;
import org.money.money.kits.blastborn.ImpactGrenadeListener;
import org.money.money.kits.blastborn.PhoenixDetonatorListener;
import org.money.money.kits.blastborn.SweatMachineGunListener;
import org.money.money.kits.bluerose.BlueRoseGuardianManager;
import org.money.money.kits.bluerose.BlueRoseWardListener;
import org.money.money.kits.bluerose.RosebindListener;
import org.money.money.kits.bluerose.PetalStepListener;
import org.money.money.kits.bluerose.HeritageBloomListener;
import org.money.money.kits.bluerose.GardenOfFirstRoseListener;
import org.money.money.kits.bluerose.SoumetsuStormListener;
import org.bukkit.entity.Player;

public final class Main extends JavaPlugin {
    private ElementalReactions elemental;
    private DioStandFollower dio;
    private GrillManager grillManager;
    private org.money.money.kits.ladynagan.LadyNaganSniperListener ladySniper;
    private TimeWalkerMomentumListener timeWalkerMomentum;
    private TimeWalkerUltListener timeWalkerUlt;
    private BlastbornManager blastbornManager;
    private ImpactGrenadeListener blastGrenade;
    private PhoenixDetonatorListener blastPhoenix;
    private SweatMachineGunListener blastGun;
    private IshigavaCloneListener ishigavaClones;
    private BlueRoseGuardianManager blueRose;

    @Override
    public void onEnable() {

        saveDefaultConfig();
        org.money.money.meta.ClassRegistry.init(this);
        org.money.money.session.KitSession.configureLobbyWorlds(getConfig().getStringList("lobby-worlds"));

        elemental = new ElementalReactions(this);
        grillManager = new GrillManager(this);        // сам регистрирует свои события

        var bow  = new GanyuBowListener(this,elemental);
        var bud  = new GanyuBudListener(this,elemental);
        var ult  = new GanyuUltListener(this,elemental);
        var meteor = new GanyuMeteorListener(this);
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
        var windTornado = new WindTornadoListener(this);
        var valkyrie = new ValkyrieWeaponListener(this);
        var haoh = new SwordShield(this);
        var mask = new MaskAbility(this);
        var haoPerk = new HaoHaoPerkListener(this);
        var opera = new OperaTransformationListener(this);
        var operaAura = new OperaAuraListener(this);
        var saberlightexcalibur = new SaberLightExcaliburListener(this);
        var saberlightrealese = new SaberLightUltimateListener(this, saberlightexcalibur);
        var saberdarkexcalibur = new SaberDarkExcaliburListener(this);
        var saberdarkrelease = new SaberDarkUltimateListener(this, saberdarkexcalibur);
        var fukukoPistol   = new FukukoPistolListener(this);
        var fukukoMortira  = new FukukoMortiraListener(this);
        var fukukoBombZone = new FukukoBombZoneListener(this);
        var fukukoShock    = new FukukoShockGrenadeListener(this);
        var ladyCooldowns  = new LadyCooldownManager(this);
        this.ladySniper    = new LadyNaganSniperListener(this, ladyCooldowns);
        var ladySniper     = this.ladySniper;
        var ladyFly        = new LadyNaganFlyListener(this, ladyCooldowns);
        var ladyTraps      = new LadyNaganTrapsListener(this, ladyCooldowns);
        var ladyExplosion  = new LadyNaganExplosionListener(this, ladyCooldowns);
        var saskeCooldowns = new LadyCooldownManager(this);
        var saskeSword     = new SaskeSwordListener(this);
        var saskeShuriken  = new SaskeShurikenListener(this);
        var saskeBody      = new SaskeBodyReplacementListener(this, saskeCooldowns);
        var saskeChidori   = new SaskeChidoriListener(this, saskeCooldowns);
        var saskeAttraction = new SaskeAttractionListener(this, saskeCooldowns);
        var ishiShield     = new IshigavaWaterShieldListener(this);
        var ishiBridges    = new IshigavaWaterBridgesListener(this);
        var ishiWall       = new IshigavaLastWaterWallListener(this);
        var ishiAura       = new IshigavaAuraListener(this);
        var ishiKunai      = new IshigavaKunaiBowListener(this);
        var ishiClones     = new IshigavaCloneListener(this);
        this.ishigavaClones = ishiClones;
        var twRun          = new TimeWalkerRunListener(this);
        var twSlash        = new TimeWalkerSlashListener(this);
        var twMomentum     = new TimeWalkerMomentumListener(this);
        var twUlt          = new TimeWalkerUltListener(this);
        var twRerun        = new TimeWalkerReRunListener(this);
        this.timeWalkerMomentum = twMomentum;
        this.timeWalkerUlt = twUlt;
        var blastMgr       = new BlastbornManager(this);
        var blastGloves    = new BlastGlovesListener(this, blastMgr);
        var blastGren      = new ImpactGrenadeListener(this, blastMgr);
        var blastPhx       = new PhoenixDetonatorListener(this, blastMgr);
        var blastGun       = new SweatMachineGunListener(this, blastMgr);
        this.blastbornManager = blastMgr;
        this.blastGrenade = blastGren;
        this.blastPhoenix = blastPhx;
        this.blastGun = blastGun;

        var brgMgr        = new BlueRoseGuardianManager(this);
        var brgWard       = new BlueRoseWardListener(brgMgr);
        var brgRosebind   = new RosebindListener(brgMgr);
        var brgPetalStep  = new PetalStepListener(brgMgr);
        var brgHeritage   = new HeritageBloomListener(brgMgr);
        var brgGarden     = new GardenOfFirstRoseListener(brgMgr);
        var brgStorm      = new SoumetsuStormListener(brgMgr);
        this.blueRose = brgMgr;


        getServer().getPluginManager().registerEvents(bow,  this);
        getServer().getPluginManager().registerEvents(bud,  this);
        getServer().getPluginManager().registerEvents(ult,  this);
        getServer().getPluginManager().registerEvents(meteor, this);
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
        getServer().getPluginManager().registerEvents(windTornado, this);
        getServer().getPluginManager().registerEvents(valkyrie, this);
        getServer().getPluginManager().registerEvents(haoh, this);
        getServer().getPluginManager().registerEvents(mask, this);
        getServer().getPluginManager().registerEvents(haoPerk, this);
        getServer().getPluginManager().registerEvents(opera, this);
        getServer().getPluginManager().registerEvents(operaAura, this);
        getServer().getPluginManager().registerEvents(saberlightrealese, this);
        getServer().getPluginManager().registerEvents(saberdarkrelease, this);
        getServer().getPluginManager().registerEvents(fukukoPistol,   this);
        getServer().getPluginManager().registerEvents(fukukoMortira,  this);
        getServer().getPluginManager().registerEvents(fukukoBombZone, this);
        getServer().getPluginManager().registerEvents(fukukoShock,    this);
        getServer().getPluginManager().registerEvents(ladySniper,    this);
        getServer().getPluginManager().registerEvents(ladyFly,       this);
        getServer().getPluginManager().registerEvents(ladyTraps,     this);
        getServer().getPluginManager().registerEvents(ladyExplosion, this);
        getServer().getPluginManager().registerEvents(saskeSword,      this);
        getServer().getPluginManager().registerEvents(saskeShuriken,   this);
        getServer().getPluginManager().registerEvents(saskeBody,       this);
        getServer().getPluginManager().registerEvents(saskeChidori,    this);
        getServer().getPluginManager().registerEvents(saskeAttraction, this);
        getServer().getPluginManager().registerEvents(ishiShield,   this);
        getServer().getPluginManager().registerEvents(ishiBridges,  this);
        getServer().getPluginManager().registerEvents(ishiWall,     this);
        getServer().getPluginManager().registerEvents(ishiAura,     this);
        getServer().getPluginManager().registerEvents(ishiKunai,    this);
        getServer().getPluginManager().registerEvents(ishiClones,   this);
        getServer().getPluginManager().registerEvents(twRun,        this);
        getServer().getPluginManager().registerEvents(twSlash,      this);
        getServer().getPluginManager().registerEvents(twMomentum,   this);
        getServer().getPluginManager().registerEvents(twUlt,        this);
        getServer().getPluginManager().registerEvents(twRerun,      this);
        getServer().getPluginManager().registerEvents(blastMgr,     this);
        getServer().getPluginManager().registerEvents(blastGloves,  this);
        getServer().getPluginManager().registerEvents(blastGren,    this);
        getServer().getPluginManager().registerEvents(blastPhx,     this);
        getServer().getPluginManager().registerEvents(blastGun,     this);
        getServer().getPluginManager().registerEvents(brgMgr,       this);
        getServer().getPluginManager().registerEvents(brgWard,      this);
        getServer().getPluginManager().registerEvents(brgRosebind,  this);
        getServer().getPluginManager().registerEvents(brgPetalStep, this);
        getServer().getPluginManager().registerEvents(brgHeritage,  this);
        getServer().getPluginManager().registerEvents(brgGarden,    this);
        getServer().getPluginManager().registerEvents(brgStorm,     this);

        var kit = new KitGiveCommand(this, bud, ult, meteor, homa, pyro, boom, timestop, vampire, glove, gravity, post,
                levitationMark, rassengan, randomtp, clones, grill, garden, hungry, wind, windult, windsword, windinvis, windTornado,
                haoh, mask, opera, operaAura, saberlightexcalibur, saberlightrealese, saberdarkexcalibur, saberdarkrelease,
                fukukoPistol, fukukoMortira, fukukoBombZone, fukukoShock,
                ladySniper, ladyFly, ladyTraps, ladyExplosion,
                saskeSword, saskeShuriken, saskeBody, saskeChidori, saskeAttraction,
                ishiShield, ishiBridges, ishiWall, ishiAura, ishiKunai,
                twRun, twSlash, twMomentum, twUlt, twRerun,
                blastMgr, blastGloves, blastGren, blastPhx, blastGun,
                ishiClones, brgMgr, valkyrie);
        getCommand("kitgive").setExecutor(kit);
        getCommand("kitgive").setTabCompleter(kit);
        // Valkyrie pulls teammate weapons through the give command's factory (breaks the ctor cycle).
        valkyrie.setWeaponSource(kit::mainWeaponFor);

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

        // Очистка состояния способностей при возврате в лобби / по команде конца игры.
        java.util.List<org.money.money.session.KitResettable> resettables = new java.util.ArrayList<>();
        if (this.ladySniper != null) resettables.add(this.ladySniper);
        resettables.add(homa);
        resettables.add(tradesListener);
        if (this.dio != null) resettables.add(this.dio);
        resettables.add(windTornado);
        resettables.add(valkyrie);
        resettables.add(twRun);
        resettables.add(twSlash);
        resettables.add(twMomentum);
        resettables.add(twUlt);
        resettables.add(twRerun);
        resettables.add(blastMgr);
        resettables.add(blastGloves);
        resettables.add(blastGren);
        resettables.add(blastPhx);
        resettables.add(blastGun);
        resettables.add(ishiClones);
        resettables.add(brgMgr);
        var sessionManager = new org.money.money.session.SessionManager(resettables);
        getServer().getPluginManager().registerEvents(sessionManager, this);
        getCommand("warriors").setExecutor(sessionManager);
        getCommand("warriors").setTabCompleter(sessionManager);
    }

    public ElementalReactions elemental() { return elemental; }

    @Override public void onDisable(){ if (dio != null) dio.shutdown();
        if (ladySniper != null) ladySniper.shutdown();
        if (timeWalkerMomentum != null) timeWalkerMomentum.stop();
        if (timeWalkerUlt != null) timeWalkerUlt.shutdownClones();
        if (blastbornManager != null) blastbornManager.stop();
        if (blastGrenade != null) blastGrenade.stop();
        if (blastPhoenix != null) blastPhoenix.shutdown();
        if (blastGun != null) blastGun.stop();
        if (ishigavaClones != null) ishigavaClones.shutdown();
        if (blueRose != null) blueRose.stop();

    }

}
