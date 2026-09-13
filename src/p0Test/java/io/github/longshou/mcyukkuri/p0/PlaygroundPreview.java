package io.github.longshou.mcyukkuri.p0;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.play.*;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

final class PlaygroundPreview {
    private static final AABB AREA=new AABB(1800,63,0,1880,76,16);
    private static BlockPos root(PlayBlock.Kind kind){return new BlockPos(1806+kind.ordinal()*14,65,8);}
    static void command(MinecraftServer server,JsonObject request){
        server.execute(()->{
            try{
                var level=server.overworld();var player=server.getPlayerList().getPlayers().getFirst();String command=request.get("command").getAsString();
                if(command.equals("play-setup")){
                    for(int x=112;x<=117;x++)level.setChunkForced(x,0,true);
                    level.getEntitiesOfClass(Entity.class,AREA,e->e.getTags().contains("play_preview")).forEach(Entity::discard);
                    for(int x=1800;x<1880;x++)for(int z=0;z<16;z++){
                        for(int y=65;y<76;y++)level.removeBlock(new BlockPos(x,y,z),false);
                        level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.SMOOTH_QUARTZ.defaultBlockState());
                    }
                    level.getEntitiesOfClass(ItemEntity.class,AREA).forEach(Entity::discard);
                    for(var kind:PlayBlock.Kind.values()){
                        var facility=PlayItem.place(level,root(kind),kind,Direction.SOUTH);if(facility==null)throw new IllegalStateException("placement "+kind);
                        if(kind==PlayBlock.Kind.POOL)facility.fill();
                    }
                    for(int x=1800;x<1880;x+=3)for(int z=0;z<16;z+=3)level.setBlockAndUpdate(new BlockPos(x,69,z),Blocks.LIGHT.defaultBlockState());
                    player.setGameMode(GameType.SPECTATOR);level.setDayTime(6000);level.setWeatherParameters(6000,0,false,false);
                }else if(command.equals("play-route")){
                    level.getEntitiesOfClass(YukkuriEntity.class,AREA).forEach(Entity::discard);
                    var kind=PlayBlock.Kind.valueOf(request.get("kind").getAsString());var root=root(kind);var facility=PlayBlock.facility(level,root);
                    var start=PlayGeometry.world(root,Direction.SOUTH,kind==PlayBlock.Kind.POOL?-.95:0,0,kind==PlayBlock.Kind.SLIDE?-2.8:kind==PlayBlock.Kind.POOL?-1.2:-2);
                    var variant=request.has("variant")?io.github.longshou.mcyukkuri.entity.YukkuriVariant.valueOf(request.get("variant").getAsString()):io.github.longshou.mcyukkuri.entity.YukkuriVariant.REIMU;
                    var e=ModEntities.forVariant(variant).create(level);e.moveTo(start.x,start.y,start.z,0,0);e.setPersistenceRequired();e.setOnGround(true);e.addTag("play_preview");
                    var state=e.life().save();state.putFloat("Energy",95);state.putFloat("Nutrition",95);e.life().load(state);
                    var family=e.family().save();family.putBoolean("Initialized",true);e.family().load(family);level.addFreshEntity(e);
                    if(!e.playLife().begin(facility))throw new IllegalStateException("route refused "+kind);
                    player.teleportTo(level,root.getX()+4,65.7,root.getZ()-4,45,22);
                }else throw new IllegalArgumentException(command);
                P0Control.reply(request,true,Map.of("command",command));
            }catch(Throwable error){P0Control.reply(request,false,error.toString());}
        });
    }
}
