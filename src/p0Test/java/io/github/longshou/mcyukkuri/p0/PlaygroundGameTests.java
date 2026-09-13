package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.play.*;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class PlaygroundGameTests {
    private record Check(String name,boolean pass){}
    private final List<Check> checks=new ArrayList<>();
    private void check(String name,boolean pass){checks.add(new Check(name,pass));}
    private static void floor(ServerLevel level,int start){
        for(int chunk=start>>4;chunk<=(start+79)>>4;chunk++)level.setChunkForced(chunk,0,true);
        level.getEntitiesOfClass(Entity.class,new AABB(start,63,0,start+80,73,16),e->e instanceof YukkuriEntity||e instanceof ItemEntity).forEach(Entity::discard);
        for(int x=start;x<start+80;x++)for(int z=0;z<16;z++){
            for(int y=65;y<72;y++)level.removeBlock(new BlockPos(x,y,z),false);
            level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.STONE.defaultBlockState());
        }
        level.getEntitiesOfClass(ItemEntity.class,new AABB(start,63,0,start+80,73,16)).forEach(Entity::discard);
    }
    private static YukkuriEntity spawn(ServerLevel level,Vec3 pos){
        var e=ModEntities.REIMU.get().create(level);e.moveTo(pos.x,pos.y,pos.z,0,0);e.setPersistenceRequired();e.setOnGround(true);
        var state=e.life().save();state.putFloat("Nutrition",95);state.putFloat("Energy",95);state.putFloat("Hygiene",100);e.life().load(state);
        var family=e.family().save();family.putBoolean("Initialized",true);e.family().load(family);level.addFreshEntity(e);return e;
    }
    private void finish(GameTestHelper helper,String file,int start){
        P0Control.write(file,checks);
        helper.getLevel().getEntitiesOfClass(YukkuriEntity.class,new AABB(start,63,0,start+80,73,16)).forEach(Entity::discard);
        for(int chunk=start>>4;chunk<=(start+79)>>4;chunk++)helper.getLevel().setChunkForced(chunk,0,false);
        var failed=checks.stream().filter(c->!c.pass).toList();if(failed.isEmpty())helper.succeed();else helper.fail(failed.toString());
    }
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=700,batch="play_rules")
    public static void rules(GameTestHelper helper){
        var test=new PlaygroundGameTests();var level=helper.getLevel();floor(level,1600);
        helper.startSequence().thenWaitUntil(()->helper.assertTrue(level.isPositionEntityTicking(new BlockPos(1605,65,8)),"waiting for physical playground area"))
        .thenExecute(()->{
            try{
                var root=new BlockPos(1610,65,8);
                for(var kind:PlayBlock.Kind.values())for(var facing:Direction.Plane.HORIZONTAL){
                    var placed=PlayItem.place(level,root,kind,facing);
                    test.check(kind+" "+facing+" full placement",placed!=null&&PlayItem.positions(root,kind,facing).stream().allMatch(p->PlayBlock.facility(level,p)==placed));
                    int before=level.getEntitiesOfClass(ItemEntity.class,new AABB(root).inflate(4)).stream().mapToInt(e->e.getItem().getCount()).sum();
                    level.removeBlock(root.relative(facing),false);
                    int after=level.getEntitiesOfClass(ItemEntity.class,new AABB(root).inflate(4)).stream().mapToInt(e->e.getItem().getCount()).sum();
                    test.check(kind+" "+facing+" dismantles every part once",after==before+1&&PlayItem.positions(root,kind,facing).stream().allMatch(p->level.getBlockState(p).isAir()));
                    level.getEntitiesOfClass(ItemEntity.class,new AABB(root).inflate(4)).forEach(Entity::discard);
                }
                level.setBlockAndUpdate(root.above(),Blocks.STONE.defaultBlockState());
                test.check("occupied placement leaves obstacle",PlayItem.place(level,root,PlayBlock.Kind.PAPER,Direction.SOUTH)==null&&level.getBlockState(root.above()).is(Blocks.STONE)&&level.getBlockState(root).isAir());
                level.removeBlock(root.above(),false);
                var tube=PlayItem.place(level,root,PlayBlock.Kind.CERAMIC,Direction.SOUTH);var e=spawn(level,PlayGeometry.world(root,Direction.SOUTH,0,0,-2));var peer=spawn(level,PlayGeometry.world(root,Direction.SOUTH,1,0,-2));
                test.check("first route reserves facility",e.playLife().begin(tube));
                test.check("second route denied during lease",!peer.playLife().begin(tube));
                var save=e.playLife().save();e.playLife().load(save);
                test.check("reload cancels route and retains cooldown",!e.playLife().active()&&!e.playLife().begin(tube));
                test.check("reload releases facility for next participant",peer.playLife().begin(tube));
                var token=YukkuriWorldData.get(level).capture(peer);
                test.check("custody cancels facility route",token!=null&&!peer.playLife().active());
                e.playLife().load(new CompoundTag());test.check("custody releases reservation",e.playLife().begin(tube));
                e.setNoAi(true);e.playLife().clock();test.check("disabling AI cancels route",!e.playLife().active());
                e.setNoAi(false);e.playLife().load(new CompoundTag());test.check("cancelled route releases facility",e.playLife().begin(tube));
                tube.dismantle();e.playLife().clock();test.check("removed facility interrupts route",!e.playLife().active());e.discard();
                var pool=PlayItem.place(level,root,PlayBlock.Kind.POOL,Direction.SOUTH);pool.fill();
                pool.loadWithComponents(pool.saveWithFullMetadata(level.registryAccess()),level.registryAccess());
                test.check("pool water persists without expansion",pool.water()==3&&level.getFluidState(root).getAmount()==3&&level.getFluidState(root.east()).isEmpty());
                level.removeBlock(root,false);
                test.check("filled pool removal drains contained water",PlayItem.positions(root,PlayBlock.Kind.POOL,Direction.SOUTH).stream().allMatch(p->level.getBlockState(p).isAir()&&level.getFluidState(p).isEmpty()));
            }catch(Throwable error){test.check("unexpected "+error,false);}
            test.finish(helper,"playground-rule-checks",1600);
        });
    }
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=900,batch="play_live")
    public static void routes(GameTestHelper helper){
        var test=new PlaygroundGameTests();var level=helper.getLevel();floor(level,1696);var entities=new ArrayList<YukkuriEntity>();var trace=new ArrayList<Object>();
        helper.startSequence().thenWaitUntil(()->helper.assertTrue(level.isPositionEntityTicking(new BlockPos(1700,65,8)),"waiting for real playground route area"))
        .thenExecute(()->{
            int i=0;for(var kind:PlayBlock.Kind.values()){
                var root=new BlockPos(1702+i*12,65,8);var facility=PlayItem.place(level,root,kind,Direction.SOUTH);if(kind==PlayBlock.Kind.POOL)facility.fill();
                var e=spawn(level,PlayGeometry.world(root,Direction.SOUTH,kind==PlayBlock.Kind.POOL?-.95:0,0,kind==PlayBlock.Kind.SLIDE?-2.8:kind==PlayBlock.Kind.POOL?-1.2:-2));
                test.check(kind+" live route starts",e.playLife().begin(facility));entities.add(e);i++;
            }
        }).thenExecuteFor(650,()->{
            if(level.getGameTime()%20==0)trace.add(entities.stream().map(e->java.util.Map.of("id",e.getId(),"x",e.getX(),"y",e.getY(),"z",e.getZ(),"activity",e.playLife().activity(),"bounces",e.playLife().bounces(),"complete",e.playLife().completed())).toList());
        }).thenExecute(()->{
            for(int i=0;i<entities.size();i++){var e=entities.get(i);var kind=PlayBlock.Kind.values()[i];
                test.check(kind+" real route completed",e.playLife().completed());test.check(kind+" real route costs energy",e.life().energy()<94);test.check(kind+" stops without injury",!e.playLife().active()&&e.body().wound()==0);
                if(kind==PlayBlock.Kind.TRAMPOLINE)test.check("three physical trampoline launches",e.playLife().bounces()==3);
            }
            P0Control.write("playground-routes-trace",trace);test.finish(helper,"playground-live-checks",1696);
        });
    }
}
