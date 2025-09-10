package com.itharia.wanted;

import com.itharia.wanted.command.WantedCommand;
import com.itharia.wanted.display.DisplayManager;
import com.itharia.wanted.listeners.ChestOpenListener;
import com.itharia.wanted.listeners.QuitCloseListener;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

public final class WantedPlugin extends JavaPlugin {
    private Settings settings;
    private DisplayManager displayManager;
    private WantedSessionManager sessionManager;

    private NamespacedKey keyPlayerPlaced, keyHostileVillager, keySpawnedVillager, keyWitnessVillager, keyChestTriggeredOnce;

    @Override public void onEnable(){
        saveDefaultConfig();
        settings=new Settings(this);
        displayManager=new DisplayManager(this);
        sessionManager=new WantedSessionManager(this);
        keyPlayerPlaced=new NamespacedKey(this,"player_placed");
        keyHostileVillager=new NamespacedKey(this,"wanted_hostile");
        keySpawnedVillager=new NamespacedKey(this,"spawned_enforcer");
        keyWitnessVillager=new NamespacedKey(this,"witness");
        keyChestTriggeredOnce=new NamespacedKey(this,"chest_triggered_once");
        WantedCommand cmd=new WantedCommand(this);
        getCommand("wanted").setExecutor(cmd); getCommand("wanted").setTabCompleter(cmd);
        getServer().getPluginManager().registerEvents(new ChestOpenListener(this),this);
        getServer().getPluginManager().registerEvents(new QuitCloseListener(this),this);
        if(settings.debug()) getLogger().info("[Wanted] Enabled v"+getDescription().getVersion());
    }
    @Override public void onDisable(){ if(sessionManager!=null) sessionManager.endAll("plugin-disable"); if(displayManager!=null) displayManager.hideAll(); }
    public Settings settings(){return settings;} public DisplayManager displayManager(){return displayManager;} public WantedSessionManager sessions(){return sessionManager;}
    public NamespacedKey keyPlayerPlaced(){return keyPlayerPlaced;} public NamespacedKey keyHostileVillager(){return keyHostileVillager;}
    public NamespacedKey keySpawnedVillager(){return keySpawnedVillager;} public NamespacedKey keyWitnessVillager(){return keyWitnessVillager;}
    public NamespacedKey keyChestTriggeredOnce(){return keyChestTriggeredOnce;}
    public void reloadWantedConfig(){ sessions().endAll("reload"); displayManager.hideAll(); reloadConfig(); settings=new Settings(this); displayManager.applySettings(settings); if(settings.debug()) getLogger().info("[Wanted] Config reloaded."); }
}
