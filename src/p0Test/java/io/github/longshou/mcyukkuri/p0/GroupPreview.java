package io.github.longshou.mcyukkuri.p0;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.Facilities;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

final class GroupPreview {
    private static final AABB AREA=new AABB(1400,63,0,1440,78,24);
    private static YukkuriEntity spawn(ServerLevel level,double x,double z){
        var e=ModEntities.REIMU.get().create(level);e.moveTo(x,65,z,180,0);e.yBodyRot=e.yBodyRotO=180;e.setPersistenceRequired();e.setOnGround(true);
        var saved=e.life().save();saved.putFloat("Nutrition",95);saved.putFloat("Energy",95);e.life().load(saved);e.addTag("group_preview");level.addFreshEntity(e);return e;
    }
    static void command(MinecraftServer server,JsonObject request){
        server.execute(()->{
            try{
                var level=server.overworld();var player=server.getPlayerList().getPlayers().getFirst();String command=request.get("command").getAsString();
                switch(command){
                    case "group-setup"->{
                        for(int x=87;x<=90;x++)for(int z=0;z<2;z++)level.setChunkForced(x,z,true);
                        level.getEntitiesOfClass(Entity.class,AREA,e->e.getTags().contains("group_preview")).forEach(Entity::discard);
                        for(int x=1400;x<1440;x++)for(int z=0;z<24;z++){
                            level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.SMOOTH_QUARTZ.defaultBlockState());
                            for(int y=65;y<77;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
                            if(x%3==0&&z%3==0)level.setBlockAndUpdate(new BlockPos(x,67,z),Blocks.LIGHT.defaultBlockState());
                        }
                        level.setDayTime(6000);level.setWeatherParameters(6000,0,false,false);
                        level.getEntitiesOfClass(ItemEntity.class,AREA).forEach(Entity::discard);
                        var blocks=java.util.List.of(Facilities.NEWSPAPER,Facilities.SOIL,Facilities.SANDPAPER,Facilities.CERAMIC_TOILET,Facilities.COVERED_TOILET);
                        for(int i=0;i<blocks.size();i++){
                            var pos=new BlockPos(1403+i*4,65,5);level.setBlockAndUpdate(pos,blocks.get(i).get().defaultBlockState());
                            if(i>=3){var care=(io.github.longshou.mcyukkuri.life.CareBlockEntity)level.getBlockEntity(pos);for(int j=0;j<5;j++)care.relieve();}
                        }
                        int i=0;for(var item:Facilities.ITEMS.entrySet()){
                            var display=EntityType.ITEM_DISPLAY.create(level);var tag=new CompoundTag();tag.put("item",new ItemStack(item.getValue().get()).save(level.registryAccess()));tag.putString("item_display","fixed");
                            display.load(tag);display.moveTo(1403+(i%6)*3,65.65,12+(i/6)*4,180,0);display.addTag("group_preview");level.addFreshEntity(display);i++;
                        }
                        player.setGameMode(GameType.SPECTATOR);player.teleportTo(level,1411,69,0,0,22);
                    }
                    case "group-bout"->{
                        level.getEntitiesOfClass(YukkuriEntity.class,AREA).forEach(Entity::discard);
                        var a=spawn(level,1406.2,19);var b=spawn(level,1407.2,19);var relations=YukkuriWorldData.get(level);
                        relations.relate(a.getUUID(),b.getUUID(),20);relations.relate(b.getUUID(),a.getUUID(),20);
                        if(!a.groupInteraction().challenge(b))throw new IllegalStateException("preview bout refused");
                        player.teleportTo(level,1408.8,65,15.5,30,23);
                    }
                    case "group-toy"->{
                        level.getEntitiesOfClass(YukkuriEntity.class,AREA).forEach(Entity::discard);
                        var e=spawn(level,1406,19);var toy=new ItemEntity(level,1406,65,18.5,new ItemStack(Facilities.ITEMS.get("rubber_ball").get()));
                        toy.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);toy.setExtendedLifetime();toy.addTag("group_preview");level.addFreshEntity(toy);
                        if(!e.social().beginPlay(toy))throw new IllegalStateException("preview toy refused");
                        player.teleportTo(level,1408.4,65,15,25,23);
                    }
                    default->throw new IllegalArgumentException(command);
                }
                P0Control.reply(request,true,Map.of("command",command));
            }catch(Throwable error){P0Control.reply(request,false,error.toString());}
        });
    }
}
