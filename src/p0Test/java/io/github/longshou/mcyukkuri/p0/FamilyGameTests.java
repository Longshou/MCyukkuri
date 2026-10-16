package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyConfig;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.FamilyGeometry;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class FamilyGameTests {
    private static final AABB AREA=new AABB(160,60,0,224,85,56);
    private static final BlockPos HOME_A=new BlockPos(180,65,21),HOME_B=new BlockPos(183,65,21);
    private record Check(String name,boolean pass,String detail) {}
    private record FallingChild(YukkuriEntity entity,Vec3 birthPosition) {}
    private record GroundContact(long tick,Vec3 birthPosition,Vec3 position,String model,int subspeciesFlags,
            boolean groundFlag,boolean verticalCollision,double supportTop) {}
    private record FollowSample(long tick,UUID child,UUID mother,UUID father,String childPosition,String motherPosition,
            String fatherPosition,String activity,String motherActivity,String fatherActivity,double distanceSquared,boolean movedInFollow) {}
    private final List<Check> checks=new ArrayList<>();
    private final List<FamilyData.Brood> batches=new ArrayList<>();
    private final Map<UUID,FallingChild> landingSubjects=new LinkedHashMap<>();
    private final Map<UUID,GroundContact> groundContacts=new LinkedHashMap<>();
    private final List<FollowSample> followTrace=new ArrayList<>();
    private boolean observingFollow,previousFollow,approachedParent;
    private Vec3 previousFollowPosition;
    private double closestFollowingDistance=Double.POSITIVE_INFINITY;
    private double followingStartDistance;
    private GameTestHelper helper;
    private ServerLevel level;
    private net.minecraft.gametest.framework.GameTestSequence sequence;
    private YukkuriEntity a,b,child;
    private FamilyData.Brood pregnancy;
    private FamilyData.Brood pendingStem;
    private long naturalStartTick;
    private UUID savedId,token;
    private int growthBirthFlags;
    private float growthStartWidth,growthStartHeight;
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=6000,batch="family")
    public static void familyAcceptance(GameTestHelper helper) {
        FamilyGameTests test=new FamilyGameTests();test.helper=helper;test.level=helper.getLevel();test.setup();
        helper.onEachTick(()-> { test.observeGroundContacts();test.observeFollowing(); });
        test.sequence=helper.startSequence().thenWaitUntil(() -> {
            for(int x=10;x<=13;x++)for(int z=0;z<=3;z++)
                helper.assertTrue(test.level.isPositionEntityTicking(new BlockPos(x*16+8,65,z*16+8)), "Waiting for family fixture chunks");
        });
        test.rules();test.growth();test.natural();test.persistence();
        test.later(1,"cleanup",test::clear);
        test.sequence.thenIdle(1).thenExecute(()-> {
            P0Control.write("family-checks",test.checks);var failed=test.checks.stream().filter(c->!c.pass).toList();
            if(failed.isEmpty())helper.succeed();else helper.fail(failed.size()+" family checks failed; first: "+failed.getFirst().name);
        });
    }
    private void check(String name,boolean valid,Object detail) {
        var result=new Check(name,valid,String.valueOf(detail));checks.add(result);
        if(!valid)System.out.println("MCYUKKURI_P0 family check failed: "+result);
    }
    private void observeGroundContacts() {
        for(var entry:landingSubjects.entrySet()) {
            var tracked=entry.getValue();var baby=tracked.entity();
            if(groundContacts.containsKey(entry.getKey())||!baby.isAlive()||baby.isRemoved()
                    ||baby.family().flying()||baby.isPassenger()||baby.isInWaterOrBubble())continue;
            if(baby.getY()>=tracked.birthPosition().y-.25||!level.noCollision(baby))continue;
            var box=baby.getBoundingBox();double supportTop=Double.NaN;
            // onGround may be cleared by a zero-displacement step before this observer runs.
            // Independently require the descended, un-clipped sole to touch an actual block's top plane.
            for(var shape:level.getBlockCollisions(baby,box.move(0,-.01,0)))for(var support:shape.toAabbs()) {
                if(Math.abs(support.maxY-box.minY)<1.0e-6
                        &&Math.min(support.maxX,box.maxX)-Math.max(support.minX,box.minX)>1.0e-6
                        &&Math.min(support.maxZ,box.maxZ)-Math.max(support.minZ,box.minZ)>1.0e-6)supportTop=support.maxY;
            }
            if(!Double.isFinite(supportTop))continue;
            var contact=new GroundContact(helper.getTick(),tracked.birthPosition(),baby.position(),baby.subspecies().assetId(),
                    baby.subspeciesFlags(),baby.onGround(),baby.verticalCollision,supportTop);
            groundContacts.put(entry.getKey(),contact);
            System.out.println("MCYUKKURI_P0 actual newborn ground contact id="+entry.getKey()+" "+contact);
        }
    }
    private List<String> landingDetails(FamilyData.Brood brood) {
        return brood.children.stream().map(c->{
            var entity=level.getEntity(c.id);
            return "id="+c.id+", ledgerLanded="+c.landed+", firstContact="+groundContacts.get(c.id)
                    +(entity instanceof YukkuriEntity baby?", position="+baby.position()+", currentGround="+baby.onGround()
                            +", awaiting="+baby.family().awaitingLanding()+", flight="+baby.family().flying()
                            +", velocity="+baby.getDeltaMovement()+", model="+baby.subspecies().assetId():", entity="+entity);
        }).toList();
    }
    private void observeFollowing() {
        if(!observingFollow||child==null||!child.isAlive())return;
        var mother=level.getEntity(child.family().mother());var father=level.getEntity(child.family().father());
        if(!(mother instanceof YukkuriEntity m)||!(father instanceof YukkuriEntity f)||!m.isAlive()||!f.isAlive())return;
        boolean following=child.family().active()&&child.family().activity().equals("follow_parent")&&child.getBehaviorId().equals("follow_parent");
        // The setup displacement is excluded; require actual movement during consecutive FOLLOW observations.
        boolean moved=previousFollow&&following&&child.position().distanceToSqr(previousFollowPosition)>1.0e-6;
        double distance=Math.min(child.distanceToSqr(m),child.distanceToSqr(f));
        if(moved) { closestFollowingDistance=Math.min(closestFollowingDistance,distance);if(distance<followingStartDistance-1)approachedParent=true; }
        followTrace.add(new FollowSample(helper.getTick(),child.getUUID(),m.getUUID(),f.getUUID(),child.position().toString(),
                m.position().toString(),f.position().toString(),child.getBehaviorId(),m.getBehaviorId(),f.getBehaviorId(),distance,moved));
        previousFollow=following;previousFollowPosition=child.position();
    }
    private void later(int delay,String name,Runnable action) {
        sequence.thenIdle(delay).thenExecute(()-> {
            try{action.run();}catch(Throwable error){check(name,false,error.toString());}
            P0Control.write("family-checks",checks);
        });
    }
    private void setup() {
        for(int x=10;x<=13;x++)for(int z=0;z<=3;z++)GameTestChunks.setForced(level,x,z,true);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,level.getServer());
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,level.getServer());
        level.setDayTime(6000);level.setWeatherParameters(6000,0,false,false);
        for(int x=160;x<224;x++)for(int z=0;z<56;z++) {
            for(int y=65;y<=75;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.SMOOTH_STONE.defaultBlockState());
        }
        for(int x=173;x<=191;x++)for(int z=16;z<=30;z++)level.setBlockAndUpdate(new BlockPos(x,68,z),Blocks.OAK_PLANKS.defaultBlockState());
        clear();
    }
    private void clear() {
        landingSubjects.clear();groundContacts.clear();
        for(var brood:batches)FamilyData.get(level).cancel(brood);batches.clear();
        level.getEntitiesOfClass(Entity.class,AREA,e->e instanceof YukkuriEntity||e instanceof net.minecraft.world.entity.item.ItemEntity).forEach(Entity::discard);
        level.setBlockAndUpdate(HOME_A,Blocks.AIR.defaultBlockState());level.setBlockAndUpdate(HOME_B,Blocks.AIR.defaultBlockState());
    }
    private void family(YukkuriEntity entity,Consumer<CompoundTag> edit) { var data=entity.family().save();edit.accept(data);entity.family().load(data); }
    private void life(YukkuriEntity entity,Consumer<CompoundTag> edit) { var data=entity.life().save();edit.accept(data);entity.life().load(data); }
    private YukkuriEntity spawn(EntityType<YukkuriEntity> type,double x,double z) {
        var entity=type.create(level);entity.moveTo(x,65,z,0,0);entity.setNoAi(true);entity.setOnGround(true);entity.setPersistenceRequired();
        family(entity,data->{data.putBoolean("Initialized",true);data.putLong("Maturity",24000);data.putLong("Age",24001);data.putLong("Nourishment",24000);data.putLong("Lifespan",72000);});
        life(entity,data->{data.putFloat("Nutrition",90);data.putFloat("Energy",90);data.putFloat("Hygiene",100);data.putInt("RestWait",0);});
        if(!level.addFreshEntity(entity))throw new IllegalStateException("Family fixture spawn rejected");return entity;
    }
    private void pair() {
        pair(ModEntities.MARISA.get());
    }
    private void pair(EntityType<YukkuriEntity> second) {
        clear();a=spawn(ModEntities.REIMU.get(),180.5,20.5);b=spawn(second,182.5,20.5);
        level.setBlockAndUpdate(HOME_A,ModBlocks.NEST.get().defaultBlockState());level.setBlockAndUpdate(HOME_B,ModBlocks.NEST.get().defaultBlockState());
        if(!a.life().assignHome(GlobalPos.of(level.dimension(),HOME_A))||!b.life().assignHome(GlobalPos.of(level.dimension(),HOME_B)))throw new IllegalStateException("Family fixture home failed");
        var data=FamilyData.get(level);data.register(a);data.register(b);
        YukkuriWorldData.get(level).relate(a.getUUID(),b.getUUID(),30);YukkuriWorldData.get(level).relate(b.getUUID(),a.getUUID(),30);
    }
    private FamilyData.Brood conceive(int count) {
        long base=a.getUUID().getMostSignificantBits()^b.getUUID().getLeastSignificantBits();int serial=0;
        while(FamilyData.offspringCount(a.family().adultScale(),base^serial)!=count&&serial<100000)serial++;
        int selected=serial;family(a,tag->tag.putInt("Conceptions",selected));
        var brood=FamilyData.get(level).conceive(a,b,FamilyData.BirthKind.STEM,GlobalPos.of(level.dimension(),HOME_A));
        if(brood==null)throw new IllegalStateException("Eligible pair did not conceive: "+a.life().save()+" / "+a.family().save());
        batches.add(brood);return brood;
    }
    private void rules() {
        for (var variant : List.of(io.github.longshou.mcyukkuri.entity.YukkuriVariant.CHEN,
                io.github.longshou.mcyukkuri.entity.YukkuriVariant.ALICE,
                io.github.longshou.mcyukkuri.entity.YukkuriVariant.PATCHOULI)) {
            later(1, "new species family " + variant, () -> {
                clear();
                level.setBlockAndUpdate(HOME_A,ModBlocks.NEST.get().defaultBlockState());
                level.setBlockAndUpdate(HOME_B,ModBlocks.NEST.get().defaultBlockState());
                a=spawn(ModEntities.forVariant(variant),180.5,20.5);b=spawn(ModEntities.forVariant(variant),182.5,20.5);
                if(!a.life().assignHome(GlobalPos.of(level.dimension(),HOME_A)) || !b.life().assignHome(GlobalPos.of(level.dimension(),HOME_B)))
                    throw new IllegalStateException("New species fixture home failed: " + variant);
                var data=FamilyData.get(level);data.register(a);data.register(b);
                YukkuriWorldData.get(level).relate(a.getUUID(),b.getUUID(),30);YukkuriWorldData.get(level).relate(b.getUUID(),a.getUUID(),30);
                var brood=conceive(5);a.syncFamily();
                for(int i=0;i<5;i++) check(variant+" unborn sync slot "+i,a.broodVariant(i)==variant,a.broodVariant(i));
                var restored=FamilyData.load(data.save(new CompoundTag(),level.registryAccess()),level.registryAccess()).brood(brood.id);
                check(variant+" unborn ledger roundtrip",restored.children.stream().allMatch(c->c.variant==variant),restored.id);
                for(var childVariant:io.github.longshou.mcyukkuri.entity.YukkuriVariant.values()) {
                    var base=FamilyGeometry.childBase(5,4,1,1,1,variant,childVariant);
                    check(variant+" family geometry "+childVariant,Double.isFinite(base.lengthSqr()),base);
                }
                brood.elapsed=brood.duration+40;
                var newborn=data.birth(brood,4,level,new Vec3(176.5,65,27.5));
                check(variant+" birth creates matching entity",newborn!=null&&newborn.getVariant()==variant,newborn);
                var token=a.life().takeAccessory(null);
                var item=io.github.longshou.mcyukkuri.registry.ModItems.accessoryFor(variant);
                check(variant+" own accessory item",net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath().startsWith(variant.assetId()+"_"),item);
                check(variant+" accessory return",token!=null&&a.life().returnAccessory(a.getUUID(),token,null),token);
            });
        }
        later(1,"size distribution",()-> {
            double saved=FamilyConfig.OFFSPRING_SIZE_INFLUENCE.get();
            try {
                FamilyConfig.OFFSPRING_SIZE_INFLUENCE.set(FamilyConfig.DEFAULT_OFFSPRING_SIZE_INFLUENCE);
                int small=0,big=0;boolean legacy=true,bounded=true;
                for(int seed=0;seed<1000;seed++) {
                    small+=FamilyData.offspringCount(.8f,seed);big+=FamilyData.offspringCount(1.2f,seed);
                    for(float scale:new float[]{.5f,.8f,.9f,1,1.1f,1.2f,2}) {
                        var random=new java.util.Random(seed);double probability=.15+.70*Math.clamp((scale-.8)/.4,0,1);
                        int expected=1;for(int i=0;i<4;i++)if(random.nextDouble()<probability)expected++;
                        int actual=FamilyData.offspringCount(scale,seed);
                        legacy&=actual==expected;bounded&=actual>=1&&actual<=5;
                    }
                }
                check("default offspring probability preserves legacy seeded results",legacy,"7 scales / 1000 seeds");
                check("larger mothers shift the count distribution",big>small+1000,small+" / "+big);
                FamilyConfig.OFFSPRING_SIZE_INFLUENCE.set(0.0);
                boolean neutral=true;
                for(int seed=0;seed<1000;seed++) {
                    int count=FamilyData.offspringCount(1,seed);
                    neutral&=count==FamilyData.offspringCount(.5f,seed)&&count==FamilyData.offspringCount(2,seed);
                    bounded&=count>=1&&count<=5;
                }
                check("zero offspring size influence removes size differences",neutral,"same seed, small/standard/large");
                FamilyConfig.OFFSPRING_SIZE_INFLUENCE.set(1.4);
                int strongerSmall=0,strongerBig=0;
                for(int seed=0;seed<1000;seed++) {
                    strongerSmall+=FamilyData.offspringCount(.8f,seed);strongerBig+=FamilyData.offspringCount(1.2f,seed);
                }
                check("higher offspring influence strengthens the size difference",strongerSmall<small&&strongerBig>big,strongerSmall+" / "+strongerBig);
                FamilyConfig.OFFSPRING_SIZE_INFLUENCE.set(4.0);
                boolean capped=true;
                for(int seed=0;seed<1000;seed++)capped&=FamilyData.offspringCount(.5f,seed)==1&&FamilyData.offspringCount(2,seed)==5;
                check("strong influence clamps extreme mothers to 1-5 offspring",capped&&bounded,"limits remain unchanged");
            } finally { FamilyConfig.OFFSPRING_SIZE_INFLUENCE.set(saved); }
        });
        later(1,"recorded bean-body birth",()-> {
            pair();var data=FamilyData.get(level);var brood=conceive(1);
            // Exercise the rare congenital branch deterministically, without changing production odds.
            brood.children.getFirst().subspeciesFlags=3<<4;
            brood.elapsed=brood.duration+40;
            var baby=data.birth(brood,0,level,new Vec3(176.5,65,27.5));
            check("recorded bean newborn retains ancestry and narrower juvenile bounds",baby!=null
                    &&baby.family().mother().equals(a.getUUID())&&baby.family().father().equals(b.getUUID())
                    &&io.github.longshou.mcyukkuri.family.LiveBirthGeometry.bean(baby.subspeciesFlags())
                    &&baby.family().stage()==FamilyLife.Stage.JUVENILE
                    &&Math.abs(baby.growthScale()-io.github.longshou.mcyukkuri.family.GrowthSize.STEM)<.001&&Math.abs(baby.getBbWidth()-.75f*.82f*io.github.longshou.mcyukkuri.family.GrowthSize.STEM)<.001,
                    baby==null?"birth failed":baby.subspecies().save()+" width="+baby.getBbWidth());
        });
        for(int wanted=1;wanted<=5;wanted++) {
            int count=wanted;
            later(1,"birth count "+count,()-> {
                pair();var data=FamilyData.get(level);int before=data.population(level,HOME_A);var brood=conceive(count);int population=data.population(level,HOME_A);
                check(count+" fetus identities fixed at conception",brood.children.size()==count&&brood.children.stream().map(c->c.id).distinct().count()==count,brood.id);
                check(count+" unborn reserve real capacity",population==before+count,population);
                check(count+" parents cannot create a second brood",data.conceive(b,a,FamilyData.BirthKind.STEM,brood.anchor)==null&&data.population(level,HOME_A)==population,"second attempt");
                check(count+" fetus cannot be born early",data.birth(brood,0,level,new Vec3(176.5,65,27.5))==null,brood.elapsed);
                brood.elapsed=brood.duration/2;var serialized=data.save(new CompoundTag(),level.registryAccess());var loaded=FamilyData.load(serialized,level.registryAccess()).brood(brood.id);
                check(count+" native ledger save preserves brood and genes",loaded!=null&&loaded.elapsed==brood.elapsed&&loaded.children.getFirst().id.equals(brood.children.getFirst().id)
                        &&java.util.Arrays.equals(loaded.children.getFirst().traits,brood.children.getFirst().traits),serialized.getInt("Version"));
                brood.elapsed=brood.duration+40;
                for(int i=0;i<count;i++) {
                    if(i==count-1) {
                        UUID last=brood.children.get(i).id;
                        java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> reject=event->{if(event.getEntity().getUUID().equals(last))event.setCanceled(true);};
                        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(reject);
                        try {
                            check(count+" rejected last birth cannot complete the brood",data.birth(brood,i,level,new Vec3(176.5+i,65,27.5))==null&&!brood.complete(),brood.unborn());
                            a.family().tick();
                            check(count+" rejected birth grants no empty stem",!io.github.longshou.mcyukkuri.family.ShedStemDrops.get(level).pending(brood.id)
                                    &&!io.github.longshou.mcyukkuri.family.ShedStemDrops.get(level).delivered(brood.id),brood.id);
                        } finally { net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(reject); }
                    }
                    var baby=data.birth(brood,i,level,new Vec3(176.5+i,66.5,27.5));if(baby==null)throw new IllegalStateException("Birth failed at "+i);
                    landingSubjects.put(baby.getUUID(),new FallingChild(baby,baby.position()));
                    WaddleGameTests.clearGoals(baby); // Keep real gravity and landing ticks, isolate random movement.
                    // Bean bodies keep the registered base width; rounded normal bodies use the selected .8-block mesh.
                    float expectedWidth=io.github.longshou.mcyukkuri.family.LiveBirthGeometry.bean(baby.subspeciesFlags())
                            ?ModEntities.forVariant(baby.getVariant()).getDimensions().width()*.82f*io.github.longshou.mcyukkuri.family.GrowthSize.STEM:.8f*io.github.longshou.mcyukkuri.family.GrowthSize.STEM;
                    check(count+" child "+i+" has actual parents and juvenile bounds",baby.family().mother().equals(a.getUUID())&&baby.family().father().equals(b.getUUID())
                            &&baby.family().stage()==FamilyLife.Stage.JUVENILE&&Math.abs(baby.getBbWidth()-expectedWidth)<.001&&Math.abs(baby.growthScale()-io.github.longshou.mcyukkuri.family.GrowthSize.STEM)<.001,
                            java.util.Map.of("family",baby.family().save(),"model",baby.subspecies().assetId(),"width",baby.getBbWidth(),"expectedWidth",expectedWidth));
                    check(count+" child "+i+" birth idempotent",data.birth(brood,i,level,new Vec3(185,65,27))==null,baby.getUUID());
                    child=baby;
                }
                a.family().tick();
                var stems=io.github.longshou.mcyukkuri.family.ShedStemDrops.get(level);
                a.syncFamily();
                check(count+" successful last spawn retains mother stem until actual landing",a.family().brood()==brood&&a.broodCount()==count&&child.family().awaitingLanding()&&stems.pending(brood.id)&&!stems.ready(brood.id),a.family().save());
                stems.deliver(level.getServer());
                check(count+" airborne children cannot produce a stem item",level.getEntity(io.github.longshou.mcyukkuri.family.ShedStemDrops.itemId(brood.id))==null,brood.id);
                pendingStem=brood;
                check(count+" complete brood leaves the same population",brood.complete()&&data.population(level,HOME_A)==population,data.population(level,HOME_A));
                check(count+" genealogy survives appearance loss",data.related(a.getUUID(),child.getUUID()),child.getUUID());
            });
            later(35,"natural stem waits for actual ground landing "+count,()-> {
                var brood=pendingStem;var stems=io.github.longshou.mcyukkuri.family.ShedStemDrops.get(level);
                check(count+" all children physically landed",brood.children.stream().allMatch(c->c.landed&&groundContacts.containsKey(c.id)
                        &&level.getEntity(c.id) instanceof YukkuriEntity baby&&!baby.family().awaitingLanding()),landingDetails(brood));
                a.family().tick();stems.deliver(level.getServer());
                check(count+" landing removes the maternal empty stem",a.family().brood()==null&&a.broodCount()==0&&!a.hasSpentStem(),a.family().save());
                var dropped=level.getEntity(io.github.longshou.mcyukkuri.family.ShedStemDrops.itemId(brood.id));
                check(count+" natural completion creates one real empty stem",dropped instanceof net.minecraft.world.entity.item.ItemEntity item
                        &&item.getItem().is(io.github.longshou.mcyukkuri.registry.ModItems.YUKKURI_STEM.get())&&item.getItem().getCount()==1,dropped);
                a.family().load(a.family().save());a.family().tick();stems.deliver(level.getServer());
                check(count+" mother reload cannot duplicate a completed stem",stems.delivered(brood.id)
                        &&level.getEntity(io.github.longshou.mcyukkuri.family.ShedStemDrops.itemId(brood.id))==dropped,brood.id);
            });
        }
        later(1,"capacity and qualification",()-> {
            pair();int existing=FamilyData.get(level).population(level,HOME_A);
            for(int i=existing;i<FamilyConfig.POPULATION_CAP.get();i++)spawn(ModEntities.REIMU.get(),170.5+i,25.5);
            check("full area rejects conception without consuming resources",FamilyData.get(level).conceive(a,b,FamilyData.BirthKind.STEM,GlobalPos.of(level.dimension(),HOME_A))==null
                    &&a.life().nutrition()==90&&a.family().conceptions()==0,a.family().save());
            pair();family(a,t->t.putString("Stage","JUVENILE"));
            check("juveniles cannot mate or be introduced",!a.family().canReproduceWith(b)&&!a.family().introduce(b),a.family().stage());
            family(a,t->t.putString("Stage","ADULT"));life(a,t->t.putFloat("Nutrition",20));
            check("hunger rejects reproduction",!a.family().canReproduceWith(b),a.life().nutrition());
            life(a,t->t.putFloat("Nutrition",90));
            check("ordinary pair cannot create house eggs",FamilyData.get(level).conceive(a,b,FamilyData.BirthKind.EGG,GlobalPos.of(level.dimension(),HOME_A))==null,"ecology");
        });
    }
    private void growth() {
        later(1,"growth setup",()-> {
            pair();pregnancy=conceive(1);pregnancy.elapsed=pregnancy.duration;
            child=FamilyData.get(level).birth(pregnancy,0,level,new Vec3(177.5,65,26.5));
            growthBirthFlags=pregnancy.children.getFirst().subspeciesFlags;
            growthStartWidth=child.getBbWidth();growthStartHeight=child.getBbHeight();
            family(child,t->{t.putLong("Maturity",24000);t.putLong("Lifespan",72000);t.putLong("Age",24001);t.putLong("Nourishment",24000);t.putBoolean("AwaitingLanding",false);});
            life(child,t->{t.putFloat("Energy",5);t.putInt("RestWait",0);t.putFloat("Nutrition",90);});child.setNoAi(false);
            check("elapsed age alone does not mature a child",child.family().stage()==FamilyLife.Stage.JUVENILE,child.family().save());
        });
        later(40,"sleep entered",()->check("juvenile enters actual rest",child.life().isSleeping(),child.getBehaviorId()));
        later(300,"wake growth",()-> {
            // Birth reserves the congenital shape. Bean bodies are narrower and taller,
            // so compare actual resized bounds to that identity's dimensional contract.
            boolean bean=((growthBirthFlags>>4)&15)==3;
            var base=child.getType().getDimensions();float scale=child.growthScale()*child.getScale();
            boolean rounded=(growthBirthFlags>>4&15)==0&&!child.getVariant().isHouse();
            float expectedWidth=(rounded?.8f:base.width())*scale*(bean?.82f:1),expectedHeight=(rounded?.6f:base.height())*scale*(bean?1.55f:1);
            check("fed actual wake matures and resizes child",child.family().stage()==FamilyLife.Stage.ADULT&&child.growthScale()>=.8f
                    &&child.subspeciesFlags()==growthBirthFlags&&child.getBbWidth()>growthStartWidth&&child.getBbHeight()>growthStartHeight
                    &&Math.abs(child.getBbWidth()-expectedWidth)<1e-5&&Math.abs(child.getBbHeight()-expectedHeight)<1e-5,
                child.family().save()+" / variant="+child.getVariant()+", birthFlags="+growthBirthFlags+", flags="+child.subspeciesFlags()
                    +", before="+growthStartWidth+"x"+growthStartHeight+", expected="+expectedWidth+"x"+expectedHeight+", actual="+child.getBbWidth()+"x"+child.getBbHeight());
            child.setNoAi(true);family(child,t->{t.putString("Stage","JUVENILE");t.putFloat("Scale",.3f);t.putFloat("AdultScale",1.2f);t.putLong("Age",50000);t.putLong("Nourishment",50000);});
            child.moveTo(176.5,65,25.5,0,0);BlockPos roof=new BlockPos(176,65,25);
            level.setBlockAndUpdate(roof,Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE,SlabType.TOP));
            check("growth waits when enlarged body would hit a ceiling",!child.family().woke()&&child.growthScale()==.3f,child.getBoundingBox());
            level.setBlockAndUpdate(roof,Blocks.AIR.defaultBlockState());
            check("growth resumes in clear space",child.family().woke()&&child.growthScale()==1.2f,child.getBoundingBox());
            check("known parent cannot pair with grown offspring",!a.family().introduce(child),child.family().mother());
        });
    }
    private void natural() {
        later(1,"natural court setup",()-> {
            // This scene checks courtship, ordinary landing and following. A Marisa catch hat also
            // introduces a valid random birth-loss branch, which belongs to the separate hat cases.
            pair(ModEntities.REIMU.get());pregnancy=null;child=null;
            if(a.getUUID().compareTo(b.getUUID())>0){var swap=a;a=b;b=swap;}
            a.setNoAi(false);b.setNoAi(false);a.family().introduce(b);
        });
        // Courtship includes real navigation. Observe conception instead of assuming
        // it always finishes exactly 220 ticks after both goals become available.
        // The unchanged overall 6000-tick GameTest deadline still catches failure.
        sequence.thenWaitUntil(()->helper.assertTrue(a.family().brood()!=null||b.family().brood()!=null,
                "Waiting for actual family goal to conceive: "+a.getBehaviorId()+" / "+a.family().save()+" / "+b.family().save()));
        later(1,"natural court",()-> {
            // UUID order chooses the courtship initiator, not the reproductive mother.
            if(a.family().brood()==null&&b.family().brood()!=null){var swap=a;a=b;b=swap;}
            pregnancy=a.family().brood();check("actual family goal conceives after courtship",pregnancy!=null,a.getBehaviorId()+" / "+a.family().save());
            check("natural birth is observed on its recorded mother",pregnancy!=null&&pregnancy.mother.equals(a.getUUID()),pregnancy==null?"missing":pregnancy.mother);
            if(pregnancy==null)return;batches.add(pregnancy);pregnancy.duration=200;pregnancy.elapsed=20;
            naturalStartTick=helper.getTick();
            life(a,t->t.putFloat("Nutrition",90));life(b,t->t.putFloat("Nutrition",90));
        });
        // Gestation counts eligible ticks, not wall ticks: LIVE delivery pauses for
        // movement or an occupied outlet and each sibling has its own birthTick.
        // Observe real completion within the same overall 6000-tick deadline.
        sequence.thenIdle(800).thenWaitUntil(()->helper.assertTrue(pregnancy!=null&&pregnancy.complete(),
                "Waiting for actual gestation: "+pregnancyDetails()));
        later(1,"natural delivery",()-> {
            check("actual gestation gives birth without a spawn command",pregnancy!=null&&pregnancy.complete(),pregnancyDetails());
            if(pregnancy==null||!pregnancy.complete())return;
            child=(YukkuriEntity)level.getEntity(pregnancy.children.getFirst().id);
        });
        sequence.thenWaitUntil(()->helper.assertTrue(child!=null&&!child.family().awaitingLanding(),
                "Waiting for newborn to land before following: "+(child==null?pregnancyDetails():child.position())));
        later(1,"natural newborn landing",()-> {
            check("newborn has landed and woken before following",child!=null&&!child.family().awaitingLanding(),child==null?"missing":child.position());
            helper.assertTrue(child!=null&&pregnancy.mother.equals(child.family().mother())&&pregnancy.father.equals(child.family().father())
                    &&a.getUUID().equals(child.family().mother())&&b.getUUID().equals(child.family().father()),"Following uses the actual recorded biological parents");
            Vec3 start=null;
            for(var offset:List.of(new Vec3(5,0,0),new Vec3(-5,0,0),new Vec3(0,0,5),new Vec3(0,0,-5))) {
                var candidate=a.position().add(offset);
                if(candidate.distanceToSqr(a.position())<=16||candidate.distanceToSqr(b.position())<=16
                        ||!child.life().dryStand(BlockPos.containing(candidate))
                        ||!level.noCollision(child,child.getBoundingBox().move(candidate.subtract(child.position()))))continue;
                start=candidate;break;
            }
            helper.assertTrue(start!=null,"A clear dry following start exists beyond four blocks from both actual parents");
            child.moveTo(start.x,start.y,start.z,0,0);child.setDeltaMovement(Vec3.ZERO);child.getNavigation().stop();
            child.move(net.minecraft.world.entity.MoverType.SELF,new Vec3(0,-.125,0));
            helper.assertTrue(child.onGround()&&child.verticalCollision&&level.noCollision(child)
                    &&child.distanceToSqr(a)>16&&child.distanceToSqr(b)>16&&child.distanceToSqr(a)<144
                    &&child.life().reachable(a.blockPosition(),false),"Following starts on the real floor, outside both parent thresholds, with a reachable mother");
            life(child,t->t.putFloat("Nutrition",85));
            followTrace.clear();previousFollow=false;previousFollowPosition=child.position();
            // Newborns now crawl at their real small-body gait. Require measurable approach during
            // FOLLOW, rather than the old adult-speed expectation of arriving within three blocks.
            followingStartDistance=Math.min(child.distanceToSqr(a),child.distanceToSqr(b));
            closestFollowingDistance=Double.POSITIVE_INFINITY;approachedParent=false;observingFollow=true;
            observeFollowing();
        });
        later(180,"family following",()-> {
            observeFollowing();observingFollow=false;P0Control.write("family-follow-trace",followTrace);
            check("child approaches a real parent",approachedParent,"closest squared distance during moving FOLLOW="+closestFollowingDistance
                    +", samples="+followTrace.size()+", last="+(followTrace.isEmpty()?"none":followTrace.getLast()));
        });
    }
    private String pregnancyDetails() {
        if(pregnancy==null)return "no brood";
        int last=pregnancy.children.size()-1;
        int next=0;while(next<pregnancy.children.size()&&pregnancy.children.get(next).born)next++;
        String nextAt=next==pregnancy.children.size()?"complete":String.valueOf(pregnancy.duration
                +(pregnancy.kind==FamilyData.BirthKind.LIVE?LiveBirthGeometry.birthTick(next):next*8));
        int completeAt=pregnancy.duration+(pregnancy.kind==FamilyData.BirthKind.LIVE?LiveBirthGeometry.birthTick(last):last*8);
        String outletClear=pregnancy.kind==FamilyData.BirthKind.LIVE&&next<pregnancy.children.size()
                ?String.valueOf(LiveBirthGeometry.clear(a,pregnancy.children.get(next),next)):"not pending";
        return "kind="+pregnancy.kind+" observedTicks="+(helper.getTick()-naturalStartTick)+" mother="+a.getVariant()+" children="+pregnancy.children.size()+" unborn="+pregnancy.unborn()
                +" elapsed="+pregnancy.elapsed+" nextAt="+nextAt+" completeAt="+completeAt
                +" ready="+a.family().readyForDelivery()+" outletClear="+outletClear+" motion="+a.getDeltaMovement()+" behavior="+a.getBehaviorId()
                +" pos="+a.position()+" home="+a.life().homeStand()+" life="+a.life().save()
                +" children="+pregnancy.children.stream().map(c->c.id+":"+FamilyData.get(level).person(c.id).status+":"+level.getEntity(c.id)).toList();
    }
    private void persistence() {
        later(1,"pregnant capture setup",()-> {
            pair();pregnancy=conceive(3);pregnancy.elapsed=123;savedId=a.getUUID();
            token=YukkuriWorldData.get(level).capture(a);
            check("pregnant mother enters single custody",token!=null&&FamilyData.get(level).brood(pregnancy.id).unborn()==3,token);
        });
        later(100,"pregnant capture return",()-> {
            check("carried pregnancy does not advance",pregnancy.elapsed==123,pregnancy.elapsed);
            a=YukkuriWorldData.get(level).release(token,level,new Vec3(180.5,65,20.5),0);
            check("pregnant release preserves mother and fetal identities",a!=null&&a.getUUID().equals(savedId)&&a.family().brood().id.equals(pregnancy.id),a==null?"missing":a.family().save());
            if(a==null)return;a.setNoAi(true);
            var snapshot=new CompoundTag();a.save(snapshot);a.discard();
            a=(YukkuriEntity)EntityType.loadEntityRecursive(snapshot,level,e->e);level.addFreshEntity(a);
            check("entity NBT and world ledger reconnect after load",a.family().brood()!=null&&a.family().brood().elapsed==123,a.family().save());
            pregnancy.elapsed=pregnancy.duration;child=FamilyData.get(level).birth(pregnancy,0,level,new Vec3(178.5,65,27.5));
            child.setNoAi(true);a.hurt(level.damageSources().generic(),100);
            check("maternal death cancels only unborn siblings",pregnancy.cancelled&&child.isAlive()&&FamilyData.get(level).person(child.getUUID()).status==FamilyData.Status.ALIVE
                    &&FamilyData.get(level).person(pregnancy.children.get(1).id).status==FamilyData.Status.LOST,pregnancy.unborn());
            var copy=(YukkuriEntity)EntityType.loadEntityRecursive(snapshot,level,e->e);
            check("stale dead mother copy cannot rejoin",!level.addFreshEntity(copy),savedId);
            long age=child.family().age();var ledger=FamilyData.get(level);child.setNoAi(true);
            check("unknown parent stays unknown",ledger.person(UUID.randomUUID())==null,age);
        });
    }
}
