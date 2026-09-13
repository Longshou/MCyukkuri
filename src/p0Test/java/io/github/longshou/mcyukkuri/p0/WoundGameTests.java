package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.body.*;
import io.github.longshou.mcyukkuri.entity.*;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3f;
import java.util.*;

@PrefixGameTestTemplate(false)
public final class WoundGameTests {
    private record Check(String name,boolean pass){}
    private final List<Check> checks=new ArrayList<>();
    private void check(String name,boolean pass){checks.add(new Check(name,pass));}
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=1600,batch="wounds")
    public static void wounds(GameTestHelper helper){var test=new WoundGameTests();var level=helper.getLevel();
        for(int x=56;x<=59;x++)level.setChunkForced(x,0,true);
        helper.startSequence().thenWaitUntil(()->helper.assertTrue(level.isPositionEntityTicking(new net.minecraft.core.BlockPos(908,65,6)),"Wound area is ticking"))
            .thenExecute(()->{try{test.run(level);}catch(Throwable error){error.printStackTrace();test.check("Unexpected: "+error,false);}finally{
                level.getEntitiesOfClass(Entity.class,new AABB(896,60,0,944,80,16),e->e.getTags().contains("wound_test")||e instanceof ItemEntity).forEach(Entity::discard);
                for(int x=56;x<=59;x++)level.setChunkForced(x,0,false);
            }P0Control.write("wound-checks",test.checks);var failures=test.checks.stream().filter(c->!c.pass).toList();if(failures.isEmpty())helper.succeed();else helper.fail(failures.toString());});
    }
    static BodyWound at(YukkuriEntity e,String kind,Vector3f origin,Vector3f direction,int severity){var mesh=WoundSurface.get(e.getVariant().assetId());var hit=mesh.ray(origin,direction,null,List.of());if(hit==null)throw new IllegalStateException("No surface at "+origin+" for "+e.getVariant());return new BodyWound(UUID.randomUUID(),kind,hit.triangle(),hit.u(),hit.v(),severity,e.getVariant().isHouse()?.35f:1,0,0);}
    private YukkuriEntity spawn(ServerLevel level,YukkuriVariant variant){var e=ModEntities.forVariant(variant).create(level);e.moveTo(908,65,6,180,0);e.yBodyRot=e.yBodyRotO=180;e.setNoAi(true);e.setNoGravity(true);e.addTag("wound_test");level.addFreshEntity(e);return e;}
    private void run(ServerLevel level){
        for(var variant:YukkuriVariant.values()){var e=spawn(level,variant);var mesh=WoundSurface.get(variant.assetId());float y=variant.isHouse()?1.4f:2.2f;
            var front=at(e,"tear",new Vector3f(0,y,-30),new Vector3f(0,0,1),2);var back=at(e,"tear",new Vector3f(0,y,30),new Vector3f(0,0,-1),2);
            e.body().addWound(front);e.body().addWound(back);check(variant+" two independent wounds sync immediately",e.visibleWounds().equals(e.body().wounds())&&e.body().wounds().size()==2);
            var save=new CompoundTag();e.saveWithoutId(save);var restored=ModEntities.forVariant(variant).create(level);restored.load(save);
            check(variant+" entity NBT preserves identity and coordinates",restored.body().wounds().equals(e.body().wounds())&&restored.visibleWounds().equals(e.body().wounds()));
            for(String clip:List.of("idle","side_bend","stretch_shape","waddle","press"))for(float phase:new float[]{0,.45f,.9f}){
                var pose=mesh.pose(clip,phase);var p=mesh.point(front,pose,e.body().wounds());var a=mesh.posed(front.triangle(),pose,e.body().wounds());var n=mesh.posed(front.triangle()+1,pose,e.body().wounds()).sub(a).cross(mesh.posed(front.triangle()+2,pose,e.body().wounds()).sub(a)).normalize();
                var hit=mesh.ray(new Vector3f(p).fma(2,n),new Vector3f(n).negate(),pose,e.body().wounds());check(variant+" "+clip+" "+phase+" inverse posed skin",hit!=null&&mesh.point(hit,pose,e.body().wounds()).distance(p)<.015f);
            }
            for(String contact:List.of("NONE","SUPINE","FIXED","GRIP","ROLL","HOOK")){e.syncOperation(contact,147);e.yBodyRot=63;var outlet=WoundPlacement.outlet(e,front);var inverse=WoundPlacement.transform(e).invert().transformPosition(outlet.subtract(e.position()).toVector3f());check(variant+" "+contact+" release follows scale and contact transform",inverse.distance(mesh.point(front,WoundPlacement.pose(e),e.body().wounds()))<.02f);}
            e.syncOperation("NONE",0);e.yBodyRot=180;
            check(variant+" first actual outlet emits",e.body().releaseFilling("wound",front));check(variant+" second actual outlet emits",e.body().releaseFilling("wound",back));
            var drops=level.getEntitiesOfClass(ItemEntity.class,e.getBoundingBox().inflate(3),d->FillingResource.isFilling(d.getItem())&&FillingResource.data(d.getItem()).hasUUID("Source")&&FillingResource.data(d.getItem()).getUUID("Source").equals(e.getUUID()));
            check(variant+" released items carry distinct outlet identities",drops.size()==2&&drops.stream().map(d->FillingResource.data(d.getItem()).getUUID("WoundId")).distinct().count()==2);
            check(variant+" server drop starts at actual surface",drops.stream().allMatch(d->{var id=FillingResource.data(d.getItem()).getUUID("WoundId");var w=e.body().wounds().stream().filter(v->v.id().equals(id)).findFirst().orElseThrow();return d.position().distanceTo(WoundPlacement.outlet(e,w))<.04;}));
            var before=e.body().save();e.body().load(before);check(variant+" emitted quantities survive reload",e.body().wounds().stream().allMatch(w->w.emitted()==1)&&e.body().filling()==10);
            var life=e.life().save();life.putFloat("Nutrition",90);life.putFloat("Hygiene",90);e.life().load(life);e.setNoAi(false);check(variant+" bandage accepted",e.body().treat(false));
            for(int i=0;i<800;i++){e.body().tick();e.setHealth(e.getMaxHealth());}
            check(variant+" bandage heals both wounds without more drops",e.body().wounds().isEmpty()&&e.body().filling()==10);
            e.setNoAi(true);e.body().addWound(front);for(int i=0;i<12;i++)e.body().addWound(new BodyWound(UUID.randomUUID(),"scratch",back.triangle(),back.u(),back.v(),1,back.size(),0,0));
            check(variant+" capacity preserves original anchor identity",e.body().wounds().size()==8&&e.body().wounds().getFirst().id().equals(front.id()));
            while(e.body().filling()>0)e.body().releaseFilling("wound",e.body().wounds().getFirst());check(variant+" finite filling blocks additional releases",!e.body().releaseFilling("wound",e.body().wounds().getFirst()));
            e.body().restorePhysical();check(variant+" juice clears all locations and synchronizes",e.body().wounds().isEmpty()&&e.visibleWounds().isEmpty()&&e.body().filling()==12);
            CompoundTag legacy=new CompoundTag();legacy.putInt("Wound",3);legacy.putInt("Filling",9);e.body().load(legacy);check(variant+" old scalar saves migrate",e.body().wounds().size()==1&&e.body().wound()==3&&e.body().filling()==9);
            for(int oldWound:new int[]{1,8}){var mixed=new CompoundTag();mixed.putInt("Wound",oldWound);mixed.putInt("Burns",8);e.body().load(mixed);var migrated=e.body().save();e.body().load(migrated);
                check(variant+" mixed legacy "+oldWound+"/8 preserves both totals through reload",e.body().wound()==oldWound&&e.body().burns()==8&&e.body().wounds().size()<=6&&e.body().wounds().stream().map(mesh::anchor).distinct().count()==e.body().wounds().size());}
            e.discard();drops.forEach(Entity::discard);
        }
        var e=spawn(level,YukkuriVariant.REIMU);var player=net.neoforged.neoforge.common.util.FakePlayerFactory.get(level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"WoundAim"));
        var target=e.position().add(0,2.2/16,-4.8/16);player.moveTo(target.x,target.y-player.getEyeHeight(),target.z-2,0,0);player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD));
        check("Accepted real damage creates a tear",e.hurt(e.damageSources().playerAttack(player),2)&&e.body().wounds().size()==1&&e.body().wounds().getFirst().kind().equals("tear"));
        int count=e.body().wounds().size();e.hurt(e.damageSources().playerAttack(player),2);check("Rejected duplicate hit creates no extra wound",e.body().wounds().size()==count);
        e.invulnerableTime=0;e.hurt(e.damageSources().drown(),1);check("Drowning creates no external wound",e.body().wounds().size()==count);
        e.discard();e=spawn(level,YukkuriVariant.REIMU);e.hurt(e.damageSources().inFire(),4);e.clearFire();
        check("Four-point fire hit records two scorch grades",e.body().burns()==2&&e.body().wounds().size()==1&&e.body().wounds().getFirst().severity()==2);
        var clean=e.life().save();clean.putFloat("Nutrition",90);clean.putFloat("Hygiene",90);e.life().load(clean);e.setNoAi(false);
        for(int i=0;i<800;i++){e.body().tick();e.setHealth(e.getMaxHealth());}
        check("Scorch heals fully after its scalar would previously reach zero",e.body().burns()==0&&e.body().wounds().isEmpty());e.setNoAi(true);
        var front=at(e,"tear",new Vector3f(0,2.2f,-30),new Vector3f(0,0,1),1);
        for(int i=0;i<8;i++)e.body().addWound(new BodyWound(UUID.randomUUID(),"tear",front.triangle(),front.u(),front.v(),1,1,0,0));
        var last=e.body().wounds().getLast();var hot=new net.minecraft.world.item.ItemStack(io.github.longshou.mcyukkuri.item.OperationToolItem.Tool.HEATED_AWL.item());net.minecraft.world.item.component.CustomData.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA,hot,t->t.putLong("HeatUntil",level.getGameTime()+200));player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,hot);e.setHealth(e.getMaxHealth());e.invulnerableTime=0;e.hurt(e.damageSources().playerAttack(player),2);
        check("Full-capacity heated hit never rewrites an unrelated last wound",e.body().wounds().getLast().equals(last));
        for(var variant:YukkuriVariant.values())for(boolean slab:new boolean[]{false,true}){
            var bottom=spawn(level,variant);bottom.moveTo(908.5,slab?64.5:65,6.5,180,0);bottom.yBodyRot=180;
            var floor=new net.minecraft.core.BlockPos(908,64,6);level.setBlockAndUpdate(floor,(slab?net.minecraft.world.level.block.Blocks.STONE_SLAB:net.minecraft.world.level.block.Blocks.STONE).defaultBlockState());
            var wound=at(bottom,"tear",new Vector3f(0,-30,0),new Vector3f(0,1,0),1);bottom.body().addWound(wound);boolean emitted=bottom.body().releaseFilling("wound",wound);
            var drops=level.getEntitiesOfClass(ItemEntity.class,bottom.getBoundingBox().inflate(2),d->FillingResource.isFilling(d.getItem())&&FillingResource.data(d.getItem()).hasUUID("Source")&&FillingResource.data(d.getItem()).getUUID("Source").equals(bottom.getUUID()));
            check(variant+" bottom outlet clears "+(slab?"slab":"full block"),emitted&&drops.size()==1&&!level.getBlockCollisions(drops.getFirst(),drops.getFirst().getBoundingBox()).iterator().hasNext());
            bottom.discard();drops.forEach(Entity::discard);level.setBlockAndUpdate(floor,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        }
    }
}
