package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.body.FillingResource;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class FillingGameTests {
    private record Check(String name, boolean pass) {}
    private static final AABB AREA = new AABB(400, 63, 0, 445, 70, 12);
    private final List<Check> checks = new ArrayList<>();
    private void check(String name, boolean passed) { checks.add(new Check(name, passed)); }

    @GameTest(templateNamespace=P0Mod.ID, template="empty", timeoutTicks=600, batch="filling")
    public static void fillingAcceptance(GameTestHelper helper) {
        var test = new FillingGameTests();
        var level = helper.getLevel();
        for (int x=25;x<=27;x++) level.setChunkForced(x,0,true);
        for (int x=400;x<445;x++) for(int z=0;z<12;z++) {
            level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.STONE.defaultBlockState());
            for(int y=65;y<70;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
        }
        // Forced chunks must enter entity-ticking status before testing same-tick transfers.
        helper.startSequence().thenWaitUntil(() -> {
            for(int x=25;x<=27;x++)helper.assertTrue(level.isPositionEntityTicking(new BlockPos(x*16+8,65,5)),"Waiting for filling fixture chunks");
        }).thenExecute(() -> {
        try {
            level.getEntitiesOfClass(Entity.class,AREA,e->e instanceof YukkuriEntity||e instanceof ItemEntity).forEach(Entity::discard);
            test.run(level);
        } catch (Throwable error) {
            test.check("Unexpected exception: " + error, false);
        } finally {
            level.getEntitiesOfClass(Entity.class,AREA,e->e instanceof YukkuriEntity||e instanceof ItemEntity).forEach(Entity::discard);
            level.setBlockAndUpdate(new BlockPos(406,65,5),Blocks.AIR.defaultBlockState());
            for (int x=25;x<=27;x++)level.setChunkForced(x,0,false);
        }
        P0Control.write("filling-checks",test.checks);
        var failed=test.checks.stream().filter(c->!c.pass).toList();
        if(failed.isEmpty())helper.succeed();else helper.fail(failed.toString());
        });
    }
    private YukkuriEntity spawn(ServerLevel level,double x) {
        var entity=ModEntities.REIMU.get().create(level);
        entity.moveTo(x,65,5.5,0,0);entity.setNoAi(true);entity.setPersistenceRequired();level.addFreshEntity(entity);
        nutrition(entity,50);return entity;
    }
    private void nutrition(YukkuriEntity entity,float value) {
        var tag=entity.life().save();tag.putFloat("Nutrition",value);tag.putFloat("Energy",90);entity.life().load(tag);
    }
    private void tickMeal(YukkuriEntity entity,int count) {
        entity.setNoAi(false);for(int i=0;i<count;i++)entity.life().tick();entity.setNoAi(true);
    }
    private void run(ServerLevel level) {
        var source=spawn(level,405.5);var witness=spawn(level,407.5);var later=spawn(level,430.5);
        check("Body emits a real finite portion",source.body().releaseFilling("wound")&&source.body().filling()==11);
        var loose=level.getEntitiesOfClass(ItemEntity.class,AREA).getFirst();var portion=loose.getItem().copy();
        check("Fresh leak separates identity, composition and method",FillingResource.data(portion).hasUUID("Portion")
                &&FillingResource.data(portion).getString("Composition").equals("bean_paste")
                &&FillingResource.data(portion).getString("Method").equals("wound")&&FillingResource.odor(portion)==0);
        check("Only local witnesses know the source",witness.body().knowsSource(portion)&&!later.body().knowsSource(portion));
        check("Known source changes willingness without changing the food",!witness.life().acceptsFood(portion)&&later.life().acceptsFood(portion));
        var saved=witness.body().save();witness.body().load(saved);
        check("Source knowledge survives body NBT",witness.body().knowsSource(portion));
        check("Remote notification is refused",!witness.body().tellSource(later,portion));

        var pos=new BlockPos(406,65,5);level.setBlockAndUpdate(pos,ModBlocks.FOOD_BOWL.get().defaultBlockState());
        var bowl=(CareBlockEntity)level.getBlockEntity(pos);bowl.setItem(0,portion.copy());loose.discard();
        check("Bowl preserves a refused portion",bowl.takeFood(witness.life()::acceptsFood).isEmpty()&&bowl.foodCount()==1);
        var transferred=bowl.takeFood(later.life()::acceptsFood);
        check("Competing consumers cannot take a second copy",transferred.getCount()==1&&bowl.takeFood().isEmpty());
        check("An unwitnessed portion can be eaten",later.life().beginMeal(transferred,null,true));
        later.setHealth(6);tickMeal(later,44);
        check("Swallow retires exactly one portion with fixed nutrition",later.life().mouth().isEmpty()&&later.life().nutrition()==55);
        check("Filling does not fabricate scraps or heal the wound",later.getHealth()==6
                &&level.getEntitiesOfClass(ItemEntity.class,AREA,e->e.getItem().is(ModItems.FOOD_SCRAP.get())).isEmpty());
        tickMeal(later,25);check("Finished chewing grants no second nutrition",later.life().nutrition()==55);

        source.body().releaseFilling("vomit");
        var second=level.getEntitiesOfClass(ItemEntity.class,AREA,e->e.getItem().is(ModItems.FILLING.get())).getFirst();
        check("Each emission gets a separate portion identity",!FillingResource.data(second.getItem()).getUUID("Portion").equals(FillingResource.data(portion).getUUID("Portion")));
        later.moveTo(409.5,65,5.5,0,0);nutrition(later,50);
        check("A later arrival does not inherit old witness knowledge",!later.body().knowsSource(second.getItem()));
        check("Second physical portion enters the mouth",later.life().beginMeal(second.getItem().copy(),null,true));second.discard();
        check("A nearby actual witness can warn the eater",witness.body().tellSource(later,later.life().mouth()));
        tickMeal(later,1);
        check("Warning before swallowing returns the same physical portion",later.life().mouth().isEmpty()&&later.life().nutrition()==50
                &&level.getEntitiesOfClass(ItemEntity.class,AREA,e->e.getItem().is(ModItems.FILLING.get())).size()==1);
        nutrition(witness,10);check("Severe hunger permits a known portion",witness.life().acceptsFood(portion));
        var smelly=portion.copy();CustomData.update(DataComponents.CUSTOM_DATA,smelly,t->t.putInt("Odor",80));
        nutrition(later,50);check("Odor is separate from source knowledge",!later.life().acceptsFood(smelly));
        nutrition(later,10);check("Severe hunger permits foul food",later.life().acceptsFood(smelly));
        check("Contaminated serving can begin",later.life().beginMeal(smelly,null,true));tickMeal(later,44);
        check("Contamination settles only at swallow",later.body().infection()==1&&later.life().nutrition()==15);

        while(source.body().filling()>0)source.body().releaseFilling("wound");
        int count=level.getEntitiesOfClass(ItemEntity.class,AREA,e->e.getItem().is(ModItems.FILLING.get())).stream().mapToInt(e->e.getItem().getCount()).sum();
        check("An exhausted body cannot emit another portion",!source.body().releaseFilling("wound")
                &&count==level.getEntitiesOfClass(ItemEntity.class,AREA,e->e.getItem().is(ModItems.FILLING.get())).stream().mapToInt(e->e.getItem().getCount()).sum());

        var player=FakePlayerFactory.get(level,new com.mojang.authlib.GameProfile(UUID.fromString("f891e711-599c-4dbb-a642-a6e76531800e"),"FillingCare"));
        player.moveTo(403.5,65,5.5,0,0);player.setShiftKeyDown(true);
        var cloth=new ItemStack(ModItems.WASH_CLOTH.get());CustomData.update(DataComponents.CUSTOM_DATA,cloth,t->t.putInt("Water",8));
        player.setItemInHand(InteractionHand.MAIN_HAND,cloth);
        var floor=new BlockPos(405,64,5);
        var use=new UseOnContext(player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(floor),Direction.UP,floor,false));
        var keep=new ItemEntity(level,405.5,65,5.5,new ItemStack(Items.APPLE));level.addFreshEntity(keep);
        cloth.useOn(use);
        check("Cleaning consumes one water charge and keeps ordinary food",io.github.longshou.mcyukkuri.item.WashClothItem.water(cloth)==7&&keep.isAlive());
        check("Cleaned portions cannot be consumed again",level.getEntitiesOfClass(ItemEntity.class,new AABB(floor.above()).inflate(1),e->e.getItem().is(ModItems.FILLING.get())).isEmpty());
        cloth.useOn(use);check("Empty cleanup does not spend water",io.github.longshou.mcyukkuri.item.WashClothItem.water(cloth)==7);
        var body=source.body().save();body.putInt("Wound",3);body.putInt("Filling",4);body.putInt("Infection",2);body.putInt("Mold",5);source.body().load(body);source.setHealth(6);
        var juice=new ItemStack(ModItems.ORANGE_JUICE.get(),2);player.setItemInHand(InteractionHand.MAIN_HAND,juice);
        int bottles=player.getInventory().countItem(Items.GLASS_BOTTLE);
        juice.interactLivingEntity(player,source,InteractionHand.MAIN_HAND);
        check("Juice spends one serving and repairs all existing physical fields",juice.getCount()==1&&source.body().wound()==0&&source.body().filling()==12&&source.getHealth()==source.getMaxHealth());
        check("Juice does not equate mold with physical injury",source.body().mold()==5&&source.body().infection()==2);
        check("Successful bottled treatment returns one empty bottle",player.getInventory().countItem(Items.GLASS_BOTTLE)==bottles+1);
        juice.interactLivingEntity(player,source,InteractionHand.MAIN_HAND);
        check("Redundant treatment consumes neither juice nor another bottle",juice.getCount()==1&&player.getInventory().countItem(Items.GLASS_BOTTLE)==bottles+1);
        var remover=new ItemStack(ModItems.MOLD_REMOVER.get(),2);player.setItemInHand(InteractionHand.MAIN_HAND,remover);
        remover.interactLivingEntity(player,source,InteractionHand.MAIN_HAND);
        check("Mold remover clears mold and preserves separate infection",remover.getCount()==1&&source.body().mold()==0&&source.body().infection()==2);
        remover.interactLivingEntity(player,source,InteractionHand.MAIN_HAND);check("Mold-free bodies reject a second dose",remover.getCount()==1);
        source.body().load(source.body().save());check("Distinct disease fields survive save",source.body().mold()==0&&source.body().infection()==2);
        var grainEater=spawn(level,440.5);
        for(var feed:List.of(ModItems.GRAIN_BUDGET.get(),ModItems.GRAIN_REGULAR.get(),ModItems.GRAIN_PREMIUM.get())){
            nutrition(grainEater,20);check("Grain serving accepted: "+feed,grainEater.life().beginMeal(new ItemStack(feed),null,true));
            tickMeal(grainEater,64);int gain=feed==ModItems.GRAIN_BUDGET.get()?12:feed==ModItems.GRAIN_PREMIUM.get()?30:20;
            check("Grain tier uses its actual nutritional value: "+feed,grainEater.life().nutrition()==20+gain);
        }
        var toiletPos=new BlockPos(413,65,5);level.setBlockAndUpdate(toiletPos,ModBlocks.TOILET.get().defaultBlockState());
        var toilet=(CareBlockEntity)level.getBlockEntity(toiletPos);
        var small=io.github.longshou.mcyukkuri.body.DetachedItems.waste(source);CustomData.update(DataComponents.CUSTOM_DATA,small,t->t.putFloat("SourceScale",.3f));
        toilet.relieve(small);toilet.relieve(io.github.longshou.mcyukkuri.body.DetachedItems.waste(source));
        toilet.loadWithComponents(toilet.saveWithFullMetadata(level.registryAccess()),level.registryAccess());
        var waste=toilet.cleanItems();
        check("Toilet retains each source size through save and cleaning",waste.size()==2&&FillingResource.data(waste.getFirst()).getFloat("SourceScale")==.3f&&FillingResource.data(waste.getLast()).getFloat("SourceScale")==1);
        check("Second toilet cleanup cannot duplicate waste",toilet.cleanItems().isEmpty());
        level.setBlockAndUpdate(toiletPos,Blocks.AIR.defaultBlockState());
        var stack=portion.copyWithCount(13);
        var vanilla=new ItemEntity(level,440.5,65,5.5,stack);vanilla.setPickUpDelay(60);level.addFreshEntity(vanilla);
        ((io.github.longshou.mcyukkuri.item.FillingItem)stack.getItem()).onEntityItemUpdate(stack,vanilla);
        var converted=level.getEntitiesOfClass(io.github.longshou.mcyukkuri.entity.MaterialDrop.class,new AABB(439,64,4,442,67,7));
        check("Player-dropped filling converts once with the full count and provenance",!vanilla.isAlive()&&converted.size()==1
                &&ItemStack.matches(converted.getFirst().getItem(),stack)&&converted.getFirst().hasPickUpDelay());
        var disk=new net.minecraft.nbt.CompoundTag();converted.getFirst().save(disk);
        var restored=net.minecraft.world.entity.EntityType.loadEntityRecursive(disk,level,e->e);
        check("Saved material entity restores its actual class, count and provenance",restored instanceof io.github.longshou.mcyukkuri.entity.MaterialDrop material
                &&ItemStack.matches(material.getItem(),stack)&&material.hasPickUpDelay());
        player.setShiftKeyDown(false);player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
    }
}
