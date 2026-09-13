package io.github.longshou.mcyukkuri.p0;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.entity.*;
import io.github.longshou.mcyukkuri.registry.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** Live predator scenarios in an isolated developer world; no test hooks in production AI. */
public final class PredatorPreview {
    private static final int X=3200;
    private static final AABB AREA=new AABB(X-2,63,-2,X+40,80,30);
    public static void command(MinecraftServer server,JsonObject request){
        try{
            var level=server.overworld();var op=request.get("command").getAsString();
            if(op.equals("predator-setup")){
                for(int x=X>>4;x<=(X+40)>>4;x++)for(int z=-1;z<=1;z++)level.setChunkForced(x,z,true);
                level.setDayTime(6000);level.setWeatherParameters(60000,0,false,false);
                level.getEntitiesOfClass(Entity.class,AREA,e->!(e instanceof net.minecraft.world.entity.player.Player)).forEach(Entity::discard);
                for(int x=X-1;x<X+39;x++)for(int z=-1;z<29;z++){
                    level.setBlock(new BlockPos(x,64,z),Blocks.SMOOTH_STONE.defaultBlockState(),2);
                    for(int y=65;y<80;y++)level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);
                }
                var variant=request.has("variant")?YukkuriVariant.valueOf(request.get("variant").getAsString().toUpperCase(Locale.ROOT)):YukkuriVariant.REMILIA;
                var hunter=ModEntities.forVariant(variant).create(level);hunter.moveTo(X+7,65,10,0,0);hunter.setPersistenceRequired();hunter.addTag("predator_preview");
                var life=hunter.life().save();life.putFloat("Nutrition",25);life.putFloat("Energy",95);hunter.life().load(life);level.addFreshEntity(hunter);
                var prey=ModEntities.REIMU.get().create(level);prey.moveTo(X+13,65,10,0,0);prey.setPersistenceRequired();level.addFreshEntity(prey);
                for(int z=5;z<=15;z++)level.setBlock(new BlockPos(X+10,65,z),Blocks.STONE.defaultBlockState(),2);
                level.setBlock(new BlockPos(X+5,65,8),ModBlocks.PREDATOR_NEST.get().defaultBlockState(),3);
                for(var p:server.getPlayerList().getPlayers()){
                    p.closeContainer();p.setGameMode(GameType.SPECTATOR);p.teleportTo(level,X+10,66,18,180,14);
                }
            }else if(op.equals("predator-view")){
                var animal=level.getEntitiesOfClass(YukkuriEntity.class,AREA,y->y.getTags().contains("predator_preview")).getFirst();
                for(var p:server.getPlayerList().getPlayers()){
                    p.closeContainer();p.setGameMode(GameType.SPECTATOR);p.teleportTo(level,animal.getX()+2.5,animal.getY()+.1,animal.getZ()+3.3,143,10);
                }
            }else if(op.equals("predator-cleanup")){
                level.getEntitiesOfClass(Entity.class,AREA,e->!(e instanceof net.minecraft.world.entity.player.Player)).forEach(Entity::discard);
                for(int x=X>>4;x<=(X+40)>>4;x++)for(int z=-1;z<=1;z++)level.setChunkForced(x,z,false);
            }else if(!op.equals("predator-snapshot"))throw new IllegalArgumentException(op);
            P0Control.reply(request,true,level.getEntitiesOfClass(YukkuriEntity.class,AREA).stream().map(y->Map.of("variant",y.getVariant().assetId(),"position",y.position().toString(),"flying",y.predatorFlying(),"action",y.getAnimationId(),"behavior",y.getBehaviorId(),"health",y.getHealth(),"food",y.life().nutrition())).toList());
        }catch(Throwable e){P0Control.reply(request,false,e.toString());}
    }
}
