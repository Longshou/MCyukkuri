package io.github.longshou.mcyukkuri.p0;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import java.util.Map;

final class SubspeciesPreview {
    static void command(MinecraftServer server,JsonObject request){
        var level=server.overworld();
        if(request.get("command").getAsString().equals("subspecies-state")){
            var y=(YukkuriEntity)(request.has("uuid")?level.getEntity(java.util.UUID.fromString(request.get("uuid").getAsString())):level.getEntity(request.get("target").getAsInt()));
            if(y==null)throw new IllegalStateException("Subject not loaded");var boat=HatBoatEntity.find(y);
            P0Control.reply(request,true,Map.of("uuid",y.getUUID(),"target",y.getId(),"accessory",y.life().hasAccessory(),"passenger",y.isPassenger(),"wetness",y.life().wetness(),"boat",boat==null?-1:boat.getId(),"boatState",boat==null?"absent":boat.state().name(),"boatUuid",boat==null?"absent":boat.getUUID().toString(),"entityCode",YukkuriEntity.class.getProtectionDomain().getCodeSource().getLocation().toString()));return;
        }
        if(request.get("command").getAsString().equals("subspecies-empty-hand")){
            for(var player:server.getPlayerList().getPlayers())player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,net.minecraft.world.item.ItemStack.EMPTY);
            P0Control.reply(request,true,"empty hand");return;
        }
        String kind=request.has("kind")?request.get("kind").getAsString():"shell";
        boolean reimu=kind.startsWith("reimu")||kind.equals("wasa");boolean boatScene=kind.equals("boat")||kind.equals("capsized")||kind.equals("bank");
        level.setChunkForced(224/16,0,true);
        level.getEntitiesOfClass(Entity.class,new AABB(224,63,0,240,85,16),e->!(e instanceof net.minecraft.world.entity.player.Player)).forEach(Entity::discard);
        for(var p:BlockPos.betweenClosed(224,64,0,239,72,15))level.setBlockAndUpdate(p,p.getY()==64?Blocks.SMOOTH_QUARTZ.defaultBlockState():Blocks.AIR.defaultBlockState());
        var y=(reimu?ModEntities.REIMU:ModEntities.MARISA).get().create(level);y.moveTo(231.5,65,7.5,180,0);y.yBodyRot=y.yHeadRot=180;
        WaddleGameTests.clearGoals(y);y.setPersistenceRequired();level.addFreshEntity(y);
        if(kind.equals("shell"))y.subspecies().set(SubspeciesLife.Kind.SHELL_MARISA,SubspeciesLife.Congenital.NORMAL);
        else if(kind.equals("wasa"))y.subspecies().set(SubspeciesLife.Kind.WASA_REIMU,SubspeciesLife.Congenital.NORMAL);
        else if(kind.endsWith("sparse"))y.subspecies().set(SubspeciesLife.Kind.NORMAL,SubspeciesLife.Congenital.SPARSE_HAIR);
        else if(kind.endsWith("face"))y.subspecies().set(SubspeciesLife.Kind.NORMAL,SubspeciesLife.Congenital.FACE_ASYMMETRY);
        else if(kind.endsWith("bean"))y.subspecies().set(SubspeciesLife.Kind.NORMAL,SubspeciesLife.Congenital.BEAN_BODY);
        if(request.has("young")&&request.get("young").getAsBoolean()){
            var tag=y.family().save();tag.putString("Stage","BABY");tag.putLong("Age",0);tag.putLong("Nourishment",0);tag.putFloat("Scale",.32f);tag.putBoolean("Initialized",true);y.family().load(tag);y.syncFamily();
        }
        if(request.has("bare")&&request.get("bare").getAsBoolean())y.life().takeAccessory(null);
        if(request.has("wound")&&request.get("wound").getAsBoolean()){
            var mesh=io.github.longshou.mcyukkuri.body.WoundSurface.get(y.subspecies().assetId());
            float low=Float.MAX_VALUE,high=-Float.MAX_VALUE;
            for(int i=0;i<mesh.skinCount;i++){low=Math.min(low,mesh.points[i*5+1]);high=Math.max(high,mesh.points[i*5+1]);}
            var hit=mesh.ray(new org.joml.Vector3f(0,(low+high)/2,-60),new org.joml.Vector3f(0,0,1),null,java.util.List.of());
            if(hit==null)throw new IllegalStateException("No front skin hit");
            y.body().addWound(new io.github.longshou.mcyukkuri.body.BodyWound(java.util.UUID.randomUUID(),"dent",hit.triangle(),hit.u(),hit.v(),2,1,0,0));
        }
        if(boatScene){
            for(var p:BlockPos.betweenClosed(230,63,5,237,63,10))level.setBlockAndUpdate(p,Blocks.SMOOTH_QUARTZ.defaultBlockState());
            for(var p:BlockPos.betweenClosed(230,64,5,237,64,10))level.setBlockAndUpdate(p,Blocks.WATER.defaultBlockState());
            y.teleportTo(229.5,65,7.5);
            if(!kind.equals("bank")){y.subspecies().set(io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.AQUATIC_MARISA,io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Congenital.NORMAL);var boat=HatBoatEntity.createFor(y,new BlockPos(230,64,7));
                if(boat==null)throw new IllegalStateException("preview boat launch failed");
                if(kind.equals("capsized"))boat.capsize();}
        }
        float angle=request.has("angle")?request.get("angle").getAsFloat():35;
        double rad=Math.toRadians(angle);double distance=kind.equals("shell")?3.4:2.7;
        double cx=boatScene?230.5:231.5;
        for(var p:server.getPlayerList().getPlayers()){
            if(kind.equals("bank")){
                p.setGameMode(GameType.SURVIVAL);p.teleportTo(level,229.5,65,5.5,0,15);
                p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK));
            }else{p.setGameMode(GameType.SPECTATOR);
                p.teleportTo(level,cx+Math.sin(rad)*distance,64.05+(kind.endsWith("bean")?.35:0),7.5-Math.cos(rad)*distance,angle,8);}
        }
        if(request.has("action"))y.playAction(request.get("action").getAsString());
        P0Control.reply(request,true,Map.of("entity",y.getId(),"asset",y.subspecies().assetId(),"boat",boatScene));
    }
}
