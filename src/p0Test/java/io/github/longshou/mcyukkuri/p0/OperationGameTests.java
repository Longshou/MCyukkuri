package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.interaction.CareAction;
import io.github.longshou.mcyukkuri.interaction.CareMenu;
import io.github.longshou.mcyukkuri.item.OperationToolItem;
import io.github.longshou.mcyukkuri.item.OperationToolItem.Tool;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class OperationGameTests {
    private record Check(String name,boolean pass){}
    private final List<Check> checks=new ArrayList<>();
    private void check(String name,boolean pass){checks.add(new Check(name,pass));}
    private static final AABB AREA=new AABB(700,63,0,732,72,12);
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=500,batch="operations")
    public static void operationAcceptance(GameTestHelper helper){
        var test=new OperationGameTests();var level=helper.getLevel();
        for(int x=43;x<=45;x++)GameTestChunks.setForced(level,x,0,true);
        for(int x=700;x<732;x++)for(int z=0;z<12;z++){
            level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.STONE.defaultBlockState());
            for(int y=65;y<72;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
        }
        helper.startSequence().thenWaitUntil(()->{for(int x=43;x<=45;x++)helper.assertTrue(level.isPositionEntityTicking(new BlockPos(x*16+8,65,5)),"Waiting for operation chunks");})
        .thenExecute(()->{
            try{test.run(level);}catch(Throwable error){test.check("Unexpected exception: "+error,false);}
            finally{
                level.getEntitiesOfClass(Entity.class,AREA,e->e instanceof YukkuriEntity||e instanceof ItemEntity).forEach(Entity::discard);
                for(int x=43;x<=45;x++)GameTestChunks.setForced(level,x,0,false);
            }
            P0Control.write("operation-checks",test.checks);
            var failed=test.checks.stream().filter(c->!c.pass).toList();if(failed.isEmpty())helper.succeed();else helper.fail(failed.toString());
        });
    }
    private YukkuriEntity spawn(ServerLevel level,double x){
        var e=ModEntities.REIMU.get().create(level);e.moveTo(x,65,5,0,0);e.setNoAi(true);e.setOnGround(true);e.setPersistenceRequired();level.addFreshEntity(e);return e;
    }
    private long count(ServerLevel level,net.minecraft.world.item.Item item){return level.getEntitiesOfClass(ItemEntity.class,AREA,e->e.isAlive()&&e.getItem().is(item)).stream().mapToLong(e->e.getItem().getCount()).sum();}
    private void run(ServerLevel level){
        var player=FakePlayerFactory.get(level,new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"OperationTest"));
        player.moveTo(707,65,5);player.getAbilities().instabuild=false;level.addNewPlayer(player);
        try{
            var e=spawn(level,709);var menu=new CareMenu(12,player.getInventory(),e.getId());
            check("near player can use care menu",menu.stillValid(player));
            check("invalid action ids rejected",!menu.clickMenuButton(player,-1)&&!menu.clickMenuButton(player,999));
            check("tool operation requires actual held tool",!e.operations().perform(player,CareAction.PIN));
            player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Tool.PIN.item(),3));
            check("pin starts and debits one",e.operations().perform(player,CareAction.PIN)&&player.getMainHandItem().getCount()==2&&e.operations().inserted().getCount()==1);
            check("active operation rejects a competing operation",!e.operations().perform(player,CareAction.GRIP));
            float hp=e.getHealth();for(int i=0;i<25;i++)e.operations().tick();
            check("stationary pin causes no ground abrasion",e.getHealth()==hp&&e.operations().active());
            long before=count(level,Tool.PIN.item());check("release returns inserted pin",e.operations().end()&&count(level,Tool.PIN.item())==before+1);
            e.operations().end();check("second release cannot duplicate pin",count(level,Tool.PIN.item())==before+1);
            e.operations().load(new CompoundTag());player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Tool.RESTRAINT.item(),2));
            check("fixed supine starts",e.operations().perform(player,CareAction.FIX_SUPINE));
            var saved=e.operations().save();before=count(level,Tool.RESTRAINT.item());
            var loaded=spawn(level,711);loaded.operations().load(saved);e.discard();loaded.operations().tick();
            check("load cancels pose and returns one durable binding",!loaded.operations().active()&&count(level,Tool.RESTRAINT.item())==before+1);
            loaded.operations().tick();check("load return does not duplicate on later ticks",count(level,Tool.RESTRAINT.item())==before+1);
            loaded.discard();e=spawn(level,709);player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Tool.SLING.item(),2));
            check("no ceiling rejects suspension without spending tool",!e.operations().perform(player,CareAction.WRAP_HANG)&&player.getMainHandItem().getCount()==2);
            var roof=new BlockPos(709,68,5);level.setBlockAndUpdate(roof,Blocks.STONE.defaultBlockState());
            check("supported sling consumes one and syncs anchor",e.operations().perform(player,CareAction.WRAP_HANG)&&player.getMainHandItem().getCount()==1&&roof.equals(e.contactAnchor()));
            before=count(level,Tool.SLING.item());level.setBlockAndUpdate(roof,Blocks.AIR.defaultBlockState());e.operations().tick();
            check("removing support releases and returns sling",!e.operations().active()&&e.contactAnchor()==null&&count(level,Tool.SLING.item())==before+1);
            e.operations().load(new CompoundTag());check("gentle hold begins",e.operations().perform(player,CareAction.LIFT));
            player.moveTo(725,65,5);e.operations().tick();check("operator leaving range releases target",!e.operations().active()&&!menu.stillValid(player));player.moveTo(707,65,5);
            e.operations().load(new CompoundTag());player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Tool.CUTTER.item()));
            before=count(level,ModItems.TRIMMED_HAIR.get());check("side cut creates real hair and records loss",e.operations().perform(player,CareAction.CUT_LEFT)&&e.body().hairLoss()==1&&count(level,ModItems.TRIMMED_HAIR.get())==before+1);
            int damage=player.getMainHandItem().getDamageValue();e.operations().load(new CompoundTag());
            check("same cut cannot farm hair or consume durability",!e.operations().perform(player,CareAction.CUT_LEFT)&&count(level,ModItems.TRIMMED_HAIR.get())==before+1&&player.getMainHandItem().getDamageValue()==damage);
            var body=e.body().save();var copy=spawn(level,712);copy.body().load(body);check("hair loss survives save load",copy.body().hairLoss()==1);
            e.operations().load(new CompoundTag());player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Tool.HEATED_AWL.item()));
            CustomData.update(DataComponents.CUSTOM_DATA,player.getMainHandItem(),tag->tag.putLong("HeatUntil",level.getGameTime()-1));
            check("cooled awl cannot burn",!e.operations().perform(player,CareAction.BURN));
            var tool=(OperationToolItem)Tool.HEATED_AWL.item();tool.inventoryTick(player.getMainHandItem(),level,player,player.getInventory().selected,true);
            check("cooled tool becomes reusable ordinary awl",player.getMainHandItem().is(Tool.AWL.item()));
            player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Tool.HEATED_AWL.item()));tool.inventoryTick(player.getMainHandItem(),level,player,player.getInventory().selected,true);
            e.invulnerableTime=0;check("heated tool causes separate burn state",e.operations().perform(player,CareAction.BURN)&&e.body().burns()>0);
            e.operations().load(new CompoundTag());e.invulnerableTime=0;player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Tool.PIN.item(),2));
            check("another pin starts before fatal injury",e.operations().perform(player,CareAction.PIN));before=count(level,Tool.PIN.item());e.invulnerableTime=0;e.hurt(e.damageSources().generic(),100);
            check("death returns binding exactly once",!e.isAlive()&&!e.operations().active()&&count(level,Tool.PIN.item())==before+1);
            var roller=spawn(level,718);player.moveTo(716,65,5);player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
            check("roll starts on hard ground",roller.operations().perform(player,CareAction.ROLL));hp=roller.getHealth();
            for(int i=0;i<38;i++)roller.operations().tick();
            check("real hard-ground travel causes abrasion",roller.getX()>719&&roller.getHealth()<hp&&roller.contactTurn()>0);
            roller.operations().end();roller.discard();
            for(int x=718;x<=721;x++)level.setBlockAndUpdate(new BlockPos(x,65,5),Blocks.WHITE_CARPET.defaultBlockState());
            roller=spawn(level,718);roller.setPos(718,65.0625,5.5);roller.setOnGround(true);
            check("roll starts on carpet",roller.operations().perform(player,CareAction.ROLL));hp=roller.getHealth();
            for(int i=0;i<38;i++)roller.operations().tick();
            check("carpet permits travel without abrasion",roller.getX()>719&&roller.getHealth()==hp);roller.operations().end();roller.discard();
            for(int x=718;x<=721;x++)level.setBlockAndUpdate(new BlockPos(x,65,5),Blocks.AIR.defaultBlockState());
            var crowded=spawn(level,718.3);level.setBlockAndUpdate(new BlockPos(719,65,5),Blocks.STONE.defaultBlockState());
            check("wall blocks laying down",!crowded.operations().perform(player,CareAction.LAY_DOWN));level.setBlockAndUpdate(new BlockPos(719,65,5),Blocks.AIR.defaultBlockState());
            var owner=spawn(level,718);var token=owner.life().takeAccessory(player.getUUID());var accessory=new ItemStack(ModItems.accessoryFor(owner.getVariant()));
            CustomData.update(DataComponents.CUSTOM_DATA,accessory,tag->{tag.putUUID("Owner",owner.getUUID());tag.putUUID("Token",token);});
            var ceiling=new BlockPos(718,67,5);level.setBlockAndUpdate(ceiling,Blocks.STONE.defaultBlockState());
            check("suspending actual accessory transfers one item",io.github.longshou.mcyukkuri.item.AccessoryItem.suspend(player,owner,accessory)&&accessory.isEmpty());
            var hung=level.getEntitiesOfClass(ItemEntity.class,AREA,item->io.github.longshou.mcyukkuri.item.AccessoryItem.matches(owner,item.getItem())).getFirst();
            check("hung accessory records support and no gravity",hung.isNoGravity()&&hung.getItem().get(DataComponents.CUSTOM_DATA).contains("SuspensionAnchor"));
            level.setBlockAndUpdate(ceiling,Blocks.AIR.defaultBlockState());((io.github.longshou.mcyukkuri.item.AccessoryItem)hung.getItem().getItem()).onEntityItemUpdate(hung.getItem(),hung);
            check("removing ceiling releases accessory",!hung.isNoGravity()&&!hung.getItem().get(DataComponents.CUSTOM_DATA).contains("SuspensionAnchor"));
            check("owner token returns only once",owner.life().returnAccessory(owner.getUUID(),token,null)&&!owner.life().returnAccessory(owner.getUUID(),token,null));
            player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Tool.CUTTER.item()));
            check("native cutter severs actual accessory",owner.operations().perform(player,CareAction.CUT_ACCESSORY)&&!owner.life().hasAccessory());
            var cut=level.getEntitiesOfClass(ItemEntity.class,AREA,item->item.getItem().getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getBoolean("Cut")).getFirst();
            check("cut accessory cannot be returned as intact",!io.github.longshou.mcyukkuri.item.AccessoryItem.matches(owner,cut.getItem())&&cut.getItem().getItem().interactLivingEntity(cut.getItem(),player,owner,InteractionHand.MAIN_HAND)==net.minecraft.world.InteractionResult.FAIL);
            owner.operations().load(new CompoundTag());check("missing accessory cannot be cut twice",!owner.operations().perform(player,CareAction.CUT_ACCESSORY));
        }finally{player.discard();}
    }
}
