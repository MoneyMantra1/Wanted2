package com.itharia.wanted.listeners;

import com.itharia.wanted.WantedPlugin;
import com.itharia.wanted.WantedSessionManager.Crime;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.*;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.persistence.PersistentDataType;

public final class ChestOpenListener implements Listener {
    private final WantedPlugin plugin;
    private final NamespacedKey keyPlayerPlaced, keyChestTriggeredOnce;

    public ChestOpenListener(WantedPlugin plugin){
        this.plugin=plugin;
        this.keyPlayerPlaced=plugin.keyPlayerPlaced();
        this.keyChestTriggeredOnce=plugin.keyChestTriggeredOnce();
    }

    @EventHandler(priority= EventPriority.MONITOR, ignoreCancelled=true)
    public void onBlockPlace(BlockPlaceEvent e){
        Block b=e.getBlockPlaced(); Material t=b.getType();
        if(!plugin.settings().triggerContainers().contains(t)) return;
        BlockState st=b.getState();
        if(st instanceof TileState tile){
            tile.getPersistentDataContainer().set(keyPlayerPlaced, PersistentDataType.BYTE,(byte)1);
            tile.update(true);
        }
    }

    @EventHandler(priority= EventPriority.HIGHEST, ignoreCancelled=true)
    public void onInventoryOpen(InventoryOpenEvent e){
        HumanEntity he=e.getPlayer(); if(!(he instanceof Player p)) return;
        if(plugin.sessions().isParticipant(p)) return;
        InventoryHolder holder=e.getInventory().getHolder(); if(holder==null) return;
        Material mat=null; boolean playerPlaced=false; Block chestBlock=null;

        if(holder instanceof Chest chest){
            mat=Material.CHEST; chestBlock=chest.getBlock(); playerPlaced=isPlayerPlaced(chest.getBlock());
            if(plugin.settings().chestTriggerOnce() && isChestTriggeredOnce(chest.getBlock())) return;
        } else if(holder instanceof Barrel barrel){
            mat=Material.BARREL; chestBlock=barrel.getBlock(); playerPlaced=isPlayerPlaced(barrel.getBlock());
            if(plugin.settings().chestTriggerOnce() && isChestTriggeredOnce(barrel.getBlock())) return;
        } else if(holder instanceof DoubleChest dc){
            mat=Material.CHEST;
            Chest left=(Chest)dc.getLeftSide(), right=(Chest)dc.getRightSide();
            Block lb=left.getBlock(), rb=right.getBlock();
            playerPlaced=isPlayerPlaced(lb)||isPlayerPlaced(rb);
            if(plugin.settings().chestTriggerOnce() && (isChestTriggeredOnce(lb)||isChestTriggeredOnce(rb))) return;
            chestBlock=lb;
        }

        if(mat==null) return;
        if(!plugin.settings().triggerContainers().contains(mat)) return;
        if(playerPlaced) return;
        if(chestBlock==null) return;

        int nearby = chestBlock.getWorld().getNearbyEntities(chestBlock.getLocation().add(0.5,0.5,0.5),
                plugin.settings().radius(), plugin.settings().radius(), plugin.settings().radius(),
                e2 -> e2 instanceof org.bukkit.entity.Villager).size();
        if(nearby < plugin.settings().minNearbyToTrigger()) return;
        if(!plugin.sessions().canTriggerCrime(p, Crime.LARCENY)) return;

        if(plugin.settings().chestTriggerOnce()) markChestTriggered(chestBlock);
        plugin.sessions().startOrRefreshSession(p,chestBlock.getLocation(), Crime.LARCENY);
    }

    private boolean isPlayerPlaced(Block b){
        var st=b.getState();
        if(st instanceof TileState tile){
            Byte flag=tile.getPersistentDataContainer().get(keyPlayerPlaced, PersistentDataType.BYTE);
            return flag!=null && flag==(byte)1;
        }
        return false;
    }
    private boolean isChestTriggeredOnce(Block b){
        var st=b.getState();
        if(st instanceof TileState tile){
            Byte flag=tile.getPersistentDataContainer().get(keyChestTriggeredOnce, PersistentDataType.BYTE);
            return flag!=null && flag==(byte)1;
        }
        return false;
    }
    private void markChestTriggered(Block b){
        var st=b.getState();
        if(st instanceof TileState tile){
            tile.getPersistentDataContainer().set(keyChestTriggeredOnce, PersistentDataType.BYTE, (byte)1);
            tile.update(true);
        }
    }
}
