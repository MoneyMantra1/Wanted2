package com.itharia.wanted.display;

import com.itharia.wanted.Settings;
import com.itharia.wanted.WantedPlugin;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Scoreboard;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class DisplayManager {
    private final WantedPlugin plugin; private Settings settings;
    private final Map<UUID,BossBar> bossbars=new HashMap<>();
    private final Map<UUID,Scoreboard> prevBoards=new HashMap<>(), activeBoards=new HashMap<>();
    public DisplayManager(WantedPlugin plugin){ this.plugin=plugin; this.settings=plugin.settings(); }
    public void applySettings(Settings s){ this.settings=s; }
    public void hideAll(){ for(var e:bossbars.entrySet()){ Player p=Bukkit.getPlayer(e.getKey()); if(p!=null) p.hideBossBar(e.getValue()); }
        bossbars.clear(); for(var e:prevBoards.entrySet()){ Player p=Bukkit.getPlayer(e.getKey()); if(p!=null) p.setScoreboard(e.getValue()); }
        prevBoards.clear(); activeBoards.clear(); }
    public void showBossbar(Player p, Component name, BossBar.Color color, BossBar.Overlay overlay, float progress){
        var bar=bossbars.get(p.getUniqueId()); if(bar==null){ bar=BossBar.bossBar(name,progress,color,overlay); bossbars.put(p.getUniqueId(),bar); p.showBossBar(bar); }
        else{ bar.color(color); bar.overlay(overlay); bar.progress(progress); bar.name(name); } }
    public void updateBossbar(Player p,float progress){ var bar=bossbars.get(p.getUniqueId()); if(bar!=null) bar.progress(progress); }
    public void hideBossbar(Player p){ var bar=bossbars.remove(p.getUniqueId()); if(bar!=null) p.hideBossBar(bar); }
    public void showActionbar(Player p, Component msg){ if(settings.displayMode()==Settings.DisplayMode.ACTIONBAR) p.sendActionBar(msg); }
    public void showOrUpdateScoreboard(Player p,int secondsLeft,int secondsTotal){
        if(settings.displayMode()!=Settings.DisplayMode.SCOREBOARD) return; Scoreboard sb=activeBoards.get(p.getUniqueId());
        if(sb==null){ prevBoards.putIfAbsent(p.getUniqueId(),p.getScoreboard()); sb=Bukkit.getScoreboardManager().getNewScoreboard();
            Objective obj=sb.registerNewObjective("wanted","dummy", Component.text(settings.scoreboardTitle().replace("&","§")));
            obj.setRenderType(RenderType.INTEGER); obj.setDisplaySlot(DisplaySlot.SIDEBAR); activeBoards.put(p.getUniqueId(),sb); p.setScoreboard(sb); }
        Objective obj=sb.getObjective(DisplaySlot.SIDEBAR); if(obj==null) return; for(String e:sb.getEntries()) sb.resetScores(e);
        int line=2; if(settings.scoreboardUseDivider()) obj.getScore("§7§m----------------").setScore(line--);
        obj.getScore("§fTime Left: §e"+secondsLeft+"s").setScore(line--); obj.getScore("§fStatus: §cWANTED").setScore(line--); }
    public void hideScoreboard(Player p){ Scoreboard prev=prevBoards.remove(p.getUniqueId()); if(prev!=null) p.setScoreboard(prev); activeBoards.remove(p.getUniqueId()); }
}
