package io.github.longshou.mcyukkuri.p0;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.client.DialogueAudio;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Opt-in fixture with a separate controller; never consumes another task's P0 requests. */
@EventBusSubscriber(modid=P0Mod.ID,value=Dist.CLIENT)
public final class DialogueSpeciesPreview {
    private static final Path CONTROL=Path.of("dialogue-control").toAbsolutePath();
    private static final AABB AREA=new AABB(-5,60,-5,14,75,14);
    private static String last="";
    private static int tick,end,next,index;
    private static JsonObject recording;
    private static final List<Object> frames=new ArrayList<>();
    private static boolean enabled(){return Boolean.getBoolean("mcyukkuri.dialogueSpeciesPreview");}
    private static void write(String file,Object data) throws Exception {
        Files.createDirectories(CONTROL);Files.writeString(CONTROL.resolve(file+".json"),P0Control.JSON.toJson(data));
    }
    private static void reply(JsonObject request,boolean pass,Object value) {
        try{write("response",Map.of("id",request.get("id").getAsString(),"pass",pass,"result",value));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post ignored) {
        if(!enabled())return;tick++;
        var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null||recording!=null||tick%2!=0)return;
        try {
            Path path=CONTROL.resolve("request.json");if(!Files.exists(path))return;
            var request=JsonParser.parseString(Files.readString(path)).getAsJsonObject();String id=request.get("id").getAsString();if(last.equals(id))return;last=id;
            switch(request.get("op").getAsString()) {
                case "snapshot" -> reply(request,true,snapshot());
                case "stop" -> {reply(request,true,"Stopped owned dialogue preview");mc.stop();}
                case "setup" -> {
                    var server=mc.getSingleplayerServer();if(server==null)throw new IllegalStateException("Requires integrated preview");
                    boolean young=request.has("young")&&request.get("young").getAsBoolean();
                    server.execute(()->{
                        try {
                            var level=server.overworld();level.setDayTime(6000);level.setWeatherParameters(6000,0,false,false);
                            // This fixture runs only in its disposable, copied preview world.
                            level.getEntitiesOfClass(YukkuriEntity.class,AREA).forEach(YukkuriEntity::discard);
                            for(int x=-4;x<=12;x++)for(int z=-4;z<=12;z++){
                                level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.SMOOTH_STONE.defaultBlockState());
                                for(int y=65;y<71;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
                            }
                            int i=0;
                            for(var type:List.of(ModEntities.ALICE.get(),ModEntities.CHEN.get(),ModEntities.PATCHOULI.get())) {
                                var e=type.create(level);e.setNoAi(true);e.setPersistenceRequired();e.moveTo(i++*3,65,1,0,0);e.addTag("dialogue_species");
                                var family=e.family().save();family.putString("Stage",young?"BABY":"ADULT");family.putFloat("Scale",young?.35f:1);e.family().load(family);level.addFreshEntity(e);
                            }
                            for(var player:server.getPlayerList().getPlayers()){player.setGameMode(GameType.SPECTATOR);player.teleportTo(level,3,65.3,8.5,180,10);}
                            reply(request,true,young?"young scene":"adult scene");
                        }catch(Exception e){reply(request,false,e.toString());}
                    });
                }
                case "record" -> {
                    String name=request.get("name").getAsString();if(!name.matches("[a-z0-9_-]+"))throw new IllegalArgumentException("Invalid capture name");
                    mc.setScreen(null);mc.options.hideGui=false;frames.clear();index=0;recording=request;next=tick+10;
                    end=tick+(request.has("ticks")?request.get("ticks").getAsInt():90);
                    String speech=request.get("speech").getAsString();var server=mc.getSingleplayerServer();
                    server.execute(()->server.overworld().getEntitiesOfClass(YukkuriEntity.class,AREA,e->e.getTags().contains("dialogue_species")).forEach(e->e.speak(speech)));
                }
                default -> reply(request,false,"Unknown dialogue operation");
            }
        }catch(Exception e){try{write("error",Map.of("error",e.toString()));}catch(Exception ignoredError){}recording=null;}
    }
    @SubscribeEvent public static void frame(RenderFrameEvent.Post ignored) {
        if(!enabled()||recording==null||tick<next)return;
        var mc=Minecraft.getInstance();try {
            String name=recording.get("name").getAsString();Files.createDirectories(CONTROL.resolve("screenshots"));
            Path path=CONTROL.resolve("screenshots/"+name+String.format("-%03d.png",index++));
            try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(path);}
            frames.add(Map.of("file",path.toString(),"state",snapshot()));next=tick+10;
            if(tick>=end){write(name,Map.of("frames",frames));reply(recording,true,Map.of("frames",frames.size(),"manifest",name+".json"));recording=null;}
        }catch(Exception e){reply(recording,false,e.toString());recording=null;}
    }
    private static Object snapshot() throws Exception {
        var mc=Minecraft.getInstance();List<Object> entities=new ArrayList<>();
        for(var entity:mc.level.entitiesForRendering())if(entity instanceof YukkuriEntity e&&e.getTags().contains("dialogue_species"))
            entities.add(Map.of("species",e.getVariant().assetId(),"young",e.isBaby(),"key",e.getDialogueKey(),"sequence",e.speechSequence(),"start",e.speechStart(),"end",e.speechEnd()));
        // Entity tags are server-only; the isolated scene area also identifies client copies.
        if(entities.isEmpty())for(var entity:mc.level.entitiesForRendering())if(entity instanceof YukkuriEntity e&&AREA.contains(e.position()))
            entities.add(Map.of("species",e.getVariant().assetId(),"young",e.isBaby(),"key",e.getDialogueKey(),"sequence",e.speechSequence(),"start",e.speechStart(),"end",e.speechEnd()));
        var field=DialogueAudio.class.getDeclaredField("ACTIVE");field.setAccessible(true);var voices=(Map<?,?>)field.get(null);int playing=0;
        for(var voice:voices.values())if(mc.getSoundManager().isActive((net.minecraft.client.resources.sounds.SoundInstance)voice))playing++;
        return Map.of("tick",mc.level.getGameTime(),"entities",entities,"playing",playing,"instances",voices.size());
    }
}
