package io.github.longshou.mcyukkuri.p0;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.social.DailyLife;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;

/** Actual daily goals, synchronized expression and real framebuffer; no simulated screenshots. */
final class EcologyPreview {
    static void command(MinecraftServer server,JsonObject request) {
        server.execute(()->{
            try{
                var level=server.overworld();var player=server.getPlayerList().getPlayers().getFirst();
                var area=new AABB(2208,63,0,2240,75,14);
                String op=request.get("command").getAsString();
                if(op.equals("ecology-setup")){
                    EcologyGameTests.floor(level,2208);
                    level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_MOBGRIEFING).set(true,server);
                    player.setGameMode(GameType.SPECTATOR);player.teleportTo(level,2213.7,65,1.7,0,14);
                }else if(op.equals("ecology-scene")){
                    level.getEntitiesOfClass(Entity.class,area,e->e instanceof YukkuriEntity||e instanceof ItemEntity).forEach(Entity::discard);
                    level.removeBlock(new BlockPos(2215,65,5),false);
                    String kind=request.get("kind").getAsString();
                    var variant=request.has("variant")?io.github.longshou.mcyukkuri.entity.YukkuriVariant.valueOf(request.get("variant").getAsString().toUpperCase(java.util.Locale.ROOT)):io.github.longshou.mcyukkuri.entity.YukkuriVariant.REIMU;
                    var a=EcologyGameTests.spawn(level,2213.5,5.5,variant);
                    if(kind.equals("nuzzle")||kind.equals("listen")){
                        var b=EcologyGameTests.spawn(level,2213.5+a.getBbWidth()-.02,5.5,variant);EcologyGameTests.friends(level,a,b);
                        if(request.has("young")&&request.get("young").getAsBoolean()){
                            var parent=EcologyGameTests.spawn(level,2230,12,variant);parent.setNoAi(true);
                            a.family().generatedChild(parent,null);b.family().generatedChild(parent,null);
                            b.moveTo(a.getX()+a.getBbWidth()-.01,65,a.getZ(),180,0);b.setYHeadRot(180);b.yBodyRot=b.yBodyRotO=180;
                        }
                        if(!(kind.equals("nuzzle")?a.dailyLife().nuzzle(b):a.dailyLife().singTo(b)))throw new IllegalStateException("pair refused");
                    }else if(kind.equals("sing")){
                        if(!a.dailyLife().begin(DailyLife.Kind.SING))throw new IllegalStateException("song refused");
                    }else if(kind.equals("exercise")||kind.equals("adult_exercise")){
                        if(kind.equals("exercise")){var parent=EcologyGameTests.spawn(level,2215.5,5.5);a.family().generatedChild(parent,null);}
                        if(!a.dailyLife().begin(DailyLife.Kind.EXERCISE))throw new IllegalStateException("exercise refused");
                    }else if(kind.equals("wild_toilet")||kind.equals("pet_accident")||kind.equals("toilet")){
                        var life=a.life().save();life.putFloat("Bladder",95);a.life().load(life);
                        if(kind.equals("pet_accident"))io.github.longshou.mcyukkuri.human.HumanCommerce.assign(a,player.getUUID(),"companion");
                        if(kind.equals("toilet"))level.setBlockAndUpdate(new BlockPos(2215,65,5),io.github.longshou.mcyukkuri.life.Facilities.CERAMIC_TOILET.get().defaultBlockState());
                    }else if(kind.equals("lonely")){
                        var needs=new CompoundTag();needs.putInt("Affection",95);a.dailyLife().load(needs);
                    }else if(kind.equals("meal")){
                        var life=a.life().save();life.putFloat("Nutrition",50);a.life().load(life);
                        var food=new ItemEntity(level,a.getX(),65.05,a.getZ()-.25,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.APPLE));
                        food.setNoPickUpDelay();food.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);level.addFreshEntity(food);
                    }else if(kind.equals("sleep")){
                        var life=a.life().save();life.putFloat("Energy",5);life.putInt("RestWait",0);a.life().load(life);
                    }else throw new IllegalArgumentException(kind);
                    a.addTag("ecology_subject");
                    player.teleportTo(level,2213.7,64.4,2.3,0,8);
                }else if(op.equals("ecology-interrupt")){
                    level.getEntitiesOfClass(YukkuriEntity.class,area).forEach(e->e.playAction("idle"));
                }else if(op.equals("ecology-state")){
                    P0Control.reply(request,true,level.getEntitiesOfClass(YukkuriEntity.class,area).stream().map(e->Map.of(
                            "id",e.getUUID().toString(),"behavior",e.getBehaviorId(),"expression",e.getExpressionId(),"animation",e.getAnimationId(),
                            "nuzzles",e.dailyLife().nuzzles(),"songs",e.dailyLife().songs(),"exercises",e.dailyLife().exercises(),
                            "bladder",e.life().bladder(),"hygiene",e.life().hygiene(),"affection",e.dailyLife().affectionNeed())).toList());return;
                }else if(op.equals("ecology-cleanup")){
                    level.getEntitiesOfClass(YukkuriEntity.class,area).forEach(Entity::discard);
                    for(int chunk=138;chunk<=139;chunk++)level.setChunkForced(chunk,0,false);
                }else throw new IllegalArgumentException(op);
                P0Control.reply(request,true,Map.of("command",op));
            }catch(Throwable e){P0Control.reply(request,false,e.toString());}
        });
    }
}
