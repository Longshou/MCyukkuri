package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class PersonalityDevelopmentGameTests {
    private record Check(String name,boolean pass) {}
    private static void check(List<Check> checks,String name,boolean pass){checks.add(new Check(name,pass));}
    private static void finish(GameTestHelper h,String report,List<Check> checks){P0Control.write(report,checks);h.assertTrue(checks.stream().allMatch(Check::pass),checks.stream().filter(c->!c.pass).toList().toString());h.succeed();}
    private static YukkuriPersonality personality(int self,int aggression,int attachment,int control){
        var p=new YukkuriPersonality(new UUID(1,2));var tag=p.save();tag.putIntArray("DispositionInnate",new int[]{self,aggression,attachment,control});p.load(tag);return p;
    }
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=100,batch="personality_development")
    public static void temperamentAndEnvironment(GameTestHelper h){
        var checks=new ArrayList<Check>();UUID player=new UUID(3,4);var old=personality(50,50,50,50);old.fed(player);
        var legacy=old.save();legacy.putInt("Version",1);legacy.remove("DispositionInnate");legacy.putIntArray("Innate",new int[]{35,80,95,20,70,90});legacy.putIntArray("Experience",new int[]{1,2,-8,3,4,-2});old.load(legacy);
        check(checks,"legacy care becomes selfishness with its signed experience",old.selfishness()==77);
        check(checks,"legacy sociability becomes attachment",old.attachment()==82);
        check(checks,"legacy order and patience combine into self-control",old.selfControl()==81);
        check(checks,"boldness never becomes aggression",old.aggression()==50&&old.trait(YukkuriPersonality.Trait.BOLDNESS)==87);
        check(checks,"migration preserves trust and fear",old.trust(player)==8&&old.fear(player)==0);
        var saved=old.save();old.load(saved);check(checks,"v2 migration is idempotent",old.save().equals(saved));
        var generous=personality(20,50,50,50);var selfish=personality(80,50,50,50);
        check(checks,"selfishness changes sharing choice under identical hunger",generous.shareScore(70,45,false,0)>=50&&selfish.shareScore(70,45,false,0)<50);
        check(checks,"selfishness also changes contested-food waiting",generous.queueDuration(60)>selfish.queueDuration(60));
        check(checks,"starvation overrides social waiting and giving",generous.queueDuration(10)==0&&generous.shareScore(10,0,true,100)==0);
        var calm=personality(20,10,50,50);var aggressive=personality(20,90,50,50);
        check(checks,"aggression changes escalation independently of boldness",calm.escalationScore()<aggressive.escalationScore());
        check(checks,"aggression also changes protective intervention",!calm.protects()&&aggressive.protects());
        var alone=personality(50,50,10,50);var attached=personality(50,50,90,50);
        check(checks,"attachment changes companion-seeking threshold",alone.affectionThreshold()>attached.affectionThreshold());
        check(checks,"attachment also changes approach without inventing trust",alone.approachScore(player)<attached.approachScore(player)&&alone.trust(player)==attached.trust(player));
        var impatient=personality(50,50,50,10);var patient=personality(50,50,50,90);
        check(checks,"self-control changes queue persistence",patient.queueDuration(60)>impatient.queueDuration(60));
        check(checks,"self-control also changes teaching duration",patient.lessonDuration()<impatient.lessonDuration());
        var p=personality(50,50,50,50);int initial=p.selfishness();for(int i=0;i<1000;i++)p.fed(player);
        check(checks,"repeated feeding cannot immediately alter temperament",p.selfishness()==initial);
        check(checks,"repeated feeding gives at most four environmental points",p.environment().friendliness()==54);
        p.hurt(player);int after=p.environment().friendliness();p.hurt(player);p.fed(player);
        check(checks,"one attack is deduplicated and feeding cannot erase hostility",after==42&&p.environment().friendliness()==after);
        for(int i=0;i<8;i++){for(int tick=0;tick<1200;tick++)p.tick();p.experienced(YukkuriPersonality.Disposition.SELFISHNESS,-1);}
        check(checks,"long spaced experience changes only slowly",p.selfishness()==initial-1);
        var neutral=personality(50,50,50,50);for(int i=0;i<24000;i++)neutral.tick();
        check(checks,"peaceful isolation stays neutral",neutral.environment().friendliness()==50);
        neutral.hurt(null);check(checks,"environmental damage is not invented social hostility",neutral.environment().friendliness()==50);
        neutral.fed(player);int known=neutral.trust(player);for(int i=0;i<80;i++){for(int t=0;t<40;t++)neutral.tick();neutral.attacked(UUID.randomUUID(),false);}
        check(checks,"non-player attacks never occupy or evict player memories",neutral.trust(player)==known&&neutral.save().getList("Players",10).size()==1&&neutral.environment().friendliness()<30);
        int aggressiveBefore=neutral.aggression();for(int i=0;i<8;i++){for(int t=0;t<1200;t++)neutral.tick();neutral.attacked(UUID.randomUUID(),false);}
        check(checks,"spaced real hostile experiences slowly affect aggression",neutral.aggression()>aggressiveBefore);
        var snapshot=p.save();var clone=personality(0,0,0,0);clone.load(snapshot);check(checks,"environment event history and experience cooldown survive reload",clone.save().equals(snapshot));
        finish(h,"personality-development-checks",checks);
    }
    private static YukkuriEntity animal(GameTestHelper h){
        var e=ModEntities.REIMU.get().create(h.getLevel());var pos=h.absolutePos(new BlockPos(3,2,3));e.moveTo(pos.getX()+.5,pos.getY(),pos.getZ()+.5,0,0);
        e.setNoGravity(true);e.setOnGround(true);e.tickCount=20;
        var life=e.life().save();life.putFloat("Nutrition",90);life.putFloat("Energy",90);life.putFloat("Hygiene",90);e.life().load(life);
        var family=e.family().save();family.putBoolean("Initialized",true);family.putString("Stage","ADULT");family.putFloat("Scale",1);family.putFloat("AdultScale",1);family.putLong("Age",48001);family.putLong("Nourishment",72000);e.family().load(family);
        var environment=e.life().personality().environment().save();environment.putFloat("Friendliness",20);e.life().personality().environment().load(environment);
        var form=e.subspecies().save();form.putInt("Susceptibility",0);form.putInt("DeibuExposure",23999);e.subspecies().load(form);return e;
    }
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=100,batch="personality_development")
    public static void acquiredDevelopment(GameTestHelper h){
        var checks=new ArrayList<Check>();var e=animal(h);UUID id=e.getUUID();var personal=e.life().personality().save();float width=e.getBbWidth();
        e.setNoAi(true);e.subspecies().tick();check(checks,"NoAI pauses exposure",e.subspecies().deibuExposure()==23999&&!e.subspecies().deibu());e.setNoAi(false);
        var life=e.life().save();life.putFloat("Nutrition",74);e.life().load(life);e.subspecies().tick();check(checks,"good nutrition is jointly required and missed time decays",e.subspecies().deibuExposure()==23997&&!e.subspecies().deibu());
        e=animal(h);var environment=e.life().personality().environment().save();environment.putFloat("Friendliness",30);e.life().personality().environment().load(environment);e.subspecies().tick();check(checks,"friendliness threshold is strict",!e.subspecies().deibu()&&e.subspecies().deibuExposure()==23997);
        e=animal(h);var family=e.family().save();family.putString("Stage","JUVENILE");e.family().load(family);e.subspecies().tick();check(checks,"juveniles cannot transform",!e.subspecies().deibu());
        e=animal(h);var form=e.subspecies().save();form.putInt("Susceptibility",99);e.subspecies().load(form);for(int i=0;i<200;i++)e.subspecies().tick();
        check(checks,"non-susceptible individual never rerolls on repeated checks",!e.subspecies().deibu()&&e.subspecies().susceptibility()==99);
        e=animal(h);id=e.getUUID();width=e.getBbWidth();personal=e.life().personality().save();e.subspecies().tick();
        check(checks,"qualified susceptible adult develops acquired form",e.subspecies().deibu()&&e.subspecies().assetId().equals("reimu_deibu"));
        check(checks,"adult body and collision enlarge exactly once",Math.abs(e.growthScale()-1.5f)<.001&&Math.abs(e.getBbWidth()/width-1.5f)<.001);
        check(checks,"transformation preserves UUID and innate temperament",e.getUUID().equals(id)&&java.util.Arrays.equals(personal.getIntArray("DispositionInnate"),e.life().personality().save().getIntArray("DispositionInnate")));
        var saved=new CompoundTag();e.saveWithoutId(saved);var copy=ModEntities.REIMU.get().create(h.getLevel());copy.load(saved);
        check(checks,"full entity save-load preserves acquired form, rank and scale",copy.subspecies().deibu()&&copy.subspecies().susceptibility()==0&&Math.abs(copy.growthScale()-1.5f)<.001);
        copy.subspecies().tick();check(checks,"repeated ticks never multiply form scale again",Math.abs(copy.growthScale()-1.5f)<.001);
        var wasa=animal(h);wasa.subspecies().set(SubspeciesLife.Kind.WASA_REIMU,SubspeciesLife.Congenital.NORMAL);wasa.subspecies().tick();
        check(checks,"Wasa ancestry and fur survive acquired development",wasa.subspecies().deibu()&&wasa.subspecies().kind()==SubspeciesLife.Kind.WASA_REIMU&&wasa.subspecies().assetId().equals("reimu_wasa_deibu"));
        var deficient=animal(h);deficient.subspecies().set(SubspeciesLife.Kind.NORMAL,SubspeciesLife.Congenital.BEAN_BODY);deficient.subspecies().tick();check(checks,"undefined congenital combination preserves existing body",!deficient.subspecies().deibu()&&deficient.subspecies().congenital()==SubspeciesLife.Congenital.BEAN_BODY);
        var birth=ModEntities.REIMU.get().create(h.getLevel());birth.subspecies().initializeBirth(SubspeciesLife.DEIBU_FLAG);check(checks,"birth flags cannot create an acquired adult form",!birth.subspecies().deibu());
        var child=new FamilyData.Child();child.id=UUID.randomUUID();child.variant=e.getVariant();child.traits=new int[]{10,20,30,40,50,60};child.dispositions=new int[]{21,32,43,54};child.adultScale=1;child.maturity=48000;child.lifespan=2208000;
        var brood=new FamilyData.Brood();brood.id=UUID.randomUUID();brood.mother=e.getUUID();brood.kind=FamilyData.BirthKind.LIVE;birth.family().newborn(child,brood);
        check(checks,"newborn adopts reserved four genes without maternal learned state",java.util.Arrays.equals(birth.life().personality().save().getIntArray("DispositionInnate"),child.dispositions)&&birth.life().personality().environment().friendliness()==50&&birth.life().personality().lessons()==0);
        var blocked=animal(h);var wall=blocked.blockPosition().east();h.getLevel().setBlockAndUpdate(wall,Blocks.STONE.defaultBlockState());blocked.subspecies().tick();
        check(checks,"solid obstacle delays growth rather than clipping the new body",!blocked.subspecies().deibu()&&blocked.subspecies().deibuExposure()==24000);h.getLevel().setBlockAndUpdate(wall,Blocks.AIR.defaultBlockState());blocked.subspecies().tick();
        check(checks,"clearing the obstacle allows the same saved opportunity",blocked.subspecies().deibu());
        finish(h,"deibu-development-checks",checks);
    }
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=200,batch="personality_development")
    public static void roundedIntegration(GameTestHelper h){
        var checks=new ArrayList<Check>();var e=animal(h);
        var mesh=io.github.longshou.mcyukkuri.body.WoundSurface.get("reimu");
        // Triangle 2652 is a front patch in the frozen pre-rounded Reimu surface shipped for v2 migration.
        UUID legacyId=UUID.randomUUID();var legacyBody=io.github.longshou.mcyukkuri.body.BodyWound.write(List.of(new io.github.longshou.mcyukkuri.body.BodyWound(legacyId,"dent",2652,.2f,.3f,2,1,3,40)));
        legacyBody.putInt("Version",2);var migrated=animal(h);migrated.body().load(legacyBody);
        var oldWounds=migrated.body().wounds();
        check(checks,"v2 legacy triangle migrates to current front skin preserving injury",oldWounds.size()==1&&oldWounds.getFirst().id().equals(legacyId)&&oldWounds.getFirst().severity()==2&&oldWounds.getFirst().emitted()==3&&mesh.valid(oldWounds.getFirst())&&mesh.anchor(oldWounds.getFirst()).z<0);
        var migratedSave=migrated.body().save();migrated.body().load(migratedSave);
        check(checks,"legacy wound migration becomes idempotent v3 anchors",migratedSave.getInt("Version")==3&&migrated.body().save().equals(migratedSave));
        var hit=mesh.ray(new org.joml.Vector3f(1,3,-40),new org.joml.Vector3f(0,0,1),null,List.of());
        UUID injury=UUID.randomUUID();e.body().addWound(new io.github.longshou.mcyukkuri.body.BodyWound(injury,"dent",hit.triangle(),hit.u(),hit.v(),2,1,3,40));
        var before=e.body().save();e.subspecies().tick();var wounds=e.body().wounds();
        check(checks,"morph preserves injury identity severity and released material",e.subspecies().deibu()&&wounds.size()==1&&wounds.getFirst().id().equals(injury)&&wounds.getFirst().severity()==2&&wounds.getFirst().emitted()==3);
        var current=io.github.longshou.mcyukkuri.body.WoundSurface.get(e.subspecies().assetId());
        check(checks,"morph keeps wound on the same front body region",current.valid(wounds.getFirst())&&current.anchor(wounds.getFirst()).z<0&&current.normal(wounds.getFirst().triangle()).z<0);
        var saved=e.body().save();e.body().load(saved);check(checks,"current anchors and surface identity survive save-load exactly",e.body().save().equals(saved));
        saved.putString("SurfaceHash","older triangulation");e.body().load(saved);
        check(checks,"changed topology remaps from the saved anchor",e.body().wounds().size()==1&&e.body().wounds().getFirst().id().equals(injury)&&current.valid(e.body().wounds().getFirst()));
        var waste=io.github.longshou.mcyukkuri.body.DetachedItems.waste(e).getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        check(checks,"deibu waste carries actual 1.5 body scale",Math.abs(waste.getFloat("SourceScale")-1.5f)<.001);
        check(checks,"adult deibu collision matches selected 19.2 by 14.4 body",Math.abs(e.getBbWidth()-1.2f)<.001&&Math.abs(e.getBbHeight()-.9f)<.001);
        var outlet=io.github.longshou.mcyukkuri.family.LiveBirthGeometry.modelOutlet(e,0,0);
        check(checks,"normalized deibu birth outlet is not scaled twice",Math.abs(outlet.y-1.280905f)<.001&&Math.abs(outlet.z+4.64746f)<.001);
        for(String id:List.of("reimu","marisa","chen","alice","patchouli","marisa_tsumuri","reimu_wasa","reimu_deibu","reimu_wasa_deibu")){
            var surface=io.github.longshou.mcyukkuri.body.WoundSurface.get(id);boolean finite=true;
            for(String action:List.of("idle","waddle","side_bend","stretch","taiso")){
                var pose=surface.pose(action,.3f);for(var bone:pose)finite&=bone.isFinite();
            }
            check(checks,id+" wound skin has valid numeric pose and 9.6 normalized height",finite&&surface.skinCount>0&&Math.abs(io.github.longshou.mcyukkuri.family.LiveBirthGeometry.modelHeight(id)-9.6f)<.001);
        }
        finish(h,"rounded-integration-checks",checks);
    }
}
