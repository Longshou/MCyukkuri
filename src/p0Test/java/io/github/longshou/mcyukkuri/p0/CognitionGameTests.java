package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.cognition.Cognition;
import io.github.longshou.mcyukkuri.cognition.CognitionData;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.WildEventData;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class CognitionGameTests {
    private static final int X = 4096;
    private record Check(String name, boolean pass) {}
    private final List<Check> checks = new ArrayList<>();
    private ServerLevel level;
    private YukkuriEntity learner;
    private FakePlayer player;
    private void check(String name, boolean pass) { checks.add(new Check(name, pass)); }
    private void run(Runnable action) { try { action.run(); } catch (Throwable e) { check("unexpected: " + e, false); } }
    @GameTest(templateNamespace=P0Mod.ID, template="empty", timeoutTicks=700, batch="cognition")
    public static void cognition(GameTestHelper helper) {
        var t = new CognitionGameTests(); t.level = helper.getLevel(); t.floor();
        helper.startSequence().thenWaitUntil(() -> {for(int x=X;x<X+128;x+=16)helper.assertTrue(t.level.isPositionEntityTicking(new BlockPos(x+8,65,8)), "cognition area loading");})
                .thenExecute(() -> t.run(t::rules)).thenExecute(() -> t.run(t::birth))
                .thenExecute(() -> t.run(t::startTeaching)).thenIdle(150)
                .thenExecute(() -> t.run(t::finishTeaching)).thenIdle(110)
                .thenExecute(() -> t.run(t::interruptTeaching)).thenIdle(150)
                .thenExecute(() -> t.run(() -> t.check("distance-interrupted real social lesson grants nothing", Cognition.get(t.learner).learned()==1 && t.learner.life().personality().lessons()==1)))
                .thenExecute(() -> { P0Control.write("cognition-checks",t.checks);t.clean();
                    var failed=t.checks.stream().filter(c -> !c.pass).toList();if(failed.isEmpty())helper.succeed();else helper.fail(failed.toString()); });
    }
    private void floor() {
        level.setDayTime(6000); level.setWeatherParameters(24000,0,false,false);
        for(int chunk=X/16;chunk<X/16+8;chunk++)level.setChunkForced(chunk,0,true);
        for(int x=X;x<X+128;x++)for(int z=0;z<14;z++) {
            level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.SMOOTH_STONE.defaultBlockState());
            for(int y=65;y<72;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
        }
    }
    private void clean() {
        level.getEntitiesOfClass(Entity.class,new AABB(X,63,0,X+128,76,16),e -> e instanceof YukkuriEntity).forEach(Entity::discard);
        for(int chunk=X/16;chunk<X/16+8;chunk++)level.setChunkForced(chunk,0,false);
    }
    private YukkuriEntity spawn(int offset, int innate, int learned, boolean young) {
        var e=ModEntities.REIMU.get().create(level);e.moveTo(X+offset+.5,65,6.5,0,0);e.setOnGround(true);e.setPersistenceRequired();
        if(innate>=0) {var c=new CompoundTag();c.putInt("Innate",innate);c.putInt("Learned",learned);c.putString("Origin","fixture");e.getPersistentData().put(Cognition.KEY,c);}
        var f=e.family().save();f.putBoolean("Initialized",true);f.putString("Stage",young?"JUVENILE":"ADULT");
        f.putLong("Age",young?1000:24001);f.putLong("Nourishment",young?1000:24000);f.putLong("Lifespan",72000);e.family().load(f);
        var life=e.life().save();life.putFloat("Nutrition",95);life.putFloat("Energy",95);life.putFloat("Hygiene",100);life.putFloat("Bladder",0);e.life().load(life);
        var p=e.life().personality().save();PersonalityFixtures.preferences(p,new int[]{50,50,50,50,50,60});e.life().personality().load(p);
        if(!level.addFreshEntity(e))throw new IllegalStateException("cognition fixture spawn refused");return e;
    }
    private WildEventData.Scene scene(YukkuriEntity a,YukkuriEntity b) {
        // A controlled transaction fixture. Actual jump evidence is separately checked by event GameTests.
        return WildEventData.get(level).start(WildEventData.Kind.CARD,"teach",GlobalPos.of(level.dimension(),a.blockPosition()),List.of(a.getUUID(),b.getUUID()),null,level.getGameTime());
    }
    private void rules() {
        var old=spawn(3,-1,0,false);check("old save receives stable default without personality inference",Cognition.get(old).innate()==100&&Cognition.get(old).learned()==0);
        var personality=old.life().personality().save();PersonalityFixtures.preferences(personality,new int[]{0,100,0,100,100,100});old.life().personality().load(personality);
        check("changing ORDER curiosity and other traits does not change ability",Cognition.get(old).ability()==100);
        old.subspecies().set(io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.NORMAL,io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Congenital.BEAN_BODY);
        check("congenital shape does not change ability",Cognition.get(old).ability()==100);
        old.life().takeAccessory(null);check("lost ornament does not change capability",Cognition.get(old).ability()==100);
        Cognition.initializeNatural(old);check("natural initialization cannot reroll an existing identity",Cognition.get(old).innate()==100);
        var a=spawn(7,150,0,false);var b=spawn(8,100,0,true);
        check("exact gap 50 with real juvenile is eligible",Cognition.canTeach(a,b));
        check("adult learner and self teaching reject",!Cognition.canTeach(a,old)&&!Cognition.canTeach(a,a));
        check("teaching probability strict boundary",Cognition.learningSucceeds(b,.4999)&&!Cognition.learningSucceeds(b,.5));
        check("forest probability strict boundary",Cognition.forestFindSucceeds(b,.5166)&&!Cognition.forestFindSucceeds(b,.5167));
        check("invalid probability samples reject",!Cognition.learningSucceeds(b,Double.NaN)&&!Cognition.forestFindSucceeds(b,-1)&&!Cognition.learningSucceeds(b,1));
        check("direct callback without actual classroom rejects",Cognition.commitTeaching(a,b,UUID.randomUUID())==Cognition.TeachingResult.INELIGIBLE);
        var s=scene(a,b);check("real event authority allocates classroom",s!=null);if(s==null)return;
        check("dialogue-only scene cannot pay",Cognition.commitTeaching(a,b,s.id)==Cognition.TeachingResult.INELIGIBLE&&Cognition.get(b).learned()==0);
        s.sourceCount=1;s.responses=1;
        check("teacher jump without learner imitation cannot pay",Cognition.commitTeaching(a,b,s.id)==Cognition.TeachingResult.INELIGIBLE);
        s.responses=3;a.setRemainingFireTicks(20);
        check("danger recheck prevents completed-evidence payout",Cognition.commitTeaching(a,b,s.id)==Cognition.TeachingResult.INELIGIBLE);a.clearFire();
        s.sourceCount=0;check("failed learning roll cannot pay",Cognition.commitTeaching(a,b,s.id)==Cognition.TeachingResult.INELIGIBLE);s.sourceCount=1;
        check("successful completed classroom adds exactly one learned point",Cognition.commitTeaching(a,b,s.id)==Cognition.TeachingResult.APPLIED&&Cognition.get(b).learned()==1&&Cognition.get(b).innate()==100);
        check("gap 49 becomes ineligible",!Cognition.canTeach(a,b));
        check("successful classroom is idempotent despite changed eligibility",Cognition.commitTeaching(a,b,s.id)==Cognition.TeachingResult.DUPLICATE&&Cognition.get(b).ability()==101);
        check("peer teaching never creates waiting skill",b.life().personality().lessons()==0&&!b.life().personality().knowsWait());
        WildEventData.get(level).finish(s,WildEventData.Phase.COMPLETE,"fixture",level.getGameTime());
        var snapshot=Cognition.get(b);var saved=new CompoundTag();b.save(saved);var studentId=b.getUUID();
        b.discard();var restored=(YukkuriEntity)EntityType.loadEntityRecursive(saved,level,e -> e);level.addFreshEntity(restored);
        check("entity unload and reload preserves exact cognition",Cognition.get(restored).equals(snapshot)&&restored.getUUID().equals(studentId));
        restored.getPersistentData().remove(Cognition.KEY);check("stale entity mirror cannot erase world authority",Cognition.get(restored).equals(snapshot));
        var stale=restored.getPersistentData().getCompound(Cognition.KEY).copy();stale.putInt("Learned",900);restored.getPersistentData().put(Cognition.KEY,stale);
        check("inflated stale mirror cannot replace committed world value",Cognition.get(restored).equals(snapshot));
        check("all dimensions share the same capability authority",CognitionData.get(level)==CognitionData.get(level.getServer().getLevel(net.minecraft.world.level.Level.NETHER)));
        try {
            var path=P0Control.DIRECTORY.resolve("cognition-roundtrip.nbt");java.nio.file.Files.createDirectories(path.getParent());
            NbtIo.writeCompressed(CognitionData.get(level).save(new CompoundTag(),level.registryAccess()),path);
            var loaded=CognitionData.load(NbtIo.readCompressed(path,NbtAccounter.unlimitedHeap()),level.registryAccess());
            level.getServer().overworld().getDataStorage().set("mcyukkuri_cognition",loaded);
            check("real disk roundtrip preserves value and successful classroom receipt",Cognition.get(restored).equals(snapshot)&&Cognition.commitTeaching(a,restored,s.id)==Cognition.TeachingResult.DUPLICATE);
        }catch(Exception e){throw new IllegalStateException(e);}
        var custody=YukkuriWorldData.get(level);var token=custody.capture(restored);var owner=UUID.randomUUID();
        check("real carrier and freezer accept learned individual",token!=null&&custody.freezer.store(owner,token));
        var thaw=custody.freezer.retrieve(owner);var released=custody.release(thaw,level,new Vec3(X+10.5,65,6.5),0);
        check("thaw preserves identity and innate learned split",released!=null&&released.getUUID().equals(studentId)&&Cognition.get(released).equals(snapshot));
        check("thaw cannot replay classroom or duplicate captive",released!=null&&Cognition.commitTeaching(a,released,s.id)==Cognition.TeachingResult.DUPLICATE&&custody.release(thaw,level,new Vec3(X+12.5,65,6.5),0)==null);
        if(released!=null) {
            var nether=level.getServer().getLevel(net.minecraft.world.level.Level.NETHER);var destination=new BlockPos(X+10,65,6);nether.getChunkAt(destination);
            var across=released.changeDimension(new net.minecraft.world.level.portal.DimensionTransition(nether,Vec3.atBottomCenterOf(destination),Vec3.ZERO,0,0,net.minecraft.world.level.portal.DimensionTransition.DO_NOTHING));
            check("real dimension transfer preserves UUID capability and receipt",across instanceof YukkuriEntity y&&y.getUUID().equals(studentId)&&Cognition.get(y).equals(snapshot)&&Cognition.commitTeaching(a,y,s.id)==Cognition.TeachingResult.DUPLICATE);
            if(across instanceof YukkuriEntity y) {
                var returned=y.changeDimension(new net.minecraft.world.level.portal.DimensionTransition(level,new Vec3(X+10.5,65,6.5),Vec3.ZERO,0,0,net.minecraft.world.level.portal.DimensionTransition.DO_NOTHING));
                check("return dimension transfer cannot reroll or add learning",returned instanceof YukkuriEntity r&&r.getUUID().equals(studentId)&&Cognition.get(r).equals(snapshot));
                if(returned!=null)returned.discard();else y.discard();
            }
        }
        var capped=spawn(14,999,100,false);check("malformed oversized learned data clamps within native scale",Cognition.get(capped).innate()==999&&Cognition.get(capped).learned()==1);
        var natural=ModEntities.REIMU.get().create(level);Cognition.initializeNatural(natural);int value=Cognition.get(natural).innate();Cognition.initializeNatural(natural);
        check("MC natural distribution is bounded and initializes only once",value>=50&&value<=200&&Cognition.get(natural).innate()==value);
        var actualNatural=ModEntities.REIMU.get().spawn(level,new BlockPos(X+16,65,6),net.minecraft.world.entity.MobSpawnType.NATURAL);
        check("actual natural spawn event initializes independent distribution",actualNatural!=null&&Cognition.get(actualNatural).origin().equals("mc_natural_v1")&&Cognition.get(actualNatural).innate()>=50&&Cognition.get(actualNatural).innate()<=200);
        if(actualNatural!=null)actualNatural.discard();
        var legacy=ModEntities.REIMU.get().create(level);legacy.moveTo(X+18.5,65,6.5);var p=legacy.life().personality().save();p.putInt("Lessons",3);legacy.life().personality().load(p);level.addFreshEntity(legacy);
        check("migration preserves old waiting skill without retrospective gain",legacy.life().personality().knowsWait()&&Cognition.get(legacy).learned()==0&&Cognition.get(legacy).innate()==100);
        for(var e:java.util.Arrays.asList(old,a,released,capped,legacy))if(e!=null)e.discard();
    }
    private void birth() {
        var m=spawn(69,140,200,false);var f=spawn(71,60,400,false);
        var homeA=new BlockPos(X+68,65,3);var homeB=new BlockPos(X+71,65,3);
        for(int x=X+66;x<X+75;x++)for(int z=1;z<8;z++)level.setBlockAndUpdate(new BlockPos(x,68,z),Blocks.OAK_PLANKS.defaultBlockState());
        level.setBlockAndUpdate(homeA,ModBlocks.NEST.get().defaultBlockState());level.setBlockAndUpdate(homeB,ModBlocks.NEST.get().defaultBlockState());
        check("parents claim actual covered nest entrances",m.life().assignHome(GlobalPos.of(level.dimension(),homeA))&&f.life().assignHome(GlobalPos.of(level.dimension(),homeB)));
        var relations=YukkuriWorldData.get(level);relations.relate(m.getUUID(),f.getUUID(),30);relations.relate(f.getUUID(),m.getUUID(),30);
        var families=FamilyData.get(level);var brood=families.conceive(m,f,FamilyData.BirthKind.STEM,GlobalPos.of(level.dimension(),homeA));
        check("real family conception succeeds",brood!=null);if(brood==null)return;
        brood.elapsed=brood.duration;var child=families.birth(brood,0,level,new Vec3(X+68.5,65,8.5));
        check("real family birth calls cognition inheritance",child!=null&&Cognition.get(child).origin().equals("mc_birth_v1"));
        if(child!=null){var v=Cognition.get(child);
            check("inheritance uses actual parents and only their innate values",m.getUUID().equals(v.mother())&&f.getUUID().equals(v.father())&&v.motherInnate()==140&&v.fatherInnate()==60&&v.innate()>=80&&v.innate()<=120&&v.learned()==0);
            check("repeated birth callback cannot reroll",!Cognition.born(child,m.getUUID(),f.getUUID())&&Cognition.get(child).equals(v));
            check("fabricated father callback is refused",!Cognition.born(child,m.getUUID(),UUID.randomUUID()));child.discard();}
        families.cancel(brood);m.discard();f.discard();
        var single=spawn(110,160,90,false);var home=new BlockPos(X+110,65,3);
        for(int x=X+108;x<X+114;x++)for(int z=1;z<8;z++)level.setBlockAndUpdate(new BlockPos(x,68,z),Blocks.OAK_PLANKS.defaultBlockState());
        level.setBlockAndUpdate(home,ModBlocks.NEST.get().defaultBlockState());single.life().assignHome(GlobalPos.of(level.dimension(),home));
        var assisted=families.assist(single);check("real assisted pregnancy keeps father unknown",assisted!=null&&assisted.father==null);
        if(assisted!=null){assisted.elapsed=assisted.duration+io.github.longshou.mcyukkuri.family.LiveBirthGeometry.birthTick(0);
            var baby=families.birth(assisted,0,level,io.github.longshou.mcyukkuri.family.LiveBirthGeometry.position(single,0));
            check("unknown-father live birth initializes capability",baby!=null&&Cognition.get(baby).origin().equals("mc_birth_v1"));
            if(baby!=null){var v=Cognition.get(baby);check("unknown father stays unknown with maternal innate snapshot",v.father()==null&&v.fatherInnate()==-1&&v.mother().equals(single.getUUID())&&v.motherInnate()==160&&v.learned()==0);baby.discard();}
            families.cancel(assisted);}
        single.discard();
    }
    private void startTeaching() {
        learner=spawn(35,100,0,true);player=new FakePlayer(level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"CognitionTeacher"));player.moveTo(learner.getX(),65,8);
        check("untrusted actual player cannot start teaching",!learner.social().teach(player)&&Cognition.get(learner).learned()==0);
        for(int i=0;i<6;i++)learner.life().personality().fed(player.getUUID());
        check("trusted actual player starts Social lesson",learner.social().teach(player));
        check("starting and displaying lesson dialogue grants nothing",Cognition.get(learner).learned()==0);
    }
    private void finishTeaching() {
        check("real AI ticks complete Social lesson and cognition together",learner.life().personality().lessons()==1&&Cognition.get(learner).learned()==1);
        check("duplicate player completion callback grants nothing",Cognition.playerLessonCompleted(learner,player)==Cognition.TeachingResult.DUPLICATE&&Cognition.get(learner).learned()==1);
        check("notebook contains independent observation page",io.github.longshou.mcyukkuri.item.NotebookItem.observe(learner,player).getLast().equals(io.github.longshou.mcyukkuri.cognition.CognitionObservation.page(learner)));
    }
    private void interruptTeaching() {
        player.moveTo(learner.getX(),learner.getY(),learner.getZ()+1);
        check("next actual lesson can begin after cooldown",learner.social().teach(player));
        player.moveTo(learner.getX()+10,learner.getY(),learner.getZ());
    }
}
