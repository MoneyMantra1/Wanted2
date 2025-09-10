package com.itharia.wanted;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.TileState;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.loot.Lootable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class WantedSessionManager implements Listener {
    public enum Crime { LARCENY, MURDER }

    private final WantedPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, RearmInfo> rearm = new HashMap<>();

    private static final String TEAM_RED = "wanted_red";
    private static final String TEAM_AQUA = "wanted_aqua";

    public WantedSessionManager(WantedPlugin plugin){
        this.plugin = plugin;
        setupTeams();
    }

    private void setupTeams(){
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team red = sb.getTeam(TEAM_RED);
        if(red==null){ red = sb.registerNewTeam(TEAM_RED); red.setColor(ChatColor.RED); red.setAllowFriendlyFire(true); red.setCanSeeFriendlyInvisibles(false); }
        Team aqua = sb.getTeam(TEAM_AQUA);
        if(aqua==null){ aqua = sb.registerNewTeam(TEAM_AQUA); aqua.setColor(ChatColor.AQUA); aqua.setAllowFriendlyFire(true); aqua.setCanSeeFriendlyInvisibles(false); }
    }

    // === Queries ===
    public boolean hasOwnSession(Player p){ return sessions.containsKey(p.getUniqueId()); }
    public boolean isParticipant(Player p){
        if(hasOwnSession(p)) return true;
        Location loc = p.getLocation();
        for(Session s : sessions.values()){
            if(s.anchorLoc==null || s.anchorLoc.getWorld()==null || loc.getWorld()==null) continue;
            if(!Objects.equals(s.anchorLoc.getWorld().getUID(), loc.getWorld().getUID())) continue;
            int r = plugin.settings().radius();
            if(s.anchorLoc.distanceSquared(loc) <= r*r) return true;
        }
        return false;
    }
    public boolean canTriggerCrime(Player p, Crime crime){
        Settings.RearmMode mode = plugin.settings().rearmMode();
        if(mode==Settings.RearmMode.NONE) return true;
        RearmInfo info = rearm.get(p.getUniqueId());
        long now = System.currentTimeMillis();
        if(info==null) return true;
        if(crime==Crime.LARCENY) return now >= info.nextLarcenyAt;
        else return now >= info.nextMurderAt;
    }

    // === Control ===
    public void forceStartAt(Player thief, Location anchor, boolean titlesAsNearby, Crime crime){
        Session existing = sessions.remove(thief.getUniqueId());
        if(existing!=null) existing.end("force-restart");
        Session s = new Session(thief.getUniqueId(), anchor, titlesAsNearby, crime);
        sessions.put(thief.getUniqueId(), s);
        s.begin();
    }
    public void startOrRefreshSession(Player player, Location anchor, Crime crime){
        if(isParticipant(player)) return;
        if(!canTriggerCrime(player, crime)) return;
        Session s = sessions.get(player.getUniqueId());
        if(s==null){
            s = new Session(player.getUniqueId(), anchor, false, crime);
            sessions.put(player.getUniqueId(), s);
            s.begin();
            return;
        }
        s.refresh();
    }
    public void endSession(Player p, String reason){
        Session s = sessions.remove(p.getUniqueId());
        if(s!=null) s.end(reason);
    }
    public void endAll(String reason){
        for(Session s : new ArrayList<>(sessions.values())) s.end(reason);
        sessions.clear();
    }

    private static final class RearmInfo{
        long nextLarcenyAt, nextMurderAt;
        RearmInfo(long l, long m){ nextLarcenyAt = l; nextMurderAt = m; }
    }

    private Team team(String name){ return Bukkit.getScoreboardManager().getMainScoreboard().getTeam(name); }
    private void addToTeam(Team t, Entity e){ if(t!=null){ try{ t.addEntry(e.getUniqueId().toString()); }catch(IllegalStateException ignored){} } }
    private void removeFromTeam(String name, Entity e){ Team t=team(name); if(t!=null) t.removeEntry(e.getUniqueId().toString()); }
    private boolean isHostileVillager(Entity e){
        if(!(e instanceof Villager v)) return false;
        Byte hostile = v.getPersistentDataContainer().get(plugin.keyHostileVillager(), PersistentDataType.BYTE);
        return hostile != null && hostile == (byte)1;
    }
    private boolean isAggressorType(EntityType t){
        return t==EntityType.IRON_GOLEM || t==EntityType.ZOMBIE || t==EntityType.HUSK;
    }

    // === Session ===
    private final class Session{
        enum Phase { WITNESS, WANTED }
        final UUID playerId;
        Location anchorLoc;
        long endTimeMs;
        int taskId = -1;
        int secondsTotal;
        Crime crime;
        final boolean titlesAsNearby;
        Phase phase = Phase.WITNESS;
        long witnessEndTimeMs;
        UUID witnessVillager;
        long lastHeartbeatMs = 0L;
        final Set<UUID> hostileVillagers = new HashSet<>(), spawnedVillagers = new HashSet<>(), observers = new HashSet<>();
        boolean redPhase = true; int blinkAccumTicks = 0;
        final Map<UUID, Long> nextThrowAtMs = new HashMap<>();
        boolean ended = false;

        Session(UUID playerId, Location anchor, boolean titlesAsNearby, Crime crime){
            this.playerId = playerId;
            this.anchorLoc = anchor==null?null:anchor.clone();
            this.secondsTotal = plugin.settings().durationSeconds();
            this.endTimeMs = System.currentTimeMillis() + secondsTotal*1000L;
            this.titlesAsNearby = titlesAsNearby;
            this.crime = crime;
            rearm.computeIfAbsent(playerId, id -> new RearmInfo(0L, 0L));
        }
        void begin(){
            Player p = Bukkit.getPlayer(playerId);
            if(p==null || !p.isOnline()){ cleanupEntities(); return; }
            refreshObservers();
            if(plugin.settings().witnessEnabled()) startWitness(p); else startWanted(p);
        }

        private Component amp(String s){ return LegacyComponentSerializer.legacyAmpersand().deserialize(s==null?\"\":s); }
        private Title.Times tt(){ return Title.Times.times(
                java.time.Duration.ofMillis(plugin.settings().titleFadeInMs()),
                java.time.Duration.ofMillis(plugin.settings().titleStayMs()),
                java.time.Duration.ofMillis(plugin.settings().titleFadeOutMs())
        ); }

        private void refreshObservers(){
            Set<UUID> old = new HashSet<>(observers);
            observers.clear();
            if(anchorLoc==null || anchorLoc.getWorld()==null) return;
            int r = plugin.settings().radius();
            for(Player other : anchorLoc.getWorld().getNearbyPlayers(anchorLoc, r)){
                if(!other.getUniqueId().equals(playerId)) observers.add(other.getUniqueId());
            }
            old.removeAll(observers);
            for(UUID id : old){
                Player pl = Bukkit.getPlayer(id);
                if(pl!=null){
                    plugin.displayManager().hideBossbar(pl);
                    if(plugin.settings().displayMode()==Settings.DisplayMode.SCOREBOARD) plugin.displayManager().hideScoreboard(pl);
                }
            }
        }

        private void startWitness(Player p){
            phase = Phase.WITNESS;
            witnessEndTimeMs = System.currentTimeMillis() + plugin.settings().witnessDuration()*1000L;
            lastHeartbeatMs = System.currentTimeMillis();

            // pick nearest villager
            Villager nearest = null; double best = Double.MAX_VALUE; int r = plugin.settings().radius();
            for(Entity e : p.getNearbyEntities(r,r,r)){
                if(e instanceof Villager v){
                    wakeIfSleeping(v);
                    double d2 = v.getLocation().distanceSquared(p.getLocation());
                    if(d2 < best){ best = d2; nearest = v; }
                }
            }
            if(nearest!=null){
                witnessVillager = nearest.getUniqueId();
                if(plugin.settings().witnessHighlight()){
                    nearest.setGlowing(true);
                    nearest.getPersistentDataContainer().set(plugin.keyWitnessVillager(), PersistentDataType.BYTE, (byte)1);
                }
            }

            String runnerStr = (crime==Crime.MURDER)? plugin.settings().witnessTitleMurder() : plugin.settings().witnessTitle();
            String nearbyStr = (crime==Crime.MURDER)? plugin.settings().witnessNearbyTitleMurder() : plugin.settings().witnessNearbyTitle();
            Component runnerTitle = titlesAsNearby ? amp(nearbyStr) : amp(runnerStr);

            p.showTitle(Title.title(runnerTitle, Component.empty(), tt()));
            p.playSound(p.getLocation(), plugin.settings().witnessSound(), plugin.settings().witnessSoundVolume(), 1.0f);
            for(UUID id : observers){
                Player obs = Bukkit.getPlayer(id); if(obs==null || !obs.isOnline()) continue;
                obs.showTitle(Title.title(amp(nearbyStr), Component.empty(), tt()));
                obs.playSound(obs.getLocation(), plugin.settings().witnessSound(), plugin.settings().witnessSoundVolume(), 1.0f);
                plugin.displayManager().showBossbar(obs, amp(plugin.settings().witnessBossbarText()), BossBar.Color.WHITE, BossBar.Overlay.PROGRESS, 1.0f);
            }
            plugin.displayManager().showBossbar(p, amp(plugin.settings().witnessBossbarText()), BossBar.Color.WHITE, BossBar.Overlay.PROGRESS, 1.0f);
            scheduleNextTickWitness();
        }

        private void startWanted(Player p){
            phase = Phase.WANTED;
            secondsTotal = plugin.settings().durationSeconds();
            endTimeMs = System.currentTimeMillis() + secondsTotal*1000L;

            markNearbyVillagersHostile(p);
            int naturalNearby = countNaturalHostilesNear(p) - 1;
            int extras = 0;
            if(plugin.settings().witnessEnabled()){
                if(naturalNearby==1) extras = plugin.getConfig().getInt("wanted.extra_spawns.for_1_nearby",4);
                else if(naturalNearby==2) extras = 3;
                else if(naturalNearby==3) extras = 2;
            }
            if(extras>0) spawnEnforcersAround(p, extras);

            Component subtitle = titlesAsNearby ? amp(plugin.settings().wantedStartSubtitleNearby())
                    : (crime==Crime.MURDER ? amp(plugin.settings().wantedStartSubtitleMurder()) : amp(plugin.settings().wantedStartSubtitleTrigger()));
            p.showTitle(Title.title(amp(plugin.settings().wantedStartTitle()), subtitle, tt()));
            plugin.displayManager().showBossbar(p, amp(plugin.settings().wantedBossbarText()), plugin.settings().bossbarColor(), plugin.settings().bossbarOverlay(), 1.0f);
            for(UUID id : observers){
                Player obs=Bukkit.getPlayer(id); if(obs==null || !obs.isOnline()) continue;
                obs.showTitle(Title.title(amp(plugin.settings().wantedStartTitle()), amp(plugin.settings().wantedStartSubtitleNearby()), tt()));
                plugin.displayManager().showBossbar(obs, amp(plugin.settings().wantedBossbarText()), plugin.settings().bossbarColor(), plugin.settings().bossbarOverlay(), 1.0f);
            }
            p.playSound(p.getLocation(), plugin.settings().wantedStartSound(), plugin.settings().wantedStartVolume(), 1.0f);
            scheduleNextTickWanted();
        }

        void refresh(){
            refreshObservers();
            if(phase==Phase.WITNESS){
                witnessEndTimeMs = System.currentTimeMillis() + plugin.settings().witnessDuration()*1000L;
                scheduleNextTickWitness();
            }else{
                secondsTotal = plugin.settings().durationSeconds();
                endTimeMs = System.currentTimeMillis() + secondsTotal*1000L;
                Player p = Bukkit.getPlayer(playerId);
                if(p!=null) plugin.displayManager().showBossbar(p, amp(plugin.settings().wantedBossbarText()), plugin.settings().bossbarColor(), plugin.settings().bossbarOverlay(), 1.0f);
                for(UUID id:observers){ Player obs=Bukkit.getPlayer(id); if(obs!=null && obs.isOnline())
                    plugin.displayManager().showBossbar(obs, amp(plugin.settings().wantedBossbarText()), plugin.settings().bossbarColor(), plugin.settings().bossbarOverlay(), 1.0f); }
                scheduleNextTickWanted();
            }
        }

        void end(String reason){
            if(ended) return; ended = true;
            cancelTask();
            sessions.remove(playerId, this);
            refreshObservers();

            long now = System.currentTimeMillis();
            rearm.compute(playerId, (id, info)->{
                if(info==null) info = new RearmInfo(0,0);
                if(crime==Crime.LARCENY) info.nextLarcenyAt = now + plugin.settings().cooldownLarcenySeconds()*1000L;
                else info.nextMurderAt = now + plugin.settings().cooldownMurderSeconds()*1000L;
                return info;
            });

            List<UUID> parts = new ArrayList<>(); parts.add(playerId); parts.addAll(observers);
            for(UUID id : parts){
                Player pl = Bukkit.getPlayer(id); if(pl==null) continue;
                if("witness-eliminated".equals(reason)){
                    pl.playSound(pl.getLocation(), plugin.settings().witnessKilledSound(), plugin.settings().witnessKilledVolume(), 1.0f);
                    pl.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 20, 0, false, false, false));
                }else if(phase==Phase.WANTED){
                    pl.playSound(pl.getLocation(), plugin.settings().wantedEndSound(), plugin.settings().wantedEndVolume(), 1.0f);
                    pl.showTitle(Title.title(amp(plugin.settings().wantedEndTitle()), Component.empty(), tt()));
                }
                plugin.displayManager().hideBossbar(pl);
                if(plugin.settings().displayMode()==Settings.DisplayMode.SCOREBOARD) plugin.displayManager().hideScoreboard(pl);
            }
            cleanupEntities();
            if(plugin.settings().debug()) plugin.getLogger().info("[Wanted] Ended session ("+reason+")");
        }

        private void scheduleNextTickWitness(){ cancelTask(); int i=Math.max(2, Math.min(10, plugin.settings().logicTickTicks()));
            taskId=Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, this::tickWitness, i); }
        private void scheduleNextTickWanted(){ cancelTask(); int i=Math.max(2, plugin.settings().logicTickTicks());
            taskId=Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, this::tickWanted, i); }

        private void tickWitness(){
            Player p=Bukkit.getPlayer(playerId);
            if(p==null || !p.isOnline() || p.isDead()){ end("player-offline"); return; }
            refreshObservers();
            long now = System.currentTimeMillis();
            long msLeft = Math.max(0L, witnessEndTimeMs - now);

            if(witnessVillager!=null){
                Entity e = Bukkit.getEntity(witnessVillager);
                if(e instanceof Villager v){
                    if(!v.isGlowing() && plugin.settings().witnessHighlight()) v.setGlowing(true);
                    wakeIfSleeping(v);
                }
            }

            if(msLeft==0L){
                if(witnessVillager!=null){
                    Entity e = Bukkit.getEntity(witnessVillager);
                    if(e instanceof Villager v){
                        v.getPersistentDataContainer().remove(plugin.keyWitnessVillager());
                        v.setGlowing(false); wakeIfSleeping(v);
                    }
                }
                startWanted(p);
                return;
            }

            float progress = (float)(msLeft / (double)(plugin.settings().witnessDuration()*1000L));
            plugin.displayManager().showBossbar(p, amp(plugin.settings().witnessBossbarText()), BossBar.Color.WHITE, BossBar.Overlay.PROGRESS, progress);
            for(UUID id:observers){
                Player obs=Bukkit.getPlayer(id); if(obs!=null && obs.isOnline())
                    plugin.displayManager().showBossbar(obs, amp(plugin.settings().witnessBossbarText()), BossBar.Color.WHITE, BossBar.Overlay.PROGRESS, progress);
            }

            // Repeat heartbeat
            long hbInterval = plugin.settings().witnessSoundRepeatTicks()*50L;
            if(now - lastHeartbeatMs >= hbInterval){
                p.playSound(p.getLocation(), plugin.settings().witnessSound(), plugin.settings().witnessSoundVolume(), 1.0f);
                for(UUID id:observers){
                    Player obs=Bukkit.getPlayer(id); if(obs!=null && obs.isOnline())
                        obs.playSound(obs.getLocation(), plugin.settings().witnessSound(), plugin.settings().witnessSoundVolume(), 1.0f);
                }
                lastHeartbeatMs = now;
            }

            // Witness flees
            if(witnessVillager!=null){
                Entity e = Bukkit.getEntity(witnessVillager);
                if(e instanceof Villager w && w instanceof Mob mob){
                    wakeIfSleeping(w);
                    try{ mob.getPathfinder().stopPathfinding(); }catch(Throwable ignored){}
                    Vector away = w.getLocation().toVector().subtract(p.getLocation().toVector()).normalize();
                    if(Double.isFinite(away.length())){
                        Vector dest = w.getLocation().toVector().add(away.multiply(6.0));
                        Location target = findGround(w.getWorld(), (int)Math.floor(dest.getX()), w.getLocation().getBlockY(), (int)Math.floor(dest.getZ()));
                        if(target!=null){
                            try{ mob.getPathfinder().moveTo(target, plugin.settings().witnessFleeSpeed()); }catch(Throwable ignored){}
                        }
                    }
                }
            }

            scheduleNextTickWitness();
        }

        private void tickWanted(){
            Player p = Bukkit.getPlayer(playerId);
            if(p==null || !p.isOnline() || p.isDead()){ end("player-offline"); return; }
            refreshObservers();
            int left = secondsLeft();
            if(left<=0){ end("timeout"); return; }
            float progress = Math.max(0f, Math.min(1f, left/(float)secondsTotal));
            plugin.displayManager().updateBossbar(p, progress);
            for(UUID id:observers){ Player obs=Bukkit.getPlayer(id); if(obs!=null && obs.isOnline()) plugin.displayManager().updateBossbar(obs, progress); }

            // Flash teams (police lights)
            blinkAccumTicks += plugin.settings().logicTickTicks();
            if(blinkAccumTicks >= 10){ redPhase = !redPhase; blinkAccumTicks = 0; swapHostileTeams(); }

            // Chase + throw
            if(plugin.settings().chaseEnabled()) chasePlayers();
            performThrowsAtParticipants();

            scheduleNextTickWanted();
        }

        private int secondsLeft(){ long now = System.currentTimeMillis(); long ms = Math.max(0, endTimeMs - now); return (int)Math.ceil(ms/1000.0); }
        private void cancelTask(){ if(taskId!=-1){ Bukkit.getScheduler().cancelTask(taskId); taskId=-1; } }

        private void cleanupEntities(){
            for(UUID id : new HashSet<>(hostileVillagers)){
                Entity e = Bukkit.getEntity(id);
                if(e instanceof Villager v){
                    v.getPersistentDataContainer().remove(plugin.keyHostileVillager());
                    v.setGlowing(false); wakeIfSleeping(v);
                    removeFromTeam(TEAM_RED, v); removeFromTeam(TEAM_AQUA, v);
                    if(v instanceof Mob mob){ try{ mob.getPathfinder().stopPathfinding(); }catch(Throwable ignored){} }
                }
                nextThrowAtMs.remove(id);
            }
            hostileVillagers.clear();
            for(UUID id : new HashSet<>(spawnedVillagers)){ Entity e=Bukkit.getEntity(id); if(e!=null) e.remove(); nextThrowAtMs.remove(id); }
            spawnedVillagers.clear();
            if(witnessVillager!=null){
                Entity e=Bukkit.getEntity(witnessVillager);
                if(e instanceof Villager v){ v.getPersistentDataContainer().remove(plugin.keyWitnessVillager()); v.setGlowing(false); wakeIfSleeping(v); }
            }
            witnessVillager = null;
            nextThrowAtMs.clear();
        }
        private void wakeIfSleeping(Villager v){ try{ if(v.isSleeping()) v.wakeup(); }catch(Throwable ignored){} }

        private int markNearbyVillagersHostile(Player p){
            int r=plugin.settings().radius(); int marked=0;
            for(Entity e: p.getNearbyEntities(r,r,r)){
                if(e instanceof Villager v){
                    wakeIfSleeping(v);
                    markVillagerHostile(v);
                    hostileVillagers.add(v.getUniqueId());
                    marked++;
                }
            }
            return marked;
        }
        private int countNaturalHostilesNear(Player p){
            int r=plugin.settings().radius(); int count=0;
            for(Entity e: p.getNearbyEntities(r,r,r)){
                if(e instanceof Villager v){
                    if(v.getPersistentDataContainer().getOrDefault(plugin.keySpawnedVillager(), PersistentDataType.BYTE, (byte)0) == (byte)0) count++;
                }
            }
            return count;
        }
        private void markVillagerHostile(Villager v){
            v.setAware(true); v.setGlowing(true);
            v.getPersistentDataContainer().set(plugin.keyHostileVillager(), PersistentDataType.BYTE, (byte)1);
            if(redPhase) addToTeam(team(TEAM_RED), v); else addToTeam(team(TEAM_AQUA), v);
        }
        private void swapHostileTeams(){
            for(UUID id : new ArrayList<>(hostileVillagers)){
                Entity e=Bukkit.getEntity(id);
                if(!(e instanceof Villager v)){ hostileVillagers.remove(id); nextThrowAtMs.remove(id); continue; }
                if(redPhase){ removeFromTeam(TEAM_AQUA, v); addToTeam(team(TEAM_RED), v); }
                else{ removeFromTeam(TEAM_RED, v); addToTeam(team(TEAM_AQUA), v); }
            }
        }
        private void spawnEnforcersAround(Player p, int count){
            World w=p.getWorld(); int minD=plugin.settings().spawnMinDistance(), maxD=plugin.settings().spawnMaxDistance();
            ThreadLocalRandom rnd=ThreadLocalRandom.current();
            for(int i=0;i<count;i++){
                double ang=rnd.nextDouble(0, Math.PI*2), dist=rnd.nextDouble(minD, Math.max(minD+0.1, maxD));
                Location base=p.getLocation().clone().add(Math.cos(ang)*dist,0,Math.sin(ang)*dist);
                Location spawn=findGround(w, base.getBlockX(), p.getLocation().getBlockY(), base.getBlockZ());
                if(spawn==null) continue;
                Villager v = w.spawn(spawn, Villager.class, vv->{ vv.setAdult(); vv.setAware(true); vv.setRemoveWhenFarAway(true); });
                wakeIfSleeping(v); markVillagerHostile(v);
                v.getPersistentDataContainer().set(plugin.keySpawnedVillager(), PersistentDataType.BYTE, (byte)1);
                spawnedVillagers.add(v.getUniqueId()); hostileVillagers.add(v.getUniqueId());
            }
        }
        private Location findGround(World w, int x, int initY, int z){
            int y=Math.min(w.getMaxHeight()-2, Math.max(w.getMinHeight()+1, initY));
            for(int dy=0; dy<16; dy++){
                Location check=new Location(w,x+0.5,y-dy,z+0.5);
                if(check.getBlock().getType().isSolid()){
                    Location above=check.clone().add(0,1,0);
                    if(above.getBlock().isEmpty()) return above;
                }
            }
            for(int dy=1; dy<=16; dy++){
                Location check=new Location(w,x+0.5,y+dy,z+0.5);
                if(check.getBlock().getType().isSolid()){
                    Location above=check.clone().add(0,1,0);
                    if(above.getBlock().isEmpty()) return above;
                }
            }
            return null;
        }
        private void chasePlayers(){
            double keep=plugin.settings().chaseKeepDistance();
            double keepSq=keep*keep;
            double speed=plugin.settings().chaseSpeed();
            Set<Player> targets=new HashSet<>();
            Player trigger=Bukkit.getPlayer(playerId); if(trigger!=null) targets.add(trigger);
            for(UUID id:observers){ Player obs=Bukkit.getPlayer(id); if(obs!=null) targets.add(obs); }

            for(UUID id : new ArrayList<>(hostileVillagers)){
                Entity e=Bukkit.getEntity(id);
                if(!(e instanceof Villager v)){ hostileVillagers.remove(id); nextThrowAtMs.remove(id); continue; }
                if(!(v instanceof Mob mob)) continue;
                wakeIfSleeping(v);

                Player nearest=null; double best=Double.MAX_VALUE;
                for(Player t:targets){
                    if(!Objects.equals(t.getWorld(), v.getWorld())) continue;
                    double d2=v.getLocation().distanceSquared(t.getLocation());
                    if(d2<best){ best=d2; nearest=t; }
                }
                if(nearest==null) continue;
                try{
                    if(best>keepSq) mob.getPathfinder().moveTo(nearest.getLocation(), speed);
                    else{
                        Vector to=nearest.getLocation().toVector().subtract(v.getLocation().toVector());
                        Vector right=new Vector(-to.getZ(),0,to.getX()).normalize().multiply(1.5);
                        Location orbit=nearest.getLocation().clone().add(right);
                        mob.getPathfinder().moveTo(orbit, Math.max(0.3, speed*0.9));
                    }
                }catch(Throwable ignored){}
            }
        }
        private void performThrowsAtParticipants(){
            int range=plugin.settings().throwRange(); double rangeSq=range*1.0*range;
            long intervalMs=plugin.settings().throwIntervalTicks()*50L;
            long now=System.currentTimeMillis();

            Set<Player> cands=new HashSet<>(); Player trig=Bukkit.getPlayer(playerId); if(trig!=null) cands.add(trig);
            for(UUID id:observers){ Player obs=Bukkit.getPlayer(id); if(obs!=null) cands.add(obs); }

            for(UUID id:new ArrayList<>(hostileVillagers)){
                Entity e=Bukkit.getEntity(id);
                if(!(e instanceof Villager v)){ hostileVillagers.remove(id); nextThrowAtMs.remove(id); continue; }
                long nextAt=nextThrowAtMs.getOrDefault(id,0L);
                if(now<nextAt) continue;

                Location eye=v.getEyeLocation();
                Player target=null; double best=Double.MAX_VALUE;
                for(Player pl:cands){
                    if(!Objects.equals(pl.getWorld(), v.getWorld())) continue;
                    if(pl.getGameMode()==GameMode.SPECTATOR) continue;
                    double d2=eye.distanceSquared(pl.getEyeLocation());
                    if(d2<=rangeSq && d2<best){ best=d2; target=pl; }
                }
                if(target==null) continue;

                Vector dir=target.getEyeLocation().toVector().subtract(eye.toVector()).normalize().multiply(plugin.settings().projectileVelocityMultiplier());
                Snowball ball=v.launchProjectile(Snowball.class);
                ball.setVelocity(dir);

                nextThrowAtMs.put(id, now+intervalMs);
            }
        }
    }

    // === Listeners ===
    @EventHandler(ignoreCancelled=true)
    public void onQuit(PlayerQuitEvent e){
        Player p=e.getPlayer();
        Session s=sessions.get(p.getUniqueId());
        if(s!=null) s.end("quit");
    }

    @EventHandler(ignoreCancelled=true)
    public void onVillagerDeathWitness(EntityDeathEvent e){
        if(!(e.getEntity() instanceof Villager v)) return;
        Session owner=null;
        for(Session s : sessions.values()){
            if(s.witnessVillager!=null && v.getUniqueId().equals(s.witnessVillager)){ owner=s; break; }
        }
        if(owner==null) return;
        owner.end("witness-eliminated");
    }

    @EventHandler(ignoreCancelled=true)
    public void onVillagerDeathMurder(EntityDeathEvent e){
        if(!(e.getEntity() instanceof Villager v)) return;
        Player killer = e.getEntity().getKiller(); if(killer==null) return;
        if(isParticipant(killer)) return;

        Byte hostile=v.getPersistentDataContainer().get(plugin.keyHostileVillager(), PersistentDataType.BYTE);
        Byte wit=v.getPersistentDataContainer().get(plugin.keyWitnessVillager(), PersistentDataType.BYTE);
        if((hostile!=null&&hostile==(byte)1) || (wit!=null&&wit==(byte)1)) return;

        // Need at least 2 other villagers nearby
        int nearby=0; int r = plugin.settings().radius();
        for(Entity ent : v.getNearbyEntities(r, r, r)){
            if(ent instanceof Villager && !ent.getUniqueId().equals(v.getUniqueId())) nearby++;
            if(nearby>=2) break;
        }
        if(nearby<2) return;
        if(!canTriggerCrime(killer, Crime.MURDER)) return;

        forceStartAt(killer, v.getLocation(), false, Crime.MURDER);
    }

    @EventHandler(ignoreCancelled=true)
    public void onTradeOpen(PlayerInteractEvent e){
        if(e.getAction()!=Action.RIGHT_CLICK_ENTITY) return;
        if(!(e.getRightClicked() instanceof Villager v)) return;
        if(!plugin.settings().blockTradesWhenHostile()) return;
        Byte hostile=v.getPersistentDataContainer().get(plugin.keyHostileVillager(), PersistentDataType.BYTE);
        if(hostile!=null && hostile==(byte)1){
            e.setCancelled(true);
            e.getPlayer().sendActionBar(Component.text("This villager refuses to trade!"));
        }
    }

    @EventHandler(ignoreCancelled=true)
    public void onTeleport(PlayerTeleportEvent e){
        Player p=e.getPlayer();
        Session s=sessions.get(p.getUniqueId()); if(s==null) return;
        Location to=e.getTo(); if(to==null || s.anchorLoc==null || to.getWorld()==null) return;
        if(s.anchorLoc.getWorld()!=to.getWorld()){ s.end("teleport-world-change"); return; }
        double max=plugin.settings().teleportCleanupDistance();
        if(to.distanceSquared(s.anchorLoc) > max*max) s.end("teleport-far");
    }

    // Friendly fire prevention (villager->villager snowballs)
    @EventHandler(ignoreCancelled=true)
    public void onFriendlyFire(EntityDamageByEntityEvent e){
        if(!plugin.settings().preventVillagerFriendlyFire()) return;
        if(!(e.getEntity() instanceof Villager)) return;
        if(!(e.getDamager() instanceof Projectile proj)) return;
        if(!(proj.getShooter() instanceof Villager)) return;
        e.setCancelled(true);
    }

    // Immunity vs golems/zombies/husks
    @EventHandler(ignoreCancelled=true)
    public void onAggressorTargetHostileVillager(EntityTargetLivingEntityEvent e){
        if(!(e.getEntity() instanceof Mob mob)) return;
        if(!isAggressorType(mob.getType())) return;
        if(!(e.getTarget() instanceof Villager v)) return;
        if(!isHostileVillager(v)) return;
        e.setCancelled(true);
        try{ mob.setTarget(null); }catch(Throwable ignored){}
    }
    @EventHandler(ignoreCancelled=true)
    public void onAggressorDamageHostileVillager(EntityDamageByEntityEvent e){
        if(!(e.getEntity() instanceof Villager v)) return;
        if(!isHostileVillager(v)) return;
        Entity dam = e.getDamager();
        if(dam instanceof Projectile proj && proj.getShooter() instanceof Entity shooter) dam = shooter;
        if(isAggressorType(dam.getType())) e.setCancelled(true);
    }

    // Damage players on snowball hit
    @EventHandler(ignoreCancelled=true)
    public void onHit(ProjectileHitEvent e){
        if(!(e.getEntity() instanceof Snowball ball)) return;
        if(!(ball.getShooter() instanceof Villager villager)) return;
        if(!(e.getHitEntity() instanceof Player hit)) return;
        double base = plugin.settings().projectileBaseDamage();
        double scaled = base * (1.0 + plugin.settings().buffPercent()/100.0);
        hit.damage(scaled, villager);
    }

    // Chest open -> larceny trigger (world-generated only)
    @EventHandler(ignoreCancelled=true)
    public void onInventoryOpen(InventoryOpenEvent e){
        if(!(e.getPlayer() instanceof Player p)) return;
        if(isParticipant(p)) return;
        Inventory inv = e.getInventory();
        InventoryHolder holder = inv.getHolder();
        Block chestBlock = null;
        try{
            if(holder instanceof org.bukkit.block.DoubleChest dc){
                org.bukkit.block.Chest left = (org.bukkit.block.Chest)dc.getLeftSide();
                chestBlock = left.getBlock();
            }else if(holder instanceof Chest c){
                chestBlock = c.getBlock();
            }else{
                return;
            }
        }catch(Throwable t){
            return;
        }
        if(chestBlock == null) return;
        if(!(chestBlock.getState() instanceof Lootable loot)) return;
        if(loot.getLootTable()==null) return; // not a world-generated chest

        // trigger_once per chest
        if(chestBlock.getState() instanceof TileState ts){
            Byte trig = ts.getPersistentDataContainer().get(plugin.keyChestTriggered(), PersistentDataType.BYTE);
            if(plugin.settings().chestTriggerOnce() && trig!=null && trig==(byte)1) return;
        }

        // villagers nearby?
        int r = plugin.settings().radius();
        boolean anyVillager=false;
        for(Entity ent : chestBlock.getWorld().getNearbyEntities(chestBlock.getLocation(), r, r, r)){
            if(ent instanceof Villager){ anyVillager=true; break; }
        }
        if(!anyVillager) return;
        if(!canTriggerCrime(p, Crime.LARCENY)) return;

        // mark chest if trigger_once
        if(chestBlock.getState() instanceof TileState ts){
            if(plugin.settings().chestTriggerOnce()){
                ts.getPersistentDataContainer().set(plugin.keyChestTriggered(), PersistentDataType.BYTE, (byte)1);
                ts.update(true, false);
            }
        }
        forceStartAt(p, chestBlock.getLocation().add(0.5,0,0.5), false, Crime.LARCENY);
    }
}
