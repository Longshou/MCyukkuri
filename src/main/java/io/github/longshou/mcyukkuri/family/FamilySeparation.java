package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.GlassSight;
import io.github.longshou.mcyukkuri.mind.MindLife;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;

/** Loaded server time, independent of goal interruptions. Genealogy remains in FamilyData. */
final class FamilySeparation {
    private final FamilyLife o;
    private final KinAbsence absence;
    private final Map<UUID,Integer> searching=new HashMap<>();
    private final Set<UUID> glassDismissals=new HashSet<>();
    // Keep the eight most recently seen dependants (project design), across interruptions and chunk unloads.
    private static final int CHILD_SIGHTINGS=8;
    private final Map<UUID,GlobalPos> childSightings=new LinkedHashMap<>();
    private boolean lost, desperate;
    private int hungerDecision;
    /** Loaded ticks spent away from the home it has (none before it has one), and the tick it last came home after a real absence
     *  (transient; kin at home welcome it). */
    private int away;
    private long cameHome=-1;
    /** An absence shorter than this (30 s, project design) is a step out of the door, not a homecoming. */
    private static final int ABSENCE=600;
    long cameHome(){return cameHome;}
    FamilySeparation(FamilyLife owner){o=owner;absence=new KinAbsence(owner);}
    KinAbsence absence(){return absence;}
    boolean emergencyForaging(){return desperate&&o.stage()==FamilyLife.Stage.BABY&&o.entity().life().nutrition()<10&&!o.awaitingLanding();}
    String describe(){return "lost="+lost+" searches="+searching+" childSightings="+childSightings+" limit="+searchLimit()+" absence="+absence.describe()+" glassDismissals="+glassDismissals+" dismissed="+FamilyData.get(o.level()).dismissedChildren(o.entity().getUUID())+" emergencyFood="+emergencyForaging();}

    private void remember(UUID child,GlobalPos at) {
        childSightings.remove(child);
        childSightings.put(child,at);
        if(childSightings.size()>CHILD_SIGHTINGS)childSightings.remove(childSightings.keySet().iterator().next());
    }

    boolean observeChild(YukkuriEntity child) {
        var parent=o.entity();
        if(parent.level()!=child.level()||parent.life().isSleeping()||parent.isPassenger()
                ||parent.distanceToSqr(child)>=FamilyLife.FAMILY_SEARCH_RANGE*FamilyLife.FAMILY_SEARCH_RANGE
                ||!parent.life().canRecognize(child)||!GlassSight.sees(parent,child))return false;
        remember(child.getUUID(),GlobalPos.of(o.level().dimension(),child.blockPosition()));
        return true;
    }

    Set<UUID> sightedChildren(){return Set.copyOf(childSightings.keySet());}

    Vec3 lastSeen(YukkuriEntity child) {
        var at=childSightings.get(child.getUUID());
        if(at==null||!o.level().dimension().equals(at.dimension()))return null;
        var point=Vec3.atBottomCenterOf(at.pos());
        return o.entity().distanceToSqr(point)<FamilyLife.FAMILY_SEARCH_RANGE*FamilyLife.FAMILY_SEARCH_RANGE ? point : null;
    }

    void searchedLastSight(YukkuriEntity child){childSightings.remove(child.getUUID());}
    private int searchLimit(){
        var p=o.entity().life().personality();
        long children=FamilyData.get(o.level()).children(o.entity().getUUID()).stream()
                .filter(id->FamilyData.get(o.level()).person(id).status==FamilyData.Status.ALIVE)
                .filter(id->FamilyData.get(o.level()).acknowledgesChild(o.entity().getUUID(),id)).count();
        return Math.clamp(2400+60*p.friendliness()+40*p.selfControl()+20*p.boldness()-600*(int)Math.max(0,children-1),1200,14400);
    }
    /** Within 8 blocks and in sight, or both inside the same home (its walls and floors break sight, not the family). */
    static boolean near(YukkuriEntity a,YukkuriEntity b){
        var home=a.habitat().shelter();
        return a.level()==b.level()&&a.distanceToSqr(b)<=64&&(a.hasLineOfSight(b)||home!=null&&home==b.habitat().shelter()
                &&home.contains(a.blockPosition())&&home.contains(b.blockPosition()));
    }
    boolean searchingAllowed(YukkuriEntity child){return !glassDismissals.contains(child.getUUID());}
    void dismissAtGlass(YukkuriEntity child,String reason){
        var id=child.getUUID();
        searching.remove(id);glassDismissals.add(id);childSightings.remove(id);
        GameplayLog.event(o.entity(),"family","parent dismissed child={} reason={} child_recognition=retained",id,reason);
    }
    /** In its nest, or anywhere inside its own home. */
    static boolean atHome(YukkuriEntity e){
        var home=e.habitat().shelter();
        return FamilyNest.atAssignedNest(e)||home!=null&&home.contains(e.blockPosition());
    }
    private void pressure(float perSecond,float ceiling,String cause){
        o.entity().mind().add(Math.max(0,Math.min(perSecond,ceiling-o.entity().mind().unease())),cause);
    }
    /** The parent reached the child it searched for (FamilyLife.reached): the one place the reunion is greeted and logged. */
    void reunited(YukkuriEntity child){
        var e=o.entity();
        searching.remove(child.getUUID());o.call().found(child.getUUID());
        e.showReaction("happy","caregiver",80);BirthStaging.affection(e);
        GameplayLog.event(e,"family","child search reunited child={} distance={}",child.getUUID(),Math.round(e.distanceTo(child)*100)/100.0);
    }
    void tick(){
        if(o.entity().tickCount%20!=0)return;
        var e=o.entity();var data=FamilyData.get(o.level());var p=e.life().personality();
        absence.observe();
        glassDismissals.removeIf(id->{
            var record=data.person(id);
            if(record!=null&&record.status==FamilyData.Status.DEAD)return true;
            if(!(o.level().getEntity(id) instanceof YukkuriEntity child)||!e.hasLineOfSight(child)
                    ||e.distanceToSqr(child)>FamilyLife.FAMILY_SEARCH_RANGE*FamilyLife.FAMILY_SEARCH_RANGE
                    ||!e.life().reachable(child.blockPosition(),false))return false;
            GameplayLog.event(e,"family","glass search reopened child={} reason=clear_route",id);
            return true;
        });
        if(o.stage()==FamilyLife.Stage.BABY&&!o.awaitingLanding()){
            if(e.life().nutrition()>=10){desperate=false;hungerDecision=0;}
            else if((hungerDecision-=20)<=0){
                hungerDecision=1200;
                float score=.55f*p.boldness()+.30f*p.intelligence()+.15f*(100-p.selfControl());
                desperate=score>=45;
                e.showReaction(desperate?"teary":"cry","hungry",80);
                BirthStaging.caption(e,desperate?"饿极了，离开窝边自己寻找食物":"饿极了仍不敢独自找食物，继续呼唤亲油");
                GameplayLog.event(e,"family","infant starvation decision accepted={} score={} threshold=45 nutrition={}",desperate,score,e.life().nutrition());
            }
        }else desperate=false;
        if(atHome(e)){
            if(away>=ABSENCE){cameHome=o.level().getGameTime();GameplayLog.event(e,"family","came home away={} home={}",away,e.habitat().shelter()==null?null:e.habitat().shelter().getBlockPos());}
            away=0;
        }else if(FamilyNest.care(e)!=null||e.habitat().shelter()!=null)away=Math.min(away+20,1_000_000);
        boolean wasLost=lost;lost=false;
        if(!o.adult()&&!o.subadult()&&!o.awaitingLanding()&&(o.mother()!=null||o.father()!=null)){
            boolean nearby=false,sameNest=false,expectsParent=false;
            for(UUID id:new UUID[]{o.mother(),o.father()})if(id!=null){
                expectsParent|=!absence.confirmed(id);
                if(o.level().getEntity(id) instanceof YukkuriEntity parent&&parent.isAlive()){
                    nearby|=near(e,parent);sameNest|=FamilyNest.sameNest(e,parent);
                }
            }
            lost=expectsParent&&!nearby&&!atHome(e);
            if(lost)pressure(.2f,Math.max(60,100-.4f*p.boldness()),"lost_parent");
            else if(o.stage()==FamilyLife.Stage.BABY&&!sameNest)pressure(.07f,70-.5f*p.selfControl(),"separate_nest");
            if(lost!=wasLost&&!e.grieving()){
                e.showReaction(lost?"cry":"happy",lost?"lost_parent":"caregiver",100);
                if(lost)BirthStaging.tears(e);else BirthStaging.affection(e);
                GameplayLog.event(e,"family","child separation lost={} unease={} boldness={} sameNest={}",lost,e.mind().unease(),p.boldness(),sameNest);
            }
        }
        if(o.adult())for(UUID id:data.children(e.getUUID())){
            var record=data.person(id);
            if(record==null||!data.acknowledgesChild(e.getUUID(),id))continue;
            if(!(o.level().getEntity(id) instanceof YukkuriEntity child)||!child.isAlive()||child.family().adult()||child.family().subadult()
                    ||!searchingAllowed(child))continue;
            if(o.caresFor(child)&&child.childhood().shouldStayWithParents())observeChild(child);
            if(!e.getUUID().equals(record.mother))continue;
            if(child.family().awaitingLanding()||child.isPassenger()||near(e,child)||atHome(child)){
                searching.remove(id);
                // Sharing a home can settle the absence clock through a wall. It cannot reset an unanswered call.
                if(e.hasLineOfSight(child))o.call().found(id);
                continue;
            }
            int elapsed=searching.merge(id,20,Integer::sum);
            // A slave is kept in the pit and cannot go to the child: that worry stays at 不安 with its other pit pressures.
            pressure(.12f,o.entity().settlement().society().slave()?MindLife.UNEASY:MindLife.BREAKING-10,"missing_child");
            if(elapsed==20){e.showReaction("teary","wait_family",100);GameplayLog.event(e,"family","child search clock started child={} limit={}",id,searchLimit());}
            if(elapsed>=searchLimit()&&!o.glass().tracks(child)&&GlassSight.glassBetween(e,e.getEyePosition(),child)==null){
                data.dismissChild(e,id);searching.remove(id);
                e.showReaction("teary","mood_bad",120);BirthStaging.caption(e,"搜寻太久，母油放弃寻找；孩子仍然认得母油");
                if(o.target()==child&&o.task()==FamilyLife.Task.SEARCH)o.stop();
            }
        }
        searching.keySet().removeIf(id->{var person=data.person(id);return person==null||person.status!=FamilyData.Status.ALIVE||!data.acknowledgesChild(e.getUUID(),id);});
        childSightings.keySet().removeIf(id->{
            var person=data.person(id);
            return person!=null&&(person.status==FamilyData.Status.DEAD||!data.acknowledgesChild(e.getUUID(),id));
        });
        FamilyNest.tick(e);
    }
    void save(CompoundTag tag){
        var list=new ListTag();searching.forEach((id,ticks)->{var entry=new CompoundTag();entry.putUUID("Child",id);entry.putInt("Ticks",ticks);list.add(entry);});
        tag.put("ChildSearchClocks",list);tag.putBoolean("EmergencyForaging",desperate);tag.putInt("HungerDecision",hungerDecision);
        var glass=new ListTag();for(var id:glassDismissals){var entry=new CompoundTag();entry.putUUID("Child",id);glass.add(entry);}
        tag.put("GlassSearchDismissals",glass);
        var sightings=new ListTag();
        childSightings.forEach((id,at)->{
            var entry=new CompoundTag();entry.putUUID("Child",id);FamilyData.putPosition(entry,"Seen",at);sightings.add(entry);
        });
        tag.put("ChildSightings",sightings);
        absence.save(tag);
    }
    void load(CompoundTag tag){
        searching.clear();for(var raw:tag.getList("ChildSearchClocks",Tag.TAG_COMPOUND)){var entry=(CompoundTag)raw;if(entry.hasUUID("Child"))searching.put(entry.getUUID("Child"),Math.clamp(entry.getInt("Ticks"),0,14400));}
        glassDismissals.clear();for(var raw:tag.getList("GlassSearchDismissals",Tag.TAG_COMPOUND)){var entry=(CompoundTag)raw;if(entry.hasUUID("Child"))glassDismissals.add(entry.getUUID("Child"));}
        childSightings.clear();
        for(var raw:tag.getList("ChildSightings",Tag.TAG_COMPOUND)){
            var entry=(CompoundTag)raw;
            var at=FamilyData.position(entry,"Seen");
            if(entry.hasUUID("Child")&&at!=null)remember(entry.getUUID("Child"),at);
        }
        desperate=tag.getBoolean("EmergencyForaging");hungerDecision=Math.clamp(tag.getInt("HungerDecision"),0,1200);lost=false;
        absence.load(tag);
    }
}
