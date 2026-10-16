package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatRoute;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Optional remote fixture: inventory ownership across interruptions, reloads, completed care and capsize. */
@PrefixGameTestTemplate(false)
public final class AquaticFamilyGameTests {
    private static final int X = 3472;
    private static void nutrition(YukkuriEntity y, float value) {
        var data=y.life().save(); data.putFloat("Nutrition",value); y.life().load(data);
    }
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=300,batch="aquatic_family")
    public static void realServingsAndComfort(GameTestHelper helper) {
        var level=helper.getLevel(); long daytime=level.getDayTime();
        GameTestChunks.setForced(level,X/16,0,true);
        for(var p:BlockPos.betweenClosed(X,64,0,X+15,69,15))
            level.setBlockAndUpdate(p,p.getY()<=65?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
        for(var p:BlockPos.betweenClosed(X+5,65,5,X+11,65,9)) {
            level.setBlockAndUpdate(p,Blocks.WATER.defaultBlockState());
            level.setBlockAndUpdate(p.above(5),Blocks.STONE.defaultBlockState());
        }
        var parent=ModEntities.MARISA.get().create(level); var child=ModEntities.MARISA.get().create(level); var sibling=ModEntities.MARISA.get().create(level);
        for(var y:java.util.List.of(parent,child,sibling)) {
            WaddleGameTests.clearGoals(y); y.setPersistenceRequired();
            y.subspecies().set(SubspeciesLife.Kind.AQUATIC_MARISA,SubspeciesLife.Congenital.NORMAL);
        }
        parent.moveTo(X+4.5,66,7.5,0,0); child.moveTo(X+3.5,66,7.5,0,0); sibling.moveTo(X+2.5,66,7.5,0,0);
        var kin=child.family().save(); kin.putBoolean("Initialized",true); kin.putString("Stage","BABY");
        kin.putFloat("Scale",.32f); kin.putUUID("Mother",parent.getUUID()); child.family().load(kin);
        var siblingKin=sibling.family().save(); siblingKin.putBoolean("Initialized",true); siblingKin.putString("Stage","BABY");
        siblingKin.putFloat("Scale",.32f); siblingKin.putUUID("Mother",parent.getUUID()); sibling.family().load(siblingKin);
        var boat=new HatBoatEntity[1]; var care=parent.aquaticLife().care();
        helper.startSequence().thenWaitUntil(()->helper.assertTrue(level.isPositionEntityTicking(parent.blockPosition()),"family arena loaded"))
            .thenExecute(()->{
                level.setDayTime(6000); level.addFreshEntity(parent); level.addFreshEntity(child); level.addFreshEntity(sibling);
                boat[0]=HatBoatEntity.createFor(parent,new BlockPos(X+5,65,7));
                helper.assertTrue(boat[0]!=null && boat[0].boardYoung(child) && boat[0].boardYoung(sibling),"real parent and both children aboard");
                boat[0].cargo().store(new ItemStack(Items.APPLE,2),2,false);
                boat[0].cargo().givePaddle(new ItemStack(Items.STICK),false); nutrition(parent,85); nutrition(child,40); nutrition(sibling,85);
            }).thenIdle(3).thenExecute(()->{
                var hat=boat[0];
                helper.assertTrue(hat.protects(parent)&&hat.protects(child),"both bodies are dry passengers");
                helper.assertTrue(HatBoatRoute.restingPlace(hat,parent.aquaticLife().boardingBank())!=null,"quiet surface can be found");
                helper.assertTrue(HatVessel.braidCanServe(parent,child),"the real same-hat close-care boundary reaches this child's assigned day seat");
                helper.assertTrue(!hat.cargo().feed(child)&&hat.cargo().count()==2,"a dependent newborn cannot bypass care by eating boat cargo alone");
                helper.assertTrue(care.tend(hat,false)&&hat.cargo().count()==1&&care.serving().getCount()==1
                        &&child.life().mouth().isEmpty()&&child.life().nutrition()==40,"serving reserves one real item without feeding early");
                helper.assertTrue(care.activity().equals("family_chew"),"newborn care uses the chew activity before a serving is prepared");
                care.cancel(); care.cancel();
                helper.assertTrue(hat.cargo().count()==2&&care.serving().isEmpty()&&care.meals()==0,"interruption refunds exactly once");
                care.tend(hat,false); care.load(care.save()); care.clock(); care.clock();
                helper.assertTrue(hat.cargo().count()==2&&!care.active()&&care.meals()==0,"reload recovers serving without replaying care rewards");
                care.tend(hat,false); parent.setNoAi(true); care.clock(); parent.setNoAi(false);
                helper.assertTrue(hat.cargo().count()==2&&!care.active(),"disabling AI cancels and refunds pending care");
                for(int i=0;i<24;i++)care.tend(hat,false);
                helper.assertTrue(care.serving().is(Items.APPLE)&&!io.github.longshou.mcyukkuri.life.InfantFood.isMash(care.serving())
                        &&child.life().mouth().isEmpty()&&parent.life().nutrition()==85,"parent holds the real apple while chewing; it does not gain nutrition");
                for(int i=0;i<35;i++)care.tend(hat,false);
                helper.assertTrue(care.meals()==1&&hat.cargo().count()==1&&care.serving().isEmpty()
                        &&child.life().mouth().getCount()==1&&io.github.longshou.mcyukkuri.life.InfantFood.isMash(child.life().mouth())&&child.life().nutrition()==40,
                        "forty chew ticks and twenty braid-serving ticks transfer the real serving as mash");
                helper.assertTrue(care.activity().equals("boat_feed_young"),"prepared boat serving restores the established braid-delivery activity");
                care.cancel();
                helper.assertTrue(hat.cargo().count()==1&&child.life().mouth().getCount()==1,"after handoff cancellation cannot duplicate the meal");
            }).thenIdle(70).thenExecute(()->{
                var hat=boat[0];
                helper.assertTrue(child.life().nutrition()>40&&child.life().mouth().isEmpty(),"only actual chewing and swallowing supply nutrition");
                nutrition(parent,85); nutrition(child,85);
                var needs=child.dailyLife().save(); needs.putInt("Affection",60); child.dailyLife().load(needs);
                float wet=child.life().wetness(), hygiene=child.life().hygiene(), health=child.getHealth();
                for(int i=0;i<48;i++)care.tend(hat,false);
                helper.assertTrue(care.comforts()==1&&child.dailyLife().affectionNeed()==25,"completed nuzzle eases loneliness");
                helper.assertTrue(child.life().wetness()==wet&&child.life().hygiene()==hygiene&&child.getHealth()==health
                        &&child.life().nutrition()==85,"nuzzle does not wash, heal or feed");
                level.setDayTime(13000);
            }).thenIdle(20).thenExecute(()->{
                var hat=boat[0];
                helper.assertTrue(HatVessel.braidCanServe(parent,child)&&HatVessel.braidCanServe(parent,sibling),
                        "night seat reflow keeps both protected children within the boat-care boundary");
                for(int i=0;i<80;i++)care.tend(hat,true);
                helper.assertTrue(care.songs()==1&&care.bedtimeReady()&&!parent.life().isSleeping(),"lullaby finishes before physiological sleep");
                for(int i=0;i<80;i++)care.tend(hat,true);
                helper.assertTrue(care.songs()==1,"same bedtime cannot repeatedly farm song rewards");
                nutrition(child,20); for(int i=0;i<59;i++)care.tend(hat,false);
                helper.assertTrue(care.meals()==2&&hat.cargo().count()==0&&io.github.longshou.mcyukkuri.life.InfantFood.isMash(child.life().mouth()),
                        "night-time two-child seating still completes the prepared serving");
                for(int i=0;i<37;i++)care.tend(hat,false);
                hat.cargo().store(new ItemStack(Items.APPLE),1,false); nutrition(sibling,20); care.tend(hat,false);
                helper.assertTrue(care.serving().getCount()==1&&hat.cargo().count()==0,"last serving is really reserved");
                hat.capsize(); care.clock(); care.clock();
                int apples=level.getEntitiesOfClass(ItemEntity.class,new AABB(X,64,0,X+16,75,16),i->i.getItem().is(Items.APPLE))
                        .stream().mapToInt(i->i.getItem().getCount()).sum();
                helper.assertTrue(apples==1&&care.serving().isEmpty()&&!care.active(),"capsize drops the unserved item exactly once");
                helper.assertTrue(parent.subspecies().kind()==SubspeciesLife.Kind.AQUATIC_MARISA,"family care never changes aquatic identity");
                level.getEntitiesOfClass(Entity.class,new AABB(X,64,0,X+16,75,16),e->e instanceof YukkuriEntity||e instanceof HatBoatEntity||e instanceof ItemEntity).forEach(Entity::discard);
                level.setDayTime(daytime); GameTestChunks.setForced(level,X/16,0,false);
            }).thenSucceed();
    }
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=80,batch="aquatic_family")
    public static void juvenileSailingGateAndBrim(GameTestHelper helper) {
        var child=ModEntities.MARISA.get().create(helper.getLevel());
        child.subspecies().set(SubspeciesLife.Kind.AQUATIC_MARISA,SubspeciesLife.Congenital.NORMAL);
        var growth=child.family().save();growth.putBoolean("Initialized",true);growth.putString("Stage","JUVENILE");growth.putFloat("Scale",.34f);child.family().load(growth);
        var voyage=child.aquaticLife().firstVoyage();
        helper.assertTrue(!voyage.readySize()&&!voyage.mayLaunch(),"small juveniles cannot launch their own hats");
        growth.putFloat("Scale",.35f);child.family().load(growth);
        helper.assertTrue(voyage.readySize()&&!voyage.mayLaunch(),"35 percent size still requires the first supervised voyage");
        var saved=voyage.save();saved.putBoolean("InterruptedTrip",true);saved.putLong("Bank",new BlockPos(0,65,0).asLong());
        saved.putLong("Harbor",new BlockPos(1,64,0).asLong());saved.putString("Dimension",helper.getLevel().dimension().location().toString());
        voyage.load(saved);
        helper.assertTrue(!voyage.completed()&&!voyage.mayLaunch()&&voyage.activity().equals("voyage_return"),"reload returns without awarding a successful lesson");
        saved.putBoolean("Completed",true);saved.putBoolean("InterruptedTrip",false);voyage.load(saved);
        helper.assertTrue(voyage.completed()&&voyage.mayLaunch(),"completed milestone persists and unlocks juvenile sailing");
        var v=io.github.longshou.mcyukkuri.subspecies.HatVessel.brimPlace(1.1,java.util.List.of());
        helper.assertTrue(v!=null,"small young has a real brim place");
        for(int i=0;i<32;i++){
            double h=io.github.longshou.mcyukkuri.subspecies.HatVessel.surface(io.github.longshou.mcyukkuri.subspecies.HatVessel.Mode.FLOAT,
                    v.x+1.1*Math.cos(i*Math.PI/16),v.z+1.1*Math.sin(i*Math.PI/16));
            helper.assertTrue(Double.isFinite(h)&&Math.abs(h-io.github.longshou.mcyukkuri.subspecies.HatVessel.FREEBOARD)<.001,"entire young footprint supported above water");
        }
        helper.assertTrue(io.github.longshou.mcyukkuri.subspecies.HatVessel.brimPlace(3,java.util.List.of())==null,"oversized young never hang beyond the brim");
        helper.succeed();
    }
    /** Optional remote regression: five siblings, actual father-boat departure and an interrupted return. */
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=260,batch="aquatic_family")
    public static void firstVoyageFromFather(GameTestHelper helper) {
        var level=helper.getLevel();long day=level.getDayTime();int x=X+32;
        GameTestChunks.setForced(level,x/16,0,true);level.setDayTime(1000);
        for(var p:BlockPos.betweenClosed(x,64,0,x+15,71,15))
            level.setBlockAndUpdate(p,p.getY()<=65?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
        for(var p:BlockPos.betweenClosed(x+4,65,2,x+13,65,13)){
            level.setBlockAndUpdate(p,Blocks.WATER.defaultBlockState());level.setBlockAndUpdate(p.above(5),Blocks.STONE.defaultBlockState());
        }
        var father=ModEntities.MARISA.get().create(level);WaddleGameTests.clearGoals(father);
        father.subspecies().set(SubspeciesLife.Kind.AQUATIC_MARISA,SubspeciesLife.Congenital.NORMAL);
        var adult=father.family().save();adult.putBoolean("Initialized",true);adult.putString("Stage","ADULT");adult.putFloat("Scale",1);father.family().load(adult);
        father.moveTo(x+3.5,66,7.5,0,0);level.addFreshEntity(father);nutrition(father,85);
        var home=HatBoatEntity.createFor(father,new BlockPos(x+5,65,7));helper.assertTrue(home!=null,"father uses his actual hat");
        home.cargo().givePaddle(new ItemStack(Items.STICK),false);
        var shore=father.aquaticLife().shore();
        helper.assertTrue(shore.coastal(new BlockPos(x+3,66,7))&&!shore.coastal(new BlockPos(x,66,7)),"land activity stays in the two-block bank strip");
        var crossing=new net.minecraft.world.level.pathfinder.Path(java.util.List.of(
                new net.minecraft.world.level.pathfinder.Node(x+3,66,8),new net.minecraft.world.level.pathfinder.Node(x,66,7),
                new net.minecraft.world.level.pathfinder.Node(x+3,66,7)),new BlockPos(x+3,66,7),true);
        helper.assertTrue(!shore.allows(crossing),"a coastal destination cannot hide an inland detour");
        var strain=shore.save();strain.putInt("LandTicks",600);shore.load(strain);
        var children=new java.util.ArrayList<YukkuriEntity>();
        for(int i=0;i<5;i++){
            var y=ModEntities.MARISA.get().create(level);WaddleGameTests.clearGoals(y);
            y.subspecies().set(SubspeciesLife.Kind.AQUATIC_MARISA,SubspeciesLife.Congenital.NORMAL);
            var kin=y.family().save();kin.putBoolean("Initialized",true);kin.putString("Stage","JUVENILE");kin.putFloat("Scale",i==0?.35f:.20f);
            kin.putUUID("Father",father.getUUID());y.family().load(kin);y.moveTo(home.getX(),66,home.getZ(),0,0);level.addFreshEntity(y);nutrition(y,85);
            helper.assertTrue(home.boardYoung(y),"each of five siblings can board");children.add(y);
        }
        var child=children.getFirst();child.aquaticLife().giveStick(new ItemStack(Items.STICK),false);
        helper.onEachTick(()->{
            if(!father.isAlive()||father.isRemoved())return;
            if(child.aquaticLife().firstVoyage().active())child.aquaticLife().firstVoyage().tick();
            if(father.aquaticLife().firstVoyage().active())father.aquaticLife().firstVoyage().tick();
        });
        helper.startSequence().thenIdle(40).thenExecute(()->{
            helper.assertTrue(shore.landTicks()<600,"real dry hat passengers recover land strain");
            helper.assertTrue(home.onBrim(child)&&child.aquaticLife().firstVoyage().tryStart(),"lesson starts on the father's brim");
            helper.assertTrue(home.voyageBerth(child)!=null&&!home.hasRoomForYoung(),"five-child manifest retains the pupil's berth");
        }).thenIdle(100).thenExecute(()->{
            var own=HatBoatEntity.owned(child);
            helper.assertTrue(own!=null&&own!=home&&child.getVehicle()==own,"pupil boards its own real hat from the father's boat");
            helper.assertTrue(home.youngAboard()==4&&!home.hasRoomForYoung()&&home.hasRoomFor(child),"away pupil still occupies the fifth place");
            helper.assertTrue(father.getVehicle()==home&&children.subList(1,5).stream().allMatch(y->y.getVehicle()==home),"father and four siblings remain afloat");
            helper.assertTrue(!child.life().hasAccessory()&&own.cargo().paddle()&&!child.aquaticLife().hasStick(),"hat and paddle have one physical owner");
            child.aquaticLife().firstVoyage().load(child.aquaticLife().firstVoyage().save());
        }).thenIdle(70).thenExecute(()->{
            helper.assertTrue(child.getVehicle()==home&&HatBoatEntity.owned(child)==null&&home.youngAboard()==5,"interrupted pupil returns to the reserved berth");
            helper.assertTrue(child.life().hasAccessory()&&child.aquaticLife().hasStick()&&!child.aquaticLife().firstVoyage().completed(),"same hat and paddle return without a milestone reward");
            helper.assertTrue(home.cargo().paddle()&&home.protects(father)&&children.stream().allMatch(home::protects),"all five children remain dry passengers");
            level.getEntitiesOfClass(Entity.class,new AABB(x,64,0,x+16,75,16),e->e instanceof YukkuriEntity||e instanceof HatBoatEntity||e instanceof ItemEntity).forEach(Entity::discard);
            level.setDayTime(day);GameTestChunks.setForced(level,x/16,0,false);
        }).thenSucceed();
    }
    private AquaticFamilyGameTests() {}
}
