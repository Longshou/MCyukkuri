package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyConfig;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class GrowthGameTests {
    private record Check(String name, boolean pass) {}
    private static void check(List<Check> checks, String name, boolean pass) { checks.add(new Check(name, pass)); }
    private static YukkuriEntity animal(GameTestHelper h) {
        var e=ModEntities.REIMU.get().create(h.getLevel());
        var p=h.absolutePos(new BlockPos(2,2,2));e.moveTo(p.getX()+.5,p.getY(),p.getZ()+.5,0,0);
        e.setNoAi(true);e.setNoGravity(true);e.setOnGround(true);
        var life=e.life().save();life.putFloat("Nutrition",90);life.putFloat("Energy",90);e.life().load(life);
        return e;
    }
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=100,batch="growth_revision")
    public static void growthAndMigration(GameTestHelper h) {
        var checks=new ArrayList<Check>();var e=animal(h);
        long maturity=FamilyConfig.MATURITY.get(),adult=FamilyConfig.ADULT_LIFESPAN.get();
        check(checks,"default childhood is 40 loaded minutes",maturity==48000);
        check(checks,"default adult lifetime is 30 loaded hours",adult==2160000);
        var old=e.family().save();old.putInt("Version",1);old.remove("Maturity");old.putBoolean("Initialized",true);
        old.putLong("Lifespan",72000);old.putLong("Age",12000);old.putLong("Nourishment",9000);old.putString("Stage","JUVENILE");old.putFloat("Scale",.45f);
        e.family().load(old);
        check(checks,"old juvenile keeps half childhood progress",e.family().age()==maturity/2&&e.family().maturityAge()==maturity);
        check(checks,"old nutrition progress is scaled with childhood",e.family().save().getLong("Nourishment")==18000);
        check(checks,"migration preserves juvenile stage and appearance",e.family().stage()==FamilyLife.Stage.JUVENILE&&e.family().scale()==.45f);
        check(checks,"migrated total lifetime includes new adult period",e.family().lifespan()==maturity+adult);
        var once=e.family().save();e.family().load(once);
        check(checks,"reloading v2 cannot repeat extension",e.family().save().equals(once));
        old.putString("Stage","ADULT");old.putLong("Age",48000);old.putLong("Nourishment",36000);old.putFloat("Scale",1);
        e.family().load(old);
        check(checks,"old adult retains half of adult lifespan consumed",e.family().age()==maturity+adult/2);
        check(checks,"adult migration never makes a juvenile",e.family().adult()&&e.family().scale()==1);
        old.putLong("Age",72000);e.family().load(old);
        check(checks,"already expired legacy age is not resurrected",e.family().age()==e.family().lifespan());
        old.putLong("Lifespan",75600);old.putLong("Age",25200);e.family().load(old);
        check(checks,"legacy individual variation retained",e.family().maturityAge()==50400&&e.family().lifespan()==2318400);
        var current=e.family().save();current.putLong("Maturity",maturity);current.putLong("Lifespan",maturity+adult);
        current.putString("Stage","BABY");current.putLong("Age",11999);current.putLong("Nourishment",12000);current.putFloat("Scale",.18f);e.family().load(current);e.family().woke();
        check(checks,"baby age boundary prevents premature juvenile stage",e.family().stage()==FamilyLife.Stage.BABY);
        current.putLong("Age",12000);e.family().load(current);e.family().woke();
        check(checks,"well fed baby becomes juvenile at 10 minutes",e.family().stage()==FamilyLife.Stage.JUVENILE);
        current.putString("Stage","JUVENILE");current.putFloat("Scale",.3f);current.putLong("Age",48000);current.putLong("Nourishment",48000);e.family().load(current);e.family().woke();
        check(checks,"maturity age remains strict greater-than",!e.family().adult());
        current.putLong("Age",48001);current.putLong("Nourishment",35999);e.family().load(current);e.family().woke();
        check(checks,"insufficient accumulated nutrition blocks maturity",!e.family().adult());
        current.putLong("Nourishment",48000);e.family().load(current);
        check(checks,"age alone does not trigger stage transition",!e.family().adult());
        var hungry=e.life().save();hungry.putFloat("Nutrition",34);e.life().load(hungry);e.family().woke();
        check(checks,"currently hungry individual cannot mature",!e.family().adult());
        hungry.putFloat("Nutrition",90);e.life().load(hungry);e.family().woke();
        check(checks,"fed wake matures at 40 minutes despite 30 hour adult lifespan",e.family().adult()&&Math.abs(e.family().scale()-.8f)<.0001);
        current=e.family().save();current.putLong("Nourishment",72000);e.family().load(current);e.family().woke();
        check(checks,"body reaches adult target on childhood clock",Math.abs(e.family().scale()-1)<.0001);
        long before=e.family().age();e.family().tick();check(checks,"NoAI does not age",e.family().age()==before);
        e.setNoAi(false);e.family().tick();check(checks,"active family tick ages once",e.family().age()==before+1);
        current=e.family().save();current.putLong("Age",maturity+adult*85/100-1);e.family().load(current);
        check(checks,"elder boundary uses adult lifetime",!e.family().elder());
        current.putLong("Age",maturity+adult*85/100);e.family().load(current);check(checks,"last 15 percent of adult life is elder",e.family().elder());
        current.putLong("Age",maturity+adult-2);e.family().load(current);e.family().tick();check(checks,"alive one tick before natural lifespan",e.isAlive());
        e.family().tick();check(checks,"natural death occurs at new saved lifespan",!e.isAlive());
        // A real FamilyData load/save round-trip also covers unborn records in detached stems.
        UUID childId=UUID.randomUUID(),broodId=UUID.randomUUID(),motherId=UUID.randomUUID();
        var dataTag=new CompoundTag();dataTag.putInt("Version",1);var people=new ListTag();var person=new CompoundTag();
        person.putUUID("Id",childId);person.putString("Variant","reimu");person.putString("Status","UNBORN");people.add(person);dataTag.put("People",people);
        var broods=new ListTag();var brood=new CompoundTag();brood.putUUID("Id",broodId);brood.putUUID("Mother",motherId);brood.putString("Kind","LIVE");brood.putInt("Duration",6000);
        FamilyData.putPosition(brood,"Anchor",GlobalPos.of(h.getLevel().dimension(),h.absolutePos(BlockPos.ZERO)));
        var kids=new ListTag();var kid=new CompoundTag();kid.putUUID("Id",childId);kid.putString("Variant","REIMU");kid.putFloat("AdultScale",1);kid.putLong("Lifespan",72000);kid.putIntArray("Traits",new int[]{50,50,50,50,50,50});kids.add(kid);brood.put("Children",kids);broods.add(brood);dataTag.put("Broods",broods);
        var data=FamilyData.load(dataTag,h.getLevel().registryAccess());var savedChild=data.brood(broodId).children.getFirst();
        check(checks,"unborn legacy child receives separated clocks",savedChild.maturity==maturity&&savedChild.lifespan==maturity+adult);
        var roundTrip=FamilyData.load(data.save(new CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess()).brood(broodId).children.getFirst();
        check(checks,"unborn migration is idempotent and keeps identity",roundTrip.id.equals(childId)&&roundTrip.maturity==maturity&&roundTrip.lifespan==maturity+adult);
        var newborn=animal(h);newborn.family().newborn(roundTrip,data.brood(broodId));
        check(checks,"actual newborn adopts reserved childhood and lifespan",newborn.family().age()==0&&newborn.family().maturityAge()==maturity&&newborn.family().lifespan()==maturity+adult);
        P0Control.write("growth-checks",checks);
        h.assertTrue(checks.stream().allMatch(Check::pass),"Growth checks failed: "+checks.stream().filter(c->!c.pass).toList());h.succeed();
    }
}
