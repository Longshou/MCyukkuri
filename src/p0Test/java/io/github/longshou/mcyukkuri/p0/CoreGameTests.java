package io.github.longshou.mcyukkuri.p0;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.house.HouseNestBlockEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class CoreGameTests {
    private static final AABB AREA=new AABB(256,60,0,304,85,48);
    private static final BlockPos NEST=new BlockPos(278,65,20),OTHER=new BlockPos(284,65,20);
    private record Check(String name,boolean pass,String detail){}
    private final List<Check> checks=new ArrayList<>();
    private GameTestHelper helper;private ServerLevel level;private long at=100;
    private YukkuriEntity a,b,subject;private HouseNestBlockEntity nest,other;
    private FamilyData.Brood eggs;private UUID custody;private int progress,stock;
    private boolean entered,peeked,exitedForFood,returnedAfterExit,uniqueExitResident,motionOnly;
    private UUID exitResident;
    private final List<java.util.Map<String,Object>> exitTrace=new ArrayList<>();
    private UUID roofResident;
    private boolean roofRecovered,uniqueRoofResident;
    private int roofGroundedTicks;
    private long roofRecoveryTick=-1;
    private final List<java.util.Map<String,Object>> roofTrace=new ArrayList<>();
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=9000,batch="core")
    public static void coreAcceptance(GameTestHelper helper){
        var test=new CoreGameTests();test.helper=helper;test.level=helper.getLevel();test.setup();
        helper.startSequence().thenWaitUntil(()->GameTestChunks.assertTicking(helper,16,19,0,2)).thenExecute(()->{
            test.at+=helper.getTick();test.house();test.hiding();test.body();test.founders();
            test.later(1,"cleanup",()->test.clear());
            helper.runAtTickTime(test.at+1,()->{P0Control.write("core-checks",test.checks);var failed=test.checks.stream().filter(c->!c.pass).toList();
                if(failed.isEmpty())helper.succeed();else helper.fail(failed.size()+" core checks failed; first "+failed.getFirst().name);});
        });
    }
    static void houseMotionAcceptance(GameTestHelper helper){
        var test=new CoreGameTests();test.helper=helper;test.level=helper.getLevel();test.motionOnly=true;test.setup();
        helper.startSequence().thenWaitUntil(()->GameTestChunks.assertTicking(helper,16,19,0,2)).thenExecute(()->{
            test.at+=helper.getTick();test.house();test.hiding();
            test.later(1,"crawl guards",()->{try{test.crawlGuards();}catch(ReflectiveOperationException error){throw new IllegalStateException(error);}});
            test.later(1,"cleanup",()->test.clear());
            helper.runAtTickTime(test.at+1,()->{P0Control.write("house-motion-checks",test.checks);var failed=test.checks.stream().filter(c->!c.pass).toList();
                if(failed.isEmpty())helper.succeed();else helper.fail(failed.size()+" house motion checks failed; first "+failed.getFirst().name);});
        });
    }
    @SuppressWarnings("unchecked")
    private void primeCrawl(YukkuriEntity e) throws ReflectiveOperationException {
        e.setNoAi(false);e.setOnGround(true);e.setDeltaMovement(Vec3.ZERO);e.syncCrawl(true);
        var field=YukkuriEntity.class.getDeclaredField("CRAWL_START");field.setAccessible(true);
        var key=(net.minecraft.network.syncher.EntityDataAccessor<Long>)field.get(null);
        e.getEntityData().set(key,level.getGameTime()-8);
        e.getMoveControl().setWantedPosition(e.getX(),e.getY(),e.getZ()+1,1);
    }
    private void crawlGuards() throws ReflectiveOperationException {
        clear();var e=spawn(true,270.5,18.5);primeCrawl(e);
        var initial=e.position();e.travel(Vec3.ZERO);
        double step=io.github.longshou.mcyukkuri.house.HouseCrawlMotion.advance(8);
        check("crawl travel advances the authored root stroke",Math.abs(e.getZ()-initial.z-step)<1e-8,e.position());
        e.moveTo(270.5,65,19-e.getBbWidth()/2-.001,0,0);level.setBlockAndUpdate(new BlockPos(270,65,19),Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(270,66,19),Blocks.STONE.defaultBlockState()); // Higher than the entity's one-block step.
        primeCrawl(e);initial=e.position();e.travel(Vec3.ZERO);
        check("crawl travel respects a solid wall",level.noCollision(e)&&e.getZ()-initial.z<.00101,e.position());
        level.setBlockAndUpdate(new BlockPos(270,65,19),Blocks.AIR.defaultBlockState());level.setBlockAndUpdate(new BlockPos(270,66,19),Blocks.AIR.defaultBlockState());e.moveTo(270.5,65,18.5,0,0);
        primeCrawl(e);initial=e.position();e.setNoAi(true);e.aiStep();
        check("NoAI clears crawl before any old stroke",!e.isCrawling()&&e.position().distanceToSqr(initial)<1e-10,e.position());
        primeCrawl(e);initial=e.position();e.setHealth(0);e.aiStep();
        check("death clears crawl before any old stroke",!e.isCrawling()&&e.position().distanceToSqr(initial)<1e-10,e.position());
    }
    private void hiding(){
        later(1,"hiding setup",()->{
            level.setDayTime(6000);
            clear();level.setBlockAndUpdate(NEST,ModBlocks.HOUSE_NEST.get().defaultBlockState());nest=(HouseNestBlockEntity)level.getBlockEntity(NEST);
            subject=spawn(true,278.5,19.5);subject.house().choose(nest);subject.setNoAi(false);entered=peeked=false;
        });
        for(int i=0;i<120;i++)later(10,"daytime hiding sample",()->{
            entered|=subject.blockPosition().equals(NEST)&&subject.getZ()>20.1;
        });
        later(1,"dusk wakes the resident",()->{check("daytime shelter is real grounded sleep",subject.house().sleeping(),subject.getBehaviorId());level.setDayTime(18000);});
        for(int i=0;i<40;i++)later(10,"dusk exit sample",()->{
            peeked|=subject.getBehaviorId().equals("house_peek");
        });
        later(1,"hiding and exit",()->{
            check("house actually enters the physical crevice",entered,subject.position());
            check("same resident visibly peeks from its opening",peeked,subject.getBehaviorId());
            subject.moveTo(278.5,65.75,20.5,0,0);subject.house().stop();
            // Begin above the actual roof with no inherited walking/jumping velocity.
            subject.getNavigation().stop();subject.setDeltaMovement(Vec3.ZERO);subject.setOnGround(false);
            roofResident=subject.getUUID();roofRecovered=false;uniqueRoofResident=true;
            roofGroundedTicks=0;roofRecoveryTick=-1;roofTrace.clear();
        });
        for(int i=1;i<=90;i++)helper.runAtTickTime(at+i,()->{
            var residents=level.getEntitiesOfClass(YukkuriEntity.class,AREA);
            uniqueRoofResident&=residents.size()==1&&residents.getFirst().getUUID().equals(roofResident);
            var box=subject.getBoundingBox();
            var feet=new AABB(box.minX+.01,box.minY-.02,box.minZ+.01,box.maxX-.01,box.minY+.005,box.maxZ-.01);
            boolean supported=level.getBlockCollisions(subject,feet).iterator().hasNext();
            boolean clear=level.noCollision(subject),outside=subject.getZ()<NEST.getZ();
            // Two consecutive supported, collision-free ground ticks beyond the opening
            // prove recovery; an airborne frame or a low point inside the nest cannot pass.
            boolean grounded=subject.onGround()&&subject.getY()<65.1&&clear&&supported&&outside;
            roofGroundedTicks=grounded?roofGroundedTicks+1:0;
            if(roofGroundedTicks>=2){if(!roofRecovered)roofRecoveryTick=helper.getTick();roofRecovered=true;}
            roofTrace.add(java.util.Map.of("tick",helper.getTick(),"position",subject.position().toString(),"uuid",subject.getUUID().toString(),
                "onGround",subject.onGround(),"noCollision",clear,"supported",supported,"outside",outside,
                "groundedTicks",roofGroundedTicks,"mustExit",subject.house().mustExit(),"unique",uniqueRoofResident));
        });
        later(91,"roof recovery",()->{
            check("resident leaves low roof and returns to floor",roofRecovered&&uniqueRoofResident,
                "recovered="+roofRecovered+", recoveryTick="+roofRecoveryTick+", unique="+uniqueRoofResident+", end="+subject.position()+", endGround="+subject.onGround()+", endMustExit="+subject.house().mustExit());
            P0Control.write(motionOnly?"house-motion-roof-trace":"core-roof-trace",roofTrace);
            subject.moveTo(278.5,65,20.4,0,0);subject.house().stop();
            life(subject,t->t.putFloat("Nutrition",20));deposit(nest,3,0);
            exitResident=subject.getUUID();exitedForFood=returnedAfterExit=false;uniqueExitResident=true;exitTrace.clear();
        });
        // Check the entire exit window: a later idle stroll may re-enter the opening.
        for(int i=1;i<=250;i++)helper.runAtTickTime(at+i,()->{
            var residents=level.getEntitiesOfClass(YukkuriEntity.class,AREA);
            uniqueExitResident&=residents.size()==1&&residents.getFirst().getUUID().equals(exitResident);
            boolean outside=subject.getZ()<NEST.getZ();
            returnedAfterExit|=exitedForFood&&subject.blockPosition().equals(NEST)&&subject.getZ()>NEST.getZ()+.08;
            exitedForFood|=outside;
            exitTrace.add(java.util.Map.of("tick",helper.getTick(),"position",subject.position().toString(),"uuid",subject.getUUID().toString(),
                "unique",uniqueExitResident,"outside",outside,"mustExit",subject.house().mustExit(),"mealTicks",subject.life().mealTicks(),
                "activity",subject.getBehaviorId(),"wanted",new Vec3(subject.getMoveControl().getWantedX(),subject.getMoveControl().getWantedY(),subject.getMoveControl().getWantedZ()).toString()));
        });
        later(251,"hungry nest food",()->{
            check("urgent hunger can consume real nest stock",subject.life().nutrition()>22&&nest.food()<3,subject.life().nutrition()+" / "+nest.food()+" / "+subject.getBehaviorId()+" / "+subject.house().save());
            check("hunger exits the crevice without a duplicate hidden entity",exitedForFood&&uniqueExitResident,
                "everExited="+exitedForFood+", returned="+returnedAfterExit+", unique="+uniqueExitResident+", end="+subject.position()+", mustExit="+subject.house().mustExit()+", mealTicks="+subject.life().mealTicks());
            P0Control.write(motionOnly?"house-motion-exit-trace":"core-exit-trace",exitTrace);
        });
    }
    private void check(String name,boolean pass,Object detail){checks.add(new Check(name,pass,String.valueOf(detail)));}
    private void founders(){
        later(1,"founder setup",()->{clear();level.setDayTime(18000);level.setBlockAndUpdate(NEST,ModBlocks.HOUSE_NEST.get().defaultBlockState());nest=(HouseNestBlockEntity)level.getBlockEntity(NEST);deposit(nest,12,0);});
        later(20,"founder wait",()->{
            check("supplied crevice does not create founders instantly",level.getEntitiesOfClass(YukkuriEntity.class,AREA).isEmpty(),nest.identity());
            var data=nest.saveWithFullMetadata(level.registryAccess());data.putInt("ArrivalTicks",io.github.longshou.mcyukkuri.family.FamilyConfig.HOUSE_ARRIVAL_TIME.get()-1);nest.loadWithComponents(data,level.registryAccess());
        });
        later(3,"first founder",()->{
            var residents=level.getEntitiesOfClass(YukkuriEntity.class,AREA);
            check("loaded attraction creates one real founder and consumes four scraps",residents.size()==1&&founderStock(residents)==8,residents.size()+" / "+founderStock(residents));
            residents.forEach(e->{e.setNoAi(true);e.moveTo(272,65,19,0,0);});
            var data=nest.saveWithFullMetadata(level.registryAccess());data.putInt("ArrivalTicks",io.github.longshou.mcyukkuri.family.FamilyConfig.HOUSE_ARRIVAL_TIME.get()-1);nest.loadWithComponents(data,level.registryAccess());
        });
        later(3,"second founder",()->{
            var residents=level.getEntitiesOfClass(YukkuriEntity.class,AREA);
            check("second founder has independent identity and costs four scraps",residents.size()==2&&founderStock(residents)==4&&!residents.getFirst().getUUID().equals(residents.getLast().getUUID()),residents.size()+" / "+founderStock(residents));
            residents.forEach(e->{e.setNoAi(true);e.moveTo(272,65,19,0,0);});
            var data=nest.saveWithFullMetadata(level.registryAccess());data.putInt("ArrivalTicks",io.github.longshou.mcyukkuri.family.FamilyConfig.HOUSE_ARRIVAL_TIME.get());nest.loadWithComponents(data,level.registryAccess());
        });
        later(3,"founder limit and burial",()->{
            var residents=level.getEntitiesOfClass(YukkuriEntity.class,AREA);
            check("saved arrival limit prevents unlimited founders",residents.size()==2&&founderStock(residents)==4,nest.saveWithFullMetadata(level.registryAccess()));
            var player=net.neoforged.neoforge.common.util.FakePlayerFactory.get(level,new com.mojang.authlib.GameProfile(UUID.fromString("4666b2ae-1bc7-47d3-a1cf-130132ffb487"),"CoreCare"));
            player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            var soil=new BlockPos(270,64,17);level.setBlockAndUpdate(soil,Blocks.DIRT.defaultBlockState());player.moveTo(270,65,18,0,0);
            var remains=new ItemStack(ModItems.REMAINS.get());player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,remains);
            var hit=new net.minecraft.world.phys.BlockHitResult(Vec3.atBottomCenterOf(soil.above()),net.minecraft.core.Direction.UP,soil,false);
            player.gameMode.useItemOn(player,level,remains,net.minecraft.world.InteractionHand.MAIN_HAND,hit);
            var corpses=level.getEntitiesOfClass(io.github.longshou.mcyukkuri.entity.RemainsEntity.class,new net.minecraft.world.phys.AABB(soil.above()));
            check("placing remains creates a body before burial",player.getMainHandItem().isEmpty()&&corpses.size()==1&&level.isEmptyBlock(soil.above()),corpses.size());
            if(!corpses.isEmpty()){
                var corpse=corpses.getFirst();for(int i=0;i<5;i++)corpse.tick();
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new ItemStack(Items.IRON_SHOVEL));
                player.interactOn(corpse,net.minecraft.world.InteractionHand.MAIN_HAND);
                check("survival shovel burial consumes body and creates an actual mound",corpse.isRemoved()&&level.getBlockState(soil.above()).is(ModBlocks.GRAVE.get()),level.getBlockState(soil.above()));
            }
            level.setBlockAndUpdate(soil.above(),Blocks.AIR.defaultBlockState());
        });
    }
    private int founderStock(List<YukkuriEntity> residents){return nest.food()+residents.stream().mapToInt(e->e.life().mouth().getCount()+e.house().carrying().getCount()).sum();}
    private void later(int delay,String name,Runnable step){at+=delay;helper.runAtTickTime(at,()->{try{step.run();}catch(Throwable error){check(name,false,error.toString());}P0Control.write("core-checks",checks);});}
    private void setup(){
        for(int x=16;x<=19;x++)for(int z=0;z<=2;z++)GameTestChunks.setForced(level,x,z,true);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,level.getServer());level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(true,level.getServer());
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,level.getServer());
        level.setDayTime(6000);level.setWeatherParameters(6000,0,false,false);
        clear();for(int x=256;x<304;x++)for(int z=0;z<48;z++){
            for(int y=65;y<=74;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.STONE.defaultBlockState());}
        for(int x=272;x<290;x++)for(int z=15;z<25;z++)level.setBlockAndUpdate(new BlockPos(x,68,z),Blocks.OAK_PLANKS.defaultBlockState());
    }
    private void clear(){level.getEntitiesOfClass(Entity.class,AREA,e->e instanceof YukkuriEntity||e instanceof ItemEntity).forEach(Entity::discard);
        level.setBlockAndUpdate(NEST,Blocks.AIR.defaultBlockState());level.setBlockAndUpdate(OTHER,Blocks.AIR.defaultBlockState());
        level.getEntitiesOfClass(ItemEntity.class,AREA).forEach(Entity::discard);}
    private void life(YukkuriEntity e,Consumer<CompoundTag> edit){var data=e.life().save();edit.accept(data);e.life().load(data);}
    private YukkuriEntity spawn(boolean house,double x,double z){var e=(house?ModEntities.HOUSE_REIMU.get():ModEntities.REIMU.get()).create(level);
        e.moveTo(x,65,z,0,0);e.setPersistenceRequired();e.setOnGround(true);e.setNoAi(true);level.addFreshEntity(e);
        life(e,t->{t.putFloat("Nutrition",95);t.putFloat("Energy",90);t.putFloat("Hygiene",100);t.putInt("RestWait",5000);});return e;}
    private void deposit(HouseNestBlockEntity value,int food,int materials){for(int i=0;i<food;i++)value.deposit(new ItemStack(ModItems.FOOD_SCRAP.get()));for(int i=0;i<materials;i++)value.deposit(new ItemStack(ModItems.NEST_LINT.get()));}
    private void house(){
        later(1,"house setup",()->{
            level.setDayTime(18000);
            level.setBlockAndUpdate(NEST,ModBlocks.HOUSE_NEST.get().defaultBlockState());level.setBlockAndUpdate(OTHER,ModBlocks.HOUSE_NEST.get().defaultBlockState());
            nest=(HouseNestBlockEntity)level.getBlockEntity(NEST);other=(HouseNestBlockEntity)level.getBlockEntity(OTHER);
            a=spawn(true,278.3,19.2);b=spawn(true,278.8,19.3);a.house().choose(nest);b.house().choose(nest);
            YukkuriWorldData.get(level).relate(a.getUUID(),b.getUUID(),30);YukkuriWorldData.get(level).relate(b.getUUID(),a.getUUID(),30);
            deposit(nest,3,0);deposit(other,2,3);
            check("small house body fits crevice above its floor while ordinary adult does not",level.noCollision(a,a.getDimensions(a.getPose()).makeBoundingBox(Vec3.atBottomCenterOf(NEST).add(0,.025,0)))
                    &&!level.noCollision(a,ModEntities.REIMU.get().getDimensions().makeBoundingBox(Vec3.atBottomCenterOf(NEST).add(0,.025,0))),NEST);
        });
        later(120,"no lining no eggs",()->{
            check("food without six materials cannot make a brood",nest.brood()==null&&!nest.built(),nest.materials());
            a.setNoAi(false);var lint=new ItemEntity(level,276.5,65,19.5,new ItemStack(ModItems.NEST_LINT.get(),6));lint.setDeltaMovement(Vec3.ZERO);lint.setNoGravity(true);lint.setNoPickUpDelay();level.addFreshEntity(lint);
        });
        later(4000,"material collection",()->{
            check("real goal carries and deposits six source items",nest.materials()==6&&a.house().carrying().isEmpty(),nest.materials()+" / "+a.house().save()+" / "+a.position()+" / "+a.getBehaviorId()+" / "+a.life().save()+" / "+level.getEntitiesOfClass(ItemEntity.class,AREA).stream().map(e->e.getItem()+" @ "+e.position()).toList());
            check("only three food prevents laying",nest.brood()==null,nest.food());
            a.setNoAi(true);a.moveTo(278.3,65,19.2,0,0);b.moveTo(278.8,65,19.3,0,0);
            life(a,t->{t.putFloat("Nutrition",95);t.putFloat("Energy",90);});
            nest.clean(false); // The longer collection run accumulates dirt; breeding needs a clean nest.
            deposit(nest,12,0);level.setDayTime(6000);a.setNoAi(false);b.setNoAi(false);
        });
        later(400,"lay eggs",()->{
            eggs=nest.brood();check("actual nest ticker lays a uniquely identified house brood",eggs!=null&&eggs.kind==FamilyData.BirthKind.EGG,eggs==null?"none":eggs.id);
            if(eggs==null)return;check("egg identities and parents are fixed",eggs.children.size()>=2&&eggs.children.stream().map(c->c.id).distinct().count()==eggs.children.size(),eggs.children.size());
            check("egg parents retain their committed partnership",b.getUUID().equals(a.family().partner())&&a.getUUID().equals(b.family().partner()),a.family().save());
            progress=eggs.elapsed;stock=nest.food();
            var saved=nest.saveWithFullMetadata(level.registryAccess());var copy=new HouseNestBlockEntity(NEST,nest.getBlockState());copy.setLevel(level);copy.loadWithComponents(saved,level.registryAccess());
            check("native block save restores same nest and brood",copy.identity().equals(nest.identity())&&copy.brood().id.equals(eggs.id)&&copy.food()==stock,copy.identity());
            nest.clean(false);check("normal clean preserves eggs and lining",nest.brood().id.equals(eggs.id)&&nest.materials()==6&&nest.food()==0,nest.eggs());
        });
        later(120,"starved eggs pause",()->{
            check("no stock pauses incubation",eggs!=null&&eggs.elapsed==progress,eggs==null?"none":eggs.elapsed);
            check("cleaning one nest leaves neighboring stock",other.food()==2&&other.materials()==3,other.food());
            deposit(nest,12,0);if(eggs!=null){eggs.duration=200;eggs.elapsed=100;for(var child:eggs.children)if(!child.born)child.eggElapsed=100;FamilyData.get(level).setDirty();}
        });
        later(600,"hatching",()->{
            check("real nest ticker hatches all eggs once",eggs!=null&&eggs.complete(),eggs==null?"none":eggs.unborn());
            if(eggs==null||!eggs.complete())return;
            var first=(YukkuriEntity)level.getEntity(eggs.children.getFirst().id);
            check("hatchlings are babies with real ancestry",first!=null&&first.family().stage()==io.github.longshou.mcyukkuri.family.FamilyLife.Stage.BABY&&first.family().mother().equals(eggs.mother),first==null?"missing":first.family().save());
            level.setBlockAndUpdate(NEST,Blocks.AIR.defaultBlockState());
            check("destroying a hatched nest keeps living children and genealogy",first.isAlive()&&FamilyData.get(level).related(first.getUUID(),eggs.mother),first.getUUID());
            check("destroying one nest preserves the other",other.food()==2&&other.materials()==3,other.identity());
        });
    }
    private void body(){
        later(1,"blunt injury setup",()->{clear();subject=spawn(false,275.5,19.5);subject.setNoAi(false);subject.hurt(level.damageSources().generic(),2);
            check("actual blunt damage creates a dent",subject.getHealth()==8&&subject.body().wounds().size()==1&&subject.body().wounds().getFirst().kind().equals("dent"),subject.body().save());});
        later(205,"closed dent does not leak",()->{
            check("closed dent retains filling through the leak interval",subject.body().filling()==12&&level.getEntitiesOfClass(ItemEntity.class,AREA,e->e.getItem().is(ModItems.FILLING.get())).isEmpty(),subject.body().save());
        });
        later(1,"body setup",()->{
            clear();subject=spawn(false,275.5,19.5);subject.setYRot(180);subject.yBodyRot=180;subject.setNoAi(false);
            var blade=net.neoforged.neoforge.common.util.FakePlayerFactory.get(level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"CoreBlade"));
            var aim=subject.position().add(0,2.2/16,-4.8/16);
            blade.moveTo(aim.x,aim.y-blade.getEyeHeight(),aim.z-2,0,0);
            blade.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new ItemStack(Items.IRON_SWORD));
            subject.hurt(level.damageSources().playerAttack(blade),2);
            check("one actual blade hit creates a tear after vanilla health loss",subject.getHealth()==8&&subject.body().wounds().size()==1&&subject.body().wounds().getFirst().kind().equals("tear"),subject.body().save());
            blade.discard();
        });
        later(205,"finite leak",()->{
            check("wound loses one finite filling unit",subject.body().filling()==11&&subject.body().wound()==1,subject.body().save());
            check("leak creates one physical source-tagged item",level.getEntitiesOfClass(ItemEntity.class,AREA,e->e.getItem().is(ModItems.FILLING.get())).stream().mapToInt(e->e.getItem().getCount()).sum()==1,"items");
        });
        later(120,"treatment",()->{
            check("bandage accepted after danger settles",subject.body().treat(false),subject.body().save());
            check("duplicate bandage has no second effect",!subject.body().treat(false),subject.body().bandage());
            custody=YukkuriWorldData.get(level).capture(subject);check("wounded individual is carried once",custody!=null,custody);
        });
        later(100,"body return",()->{
            subject=YukkuriWorldData.get(level).release(custody,level,new Vec3(275.5,65,19.5),0);
            check("capture preserves remaining filling and treatment",subject!=null&&subject.body().filling()==11&&subject.body().bandage()==1200,subject==null?"missing":subject.body().save());
        });
        later(210,"healed wound",()->{
            check("fed clean bandage closes wound over actual ticks",subject.body().wound()==0&&subject.body().filling()==11,subject.body().save());
            var body=subject.body().save();body.putInt("Infection",5);subject.body().load(body);
            check("medicine consumes an infection treatment",subject.body().treat(true)&&subject.body().infection()==1,subject.body().infection());
            check("medicine finishes infection but cannot heal a healthy body",subject.body().treat(true)&&!subject.body().treat(true),subject.body().infection());
            subject.hurt(level.damageSources().generic(),100);subject.body().died();
            check("death settles one physical remains despite duplicate callback",level.getEntitiesOfClass(io.github.longshou.mcyukkuri.entity.RemainsEntity.class,AREA).size()==1,"remains");
            check("dead body no longer retains extractable filling",subject.body().filling()==0,subject.body().save());
        });
    }
}
