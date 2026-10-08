package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.DetachedStemBlockEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.interaction.CareAction;
import io.github.longshou.mcyukkuri.item.OperationToolItem.Tool;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class InstrumentGameTests {
    private record Check(String name,boolean pass){}
    private final ArrayList<Check> checks=new ArrayList<>();
    private static final AABB AREA=new AABB(1200,63,0,1232,72,12);
    private void check(String name,boolean value){checks.add(new Check(name,value));}
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=500,batch="instruments")
    public static void instrumentAcceptance(GameTestHelper helper){
        var test=new InstrumentGameTests();var level=helper.getLevel();
        for(int x=75;x<=76;x++)GameTestChunks.setForced(level,x,0,true);
        for(int x=1200;x<1232;x++)for(int z=0;z<12;z++){
            level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.STONE.defaultBlockState());
            for(int y=65;y<72;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
        }
        helper.startSequence().thenWaitUntil(()->{for(int x=75;x<=76;x++)helper.assertTrue(level.isPositionEntityTicking(new BlockPos(x*16+8,65,5)),"Waiting for instrument fixture");})
        .thenExecute(()->{
            try{test.run(level);}catch(Throwable error){test.check("Unexpected exception: "+error,false);}
            finally{
                level.getEntitiesOfClass(Entity.class,AREA,e->e instanceof YukkuriEntity||e instanceof ItemEntity).forEach(Entity::discard);
                for(int x=75;x<=76;x++)GameTestChunks.setForced(level,x,0,false);
            }
            P0Control.write("instrument-checks",test.checks);
            var failed=test.checks.stream().filter(c->!c.pass).toList();if(failed.isEmpty())helper.succeed();else helper.fail(failed.toString());
        });
    }
    private YukkuriEntity spawn(ServerLevel level,double x){
        var e=ModEntities.REIMU.get().create(level);e.moveTo(x,65,5.5,0,0);e.setNoAi(true);e.setOnGround(true);e.setPersistenceRequired();
        var life=e.life().save();life.putFloat("Nutrition",90);life.putFloat("Energy",90);life.putFloat("Hygiene",100);e.life().load(life);
        var family=e.family().save();family.putBoolean("Initialized",true);e.family().load(family);level.addFreshEntity(e);return e;
    }
    private FamilyData.Brood conceive(ServerLevel level,YukkuriEntity a,YukkuriEntity b){
        for(var e:java.util.List.of(a,b)){
            var home=e.blockPosition().south();level.removeBlock(home,false);level.setBlockAndUpdate(home,ModBlocks.NEST.get().defaultBlockState());
            for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.setBlockAndUpdate(home.offset(x,3,z),Blocks.OAK_PLANKS.defaultBlockState());
            if(!e.life().assignHome(GlobalPos.of(level.dimension(),home)))throw new IllegalStateException("fixture home "+home+" ground="+e.onGround()+" path="+e.life().reachable(e.blockPosition(),false));
        }
        YukkuriWorldData.get(level).relate(a.getUUID(),b.getUUID(),30);YukkuriWorldData.get(level).relate(b.getUUID(),a.getUUID(),30);
        var brood=FamilyData.get(level).conceive(a,b,FamilyData.BirthKind.STEM,GlobalPos.of(level.dimension(),a.blockPosition().south()));
        if(brood==null)throw new IllegalStateException("fixture conception");return brood;
    }
    private void recordCutScene(ServerLevel level,YukkuriEntity mother,net.minecraft.world.entity.player.Player player,FamilyData.Brood brood,boolean bottle){
        var destinations=new ArrayList<java.util.Map<String,Object>>();
        for(var direction:net.minecraft.core.Direction.Plane.HORIZONTAL){
            var pos=mother.blockPosition().relative(direction,2);var children=new ArrayList<java.util.Map<String,Object>>();
            for(int i=0;i<brood.children.size();i++)for(float progress:new float[]{brood.progress(),1}){
                var child=brood.children.get(i);var point=net.minecraft.world.phys.Vec3.atBottomCenterOf(pos).add(
                    io.github.longshou.mcyukkuri.family.DetachedStemGeometry.childBase(brood.children.size(),i,progress,brood.variation,mother.getVariant(),child.variant,bottle));
                var box=ModEntities.forVariant(child.variant).getDimensions().scale(.3f).makeBoundingBox(point).inflate(.04);
                children.add(java.util.Map.of("index",i,"id",child.id.toString(),"progress",progress,"base",point.toString(),"bounds",box.toString(),
                    "blocked",level.getBlockCollisions(null,box).iterator().hasNext(),"overlapsSupport",box.intersects(new AABB(pos.below()))));
            }
            destinations.add(java.util.Map.of("direction",direction.toString(),"position",pos.toString(),"support",level.getBlockState(pos.below()).toString(),
                "sturdy",level.getBlockState(pos.below()).isFaceSturdy(level,pos.below(),net.minecraft.core.Direction.UP),
                "withinPlayerRange",player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos))<=25,"children",children));
        }
        P0Control.write(bottle?"instrument-bottle-cut-scene":"instrument-plain-cut-scene",java.util.Map.of("count",brood.children.size(),"variation",brood.variation,
            "brood",brood.id.toString(),"anchor",brood.anchor.pos().toString(),"mother",mother.getUUID().toString(),"position",mother.position().toString(),
            "canStart",mother.operations().canStart(player,bottle?CareAction.BOTTLE_STEM:CareAction.CUT_STEM),"destinations",destinations));
    }
    private void run(ServerLevel level){
        var player=FakePlayerFactory.get(level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"InstrumentTest"));level.addNewPlayer(player);player.moveTo(1203,65,5.5);player.getAbilities().instabuild=false;
        try{
            var e=spawn(level,1205);player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Tool.STERILIZER.item()));
            check("sterilizer rejects absent target state",!e.operations().perform(player,CareAction.STERILIZE));
            player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Tool.VIBRATOR.item(),2));
            check("vibrator consumes one inserted device",e.operations().perform(player,CareAction.VIBRATE)&&player.getMainHandItem().getCount()==1);
            float hp=e.getHealth(),food=e.life().nutrition();for(int i=0;i<40;i++)e.operations().tick();
            check("sustained vibration has its own finite result without abrasion",e.family().stimulation()>0&&e.life().nutrition()<food&&e.getHealth()==hp);
            e.operations().end();e.operations().load(new CompoundTag());player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Tool.STERILIZER.item()));
            check("sterilizer changes reproductive state",e.operations().perform(player,CareAction.STERILIZE)&&e.family().sterile());
            e.operations().load(new CompoundTag());check("sterilization cannot repeat",!e.operations().perform(player,CareAction.STERILIZE));
            var saved=e.family().save();var restored=spawn(level,1208);restored.family().load(saved);check("sterility persists",restored.family().sterile()&&!restored.family().ready());restored.discard();e.discard();
            e=spawn(level,1205);player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Tool.BOX.item(),2));
            check("iron enclosure starts",e.operations().perform(player,CareAction.ENCLOSE)&&player.getMainHandItem().getCount()==1);hp=e.getHealth();
            for(int i=0;i<80;i++)e.operations().tick();check("box confinement does not invent floor abrasion",e.getHealth()==hp&&e.contactPose().equals("BOX"));
            var binding=e.operations().save();e.discard();restored=spawn(level,1205);restored.operations().load(binding);restored.operations().tick();
            check("enclosure returns exactly once after reload",level.getEntitiesOfClass(ItemEntity.class,AREA,item->item.getItem().is(Tool.BOX.item())).stream().mapToInt(item->item.getItem().getCount()).sum()==1);restored.discard();
            var mother=spawn(level,1217);var father=spawn(level,1219);player.moveTo(1215,65,5.5);
            var brood=conceive(level,mother,father);brood.elapsed=brood.duration-20;
            var identities=brood.children.stream().map(child->child.id).toList();player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Tool.BOTTLE_SHEARS.item()));
            check("bottle cut requires actual water offhand",!mother.operations().perform(player,CareAction.BOTTLE_STEM)&&mother.family().brood()==brood);
            var water=new ItemStack(Items.POTION);water.set(DataComponents.POTION_CONTENTS,new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.WATER));player.setItemInHand(InteractionHand.OFF_HAND,water);
            recordCutScene(level,mother,player,brood,true);
            check("bottle cut transfers original brood and consumes water",mother.operations().perform(player,CareAction.BOTTLE_STEM)&&mother.family().brood()==null&&player.getOffhandItem().isEmpty());
            if(!(level.getBlockEntity(brood.anchor.pos()) instanceof DetachedStemBlockEntity holder))
                throw new IllegalStateException("Bottle transfer left "+level.getBlockState(brood.anchor.pos())+" at "+brood.anchor.pos()+"; count="+brood.children.size()+", variation="+brood.variation);
            check("transferred brood preserves every child identity",holder!=null&&holder.brood()==brood&&brood.children.stream().map(child->child.id).toList().equals(identities));
            int elapsed=brood.elapsed;for(int i=0;i<60;i++)holder.tick();check("empty bottle cannot advance maturity",brood.elapsed==elapsed);
            check("sugar supply is finite",holder.supply(new ItemStack(Items.SUGAR))&&holder.sugar()==1);
            holder.tick();var blockSave=holder.saveWithFullMetadata(level.registryAccess());
            holder.loadWithComponents(blockSave,level.registryAccess());
            check("holder token survives reload",holder.brood()==brood);
            FamilyData.get(level).died(mother);mother.discard();check("mother death cannot cancel detached brood",!brood.cancelled);
            for(int i=0;i<120;i++)holder.tick();
            check("bottle births same reserved children once",brood.complete()&&identities.stream().allMatch(id->level.getEntity(id) instanceof YukkuriEntity));
            boolean aligned=true;for(int i=0;i<brood.children.size();i++){
                var baby=level.getEntity(brood.children.get(i).id);var expected=net.minecraft.world.phys.Vec3.atBottomCenterOf(holder.getBlockPos()).add(
                        io.github.longshou.mcyukkuri.family.DetachedStemGeometry.childBase(brood.children.size(),i,1,brood.variation,holder.source(),brood.children.get(i).variant,true));
                aligned&=baby!=null&&baby.position().distanceToSqr(expected)<1e-8;
            }
            check("birth starts at the same visible fruit attachment",aligned);
            check("detached births preserve displayed facing",identities.stream().allMatch(id->level.getEntity(id) instanceof YukkuriEntity baby&&baby.getYRot()==180&&baby.yBodyRot==180&&baby.getYHeadRot()==180));
            long children=level.getEntitiesOfClass(YukkuriEntity.class,AREA,child->identities.contains(child.getUUID())).size();
            holder.loadWithComponents(blockSave,level.registryAccess());for(int i=0;i<120;i++)holder.tick();
            check("old holder snapshot cannot duplicate completed births",level.getEntitiesOfClass(YukkuriEntity.class,AREA,child->identities.contains(child.getUUID())).size()==children);
            level.removeBlock(holder.getBlockPos(),false);check("removing completed holder leaves living children",identities.stream().allMatch(id->FamilyData.get(level).person(id).status==FamilyData.Status.ALIVE));
            for(var child:level.getEntitiesOfClass(YukkuriEntity.class,AREA))child.discard();
            mother=spawn(level,1217);father=spawn(level,1219);brood=conceive(level,mother,father);brood.elapsed=brood.duration/2;
            player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Tool.STEM_SHEARS.item()));
            recordCutScene(level,mother,player,brood,false);
            check("plain shears transfer immature brood",mother.operations().perform(player,CareAction.CUT_STEM));
            if(!(level.getBlockEntity(brood.anchor.pos()) instanceof DetachedStemBlockEntity plainHolder))
                throw new IllegalStateException("Plain transfer left "+level.getBlockState(brood.anchor.pos())+" at "+brood.anchor.pos()+"; count="+brood.children.size()+", variation="+brood.variation+"; see instrument-plain-cut-scene");
            check("plain transfer preserves original brood and immature progress",plainHolder.brood()==brood&&mother.family().brood()==null&&brood.elapsed==brood.duration/2);
            for(int i=0;i<2400;i++)plainHolder.tick();
            check("unfed detached immature fruit withers without birthing copies",brood.cancelled&&brood.children.stream().allMatch(child->!child.born&&FamilyData.get(level).person(child.id).status==FamilyData.Status.LOST));
            level.removeBlock(plainHolder.getBlockPos(),false);
        }finally{player.discard();}
    }
}
