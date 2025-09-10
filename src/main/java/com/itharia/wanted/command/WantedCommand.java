package com.itharia.wanted.command;

import com.itharia.wanted.WantedPlugin;
import com.itharia.wanted.WantedSessionManager.Crime;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.command.*;

import java.util.Comparator;
import java.util.Optional;

public final class WantedCommand implements CommandExecutor, TabCompleter {
    private final WantedPlugin plugin;
    public WantedCommand(WantedPlugin plugin){ this.plugin=plugin; }
    private Component amp(String s){ return LegacyComponentSerializer.legacyAmpersand().deserialize(s==null?"":s); }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args){
        if(args.length==0 || args[0].equalsIgnoreCase("help")){ sendHelp(sender,label); return true; }
        if(args[0].equalsIgnoreCase("reload")){
            if(!sender.hasPermission("wanted.reload")){ sender.sendMessage(Component.text("You don't have permission.",NamedTextColor.RED)); return true; }
            plugin.reloadWantedConfig(); sender.sendMessage(Component.text("Wanted config reloaded.",NamedTextColor.GREEN)); return true; }
        if(args[0].equalsIgnoreCase("stop")){
            if(!(sender instanceof Player p)){ sender.sendMessage(Component.text("Run this in-game.",NamedTextColor.RED)); return true; }
            if(args.length>=2 && args[1].equalsIgnoreCase("all")){
                if(!sender.hasPermission("wanted.reload")){ sender.sendMessage(Component.text("You don't have permission.",NamedTextColor.RED)); return true; }
                plugin.sessions().endAll("command-stop-all"); sender.sendMessage(Component.text("Stopped all Wanted sessions.",NamedTextColor.YELLOW));
            }else{ plugin.sessions().endSession(p,"command-stop"); sender.sendMessage(Component.text("Stopped your Wanted session (if any).",NamedTextColor.YELLOW)); }
            return true; }
        if(args[0].equalsIgnoreCase("start")){
            if(!(sender instanceof Player p)){ sender.sendMessage(Component.text("Run this in-game.",NamedTextColor.RED)); return true; }
            if(args.length<2){ sender.sendMessage(Component.text("Usage: /"+label+" start <larceny|associate|murder>",NamedTextColor.RED)); return true; }
            if(plugin.sessions().isParticipant(p) && !plugin.sessions().hasOwnSession(p)){ p.sendMessage(amp("&8(&4&l!&8) &cYou are already inside an active Wanted event")); return true; }
            String mode=args[1].toLowerCase();
            if(mode.equals("murder")){
                int r=plugin.settings().radius(); boolean hasCluster=p.getWorld().getNearbyEntities(p.getLocation(),r,r,r,e->e instanceof Villager).size()>=2;
                if(!hasCluster){ p.sendMessage(amp("&8(&4&l!&8) &cYou must be in a village in order to start wanted event")); return true; }
                plugin.sessions().forceStartAt(p,p.getLocation(),false,Crime.MURDER);
                sender.sendMessage(Component.text("Started murder witness→wanted at your location.",NamedTextColor.YELLOW)); return true; }
            Location anchor=p.getLocation(); int radius=plugin.settings().radius();
            boolean hasVillager=!anchor.getWorld().getNearbyEntities(anchor,radius,radius,radius,e->e instanceof Villager).isEmpty();
            Optional<Location> chest=findNearestWorldGenChest(anchor,radius);
            if(!hasVillager || chest.isEmpty()){ p.sendMessage(amp("&8(&4&l!&8) &cYou must be in a village in order to start wanted event")); return true; }
            boolean titlesAsNearby=mode.equals("associate");
            if(mode.equals("larceny") || titlesAsNearby){ plugin.sessions().forceStartAt(p,chest.get(),titlesAsNearby,Crime.LARCENY);
                sender.sendMessage(Component.text("Started witness→wanted at your location ("+mode+" POV).",NamedTextColor.YELLOW)); return true; }
            sender.sendMessage(Component.text("Usage: /"+label+" start <larceny|associate|murder>",NamedTextColor.RED)); return true; }
        sendHelp(sender,label); return true; }

    private Optional<Location> findNearestWorldGenChest(Location center, int radius){
        World w=center.getWorld(); if(w==null) return Optional.empty();
        int minCX=(center.getBlockX()-radius)>>4, maxCX=(center.getBlockX()+radius)>>4;
        int minCZ=(center.getBlockZ()-radius)>>4, maxCZ=(center.getBlockZ()+radius)>>4;
        java.util.List<Location> cand=new java.util.ArrayList<>();
        for(int cx=minCX; cx<=maxCX; cx++){ for(int cz=minCZ; cz<=maxCZ; cz++){
            if(!w.isChunkLoaded(cx,cz)) continue; Chunk ch=w.getChunkAt(cx,cz);
            for(BlockState bs: ch.getTileEntities()){
                if(!(bs instanceof TileState tile)) continue;
                if(!(bs instanceof org.bukkit.block.Container)) continue;
                if(!plugin.settings().triggerContainers().contains(bs.getType())) continue;
                Byte flag=tile.getPersistentDataContainer().get(plugin.keyPlayerPlaced(), org.bukkit.persistence.PersistentDataType.BYTE);
                if(flag!=null && flag==(byte)1) continue;
                Location loc=bs.getLocation().add(0.5,0.5,0.5);
                if(loc.distanceSquared(center)<=radius*radius) cand.add(loc);
            } } }
        return cand.stream().min(Comparator.comparingDouble(l->l.distanceSquared(center)));
    }

    private void sendHelp(CommandSender sender,String label){
        String v=plugin.getDescription().getVersion();
        var header=Component.text("=== Wanted v"+v+" ===",NamedTextColor.GREEN).decorate(TextDecoration.BOLD);
        var desc=Component.text("Villagers become hostile if you loot world-generated chests nearby or murder a villager. Includes a witness pre-event.",NamedTextColor.WHITE);
        var cmds=Component.text("Commands:",NamedTextColor.YELLOW).decorate(TextDecoration.BOLD);
        var c1=Component.text("/"+label+" help",NamedTextColor.GREEN).append(Component.text(" - Show this help.",NamedTextColor.GRAY));
        var c2=Component.text("/"+label+" reload",NamedTextColor.GREEN).append(Component.text(" - Reload configuration.",NamedTextColor.GRAY));
        var c3=Component.text("/"+label+" start larceny|associate|murder",NamedTextColor.GREEN).append(Component.text(" - Begin the witness→wanted sequence.",NamedTextColor.GRAY));
        var c4=Component.text("/"+label+" stop [all]",NamedTextColor.GREEN).append(Component.text(" - Stop your (or all) sessions.",NamedTextColor.GRAY));
        var footer=Component.text("Made by MoneyMantra",NamedTextColor.GRAY).decorate(TextDecoration.ITALIC);
        sender.sendMessage(header); sender.sendMessage(desc); sender.sendMessage(Component.empty());
        sender.sendMessage(cmds); sender.sendMessage(c1); if(sender.hasPermission("wanted.reload")) sender.sendMessage(c2);
        sender.sendMessage(c3); sender.sendMessage(c4); sender.sendMessage(Component.empty()); sender.sendMessage(footer);
    }

    @Override public java.util.List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args){
        java.util.List<String> list=new java.util.ArrayList<>();
        if(args.length==1){ list.add("help"); if(sender.hasPermission("wanted.reload")) list.add("reload"); list.add("start"); list.add("stop"); }
        else if(args.length==2 && args[0].equalsIgnoreCase("start")){ list.add("larceny"); list.add("associate"); list.add("murder"); }
        else if(args.length==2 && args[0].equalsIgnoreCase("stop")){ if(sender.hasPermission("wanted.reload")) list.add("all"); }
        return list;
    }
}
