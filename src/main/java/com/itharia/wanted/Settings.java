package com.itharia.wanted;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class Settings {
    public enum DisplayMode { ACTIONBAR, BOSSBAR, SCOREBOARD, NONE }
    public enum RefreshPolicy { SAME_CHEST_ONLY, ANY_TRIGGER }
    public enum RearmMode { NONE, RADIUS, COOLDOWN, BOTH }

    private final boolean debug;
    private final DisplayMode displayMode;
    private final int autoHideTicks;
    private final BossBar.Color bossbarColor;
    private final BossBar.Overlay bossbarOverlay;
    private final String scoreboardTitle;
    private final boolean scoreboardUseDivider;

    private final int durationSeconds, radius, minNearbyToTrigger, logicTickTicks, spawnMinDistance, spawnMaxDistance;
    private final double buffPercent, projectileBaseDamage, projectileVelocityMultiplier, chaseSpeed, chaseKeepDistance;
    private final int throwIntervalTicks, throwRange;
    private final boolean chaseEnabled;
    private final RefreshPolicy refreshPolicy;

    private final boolean witnessEnabled;
    private final int witnessDuration;
    private final String witnessTitle, witnessNearbyTitle, witnessTitleMurder, witnessNearbyTitleMurder, witnessBossbarText;
    private final double witnessFleeSpeed;
    private final Sound witnessSound; private final float witnessSoundVolume;
    private final boolean witnessHighlight, witnessRequireKillerIsPlayer;
    private final int witnessSoundRepeatTicks;

    private final Sound wantedStartSound, wantedEndSound;
    private final float wantedStartVolume, wantedEndVolume;
    private final String wantedBossbarText, wantedStartTitle, wantedStartSubtitleTrigger, wantedStartSubtitleNearby, wantedStartSubtitleMurder, wantedEndTitle;

    private final int titleFadeInMs, titleStayMs, titleFadeOutMs;
    private final int teleportCleanupDistance;
    private final boolean preventVillagerFriendlyFire, blockTradesWhenHostile;

    private final RearmMode rearmMode; private final boolean rearmTrackWorld;
    private final int cooldownLarcenySeconds, cooldownMurderSeconds;

    private final boolean chestTriggerOnce;
    private final Set<Material> triggerContainers;

    private final Sound witnessKilledSound; private final float witnessKilledVolume;

    public Settings(WantedPlugin plugin){
        FileConfiguration c=plugin.getConfig();
        debug=c.getBoolean("debug",false);

        var disp=c.getConfigurationSection("display");
        DisplayMode dm=DisplayMode.BOSSBAR; int auto=1200; BossBar.Color col=BossBar.Color.RED; BossBar.Overlay ov=BossBar.Overlay.PROGRESS;
        String sbTitle="§cWANTED"; boolean sbDiv=true;
        if(disp!=null){
            dm = switch (disp.getString("mode","bossbar").toLowerCase()) {
                case "actionbar" -> DisplayMode.ACTIONBAR;
                case "scoreboard" -> DisplayMode.SCOREBOARD;
                case "none" -> DisplayMode.NONE;
                default -> DisplayMode.BOSSBAR;
            };
            auto = clamp(disp.getInt("auto_hide_ticks",1200),0,20*3600);
            var bb=disp.getConfigurationSection("bossbar");
            if(bb!=null){ col=parseColor(bb.getString("color","RED")); ov=parseOverlay(bb.getString("style","SOLID")); }
            var sb=disp.getConfigurationSection("scoreboard");
            if(sb!=null){ sbTitle=sb.getString("title","§cWANTED"); sbDiv=sb.getBoolean("use_divider",true); }
        }
        displayMode=dm; autoHideTicks=auto; bossbarColor=col; bossbarOverlay=ov; scoreboardTitle=sbTitle; scoreboardUseDivider=sbDiv;

        var core=c.getConfigurationSection("wanted"); if(core==null) core=c.createSection("wanted");
        durationSeconds=clamp(core.getInt("duration_seconds",60),5,600);
        radius=clamp(core.getInt("radius",50),5,128);
        buffPercent=clampD(core.getDouble("buff_percent",50.0),0.0,500.0);
        minNearbyToTrigger=clamp(core.getInt("min_nearby_to_trigger",1),1,100);
        refreshPolicy = "same_chest_only".equalsIgnoreCase(core.getString("refresh_policy","any_trigger"))
                ? RefreshPolicy.SAME_CHEST_ONLY : RefreshPolicy.ANY_TRIGGER;
        logicTickTicks=clamp(core.getInt("logic_tick_ticks",5),2,20);

        var proj=core.getConfigurationSection("projectile"); if(proj==null) proj=c.createSection("projectile");
        projectileBaseDamage=clampD(proj.getDouble("base_damage",2.0),0.0,100.0);
        throwIntervalTicks=clamp(proj.getInt("throw_interval_ticks",30),5,200);
        throwRange=clamp(proj.getInt("throw_range",30),5,128);
        projectileVelocityMultiplier=clampD(proj.getDouble("velocity_multiplier",1.0),0.1,3.0);

        spawnMinDistance=clamp(core.getInt("spawn_min_distance",8),4,64);
        spawnMaxDistance=clamp(core.getInt("spawn_max_distance",16),spawnMinDistance,96);

        var chase=core.getConfigurationSection("chase"); if(chase==null) chase=c.createSection("chase");
        chaseEnabled=chase.getBoolean("enabled",true);
        chaseSpeed=clampD(chase.getDouble("speed",0.5),0.05,1.5);
        chaseKeepDistance=clampD(chase.getDouble("keep_distance",3.0),1.0,8.0);

        var wit=c.getConfigurationSection("witness"); if(wit==null) wit=c.createSection("witness");
        witnessEnabled=wit.getBoolean("enabled",true);
        witnessDuration=clamp(wit.getInt("duration_seconds",15),1,120);
        witnessTitle=wit.getString("title","&cYou've been caught stealing!");
        witnessNearbyTitle=wit.getString("nearby_title","Someone was caught stealing");
        witnessBossbarText=wit.getString("bossbar_text","&4&lKill the witness before he reports you");

        var texts=c.getConfigurationSection("texts"); if(texts==null) texts=c.createSection("texts");
        witnessTitleMurder=texts.getString("witness_title_murder",witnessTitle);
        witnessNearbyTitleMurder=texts.getString("witness_nearby_title_murder",witnessNearbyTitle);

        double flee=wit.getDouble("flee_speed",0.0);
        witnessFleeSpeed=flee<=0.0?chaseSpeed:clampD(flee,0.05,1.5);
        witnessSound=parseSound(wit.getString("sound","ENTITY_WARDEN_HEARTBEAT"), org.bukkit.Sound.ENTITY_WARDEN_HEARTBEAT);
        witnessSoundVolume=(float)clampD(wit.getDouble("sound_volume",4.0),0.1,10.0);
        witnessHighlight=wit.getBoolean("highlight",true);
        witnessSoundRepeatTicks=clamp(wit.getInt("sound_repeat_ticks",20),2,200);
        witnessRequireKillerIsPlayer=wit.getBoolean("require_killer_is_player",true);

        var sounds=core.getConfigurationSection("sounds"); if(sounds==null) sounds=c.createSection("sounds");
        wantedStartSound=parseSound(sounds.getString("start","ENTITY_WITHER_SPAWN"), org.bukkit.Sound.ENTITY_WITHER_SPAWN);
        wantedStartVolume=(float)clampD(sounds.getDouble("start_volume",2.5),0.1,10.0);
        wantedEndSound=parseSound(sounds.getString("end","UI_TOAST_CHALLENGE_COMPLETE"), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE);
        wantedEndVolume=(float)clampD(sounds.getDouble("end_volume",2.2),0.1,10.0);

        var texts2=c.getConfigurationSection("texts"); if(texts2==null) texts2=c.createSection("texts");
        wantedBossbarText=texts2.getString("wanted_bossbar","&c&lYou are now &4&lWANTED. &fHide, fight, survive");
        wantedStartTitle=texts2.getString("wanted_start_title","&c&lYou are now &4&lWANTED");
        wantedStartSubtitleTrigger=texts2.getString("wanted_start_subtitle_trigger","&eCharge: Larceny");
        wantedStartSubtitleNearby=texts2.getString("wanted_start_subtitle_nearby","&eCharge: Guilty of Association");
        wantedStartSubtitleMurder=texts2.getString("wanted_start_subtitle_murder","&eCrime: Murder");
        wantedEndTitle=texts2.getString("wanted_end_title","&7You are no longer wanted");

        var t=c.getConfigurationSection("titles"); if(t==null) t=c.createSection("titles");
        titleFadeInMs=clamp(t.getInt("fade_in_ms",900),0,5000);
        titleStayMs=clamp(t.getInt("stay_ms",4200),200,10000);
        titleFadeOutMs=clamp(t.getInt("fade_out_ms",900),0,5000);

        var safety=c.getConfigurationSection("safety"); if(safety==null) safety=c.createSection("safety");
        teleportCleanupDistance=clamp(safety.getInt("teleport_cleanup_distance",128),16,1024);
        preventVillagerFriendlyFire=safety.getBoolean("preventVillagerFriendlyFire",true);
        blockTradesWhenHostile=safety.getBoolean("block_trades_when_hostile",true);

        var rearm=c.getConfigurationSection("rearm"); if(rearm==null) rearm=c.createSection("rearm");
        String rm=rearm.getString("mode","cooldown").toLowerCase();
        rearmMode = switch (rm){ case "none"->RearmMode.NONE; case "radius"->RearmMode.RADIUS; case "both"->RearmMode.BOTH; default->RearmMode.COOLDOWN; };
        rearmTrackWorld=rearm.getBoolean("track_world",false);
        var cds=rearm.getConfigurationSection("cooldowns"); if(cds==null) cds=rearm.createSection("cooldowns");
        cooldownLarcenySeconds=clamp(cds.getInt("larceny_seconds",600),0,86400);
        cooldownMurderSeconds=clamp(cds.getInt("murder_seconds",30),0,86400);

        var chest=c.getConfigurationSection("chest"); if(chest==null) chest=c.createSection("chest");
        chestTriggerOnce=chest.getBoolean("trigger_once",true);

        triggerContainers=EnumSet.noneOf(Material.class);
        List<String> types=c.getStringList("trigger_containers");
        if(types==null||types.isEmpty()){ triggerContainers.add(Material.CHEST); triggerContainers.add(Material.TRAPPED_CHEST); triggerContainers.add(Material.BARREL); }
        else{ for(String s:types){ Material m=Material.matchMaterial(s); if(m!=null) triggerContainers.add(m); } }

        var wk=c.getConfigurationSection("witness_killed"); if(wk==null) wk=c.createSection("witness_killed");
        witnessKilledSound=parseSound(wk.getString("sound","AMBIENT_WARPED_FOREST_ADDITIONS"), org.bukkit.Sound.ENTITY_VILLAGER_NO);
        witnessKilledVolume=(float)clampD(wk.getDouble("volume",2.0),0.1,10.0);
    }

    private static int clamp(int v,int min,int max){ return Math.max(min,Math.min(max,v)); }
    private static double clampD(double v,double min,double max){ return Math.max(min,Math.min(max,v)); }
    private static BossBar.Color parseColor(String s){ try{return BossBar.Color.valueOf(s.toUpperCase());}catch(Exception e){return BossBar.Color.RED;} }
    private static BossBar.Overlay parseOverlay(String s){
        return switch (s.toUpperCase()){
            case "NOTCHED_6"->BossBar.Overlay.NOTCHED_6;
            case "NOTCHED_10"->BossBar.Overlay.NOTCHED_10;
            case "NOTCHED_12"->BossBar.Overlay.NOTCHED_12;
            case "NOTCHED_20"->BossBar.Overlay.NOTCHED_20;
            default->BossBar.Overlay.PROGRESS;
        };
    }
    private static Sound parseSound(String name, Sound def){ try{return Sound.valueOf(name.toUpperCase());}catch(Exception e){return def;} }

    // getters
    public boolean debug(){return debug;}
    public DisplayMode displayMode(){return displayMode;}
    public int autoHideTicks(){return autoHideTicks;}
    public BossBar.Color bossbarColor(){return bossbarColor;}
    public BossBar.Overlay bossbarOverlay(){return bossbarOverlay;}
    public String scoreboardTitle(){return scoreboardTitle;}
    public boolean scoreboardUseDivider(){return scoreboardUseDivider;}
    public int durationSeconds(){return durationSeconds;}
    public int radius(){return radius;}
    public double buffPercent(){return buffPercent;}
    public int minNearbyToTrigger(){return minNearbyToTrigger;}
    public RefreshPolicy refreshPolicy(){return refreshPolicy;}
    public int logicTickTicks(){return logicTickTicks;}
    public double projectileBaseDamage(){return projectileBaseDamage;}
    public int throwIntervalTicks(){return throwIntervalTicks;}
    public int throwRange(){return throwRange;}
    public double projectileVelocityMultiplier(){return projectileVelocityMultiplier;}
    public int spawnMinDistance(){return spawnMinDistance;}
    public int spawnMaxDistance(){return spawnMaxDistance;}
    public boolean chaseEnabled(){return chaseEnabled;}
    public double chaseSpeed(){return chaseSpeed;}
    public double chaseKeepDistance(){return chaseKeepDistance;}
    public boolean witnessEnabled(){return witnessEnabled;}
    public int witnessDuration(){return witnessDuration;}
    public String witnessTitle(){return witnessTitle;}
    public String witnessNearbyTitle(){return witnessNearbyTitle;}
    public String witnessTitleMurder(){return witnessTitleMurder;}
    public String witnessNearbyTitleMurder(){return witnessNearbyTitleMurder;}
    public String witnessBossbarText(){return witnessBossbarText;}
    public double witnessFleeSpeed(){return witnessFleeSpeed;}
    public Sound witnessSound(){return witnessSound;}
    public float witnessSoundVolume(){return witnessSoundVolume;}
    public boolean witnessHighlight(){return witnessHighlight;}
    public boolean witnessRequireKillerIsPlayer(){return witnessRequireKillerIsPlayer;}
    public int witnessSoundRepeatTicks(){return witnessSoundRepeatTicks;}
    public Sound wantedStartSound(){return wantedStartSound;}
    public float wantedStartVolume(){return wantedStartVolume;}
    public Sound wantedEndSound(){return wantedEndSound;}
    public float wantedEndVolume(){return wantedEndVolume;}
    public String wantedBossbarText(){return wantedBossbarText;}
    public String wantedStartTitle(){return wantedStartTitle;}
    public String wantedStartSubtitleTrigger(){return wantedStartSubtitleTrigger;}
    public String wantedStartSubtitleNearby(){return wantedStartSubtitleNearby;}
    public String wantedStartSubtitleMurder(){return wantedStartSubtitleMurder;}
    public String wantedEndTitle(){return wantedEndTitle;}
    public int titleFadeInMs(){return titleFadeInMs;}
    public int titleStayMs(){return titleStayMs;}
    public int titleFadeOutMs(){return titleFadeOutMs;}
    public int teleportCleanupDistance(){return teleportCleanupDistance;}
    public boolean preventVillagerFriendlyFire(){return preventVillagerFriendlyFire;}
    public boolean blockTradesWhenHostile(){return blockTradesWhenHostile;}
    public RearmMode rearmMode(){return rearmMode;}
    public boolean rearmTrackWorld(){return rearmTrackWorld;}
    public int cooldownLarcenySeconds(){return cooldownLarcenySeconds;}
    public int cooldownMurderSeconds(){return cooldownMurderSeconds;}
    public boolean chestTriggerOnce(){return chestTriggerOnce;}
    public Set<Material> triggerContainers(){return triggerContainers;}
    public Sound witnessKilledSound(){return witnessKilledSound;}
    public float witnessKilledVolume(){return witnessKilledVolume;}
}
