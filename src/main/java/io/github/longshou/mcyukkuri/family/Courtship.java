package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.family.FamilyLife.Task;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.pet.PetLife;
import io.github.longshou.mcyukkuri.pet.PetScenes;
import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.UUID;
import net.minecraft.core.GlobalPos;
import net.minecraft.util.Mth;

/** Mutual courtship reads one initiator clock; the owner keeps all durable family and goal state. */
final class Courtship {
    private final FamilyLife o;
    private int courtNearTicks, courtPathFailures, courtRequiredTicks;
    private long courtStart=-1;
    private static final int COURT_ACCEPT_TICKS=28;
    /** Temporary arrangement and the owner-unpermitted courtship partner. */
    private UUID arranged, unauthorizedWith;

    Courtship(FamilyLife o) { this.o=o; }
    UUID arranged() { return arranged; }
    void resetAttempt() { resetCourtProgress();courtPathFailures=0; }
    void reset() { resetCourtProgress();courtPathFailures=0;unauthorizedWith=null; }

    /** Both partners read the initiator's uninterrupted server clock, never their travel time. */
    FamilyLife presentingCourt() {
        if(o.entity().level().isClientSide||o.target()==null||!o.entity().isAlive()||!o.target().isAlive())return null;
        FamilyLife leader=o.task()==Task.COURT?o:o.task()==Task.RECEIVE_COURT?o.target().family():null;
        if(leader==null||leader.task()!=Task.COURT||leader.target()==null||o.task()==Task.RECEIVE_COURT&&leader.target()!=o.entity()
                ||leader.target().family().task()!=Task.RECEIVE_COURT
                ||leader.target().family().target()!=leader.entity()||leader.courtship().courtNearTicks==0||leader.courtship().courtStart<0
                ||!ready()||!o.target().family().ready()||o.entity().distanceToSqr(o.target())>2.25
                ||!o.entity().hasLineOfSight(o.target())||!o.target().hasLineOfSight(o.entity())||!o.supported()||!o.target().family().supported())return null;
        return leader;
    }

    String courtActivity() {
        var leader=presentingCourt();
        return leader==null?Behaviors.COURTSHIP:leader.courtship().courtNearTicks<=COURT_ACCEPT_TICKS?Behaviors.PAIRING_ACCEPT:Behaviors.PAIRING;
    }

    long courtAnimationStart() {
        var leader=presentingCourt();
        return leader==null?-1:leader.courtship().courtStart+(leader.courtship().courtNearTicks<=COURT_ACCEPT_TICKS?0:COURT_ACCEPT_TICKS);
    }

    void resetCourtProgress(){courtNearTicks=courtRequiredTicks=0;courtStart=-1;}

    void facePartner(){
        float yaw=(float)(Mth.atan2(o.target().getZ()-o.entity().getZ(),o.target().getX()-o.entity().getX())*Mth.RAD_TO_DEG)-90;
        o.face(Mth.approachDegrees(o.entity().getYRot(),yaw,8));
    }

    boolean introduce(YukkuriEntity other) {
        if(other==o.entity()||!o.adult()||!other.family().adult()||o.entity().profile().houseBody()!=other.profile().houseBody()
                ||o.entity().profile().aquatic()!=other.profile().aquatic()
                ||o.entity().profile().dangerToYukkuri()!=other.profile().dangerToYukkuri()
                ||o.entity().level()!=other.level()||o.entity().distanceToSqr(other)>64)return false;
        var data=FamilyData.get(o.level());data.register(o.entity());data.register(other);
        if(Bonds.biological(o.entity(), other)||data.busyParent(o.entity().getUUID())||data.busyParent(other.getUUID()))return false;
        if(!data.pair(o.entity(),other,false))return false;
        other.family().waitTicks(0);o.waitTicks(0);
        o.entity().setPersistenceRequired();other.setPersistenceRequired();o.entity().showReaction("happy","introduced",80);other.showReaction("happy","introduced",80);
        return true;
    }

    boolean ready() { return ready(true); }

    boolean ready(boolean needHome) {
        return o.adult()&&!o.sterile()&&!o.elder()&&o.recovery()==0&&o.entity().isAlive()&&!o.entity().body().dying()&&o.entity().body().coreIntact()&&!o.entity().isPanicking()&&!o.entity().isCommandPlaying()&&!o.entity().operations().active()&&!o.entity().predator().active()&&!o.entity().agitation().blocksCourtship()
                &&!o.entity().isNoAi()&&!o.entity().pet().busy()&&!o.entity().life().isSleeping()&&!o.entity().life().sedated()
                &&!o.entity().life().hasUrgentNeed()&&o.entity().life().nutrition()>=65&&o.entity().life().energy()>=50&&o.entity().life().hygiene()>=40
                &&o.entity().getHealth()>=o.entity().getMaxHealth()*.8&&o.entity().body().condition().readyForReproduction()&&o.entity().life().mealTicks()==0&&(!needHome||o.nestStand()!=null||o.boatHome()!=null);
    }

    void arrange(UUID other){arranged=other;}

    /** Shop service check: can the two mate if brought together, ignoring distance, homes, recognition and affinity. */
    static boolean arrangedMatch(YukkuriEntity a,YukkuriEntity b) {
        a.family().arrange(b.getUUID());b.family().arrange(a.getUUID());
        try { return a.family().canReproduceWith(b)&&b.family().canReproduceWith(a); }
        finally { a.family().arrange(null);b.family().arrange(null); }
    }

    /** Long-lived compatibility for breeding permits: species rules and fertility, not momentary condition. */
    boolean compatibleWith(YukkuriEntity other) {
        if(other==o.entity()||o.entity().level()!=other.level()||!o.adult()||!other.family().adult()||o.sterile()||other.family().sterile()||o.elder()||other.family().elder()
                ||o.entity().profile().houseBody()!=other.profile().houseBody()||o.entity().profile().dangerToYukkuri()!=other.profile().dangerToYukkuri()||o.entity().profile().aquatic()!=other.profile().aquatic())return false;
        var aquatic=SubspeciesLife.Kind.AQUATIC_MARISA;
        return (o.entity().subspecies().kind()==aquatic)==(other.subspecies().kind()==aquatic)&&!Bonds.biological(o.entity(), other);
    }

    /** Rolled once per courtship attempt for owned pets; the unauthorized partner is remembered so that conception can count it. */
    boolean allowCourt(YukkuriEntity other) {
        int gate=Math.max(o.entity().pet().courtshipGate(other),other.pet().courtshipGate(o.entity()));
        if(gate==2){o.waitTicks(600);PetScenes.courtshipHeld(o.entity(),other);return false;}
        unauthorizedWith=gate==1?other.getUUID():null;return true;
    }

    boolean canReproduceWith(YukkuriEntity other) {
        boolean arr=other.getUUID().equals(arranged)||o.entity().getUUID().equals(other.family().arrangedMate());
        if(other==o.entity()||!arr&&(o.entity().dailyLife().active()||other.dailyLife().active())||o.entity().level()!=other.level()||!ready(!arr)||!other.family().ready(!arr)||!arr&&o.entity().distanceToSqr(other)>9
                ||o.entity().profile().houseBody()!=other.profile().houseBody()||o.entity().profile().dangerToYukkuri()!=other.profile().dangerToYukkuri()||!arr&&(!o.entity().life().canRecognize(other)||!other.life().canRecognize(o.entity())))return false;
        if(!FamilyData.get(o.level()).canPair(o.entity(),other,true))return false;
        // The aquatic kind only pairs within itself: aquatic couples are two aquatic Marisa.
        var aquatic=SubspeciesLife.Kind.AQUATIC_MARISA;
        if((o.entity().subspecies().kind()==aquatic)!=(other.subspecies().kind()==aquatic))return false;
        var data=FamilyData.get(o.level());var relations=YukkuriWorldData.get(o.level());
        if(Bonds.biological(o.entity(),
                other)||!arr&&(relations.affinity(o.entity().getUUID(),other.getUUID())<BondLevels.AFFINITY_COOPERATIVE||relations.affinity(other.getUUID(),o.entity().getUUID())<BondLevels.AFFINITY_COOPERATIVE))return false;
        if(arr)return true;
        // Afloat in her own hat boat the boat is the nest: neither a shore nor a claimed nest is needed.
        var a=o.nestStand();var b=other.family().nestStand();
        if(a==null&&o.boatHome()!=null)a=o.entity().blockPosition();
        if(b==null&&other.family().boatHome()!=null)b=other.blockPosition();
        return a!=null&&b!=null&&a.distSqr(b)<=64&&(o.boatHome()!=null||other.family().boatHome()!=null||o.entity().life().reachable(other.blockPosition(),false));
    }

    void tickCourt() {
        if(!canReproduceWith(o.target())){o.stop();return;}
        o.entity().getLookControl().setLookAt(o.target(),20,20);
        // The other goal must actually accept this individual, not merely happen to stand nearby.
        if(o.target().family().task()!=Task.RECEIVE_COURT||o.target().family().target()!=o.entity()) {
            resetCourtProgress();o.entity().getNavigation().stop();
            if(o.target().family().task()!=Task.NONE)o.stop();
            return;
        }
        if(o.entity().distanceToSqr(o.target())>2.25||!o.entity().hasLineOfSight(o.target())||!o.target().hasLineOfSight(o.entity())
                ||!o.supported()||!o.target().family().supported()) {
            resetCourtProgress();
            if(o.taskTicks()%10==1) {
                if(o.entity().life().navigate(o.target().blockPosition(),.8,false))courtPathFailures=0;
                else if(++courtPathFailures>=3)o.stop();
            }
            return;
        }
        o.entity().getNavigation().stop();facePartner();
        if(courtNearTicks==0){courtStart=o.level().getGameTime();courtRequiredTicks=o.sexDrive()>0?60:120;}
        if(++courtNearTicks>=courtRequiredTicks) {
            // A Reimu and Marisa couple: Reimu always carries the young and Marisa is the father.
            YukkuriEntity peer=o.target(),mother=o.entity(),father=o.target();
            if(o.entity().getVariant()==YukkuriVariant.MARISA&&o.target().getVariant()==YukkuriVariant.REIMU){mother=o.target();father=o.entity();}
            boolean unauthorized=o.target().getUUID().equals(unauthorizedWith);
            boolean motherHeat=mother.family().inBreedingPeriod(),fatherHeat=father.family().inBreedingPeriod();
            var brood=FamilyData.get(o.level()).conceive(mother,father,mother.family().naturalBirthKind(father),GlobalPos.of(o.level().dimension(),mother.family().birthAnchor()));
            if(brood!=null) {
                // A partner resolving a heat period speaks the staged sukkiri beats instead of this line.
                if(!motherHeat)mother.showReaction("happy","expecting",100);
                if(!fatherHeat)father.showReactionDelayed("happy","expecting",100,18);
                if(unauthorized)PetLife.unauthorizedConception(mother,father);
            } else {
                o.entity().showReaction("confused","",60);peer.showReaction("confused","",60);
            }
            unauthorizedWith=null;
            peer.family().stop();peer.family().waitTicks(200);
            o.stop();o.waitTicks(200);
        }
    }

    void tickReceiver() {
        if(o.target().family().task()!=Task.COURT||o.target().family().target()!=o.entity()||!canReproduceWith(o.target())){o.stop();return;}
        o.entity().getNavigation().stop();o.entity().getLookControl().setLookAt(o.target(),20,20);
        if(presentingCourt()!=null)facePartner();
    }
}
