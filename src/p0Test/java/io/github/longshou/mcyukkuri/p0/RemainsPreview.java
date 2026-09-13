package io.github.longshou.mcyukkuri.p0;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.entity.*;
import io.github.longshou.mcyukkuri.registry.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

final class RemainsPreview {
    static void command(MinecraftServer server,JsonObject request){
        var level=server.overworld();var player=server.getPlayerList().getPlayers().getFirst();
        String command=request.get("command").getAsString();
        if(command.equals("remains-view")){
            player.teleportTo(level,request.get("x").getAsDouble(),request.get("y").getAsDouble(),request.get("z").getAsDouble(),request.get("yaw").getAsFloat(),request.get("pitch").getAsFloat());
        }else if(command.equals("remains-setup")){
            for(int x=60;x<=61;x++)level.setChunkForced(x,0,true);
            var area=new AABB(960,60,0,992,80,16);level.getEntitiesOfClass(Entity.class,area,e->e.getTags().contains("remains_preview")||e instanceof RemainsEntity).forEach(Entity::discard);
            for(int x=960;x<992;x++)for(int z=0;z<16;z++){
                level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.DIRT.defaultBlockState());
                for(int y=65;y<78;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
            }
            level.setDayTime(6000);level.setWeatherParameters(6000,0,false,false);
            var attacker=net.neoforged.neoforge.common.util.FakePlayerFactory.get(level,new com.mojang.authlib.GameProfile(java.util.UUID.fromString("9abcdee1-5473-44f7-8ddd-08abc0000022"),"RemainsView"));
            int index=0;
            for(var variant:java.util.List.of(YukkuriVariant.HOUSE_REIMU,YukkuriVariant.HOUSE_MARISA,YukkuriVariant.REIMU,YukkuriVariant.MARISA)){
                for(boolean fatal:new boolean[]{false,true}){
                    var e=ModEntities.forVariant(variant).create(level);e.moveTo(963+index*6,65,fatal?9:6,180,0);e.yHeadRot=e.yHeadRotO=e.yBodyRot=e.yBodyRotO=180;e.setNoAi(true);e.setPersistenceRequired();e.addTag("remains_preview");level.addFreshEntity(e);
                    if(request.has("flags"))e.subspecies().initializeBirth(request.get("flags").getAsInt());
                    // Aim at the face at body height, then pass through vanilla damage and death.
                    attacker.moveTo(e.getX(),65+e.getBbHeight()*.45-attacker.getEyeHeight(),e.getZ()-2,0,0);
                    attacker.setItemInHand(InteractionHand.MAIN_HAND,request.has("fist")&&request.get("fist").getAsBoolean()?ItemStack.EMPTY:new ItemStack(Items.IRON_SWORD));
                    e.hurt(e.damageSources().playerAttack(attacker),2);
                    if(fatal){e.invulnerableTime=0;e.hurt(e.damageSources().playerAttack(attacker),100);}
                }index++;
            }
            player.setGameMode(GameType.SPECTATOR);player.teleportTo(level,963,64.0,7.8,0,18);
        }else if(command.equals("remains-state")){
            P0Control.reply(request,true,level.getEntitiesOfClass(RemainsEntity.class,new AABB(960,60,0,992,80,16)).stream().map(e->java.util.Map.of("id",e.getId(),"data",e.data().toString(),"position",e.position().toString())).toList());return;
        }else if(command.equals("remains-item")||command.equals("remains-prepare-collect")){
            var corpse=level.getEntitiesOfClass(RemainsEntity.class,new AABB(960,60,0,992,80,16)).stream().filter(e->!request.has("model")||e.data().getString("Model").equals(request.get("model").getAsString())).findFirst().orElseThrow();
            player.setGameMode(GameType.CREATIVE);player.setShiftKeyDown(false);
            if(command.equals("remains-prepare-collect")){
                player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
                player.teleportTo(level,corpse.getX(),65,corpse.getZ()-2,0,37);
                P0Control.reply(request,true,java.util.Map.of("target",corpse.getId(),"data",corpse.data().toString()));return;
            }
            player.setItemInHand(InteractionHand.MAIN_HAND,corpse.remains());
        }else throw new IllegalArgumentException("Unknown remains preview");
        P0Control.reply(request,true,"Remains preview updated");
    }
}
