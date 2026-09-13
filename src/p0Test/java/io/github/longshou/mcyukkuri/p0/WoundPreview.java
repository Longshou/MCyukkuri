package io.github.longshou.mcyukkuri.p0;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.body.*;
import io.github.longshou.mcyukkuri.entity.*;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;

final class WoundPreview {
    static void command(MinecraftServer server,JsonObject request){var level=server.overworld();var player=server.getPlayerList().getPlayers().getFirst();var area=new AABB(896,60,0,944,80,20);
        switch(request.get("command").getAsString()){
            case "wound-setup"->{
                for(int x=56;x<=59;x++)for(int z=0;z<=1;z++)level.setChunkForced(x,z,true);
                level.getEntitiesOfClass(Entity.class,area,e->e.getTags().contains("wound_preview")).forEach(Entity::discard);
                for(int x=896;x<944;x++)for(int z=0;z<20;z++){level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.SMOOTH_QUARTZ.defaultBlockState());for(int y=65;y<78;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());}
                level.setDayTime(6000);level.setWeatherParameters(6000,0,false,false);int i=0;
                for(var variant:YukkuriVariant.values()){
                    if(request.has("housesOnly")&&request.get("housesOnly").getAsBoolean()&&!variant.isHouse())continue;
                    var e=ModEntities.forVariant(variant).create(level);e.moveTo(901+i*5,65,6,180,0);e.yBodyRot=e.yBodyRotO=180;e.setNoAi(true);e.setNoGravity(true);e.setPersistenceRequired();e.addTag("wound_preview");level.addFreshEntity(e);float s=variant.isHouse()?.32f:1;
                    e.body().addWound(WoundGameTests.at(e,"tear",new Vector3f(3.6f*s,2.2f*s,-30),new Vector3f(0,0,1),3));
                    e.body().addWound(WoundGameTests.at(e,"scratch",new Vector3f(-3.6f*s,2.2f*s,-30),new Vector3f(0,0,1),2));
                    e.body().addWound(WoundGameTests.at(e,"dent",new Vector3f(30,2.5f*s,0),new Vector3f(-1,0,0),3));
                    e.body().addWound(WoundGameTests.at(e,"scorch",new Vector3f(0,3*s,30),new Vector3f(0,0,-1),2));
                    e.body().addWound(WoundGameTests.at(e,"tear",new Vector3f(0,30,0),new Vector3f(0,-1,0),2));
                    e.body().addWound(WoundGameTests.at(e,"tear",new Vector3f(0,-30,0),new Vector3f(0,1,0),2));i++;
                }player.setGameMode(GameType.SPECTATOR);player.teleportTo(level,901,65.1,2.8,0,12);
            }
            case "wound-view"->player.teleportTo(level,request.get("x").getAsDouble(),request.get("y").getAsDouble(),request.get("z").getAsDouble(),request.get("yaw").getAsFloat(),request.get("pitch").getAsFloat());
            case "wound-state"->{P0Control.reply(request,true,level.getEntitiesOfClass(YukkuriEntity.class,area,e->e.getTags().contains("wound_preview")).stream().map(e->java.util.Map.of("id",e.getId(),"variant",e.getVariant().assetId(),"wounds",e.body().wounds(),"filling",e.body().filling())).toList());return;}
            case "wound-animate","wound-cover","wound-contact","wound-release","wound-heal"->{
                for(var e:level.getEntitiesOfClass(YukkuriEntity.class,area,e->e.getTags().contains("wound_preview"))){if(request.has("variant")&&!request.get("variant").getAsString().equals(e.getVariant().assetId()))continue;
                    switch(request.get("command").getAsString()){
                        case "wound-animate"->e.playAction(request.get("action").getAsString());
                        case "wound-cover"->{boolean cover=request.get("cover").getAsBoolean();var body=e.body().save();body.putInt("HairLoss",cover?0:7);e.body().load(body);if(!cover)e.life().takeAccessory(player.getUUID());}
                        case "wound-contact"->e.syncOperation(request.get("pose").getAsString(),request.has("turn")?request.get("turn").getAsFloat():0);
                        case "wound-release"->{for(var w:e.body().wounds())if(w.kind().equals("tear"))e.body().releaseFilling("wound",w);}
                        case "wound-heal"->e.body().restorePhysical();
                    }
                }
            }
            default->throw new IllegalArgumentException("Unknown wound preview");
        }P0Control.reply(request,true,"Wound preview updated");
    }
}
