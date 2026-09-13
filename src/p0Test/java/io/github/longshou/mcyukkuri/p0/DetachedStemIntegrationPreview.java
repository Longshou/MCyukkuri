package io.github.longshou.mcyukkuri.p0;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.DetachedStemBlockEntity;
import io.github.longshou.mcyukkuri.family.DetachedStemGeometry;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** Isolated X=7000 geometry scene; conception and cut use production ownership and collision checks. */
final class DetachedStemIntegrationPreview {
    private static BlockPos holderPosition;
    static void command(MinecraftServer server,JsonObject request){
        var level=server.overworld();String command=request.get("command").getAsString();
        if(command.equals("integration-stem-setup")){
            for(int x=437;x<=438;x++)for(int z=0;z<=1;z++)level.setChunkForced(x,z,true);
            for(var e:level.getEntitiesOfClass(YukkuriEntity.class,new AABB(6996,63,0,7024,75,24),e->e.getTags().contains("integration_stem"))){
                FamilyData.get(level).cancel(e.family().brood());e.discard();
            }
            for(int x=6996;x<7024;x++)for(int z=0;z<24;z++){
                level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.SMOOTH_QUARTZ.defaultBlockState());
                for(int y=65;y<75;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
            }
            level.setDayTime(6000);level.setWeatherParameters(6000,0,false,false);
            var mother=parent(level,7006.5);var father=parent(level,7008.5);
            var relations=YukkuriWorldData.get(level);relations.relate(mother.getUUID(),father.getUUID(),30);relations.relate(father.getUUID(),mother.getUUID(),30);
            int serial=0;long seed=mother.getUUID().getMostSignificantBits()^father.getUUID().getLeastSignificantBits();
            while(FamilyData.offspringCount(mother.family().adultScale(),seed^serial)!=2)serial++;
            var family=mother.family().save();family.putInt("Conceptions",serial);mother.family().load(family);
            var brood=FamilyData.get(level).conceive(mother,father,FamilyData.BirthKind.STEM,GlobalPos.of(level.dimension(),mother.blockPosition().south()));
            if(brood==null||brood.children.size()!=2)throw new IllegalStateException("Two-child conception failed: maternalReady="+mother.family().ready()
                    +", paternalReady="+father.family().ready()+", distanceSquared="+mother.distanceToSqr(father)
                    +", maternalHome="+mother.life().homeStand()+", paternalHome="+father.life().homeStand()
                    +", recognizes="+mother.life().canRecognize(father)+"/"+father.life().canRecognize(mother)
                    +", reachable="+mother.life().reachable(father.blockPosition(),false)+", serial="+serial);
            brood.variation=1.01972258f;
            // At duration-1 fruit already has its full 0.30 visual size, but plain cut stems cannot
            // advance immature broods. This keeps a real synchronized holder visible for both clients.
            boolean fullSize=!request.has("fullSize")||request.get("fullSize").getAsBoolean();
            brood.elapsed=fullSize?brood.duration-1:brood.duration/2;FamilyData.get(level).setDirty();
            var player=server.getPlayerList().getPlayers().getFirst();player.setGameMode(GameType.CREATIVE);
            player.teleportTo(level,7004.5,65,10.5,0,0);
            if(!DetachedStemBlockEntity.cut(mother,player,false))throw new IllegalStateException("Production plain cut refused the reproduced two-child geometry");
            holderPosition=brood.anchor.pos();
            if(!(level.getBlockEntity(holderPosition) instanceof DetachedStemBlockEntity holder)||holder.brood()!=brood)
                throw new IllegalStateException("Cut did not transfer the original brood");
            var children=brood.children.stream().map(child->child.id.toString()).toList();
            mother.discard();father.discard();view(server,false);
            P0Control.reply(request,true,Map.of("holder",holderPosition.toShortString(),"brood",brood.id.toString(),"children",children,
                    "count",holder.count(),"progress",holder.progress(),"variation",holder.variation(),"fullVisualSize",fullSize,
                    "secondMatureBottom",DetachedStemGeometry.childBase(2,1,1,brood.variation,holder.source(),brood.children.get(1).variant,false).y));
        }else if(command.equals("integration-stem-view")){
            view(server,request.has("side")&&request.get("side").getAsBoolean());P0Control.reply(request,true,Map.of("holder",holderPosition.toShortString()));
        }else throw new IllegalArgumentException(command);
    }
    private static void view(MinecraftServer server,boolean side){
        if(holderPosition==null)throw new IllegalStateException("Run integration-stem-setup first");
        double x=holderPosition.getX()+.5,y=holderPosition.getY()-.75,z=holderPosition.getZ()+.5;
        for(var player:server.getPlayerList().getPlayers()){
            player.setGameMode(GameType.SPECTATOR);player.fallDistance=0;
            player.teleportTo(server.overworld(),x+(side?2:0),y,z+(side?-.35:-2.5),side?90:0,4);
            player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.NIGHT_VISION,2400,0,false,false));
        }
    }
    private static YukkuriEntity parent(ServerLevel level,double x){
        var e=ModEntities.REIMU.get().create(level);e.setNoAi(true);e.setPersistenceRequired();e.moveTo(x,65,10.5,180,0);e.setOnGround(true);e.addTag("integration_stem");
        var state=e.life().save();state.putFloat("Nutrition",90);state.putFloat("Energy",90);e.life().load(state);level.addFreshEntity(e);
        var home=e.blockPosition().south();level.setBlockAndUpdate(home,ModBlocks.NEST.get().defaultBlockState());
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)level.setBlockAndUpdate(home.offset(dx,3,dz),Blocks.OAK_PLANKS.defaultBlockState());
        if(!e.life().assignHome(GlobalPos.of(level.dimension(),home)))throw new IllegalStateException("Preview home unavailable");
        return e;
    }
}
