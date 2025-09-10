package com.itharia.wanted.listeners;

import com.itharia.wanted.WantedPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class QuitCloseListener implements Listener {
    private final WantedPlugin plugin;
    public QuitCloseListener(WantedPlugin plugin){ this.plugin=plugin; }

    @EventHandler(priority= EventPriority.MONITOR) public void onQuit(PlayerQuitEvent e){ plugin.sessions().endSession(e.getPlayer(),"quit"); }
    @EventHandler(priority= EventPriority.MONITOR) public void onClose(InventoryCloseEvent e){
        if(e.getPlayer() instanceof Player p){
            switch (plugin.settings().displayMode()){
                case ACTIONBAR -> plugin.displayManager().showActionbar(p, net.kyori.adventure.text.Component.empty());
                case SCOREBOARD -> plugin.displayManager().hideScoreboard(p);
                default -> {}
            }
        }
    }
}
