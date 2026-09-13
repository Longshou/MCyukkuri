package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
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
    private void check(String name,boolean pass){checks.add(new Check(name,pass));}
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=700,batch="subspecies")
    public static void behavior(GameTestHelper helper){
        var t=new SubspeciesGameTests();t.level=helper.getLevel();t.setup();
        helper.startSequence().thenWaitUntil(()->helper.assertTrue(t.level.isPositionEntityTicking(new BlockPos(X+7,66,7)),"waiting for subspecies arena"))
            .thenExecute(t::rules).thenExecute(t::surfaces).thenIdle(100).thenExecute(t::afloat).thenIdle(40).thenExecute(t::rescue)
            .thenExecute(()->{
                P0Control.write("subspecies-checks",t.checks);
                t.level.getEntitiesOfClass(Entity.class,new AABB(X,64,0,X+16,80,16),e->e instanceof YukkuriEntity||e instanceof HatBoatEntity).forEach(Entity::discard);
                t.level.setChunkForced(X/16,0,false);
                var failed=t.checks.stream().filter(c->!c.pass()).toList();if(failed.isEmpty())helper.succeed();else helper.fail(failed.toString());
            });
    }
    private void setup(){
        level.setChunkForced(X/16,0,true);
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
        boat=HatBoatEntity.createFor(owner,new BlockPos(X+5,65,7));
        check("launch creates a real boat",boat!=null);
        if(boat==null)throw new IllegalStateException("Boat launch failed");
        boatStartY=boat.getY();
        check("owner is actual passenger",owner.getVehicle()==boat&&boat.getPassengers().size()==1);
        check("hat transferred once",!owner.life().hasAccessory()&&owner.subspecies().boatId().equals(boat.getUUID()));
        check("duplicate launch rejected",HatBoatEntity.createFor(owner,new BlockPos(X+6,65,7))==null);
        check("foreign passenger rejected",!boat.board(ordinary));
        check("shell cannot get hat boat",HatBoatEntity.createFor(shell,new BlockPos(X+5,65,5))==null);
        var boatTag=new CompoundTag();boat.saveWithoutId(boatTag);var clone=ModEntities.HAT_BOAT.get().create(level);clone.load(boatTag);
        check("boat identity state saved",clone.ownerYukkuri().equals(owner.getUUID())&&clone.state()==HatBoatEntity.State.AFLOAT);
        owner.life().applyEnvironment(-100,0,0);
    }
    private void afloat(){
        check("water surface remains stable",Math.abs(boat.getY()-boatStartY)<.15);
        check("passenger stays aboard",owner.getVehicle()==boat&&boat.isAfloat());
        check("hat flotation keeps body dry",owner.life().wetness()<20);
        check("distant rescue rejected",!boat.rescue(new BlockPos(X+15,66,15),UUID.randomUUID()));
        boat.capsize();check("capsize ejects real passenger",owner.getVehicle()==null&&boat.state()==HatBoatEntity.State.CAPSIZED);
    }
    private void surfaces(){
        for(var id:io.github.longshou.mcyukkuri.subspecies.SubspeciesAssets.MODELS){
            var mesh=io.github.longshou.mcyukkuri.body.WoundSurface.get(id);
            try(var in=io.github.longshou.mcyukkuri.body.WoundSurface.class.getResourceAsStream("/assets/mcyukkuri/yukkuri/"+id+".json")){
                var hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
                check(id+" wound surface matches model",hash.equals(mesh.runtimeSourceHash));
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
        check("capsized boat can be rescued from bank",boat.rescue(new BlockPos(X+4,66,7),UUID.randomUUID()));
        check("rescue returns same hat and clears boat",owner.life().hasAccessory()&&owner.subspecies().boatId()==null&&boat.state()==HatBoatEntity.State.RESCUED);
        check("rescue is single-use",!boat.rescue(new BlockPos(X+4,66,7),UUID.randomUUID()));
        check("experience is acquired separately",owner.subspecies().boatExperienced()&&owner.subspecies().kind()==SubspeciesLife.Kind.NORMAL);
        owner.teleportTo(X+7.5,65.2,7.5);
        check("falling into water never creates a boat",HatBoatEntity.tryLaunchNearby(owner)==null);
    }
}
