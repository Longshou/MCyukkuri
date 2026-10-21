package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.mind.MindLife;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Loaded server time, independent of goal interruptions. Genealogy remains in FamilyData. */
final class FamilySeparation {
    private final FamilyLife o;
    private final Map<UUID,Integer> searching=new HashMap<>();
    private boolean lost, desperate;
    private int hungerDecision;
    FamilySeparation(FamilyLife owner){o=owner;}
    boolean emergencyForaging(){return desperate&&o.stage()==FamilyLife.Stage.BABY&&o.entity().life().nutrition()<10&&!o.awaitingLanding();}
    String describe(){return "lost="+lost+" searches="+searching+" limit="+searchLimit()+" dismissed="+FamilyData.get(o.level()).dismissedChildren(o.entity().getUUID())+" emergencyFood="+emergencyForaging();}
    private int searchLimit(){
        var p=o.entity().life().personality();
        long children=FamilyData.get(o.level()).children(o.entity().getUUID()).stream()
                .filter(id->FamilyData.get(o.level()).person(id).status==FamilyData.Status.ALIVE)
                .filter(id->FamilyData.get(o.level()).acknowledgesChild(o.entity().getUUID(),id)).count();
        return Math.clamp(2400+60*p.friendliness()+40*p.selfControl()+20*p.boldness()-600*(int)Math.max(0,children-1),1200,14400);
    }
    private boolean near(YukkuriEntity a,YukkuriEntity b){return a.level()==b.level()&&a.distanceToSqr(b)<=64&&a.hasLineOfSight(b);}
    private void pressure(float perSecond,float ceiling,String cause){
        o.entity().mind().add(Math.max(0,Math.min(perSecond,ceiling-o.entity().mind().unease())),cause);
    }
    void tick(){
        if(o.entity().tickCount%20!=0)return;
        var e=o.entity();var data=FamilyData.get(o.level());var p=e.life().personality();
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
        boolean wasLost=lost;lost=false;
        if(!o.adult()&&!o.subadult()&&!o.awaitingLanding()&&(o.mother()!=null||o.father()!=null)){
            boolean nearby=false,sameNest=false,knownAlive=false;
            for(UUID id:new UUID[]{o.mother(),o.father()})if(id!=null){
                var record=data.person(id);knownAlive|=record!=null&&record.status==FamilyData.Status.ALIVE;
                if(o.level().getEntity(id) instanceof YukkuriEntity parent&&parent.isAlive()){
                    nearby|=near(e,parent);sameNest|=FamilyNest.sameNest(e,parent);
                }
            }
            lost=knownAlive&&!nearby&&!FamilyNest.atAssignedNest(e);
            if(lost)pressure(.2f,Math.max(60,100-.4f*p.boldness()),"lost_parent");
            else if(o.stage()==FamilyLife.Stage.BABY&&!sameNest)pressure(.07f,70-.5f*p.selfControl(),"separate_nest");
            if(lost!=wasLost){
                e.showReaction(lost?"cry":"happy",lost?"lost_parent":"caregiver",100);
                if(lost)BirthStaging.tears(e);else BirthStaging.affection(e);
                GameplayLog.event(e,"family","child separation lost={} unease={} boldness={} sameNest={}",lost,e.mind().unease(),p.boldness(),sameNest);
            }
        }
        if(o.adult())for(UUID id:data.children(e.getUUID())){
            var record=data.person(id);
            if(record==null||!e.getUUID().equals(record.mother)||!data.acknowledgesChild(e.getUUID(),id))continue;
            if(!(o.level().getEntity(id) instanceof YukkuriEntity child)||!child.isAlive()||child.family().adult()||child.family().subadult())continue;
            if(child.family().awaitingLanding()||child.isPassenger()||near(e,child)||FamilyNest.atAssignedNest(child)){
                if(searching.remove(id)!=null){e.showReaction("happy","caregiver",80);BirthStaging.affection(e);GameplayLog.event(e,"family","child search reunited child={}",id);}
                continue;
            }
            int elapsed=searching.merge(id,20,Integer::sum);
            pressure(.12f,MindLife.BREAKING-10,"missing_child");
            if(elapsed==20){e.showReaction("teary","wait_family",100);GameplayLog.event(e,"family","child search clock started child={} limit={}",id,searchLimit());}
            if(elapsed>=searchLimit()){
                data.dismissChild(e,id);searching.remove(id);
                e.showReaction("teary","mood_bad",120);BirthStaging.caption(e,"搜寻太久，母油放弃寻找；孩子仍然认得母油");
                if(o.target()==child&&o.task()==FamilyLife.Task.SEARCH)o.stop();
            }
        }
        searching.keySet().removeIf(id->{var person=data.person(id);return person==null||person.status!=FamilyData.Status.ALIVE||!data.acknowledgesChild(e.getUUID(),id);});
        FamilyNest.tick(e);
    }
    void save(CompoundTag tag){
        var list=new ListTag();searching.forEach((id,ticks)->{var entry=new CompoundTag();entry.putUUID("Child",id);entry.putInt("Ticks",ticks);list.add(entry);});
        tag.put("ChildSearchClocks",list);tag.putBoolean("EmergencyForaging",desperate);tag.putInt("HungerDecision",hungerDecision);
    }
    void load(CompoundTag tag){
        searching.clear();for(var raw:tag.getList("ChildSearchClocks",Tag.TAG_COMPOUND)){var entry=(CompoundTag)raw;if(entry.hasUUID("Child"))searching.put(entry.getUUID("Child"),Math.clamp(entry.getInt("Ticks"),0,14400));}
        desperate=tag.getBoolean("EmergencyForaging");hungerDecision=Math.clamp(tag.getInt("HungerDecision"),0,1200);lost=false;
    }
}
