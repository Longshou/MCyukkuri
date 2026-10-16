package io.github.longshou.mcyukkuri.p0;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.entity.*;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import java.util.Map;

/** Static current-renderer specimens; mechanical acceptance is recorded by GameTests. */
final class RoundedPreview {
    private static final int X=3200;
    static void command(MinecraftServer server,JsonObject request){
        var level=server.overworld();
        for(int c=X/16;c<=(X+16)/16;c++)level.setChunkForced(c,0,true);
        for(var old:level.getEntitiesOfClass(YukkuriEntity.class,new AABB(X-2,63,-2,X+18,80,18))){FamilyData.get(level).cancel(old.family().brood());old.discard();}
        for(var p:BlockPos.betweenClosed(X,64,0,X+15,73,15))level.setBlockAndUpdate(p,p.getY()==64?Blocks.SMOOTH_QUARTZ.defaultBlockState():Blocks.AIR.defaultBlockState());
        level.setDayTime(6000);level.setWeatherParameters(24000,0,false,false);
        String id=request.has("model")?request.get("model").getAsString():"reimu_deibu";
        var variant=id.startsWith("reimu_")?YukkuriVariant.REIMU:id.startsWith("marisa_")?YukkuriVariant.MARISA:java.util.Arrays.stream(YukkuriVariant.values()).filter(v->v.assetId().equals(id)).findFirst().orElseThrow();
        var e=ModEntities.forVariant(variant).create(level);e.moveTo(X+7.5,65,7.5,180,0);e.yBodyRot=e.yBodyRotO=e.yHeadRot=e.yHeadRotO=180;
        e.setPersistenceRequired();e.setOnGround(true);WaddleGameTests.clearGoals(e);level.addFreshEntity(e);
        if(id.startsWith("reimu_wasa"))e.subspecies().set(SubspeciesLife.Kind.WASA_REIMU,SubspeciesLife.Congenital.NORMAL);
        if(id.equals("marisa_tsumuri"))e.subspecies().set(SubspeciesLife.Kind.SHELL_MARISA,SubspeciesLife.Congenital.NORMAL);
        if(id.endsWith("_bean"))e.subspecies().set(SubspeciesLife.Kind.NORMAL,SubspeciesLife.Congenital.BEAN_BODY);
        if(id.endsWith("_sparse"))e.subspecies().set(SubspeciesLife.Kind.NORMAL,SubspeciesLife.Congenital.SPARSE_HAIR);
        if(id.endsWith("_face"))e.subspecies().set(SubspeciesLife.Kind.NORMAL,SubspeciesLife.Congenital.FACE_ASYMMETRY);
        var form=e.subspecies().save();form.putBoolean("Deibu",id.endsWith("deibu"));e.subspecies().load(form);
        var family=e.family().save();family.putBoolean("Initialized",true);family.putFloat("Scale",1);family.putFloat("AdultScale",1);family.putLong("Nourishment",72000);family.putLong("Age",48001);family.putLong("Maturity",48000);family.putLong("Lifespan",2208000);e.family().load(family);
        var life=e.life().save();life.putFloat("Nutrition",100);life.putFloat("Energy",100);life.putFloat("Hygiene",100);e.life().load(life);
        String state=request.has("state")?request.get("state").getAsString():"idle";
        if(state.equals("pregnant")||state.equals("stem")){
            for(var roof:BlockPos.betweenClosed(X+5,70,5,X+10,70,10))level.setBlockAndUpdate(roof,Blocks.OAK_PLANKS.defaultBlockState());
            if(!e.life().assignNaturalHome(e.blockPosition()))throw new IllegalStateException("Preview home unavailable");
            var brood=FamilyData.get(level).assist(e);if(brood==null)throw new IllegalStateException("Preview pregnancy unavailable");
            if(state.equals("stem"))brood.kind=FamilyData.BirthKind.STEM;
            brood.elapsed=(int)(brood.duration*.88);
        }
        if(state.equals("wound")){
            var mesh=io.github.longshou.mcyukkuri.body.WoundSurface.get(id);var hit=mesh.ray(new org.joml.Vector3f(2,4,-40),new org.joml.Vector3f(0,0,1),null,java.util.List.of());
            if(hit==null)throw new IllegalStateException("Preview skin unavailable");
            e.body().addWound(new io.github.longshou.mcyukkuri.body.BodyWound(java.util.UUID.randomUUID(),"dent",hit.triangle(),hit.u(),hit.v(),2,1,0,0));
        }
        if(state.equals("badge"))e.pet().initializeStock(io.github.longshou.mcyukkuri.pet.BadgeGrade.SILVER,java.util.UUID.randomUUID(),60);
        if(state.equals("bare"))e.life().takeAccessory(null);
        e.setNoAi(true);e.syncFamily();
        e.playAction(request.has("action")?request.get("action").getAsString():"idle");
        if(java.util.List.of("FIXED","BOX","WRAPPED","SUPINE").contains(state))e.syncOperation(state,0);
        if(request.has("expression"))e.showReaction(request.get("expression").getAsString(),"",12000);
        double angle=request.has("angle")?request.get("angle").getAsDouble():35,rad=Math.toRadians(angle),distance=2.4*e.growthScale();
        for(var player:server.getPlayerList().getPlayers()){
            player.setGameMode(GameType.SPECTATOR);player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.NIGHT_VISION,24000,0,false,false));
            player.teleportTo(level,e.getX()+Math.sin(rad)*distance,65+.8*e.growthScale()-player.getEyeHeight(),e.getZ()-Math.cos(rad)*distance,(float)angle,8);
        }
        P0Control.reply(request,true,Map.of("id",e.getId(),"asset",e.subspecies().assetId(),"scale",e.growthScale(),"state",state,"width",e.getBbWidth(),"height",e.getBbHeight()));
    }
}
