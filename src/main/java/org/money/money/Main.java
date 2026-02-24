package org.money.money;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.money.money.config.MapDefinition;
import org.money.money.config.MapRegistry;
import org.money.money.gui.GuiAutoRefreshTask;
import org.money.money.listeners.*;
import org.money.money.match.WorldService;
import org.money.money.session.SessionService;

public final class Main extends JavaPlugin {

    private static Main instance;
    public static Main getInstance() { return instance; }

    private MapRegistry mapRegistry;
    public MapRegistry getMapRegistry() { return mapRegistry; }

    @Override
    public void onEnable() {
        instance = this;

        // ✅ 1) Init registries/services first
        mapRegistry = new MapRegistry(this);
        SessionService.init(this);

        // ✅ 2) Register listeners
        Bukkit.getPluginManager().registerEvents(new JoinQuitListener(), this);
        Bukkit.getPluginManager().registerEvents(new InventoryLockListener(), this);
        Bukkit.getPluginManager().registerEvents(new ProtectionListener(), this);
        Bukkit.getPluginManager().registerEvents(new InteractRouterListener(), this);
        Bukkit.getPluginManager().registerEvents(new GuiClickListener(), this);
        Bukkit.getPluginManager().registerEvents(new WorldMoveListener(), this);
        Bukkit.getPluginManager().registerEvents(new ClassSelectorListener(), this);
        Bukkit.getPluginManager().registerEvents(new FreezeMoveListener(), this);
        Bukkit.getPluginManager().registerEvents(new OnDeathListener(), this);
        Bukkit.getPluginManager().registerEvents(new InteractHubListener(), this);
        Bukkit.getPluginManager().registerEvents(new SpectatorInventoryMenuClickListener(), this);


        // ✅ auto reset all maps on startup
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (MapDefinition m : mapRegistry.getMaps()) {
                if (!m.enabled) continue;
                WorldService.resetWorld(m.worldName);
            }
            getLogger().info("[Boot] Auto-reset finished.");
        }, 1L);

        getCommand("endgame").setExecutor(new org.money.money.commands.EndGameCommand());

        Bukkit.getPluginManager().registerEvents(new org.money.money.listeners.TeamSelectListener(), this);

        GuiAutoRefreshTask.start();


        getLogger().info("[LwLogic2] Enabled. maps=" + mapRegistry.getMaps().size());
    }

    @Override
    public void onDisable() {
        getLogger().info("[LwLogic2] Disabled.");
    }
}
