package org.money.money;

import org.bukkit.plugin.java.JavaPlugin;

import org.money.money.combat.ElementalReactions;
import org.money.money.kits.ganyu.give.KitGiveCommand;
import org.money.money.kits.ganyu.listeners.GanyuBowListener;
import org.money.money.kits.ganyu.listeners.GanyuBudListener;
import org.money.money.kits.ganyu.listeners.GanyuUltListener;
import org.money.money.kits.hutao.HuTaoBoomListener;
import org.money.money.kits.hutao.HuTaoInvisListener;
import org.money.money.kits.hutao.HuTaoPyroListener;

import java.util.Objects;

public final class Main extends JavaPlugin {
    private ElementalReactions elemental;
    @Override
    public void onEnable() {

        elemental = new ElementalReactions(this);

        var bow  = new GanyuBowListener(this,elemental);
        var bud  = new GanyuBudListener(this,elemental);
        var ult  = new GanyuUltListener(this,elemental);
        var homa = new HuTaoInvisListener(this);
        var pyro = new HuTaoPyroListener(this,elemental);
        var boom = new HuTaoBoomListener(this, elemental);

        getServer().getPluginManager().registerEvents(bow,  this);
        getServer().getPluginManager().registerEvents(bud,  this);
        getServer().getPluginManager().registerEvents(ult,  this);
        getServer().getPluginManager().registerEvents(homa, this);
        getServer().getPluginManager().registerEvents(pyro, this);
        getServer().getPluginManager().registerEvents(boom, this);

        var kit = new KitGiveCommand(this, bud, ult, homa, pyro, boom);
        getCommand("kitgive").setExecutor(kit);
        getCommand("kitgive").setTabCompleter(kit);
    }

    public ElementalReactions elemental() { return elemental; }

}
