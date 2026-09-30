package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.HatCarry;
import io.github.longshou.mcyukkuri.interaction.CareAction;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class HatInteractionGameTests {
    private record Check(String name,boolean pass){}
    private static final List<Check> checks=new ArrayList<>();
    private static void check(String name,boolean pass){checks.add(new Check(name,pass));}
    private static YukkuriEntity parent,child;
    private static net.minecraft.server.level.ServerPlayer player;
    private static YukkuriEntity make(ServerLevel level,boolean marisa,double x,double z){
        var e=(marisa?ModEntities.MARISA:ModEntities.REIMU).get().create(level);
        e.setNoAi(true);e.setPersistenceRequired();e.moveTo(x,65,z,0,0);level.addFreshEntity(e);return e;
    }
    private static void baby(YukkuriEntity e,float scale){
        var tag=e.family().save();tag.putString("Stage","BABY");tag.putBoolean("Initialized",true);tag.putFloat("Scale",scale);e.family().load(tag);
    }
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=600,batch="hats")
    public static void interactions(GameTestHelper helper){
        checks.clear();var level=helper.getLevel();var floor=new BlockPos(1096,64,8);GameTestChunks.setForced(level,68,0,true);
        helper.startSequence().thenWaitUntil(()->helper.assertTrue(level.isPositionEntityTicking(floor.above()),"Hat arena is ticking"))
            .thenExecute(()->{
                for(int x=1088;x<1104;x++)for(int z=0;z<16;z++){
                    level.setBlock(new BlockPos(x,64,z),Blocks.STONE.defaultBlockState(),3);
                    for(int y=65;y<70;y++)level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);
                }
                player=net.neoforged.neoforge.common.util.FakePlayerFactory.get(level,new com.mojang.authlib.GameProfile(UUID.fromString("c790f23f-95c1-465f-8504-34456c2fe002"),"HatCare"));
                player.moveTo(1096.5,65,10.5,180,0);
                level.addNewPlayer(player);
                parent=make(level,true,1096.5,8.5);child=make(level,false,1097.3,8.5);baby(child,.12f);
                check("small newborn footprint and visible hat fit ordinary brim",HatCarry.fits("marisa","reimu",.12));
                check("adult-sized body rejected by brim geometry",!HatCarry.fits("marisa","reimu",1));
                check("bean hat fits its own newborn at supported brim center",HatCarry.fits("marisa_bean","marisa_bean",.12));
                check("bean hat refuses ordinary newborn and house hat refuses normal house newborn",!HatCarry.fits("marisa_bean","reimu",.12)&&!HatCarry.fits("house_marisa","house_reimu",.32));
                var blocked=BlockPos.containing(HatCarry.seatWorld(parent,0));level.setBlock(blocked,Blocks.STONE.defaultBlockState(),3);
                check("wall at brim refuses pickup before mounting",!HatCarry.pickUp(parent,player)&&!child.isPassenger());level.setBlock(blocked,Blocks.AIR.defaultBlockState(),3);
                check("wearing adult can pick up real child",HatCarry.pickUp(parent,player));
                check("single real passenger; child cannot drive adult",parent.getFirstPassenger()==child&&child.getVehicle()==parent&&parent.getControllingPassenger()==null);
                check("occupied hat cannot launch as a boat",io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.createFor(parent,floor)==null);
                check("carrier custody cannot capture a vehicle",io.github.longshou.mcyukkuri.life.YukkuriWorldData.get(level).capture(parent)==null);
                var data=new CompoundTag();check("rider saves as passenger",parent.save(data)&&data.getList("Passengers",10).size()==1);
                var loaded=net.minecraft.world.entity.EntityType.loadEntityRecursive(data.copy(),level,e->e);
                check("passenger relation restores without duplicating child",loaded instanceof YukkuriEntity restored&&restored.getPassengers().size()==1&&restored.getFirstPassenger().getUUID().equals(child.getUUID()));
                parent.setPos(parent.position().add(.4,0,.2));
                parent.setYRot(90);parent.yBodyRot=90;
            }).thenIdle(5).thenExecute(()->{
                check("child follows moving parent at authored hat seat",child.position().distanceToSqr(HatCarry.seatWorld(parent,0))<.003);
                check("safe put-down succeeds",HatCarry.putDown(parent));
                check("put-down restores ground entity and clears riding",!child.isPassenger()&&!parent.isVehicle()&&Math.abs(child.getY()-65)<.001);
                baby(child,.8f);check("oversized juvenile is refused",!HatCarry.canSeat(parent,child));
                baby(child,.12f);child.setPos(parent.position().add(.65,0,0));
                check("second pickup succeeds",HatCarry.pickUp(parent,player));
                check("take hat first puts child down",parent.operations().perform(player,CareAction.TAKE_ACCESSORY)&&!child.isPassenger()&&!parent.life().hasAccessory());
                var hat=player.getInventory().items.stream().filter(s->s.getItem() instanceof io.github.longshou.mcyukkuri.item.AccessoryItem).findFirst().orElseThrow();
                check("removed item retains exact model",hat.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getString("SourceModel").equals("marisa"));
                check("takeoff animation starts",parent.contactPose().equals("HAT_REMOVE"));
                check("hatless adult cannot carry",!HatCarry.canSeat(parent,child));
            }).thenIdle(25).thenExecute(()->{
                check("takeoff completes and stays hatless",parent.contactPose().equals("NONE")&&!parent.life().hasAccessory());
                var hat=player.getInventory().items.stream().filter(s->s.getItem() instanceof io.github.longshou.mcyukkuri.item.AccessoryItem).findFirst().orElseThrow();
                ((io.github.longshou.mcyukkuri.item.AccessoryItem)hat.getItem()).interactLivingEntity(hat,player,parent,InteractionHand.MAIN_HAND);
                check("return consumes unique item and animates independently",hat.isEmpty()&&parent.life().hasAccessory()&&parent.contactPose().equals("HAT_RETURN"));
                for(var shape:List.of(SubspeciesLife.Congenital.SPARSE_HAIR,SubspeciesLife.Congenital.FACE_ASYMMETRY,SubspeciesLife.Congenital.BEAN_BODY)){
                    parent.operations().end();parent.operations().load(new CompoundTag());parent.subspecies().set(SubspeciesLife.Kind.NORMAL,shape);
                    check(shape+" has separate hat motion root",HatCarry.hasHat(parent));
                    check(shape+" sparse/face/bean can remove its own hat",parent.operations().perform(player,CareAction.TAKE_ACCESSORY));
                    parent.operations().end();var state=parent.life().save();state.putBoolean("Accessory",true);state.remove("AccessoryToken");parent.life().load(state);
                    parent.operations().load(new CompoundTag());
                }
                parent.subspecies().set(SubspeciesLife.Kind.NORMAL,SubspeciesLife.Congenital.NORMAL);
                child.setPos(parent.position().add(.65,0,0));check("pickup before shears shortcut",HatCarry.pickUp(parent,player));
                player.setItemInHand(InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHEARS));player.setShiftKeyDown(true);
                parent.interact(player,InteractionHand.MAIN_HAND);player.setShiftKeyDown(false);
                check("shears shortcut puts child down and starts takeoff",!child.isPassenger()&&!parent.life().hasAccessory()&&parent.contactPose().equals("HAT_REMOVE"));
                var house=ModEntities.HOUSE_MARISA.get().create(level);house.setNoAi(true);house.moveTo(player.getX()+.4,65,player.getZ());level.addFreshEntity(house);
                check("house Marisa removes its separate hat",house.operations().perform(player,CareAction.TAKE_ACCESSORY)&&house.contactPose().equals("HAT_REMOVE"));house.discard();
                var floating=make(level,true,1099,8);floating.setNoGravity(true);floating.setPos(1099,73,8);player.setPos(1099,73,9);
                child.setNoGravity(true);child.setPos(1099.6,73,8);check("airborne fixture mounts before invalidation",HatCarry.pickUp(floating,player));
                floating.life().takeAccessory(player.getUUID());HatCarry.tick(new net.neoforged.neoforge.event.tick.EntityTickEvent.Post(floating));
                check("missing hat with no safe ground releases custody",!child.isPassenger()&&!floating.isVehicle());floating.discard();
                write();helper.assertTrue(checks.stream().allMatch(Check::pass),"All hat checks pass; see hat-checks.json");
                parent.discard();child.discard();GameTestChunks.setForced(level,68,0,false);
            }).thenSucceed();
    }
    private static void write(){
        try{var path=java.nio.file.Path.of("p0-control/hat-checks.json");java.nio.file.Files.createDirectories(path.getParent());java.nio.file.Files.writeString(path,new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(checks));}
        catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
    }
}
