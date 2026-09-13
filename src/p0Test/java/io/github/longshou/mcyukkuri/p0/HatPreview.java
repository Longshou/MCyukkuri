package io.github.longshou.mcyukkuri.p0;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.HatCarry;
import io.github.longshou.mcyukkuri.interaction.CareAction;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Disposable real-client scenes for this task's isolated runtime. */
@EventBusSubscriber(modid=P0Mod.ID)
final class HatPreview {
    private static YukkuriEntity parent,child;
    private static int moveTicks;
    private static final List<Object> trace=new ArrayList<>();
    static void command(MinecraftServer server,JsonObject request){
        var level=server.overworld();var player=server.getPlayerList().getPlayers().getFirst();
        String command=request.get("command").getAsString();
        if(command.equals("hat-setup")){
            level.setChunkForced(1,0,true);level.setDayTime(6000);level.setWeatherParameters(6000,0,false,false);
            level.getEntitiesOfClass(YukkuriEntity.class,new AABB(16,60,0,32,80,16)).forEach(YukkuriEntity::discard);
            for(int x=16;x<32;x++)for(int z=0;z<16;z++){
                level.setBlock(new BlockPos(x,64,z),Blocks.SMOOTH_QUARTZ.defaultBlockState(),3);
                for(int y=65;y<70;y++)level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);
            }
            String id=request.has("variant")?request.get("variant").getAsString():"marisa_sparse";
            parent=(id.equals("house_marisa")?ModEntities.HOUSE_MARISA:ModEntities.MARISA).get().create(level);
            parent.subspecies().set(SubspeciesLife.Kind.NORMAL,switch(id){case "marisa_sparse"->SubspeciesLife.Congenital.SPARSE_HAIR;case "marisa_face"->SubspeciesLife.Congenital.FACE_ASYMMETRY;case "marisa_bean"->SubspeciesLife.Congenital.BEAN_BODY;default->SubspeciesLife.Congenital.NORMAL;});
            parent.setNoAi(true);parent.setPersistenceRequired();parent.moveTo(22.5,65,8.5,0,0);parent.addTag("p0_display");level.addFreshEntity(parent);
            child=(id.equals("marisa_bean")?ModEntities.MARISA:ModEntities.REIMU).get().create(level);
            if(id.equals("marisa_bean"))child.subspecies().set(SubspeciesLife.Kind.NORMAL,SubspeciesLife.Congenital.BEAN_BODY);
            child.setNoAi(true);child.setPersistenceRequired();child.moveTo(23.2,65,8.5,0,0);
            var f=child.family().save();f.putString("Stage","BABY");f.putBoolean("Initialized",true);f.putFloat("Scale",.12f);child.family().load(f);level.addFreshEntity(child);
            player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);player.getAbilities().flying=true;player.onUpdateAbilities();player.teleportTo(level,20.9,64.8,11.3,210,15);
            trace.clear();moveTicks=0;
        }else if(command.equals("hat-remove")){
            if(!parent.operations().perform(player,CareAction.TAKE_ACCESSORY))throw new IllegalStateException("Actual takeoff refused");
        }else if(command.equals("hat-return")){
            var stack=player.getInventory().items.stream().filter(s->s.getItem() instanceof io.github.longshou.mcyukkuri.item.AccessoryItem).filter(s->{var t=s.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.EMPTY).copyTag();return t.hasUUID("Owner")&&t.getUUID("Owner").equals(parent.getUUID());}).findFirst().orElseThrow();
            ((io.github.longshou.mcyukkuri.item.AccessoryItem)stack.getItem()).interactLivingEntity(stack,player,parent,InteractionHand.MAIN_HAND);
        }else if(command.equals("hat-carry")){
            if(!parent.operations().perform(player,CareAction.HAT_CARRY_CHILD))throw new IllegalStateException("Actual hat pickup refused");
        }else if(command.equals("hat-move")){moveTicks=100;parent.playAction("waddle");
        }else if(command.equals("hat-put-down")){
            if(!parent.operations().perform(player,CareAction.HAT_PUT_DOWN_CHILD))throw new IllegalStateException("Actual put-down refused");
        }else if(command.equals("hat-trace")){P0Control.reply(request,true,trace);return;
        }else throw new IllegalArgumentException(command);
        P0Control.reply(request,true,Map.of("parent",parent.getId(),"child",child.getId(),"accessory",parent.life().hasAccessory(),"passenger",child.isPassenger(),"pose",parent.contactPose()));
    }
    @SubscribeEvent static void tick(ServerTickEvent.Post event){
        if(!"hats".equals(System.getProperty("mcyukkuri.p0.suite"))||parent==null||!parent.isAlive())return;
        if(moveTicks>0){moveTicks--;parent.setPos(parent.position().add(.008,0,0));parent.yBodyRot=parent.yBodyRotO=parent.getYRot()+.5f;parent.setYRot(parent.yBodyRot);}
        if(trace.size()<500)trace.add(Map.of("tick",event.getServer().getTickCount(),"hat",parent.life().hasAccessory(),"pose",parent.contactPose(),"phase",parent.contactTurn(),"passenger",child.isPassenger(),"child",List.of(child.getX(),child.getY(),child.getZ()),"seat",List.of(HatCarry.seatWorld(parent,0).x,HatCarry.seatWorld(parent,0).y,HatCarry.seatWorld(parent,0).z)));
    }
}
