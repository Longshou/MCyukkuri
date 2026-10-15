package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatRoute;
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
        var parent=ModEntities.MARISA.get().create(level); var child=ModEntities.MARISA.get().create(level);
        for(var y:java.util.List.of(parent,child)) {
            WaddleGameTests.clearGoals(y); y.setPersistenceRequired();
            y.subspecies().set(SubspeciesLife.Kind.AQUATIC_MARISA,SubspeciesLife.Congenital.NORMAL);
        }
        parent.moveTo(X+4.5,66,7.5,0,0); child.moveTo(X+3.5,66,7.5,0,0);
        var kin=child.family().save(); kin.putBoolean("Initialized",true); kin.putString("Stage","BABY");
        kin.putFloat("Scale",.32f); kin.putUUID("Mother",parent.getUUID()); child.family().load(kin);
        var boat=new HatBoatEntity[1]; var care=parent.aquaticLife().care();
        helper.startSequence().thenWaitUntil(()->helper.assertTrue(level.isPositionEntityTicking(parent.blockPosition()),"family arena loaded"))
            .thenExecute(()->{
                level.setDayTime(6000); level.addFreshEntity(parent); level.addFreshEntity(child);
                boat[0]=HatBoatEntity.createFor(parent,new BlockPos(X+5,65,7));
                helper.assertTrue(boat[0]!=null && boat[0].boardYoung(child),"real parent and child aboard");
                boat[0].cargo().store(new ItemStack(Items.APPLE,2),2,false);
                boat[0].cargo().givePaddle(new ItemStack(Items.STICK),false); nutrition(child,40);
            }).thenIdle(3).thenExecute(()->{
                var hat=boat[0];
                helper.assertTrue(hat.protects(parent)&&hat.protects(child),"both bodies are dry passengers");
                helper.assertTrue(HatBoatRoute.restingPlace(hat,parent.aquaticLife().boardingBank())!=null,"quiet surface can be found");
                helper.assertTrue(care.tend(hat,false)&&hat.cargo().count()==1&&care.serving().getCount()==1
                        &&child.life().mouth().isEmpty()&&child.life().nutrition()==40,"serving reserves one real item without feeding early");
                care.cancel(); care.cancel();
                helper.assertTrue(hat.cargo().count()==2&&care.serving().isEmpty()&&care.meals()==0,"interruption refunds exactly once");
                care.tend(hat,false); care.load(care.save()); care.clock(); care.clock();
                helper.assertTrue(hat.cargo().count()==2&&!care.active()&&care.meals()==0,"reload recovers serving without replaying care rewards");
                care.tend(hat,false); parent.setNoAi(true); care.clock(); parent.setNoAi(false);
                helper.assertTrue(hat.cargo().count()==2&&!care.active(),"disabling AI cancels and refunds pending care");
                for(int i=0;i<24;i++)care.tend(hat,false);
                helper.assertTrue(care.meals()==1&&hat.cargo().count()==1&&care.serving().isEmpty()
                        &&child.life().mouth().getCount()==1&&child.life().nutrition()==40,"handoff transfers custody to the real meal");
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
                for(int i=0;i<80;i++)care.tend(hat,true);
                helper.assertTrue(care.songs()==1&&care.bedtimeReady()&&!parent.life().isSleeping(),"lullaby finishes before physiological sleep");
                for(int i=0;i<80;i++)care.tend(hat,true);
                helper.assertTrue(care.songs()==1,"same bedtime cannot repeatedly farm song rewards");
                nutrition(child,20); care.tend(hat,false);
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
        var growth=child.family().save();growth.putBoolean("Initialized",true);growth.putString("Stage","JUVENILE");growth.putFloat("Scale",.49f);child.family().load(growth);
        var voyage=child.aquaticLife().firstVoyage();
        helper.assertTrue(!voyage.readySize()&&!voyage.mayLaunch(),"small juveniles cannot launch their own hats");
        growth.putFloat("Scale",.50f);child.family().load(growth);
        helper.assertTrue(voyage.readySize()&&!voyage.mayLaunch(),"half size still requires the first supervised voyage");
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
    private AquaticFamilyGameTests() {}
}
