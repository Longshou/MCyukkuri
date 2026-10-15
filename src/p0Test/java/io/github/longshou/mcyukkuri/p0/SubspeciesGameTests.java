package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class SubspeciesGameTests {
    private record Check(String name, boolean pass) {}
    private final List<Check> checks=new ArrayList<>();
    private ServerLevel level;
    private static final int X=3408;
    private YukkuriEntity owner, shell, ordinary;
    private HatBoatEntity boat;
    private double boatStartY;
    private float healthBeforeCapsize;
    private void check(String name,boolean pass){checks.add(new Check(name,pass));}
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=600,batch="shell_weather")
    public static void shellWeather(GameTestHelper helper){ShellWeatherChecks.run(helper);}
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=700,batch="subspecies")
    public static void behavior(GameTestHelper helper){
        var t=new SubspeciesGameTests();t.level=helper.getLevel();t.setup();
        helper.startSequence().thenWaitUntil(()->helper.assertTrue(t.level.isPositionEntityTicking(new BlockPos(X+7,66,7)),"waiting for subspecies arena"))
            .thenExecute(t::rules).thenExecute(t::surfaces).thenIdle(100).thenExecute(t::afloat).thenIdle(40).thenExecute(t::rescue)
            .thenExecute(()->{
                P0Control.write("subspecies-checks",t.checks);
                t.level.getEntitiesOfClass(Entity.class,new AABB(X,64,0,X+16,80,16),e->e instanceof YukkuriEntity||e instanceof HatBoatEntity||e instanceof net.minecraft.world.entity.item.ItemEntity).forEach(Entity::discard);
                GameTestChunks.setForced(t.level,X/16,0,false);
                var failed=t.checks.stream().filter(c->!c.pass()).toList();if(failed.isEmpty())helper.succeed();else helper.fail(failed.toString());
            });
    }
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=300,batch="subspecies")
    public static void waterHurtsEveryForm(GameTestHelper helper){
        var level=helper.getLevel();int x=X+32;
        GameTestChunks.setForced(level,x/16,0,true);
        for(var p:BlockPos.betweenClosed(x,64,0,x+15,69,15))
            level.setBlockAndUpdate(p,p.getY()==64?Blocks.STONE.defaultBlockState():p.getY()==65?Blocks.WATER.defaultBlockState():Blocks.AIR.defaultBlockState());
        var swimmers=new ArrayList<YukkuriEntity>();
        for(var variant:YukkuriVariant.values())swimmers.add(ModEntities.forVariant(variant).create(level));
        for(var kind:List.of(SubspeciesLife.Kind.SHELL_MARISA,SubspeciesLife.Kind.AQUATIC_MARISA,SubspeciesLife.Kind.WASA_REIMU)){
            var y=(kind==SubspeciesLife.Kind.WASA_REIMU?ModEntities.REIMU:ModEntities.MARISA).get().create(level);
            y.subspecies().set(kind,SubspeciesLife.Congenital.NORMAL);swimmers.add(y);
        }
        for(var type:List.of(ModEntities.REIMU,ModEntities.MARISA))for(var shape:SubspeciesLife.Congenital.values()){
            if(shape==SubspeciesLife.Congenital.NORMAL)continue;
            var y=type.get().create(level);y.subspecies().set(SubspeciesLife.Kind.NORMAL,shape);swimmers.add(y);
        }
        for(var kind:List.of(SubspeciesLife.Kind.NORMAL,SubspeciesLife.Kind.WASA_REIMU)){
            var y=ModEntities.REIMU.get().create(level);y.subspecies().set(kind,SubspeciesLife.Congenital.NORMAL);
            var data=y.subspecies().save();data.putBoolean("Deibu",true);y.subspecies().load(data);swimmers.add(y);
        }
        var dryHealth=new float[swimmers.size()];
        helper.startSequence().thenWaitUntil(()->helper.assertTrue(level.isPositionEntityTicking(new BlockPos(x+7,66,7)),"waiting for water arena"))
            .thenExecute(()->{
                for(int i=0;i<swimmers.size();i++){
                    var y=swimmers.get(i);y.setNoAi(true);y.setNoGravity(true);y.setPersistenceRequired();
                    y.moveTo(x+2.5+i%4*3,65.2,2.5+i/4*3,0,0);level.addFreshEntity(y);
                }
            }).thenIdle(25).thenExecute(()->{
                for(var y:swimmers){
                    helper.assertTrue(y.isInWaterOrBubble(),"body touches water: "+y.getVariant()+"/"+y.subspeciesFlags());
                    helper.assertTrue(y.getHealth()==y.getMaxHealth()-1,"first water damage without wetness or AI: "+y.getVariant()+"/"+y.subspeciesFlags());
                }
            }).thenIdle(20).thenExecute(()->{
                for(int i=0;i<swimmers.size();i++){
                    var y=swimmers.get(i);
                    helper.assertTrue(y.getHealth()==y.getMaxHealth()-2,"water damage repeats: "+y.getVariant()+"/"+y.subspeciesFlags());
                    dryHealth[i]=y.getHealth();y.teleportTo(y.getX(),67,y.getZ());y.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                }
            }).thenIdle(25).thenExecute(()->{
                for(int i=0;i<swimmers.size();i++){
                    var y=swimmers.get(i);
                    helper.assertTrue(!y.isInWaterOrBubble()&&y.getHealth()==dryHealth[i],"immersion damage stops out of water: "+y.getVariant()+"/"+y.subspeciesFlags());
                    y.discard();
                }
                GameTestChunks.setForced(level,x/16,0,false);helper.succeed();
            });
    }
    private void setup(){
        GameTestChunks.setForced(level,X/16,0,true);
        for(var p:BlockPos.betweenClosed(X,64,0,X+15,69,15))level.setBlockAndUpdate(p,p.getY()==64?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
        for(var p:BlockPos.betweenClosed(X,65,0,X+15,65,15))level.setBlockAndUpdate(p,Blocks.GRASS_BLOCK.defaultBlockState());
        for(var p:BlockPos.betweenClosed(X+5,65,5,X+11,65,9))level.setBlockAndUpdate(p,Blocks.WATER.defaultBlockState());
    }
    private YukkuriEntity create(boolean reimu,double x,double z){
        var y=(reimu?ModEntities.REIMU:ModEntities.MARISA).get().create(level);y.moveTo(X+x,66,z,0,0);
        WaddleGameTests.clearGoals(y);y.setPersistenceRequired();level.addFreshEntity(y);return y;
    }
    private void rules(){
        owner=create(false,4.5,7.5);shell=create(false,2.5,3.5);ordinary=create(true,2.5,5.5);
        int patience=ordinary.life().personality().trait(io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait.PATIENCE);
        check("wrong species cannot become shell",!ordinary.subspecies().set(SubspeciesLife.Kind.SHELL_MARISA,SubspeciesLife.Congenital.NORMAL));
        check("shell accepted on marisa",shell.subspecies().set(SubspeciesLife.Kind.SHELL_MARISA,SubspeciesLife.Congenital.NORMAL));
        check("removed shell uses shell item",io.github.longshou.mcyukkuri.registry.ModItems.accessoryFor(shell)==io.github.longshou.mcyukkuri.registry.ModItems.MARISA_SHELL.get());
        check("shell gains water slowly but is not immune",shell.subspecies().waterGain(8)==2&&shell.subspecies().waterGain(2)==1);
        check("normal water response remains",owner.subspecies().waterGain(8)==8);
        ordinary.subspecies().set(SubspeciesLife.Kind.NORMAL,SubspeciesLife.Congenital.BEAN_BODY);
        check("bean mouth follows higher model",io.github.longshou.mcyukkuri.body.MouthPlacement.position(ordinary).y-ordinary.getY()>.55);
        check("bean has taller collision",ordinary.getBbHeight()>.8f&&ordinary.getBbWidth()<.7f);
        check("congenital shape preserves temperament",ordinary.life().personality().trait(io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait.PATIENCE)==patience);
        check("bean and wasa cannot be combined",!ordinary.subspecies().set(SubspeciesLife.Kind.WASA_REIMU,SubspeciesLife.Congenital.BEAN_BODY));
        CompoundTag saved=new CompoundTag();ordinary.saveWithoutId(saved);var restored=ModEntities.REIMU.get().create(level);restored.load(saved);
        check("congenital shape survives entity NBT",restored.subspecies().congenital()==SubspeciesLife.Congenital.BEAN_BODY&&restored.getUUID().equals(ordinary.getUUID()));
        check("congenital does not create wounds",ordinary.body().wounds().isEmpty());
        var first=ModEntities.MARISA.get().create(level);var second=ModEntities.MARISA.get().create(level);UUID stable=UUID.fromString("00000000-0000-0000-0000-000000000099");first.setUUID(stable);second.setUUID(stable);
        SubspeciesLife.onBirth(first,shell,null);SubspeciesLife.onBirth(second,shell,null);
        check("birth uses stable identity",first.subspecies().save().equals(second.subspecies().save()));
        int selected=SubspeciesLife.birthFlags(stable,first.getVariant(),SubspeciesLife.Kind.SHELL_MARISA,null);
        var prepared=ModEntities.MARISA.get().create(level);prepared.setUUID(stable);prepared.subspecies().initializeBirth(selected);
        check("unborn model matches born selection",SubspeciesLife.assetId(first.getVariant(),selected).equals(first.subspecies().assetId()));
        check("stored birth flags reproduce original selection",prepared.subspecies().save().equals(first.subspecies().save()));
        var stored=prepared.subspecies().save().copy();prepared.subspecies().initializeBirth(0);
        check("stored birth flags cannot be applied twice",stored.equals(prepared.subspecies().save()));
        var tall=ModEntities.REIMU.get().create(level);tall.subspecies().initializeBirth(SubspeciesLife.Congenital.BEAN_BODY.ordinal()<<4);
        check("birth flags resize before insertion",tall.getBbHeight()>.8f&&!tall.isAddedToLevel());
        var birth=first.subspecies().save().copy();SubspeciesLife.onBirth(first,null,null);
        check("repeated birth hook cannot reroll",birth.equals(first.subspecies().save()));
        first.subspecies().onNaturalSpawn(MobSpawnType.NATURAL);check("natural hook cannot reroll birth",birth.equals(first.subspecies().save()));
        check("land Marisa never launches a hat boat",HatBoatEntity.createFor(owner,new BlockPos(X+5,65,7))==null);
        owner.subspecies().set(io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.AQUATIC_MARISA,io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Congenital.NORMAL);
        var remains=io.github.longshou.mcyukkuri.entity.RemainsEntity.snapshot(owner,level.damageSources().drown());
        var remainsData=remains.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
        check("drowned remains preserve aquatic ancestry and large hat",remainsData.getInt("SubspeciesFlags")==owner.subspeciesFlags()
                &&HatVessel.hatMultiplier(remainsData.getInt("SubspeciesFlags"))==HatVessel.AQUATIC_SCALE);
        boat=HatBoatEntity.createFor(owner,new BlockPos(X+5,65,7));
        check("launch creates a real boat",boat!=null);
        if(boat==null)throw new IllegalStateException("Boat launch failed");
        boatStartY=boat.getY();
        check("owner is actual passenger",owner.getVehicle()==boat&&boat.getPassengers().size()==1);
        check("hat transferred once",!owner.life().hasAccessory()&&owner.subspecies().boatId().equals(boat.getUUID()));
        check("duplicate launch rejected",HatBoatEntity.createFor(owner,new BlockPos(X+6,65,7))==null);
        check("foreign passenger rejected",!boat.board(ordinary));
        check("shell cannot get hat boat",HatBoatEntity.createFor(shell,new BlockPos(X+5,65,5))==null);
        var provisions=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.APPLE,12);
        check("hat storage transfers only its finite capacity",boat.cargo().store(provisions,12,false)==8
                && provisions.getCount()==4 && boat.cargo().count()==8);
        check("full hat cannot consume more food",boat.cargo().store(provisions,1,false)==0 && provisions.getCount()==4);
        var sticks=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK,2);
        check("paddle is one real stick",boat.cargo().givePaddle(sticks,false)&&sticks.getCount()==1);
        check("second paddle is rejected without consuming it",!boat.cargo().givePaddle(sticks,false)&&sticks.getCount()==1);
        var boatTag=new CompoundTag();boat.saveWithoutId(boatTag);var clone=ModEntities.HAT_BOAT.get().create(level);clone.load(boatTag);
        check("boat identity state saved",clone.ownerYukkuri().equals(owner.getUUID())&&clone.state()==HatBoatEntity.State.AFLOAT);
        check("food and paddle survive a boat reload",clone.cargo().count()==8&&clone.cargo().paddle());
        owner.life().applyEnvironment(-100,0,0);
    }
    private void afloat(){
        check("water surface remains stable",Math.abs(boat.getY()-boatStartY)<.15);
        check("passenger stays aboard",owner.getVehicle()==boat&&boat.isAfloat());
        check("hat flotation keeps body dry",owner.life().wetness()<20);
        check("dry hat passenger takes no immersion damage",owner.getHealth()==owner.getMaxHealth());
        check("dry protection requires a real passenger",boat.protects(owner)&&!boat.protects(ordinary));
        check("distant rescue rejected",!boat.rescue(new BlockPos(X+15,66,15),UUID.randomUUID()));
        healthBeforeCapsize=owner.getHealth();
        boat.capsize();check("capsize ejects real passenger",owner.getVehicle()==null&&boat.state()==HatBoatEntity.State.CAPSIZED);
        check("capsize never changes aquatic ancestry or hat size",HatVessel.aquatic(owner)&&HatVessel.hatMultiplier(owner.subspeciesFlags())==HatVessel.AQUATIC_SCALE);
        check("capsize clears dry protection and cargo",!boat.protects(owner)&&boat.cargo().count()==0&&!boat.cargo().paddle());
    }
    private void surfaces(){
        for(var id:io.github.longshou.mcyukkuri.subspecies.SubspeciesAssets.MODELS){
            var mesh=io.github.longshou.mcyukkuri.body.WoundSurface.get(id);
            try(var in=io.github.longshou.mcyukkuri.body.WoundSurface.class.getResourceAsStream("/assets/mcyukkuri/yukkuri/"+id+".json")){
                var hash=io.github.longshou.mcyukkuri.body.ModelFingerprint.of(in.readAllBytes());
                check(id+" wound surface matches model",hash.matches(mesh.runtimeSourceHash));
            }catch(Exception ex){throw new IllegalStateException(ex);}
            float low=Float.MAX_VALUE,high=-Float.MAX_VALUE;
            for(int i=0;i<mesh.skinCount;i++){low=Math.min(low,mesh.points[i*5+1]);high=Math.max(high,mesh.points[i*5+1]);}
            var origin=new org.joml.Vector3f(0,(low+high)/2,-60);var direction=new org.joml.Vector3f(0,0,1);
            var hit=mesh.ray(origin,direction,mesh.pose("waddle",.2f),List.of());
            check(id+" moving skin receives front hit",hit!=null);
            if(hit!=null){
                var wound=new io.github.longshou.mcyukkuri.body.BodyWound(UUID.randomUUID(),"scratch",hit.triangle(),hit.u(),hit.v(),1,1,0,0);
                check(id+" wound attachment remains finite",mesh.valid(wound)&&mesh.point(wound,mesh.pose("side_bend",.7f),List.of(wound)).isFinite());
            }
        }
    }
    private void rescue(){
        check("capsized body still receives water",owner.life().wetness()>0);
        check("capsized body takes damage before soaking threshold",owner.getHealth()<healthBeforeCapsize);
        var saved=new CompoundTag();owner.saveWithoutId(saved);var restored=ModEntities.MARISA.get().create(level);restored.load(saved);
        check("immersed aquatic identity and hat size survive reload",HatVessel.aquatic(restored)&&HatVessel.hatScale(restored)==HatVessel.hatScale(owner));
        check("capsized boat can be rescued from bank",boat.rescue(new BlockPos(X+4,66,7),UUID.randomUUID()));
        check("rescue returns same hat and clears boat",owner.life().hasAccessory()&&owner.subspecies().boatId()==null&&boat.state()==HatBoatEntity.State.RESCUED);
        check("rescue is single-use",!boat.rescue(new BlockPos(X+4,66,7),UUID.randomUUID()));
        check("experience is acquired separately",owner.subspecies().boatExperienced()&&owner.subspecies().kind()==SubspeciesLife.Kind.AQUATIC_MARISA);
        owner.teleportTo(X+7.5,65.2,7.5);
        check("falling into water never creates a boat",HatBoatEntity.tryLaunchNearby(owner)==null);
    }
}
