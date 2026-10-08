package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.body.BodyWound;
import io.github.longshou.mcyukkuri.entity.*;
import io.github.longshou.mcyukkuri.registry.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.*;

@PrefixGameTestTemplate(false)
public final class RemainsGameTests {
    private record Check(String name,boolean pass){}
    private static final List<Check> checks=new ArrayList<>();
    private static void check(String name,boolean pass){checks.add(new Check(name,pass));}
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=1000,batch="remains")
    public static void remains(GameTestHelper helper){
        var level=helper.getLevel();var soil=new BlockPos(968,64,8);var area=new AABB(960,60,0,976,80,16);
        checks.clear();GameTestChunks.setForced(level,60,0,true);
        helper.startSequence().thenWaitUntil(()->helper.assertTrue(level.isPositionEntityTicking(soil.above()),"Remains area is ticking"))
            .thenExecute(()->{try{
                var player=net.neoforged.neoforge.common.util.FakePlayerFactory.get(level,new com.mojang.authlib.GameProfile(UUID.fromString("9abcdee1-5473-44f7-8ddd-08abc0000021"),"RemainsCare"));
                player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                player.moveTo(968.5,65,6.5,0,45);player.setShiftKeyDown(false);
                level.setBlockAndUpdate(soil,Blocks.DIRT.defaultBlockState());
                for(var variant:YukkuriVariant.values())for(int flags:variant==YukkuriVariant.REIMU?new int[]{0,2,16,32,48,256,258}:variant==YukkuriVariant.MARISA?new int[]{0,1,16,32,48}:new int[]{0}){
                    var e=ModEntities.forVariant(variant).create(level);e.setNoAi(true);e.moveTo(968.5,65,8.5,180,0);e.yBodyRot=180;level.addFreshEntity(e);
                    e.subspecies().initializeBirth(flags);
                    if((flags&256)!=0){var form=e.subspecies().save();form.putBoolean("Deibu",true);e.subspecies().load(form);e.refreshDimensions();}
                    var asset=e.subspecies().assetId();
                    // An off-centre aim must stay at the struck point rather than a fixed face patch.
                    player.moveTo(e.getX()-e.getBbWidth()*.16,65+e.getBbHeight()*.45-player.getEyeHeight(),e.getZ()-2,0,0);
                    player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
                    float before=e.getHealth();boolean accepted=e.hurt(e.damageSources().playerAttack(player),.5f);
                    if(!e.body().wounds().isEmpty()){
                        var point=io.github.longshou.mcyukkuri.body.WoundPlacement.outlet(e,e.body().wounds().getFirst());
                        var delta=point.subtract(player.getEyePosition());
                        check(asset+" actual half-point wound stays on the off-centre attack ray",delta.cross(player.getLookAngle()).length()<.015&&delta.dot(player.getLookAngle())>0);
                    }
                    check(asset+" accepted half-point MC hit creates synchronized dent",accepted&&e.getHealth()<before&&e.body().wounds().size()==1&&e.visibleWounds().equals(e.body().wounds())&&e.body().wounds().getFirst().kind().equals("dent"));
                    e.hurt(e.damageSources().playerAttack(player),.5f);check(asset+" invulnerability rejects duplicate wound",e.body().wounds().size()==1);
                    e.invulnerableTime=0;player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.IRON_SWORD));
                    var identity=e.getUUID();e.hurt(e.damageSources().playerAttack(player),100);
                    var bodies=level.getEntitiesOfClass(RemainsEntity.class,area);
                    check(asset+" death creates exactly one physical body at death point",!e.isAlive()&&bodies.size()==1&&bodies.getFirst().position().equals(e.position()));
                    var corpse=bodies.getFirst();var data=corpse.data();var wounds=BodyWound.read(data.getCompound("Body"));
                    check(asset+" fatal blade wound and previous dent retained",wounds.size()==2&&wounds.getLast().kind().equals("tear")&&wounds.getFirst().kind().equals("dent"));
                    check(asset+" corpse retains identity cause model scale and filling",data.getUUID("Identity").equals(identity)&&data.getString("Cause").equals("minecraft:player_attack")&&data.getString("Model").equals(asset)&&data.getInt("Filling")==12&&data.getFloat("RenderScale")==e.growthScale()*e.getScale()&&e.body().filling()==0);
                    e.body().died();check(asset+" repeated settlement cannot duplicate corpse",level.getEntitiesOfClass(RemainsEntity.class,area).size()==1);
                    check(asset+" death drops no bag item",level.getEntitiesOfClass(ItemEntity.class,area,d->d.getItem().is(ModItems.REMAINS.get())).isEmpty());
                    var saved=new CompoundTag();corpse.saveWithoutId(saved);var restored=ModEntities.REMAINS.get().create(level);restored.load(saved);
                    check(asset+" entity save restores exact inventory payload and bounds",ItemStack.matches(corpse.remains(),restored.remains())&&corpse.getBbWidth()==restored.getBbWidth());
                    player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);player.setShiftKeyDown(false);
                    player.interactOn(corpse,InteractionHand.MAIN_HAND);
                    check(asset+" ordinary empty-hand right click transfers the exact original",corpse.isRemoved()&&ItemStack.matches(player.getMainHandItem(),restored.remains()));
                    player.interactOn(corpse,InteractionHand.MAIN_HAND);check(asset+" repeated collection stays one item",player.getMainHandItem().getCount()==1);
                    e.discard();player.setShiftKeyDown(false);
                    var hit=new BlockHitResult(new Vec3(968.5,65,8.5),Direction.UP,soil,false);
                    player.gameMode.useItemOn(player,level,player.getMainHandItem(),InteractionHand.MAIN_HAND,hit);bodies=level.getEntitiesOfClass(RemainsEntity.class,area);corpse=bodies.getFirst();
                    check(asset+" placing consumes transport item without creating grave",player.getMainHandItem().isEmpty()&&bodies.size()==1&&level.isEmptyBlock(soil.above())&&corpse.data().equals(data));
                    player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);player.interactOn(corpse,InteractionHand.MAIN_HAND);
                    level.setBlockAndUpdate(soil.above(),Blocks.STONE.defaultBlockState());
                    player.gameMode.useItemOn(player,level,player.getMainHandItem(),InteractionHand.MAIN_HAND,hit);
                    check(asset+" blocked placement retains exact transported body",ItemStack.matches(player.getMainHandItem(),restored.remains())&&level.getEntitiesOfClass(RemainsEntity.class,area).isEmpty());
                    level.removeBlock(soil.above(),false);
                    player.gameMode.useItemOn(player,level,player.getMainHandItem(),InteractionHand.MAIN_HAND,hit);bodies=level.getEntitiesOfClass(RemainsEntity.class,area);corpse=bodies.getFirst();
                    check(asset+" creative game-mode wrapper also transfers exactly one body",player.getMainHandItem().isEmpty()&&bodies.size()==1&&ItemStack.matches(corpse.remains(),restored.remains()));
                    player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                    // Controlled physics ticks establish contact before exercising the real entity interaction.
                    for(int tick=0;tick<5;tick++)corpse.tick();
                    var shovel=new ItemStack(Items.IRON_SHOVEL);player.setItemInHand(InteractionHand.MAIN_HAND,shovel);
                    level.setBlockAndUpdate(soil,Blocks.STONE.defaultBlockState());player.interactOn(corpse,InteractionHand.MAIN_HAND);
                    check(asset+" invalid soil preserves body and tool",!corpse.isRemoved()&&shovel.getDamageValue()==0);
                    level.setBlockAndUpdate(soil,Blocks.DIRT.defaultBlockState());player.interactOn(corpse,InteractionHand.MAIN_HAND);
                    check(asset+" shovel burial creates grave and debits one durability",corpse.isRemoved()&&level.getBlockState(soil.above()).is(ModBlocks.GRAVE.get())&&shovel.getDamageValue()==1);
                    var grave=level.getBlockEntity(soil.above()).saveWithFullMetadata(level.registryAccess()).getCompound("Remains");
                    check(asset+" grave retains original model identity and injuries",grave.getUUID("Identity").equals(identity)&&grave.getString("Model").equals(asset)&&grave.getCompound("Body").equals(data.getCompound("Body")));
                    level.removeBlock(soil.above(),false);
                }
                var dropped=new ItemEntity(level,968.5,65,8.5,new ItemStack(ModItems.REMAINS.get()));level.addFreshEntity(dropped);dropped.tick();
                var converted=level.getEntitiesOfClass(RemainsEntity.class,area);
                check("discarded or legacy ground item converts to physical remains",dropped.isRemoved()&&converted.size()==1);
                converted.forEach(Entity::discard);
                for(boolean drown:new boolean[]{false,true}){
                    var e=ModEntities.HOUSE_REIMU.get().create(level);e.moveTo(968.5,65,8.5,0,0);level.addFreshEntity(e);
                    e.hurt(drown?e.damageSources().drown():e.damageSources().starve(),100);
                    var corpse=level.getEntitiesOfClass(RemainsEntity.class,area).getFirst();
                    check((drown?"Drowning":"Starvation")+" death keeps body without inventing an external wound",BodyWound.read(corpse.data().getCompound("Body")).isEmpty());
                    corpse.discard();e.discard();
                }
                var burned=ModEntities.HOUSE_REIMU.get().create(level);burned.moveTo(968.5,65,8.5,180,0);burned.setNoAi(true);level.addFreshEntity(burned);
                burned.hurt(burned.damageSources().inFire(),1);for(int i=0;i<114;i++)burned.aiStep();burned.hurtTime=0;
                check("persistent scorch produces pain expression after startle",burned.body().burns()>0&&io.github.longshou.mcyukkuri.social.EcologyPresentation.expression(burned,"neutral").equals("cry"));
            }catch(Throwable error){error.printStackTrace();check("Unexpected: "+error,false);}finally{
                level.getEntitiesOfClass(Entity.class,area,e->e instanceof YukkuriEntity||e instanceof RemainsEntity||e instanceof ItemEntity).forEach(Entity::discard);
                GameTestChunks.setForced(level,60,0,false);P0Control.write("remains-checks",checks);
            }
            var failed=checks.stream().filter(c->!c.pass).toList();if(failed.isEmpty())helper.succeed();else helper.fail(failed.toString());});
    }
}
