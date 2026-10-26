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
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.UUID;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Mutual courtship reads one initiator clock; the owner keeps all durable family and goal state. */
final class Courtship {
    private final FamilyLife o;
    private int courtNearTicks, courtContactTicks, courtPathFailures, courtRequiredTicks;
    private long courtStart=-1, courtContactStart=-1;
    private static final int COURT_ACCEPT_TICKS=28;
    /** Temporary arrangement and the owner-unpermitted courtship partner. */
    private UUID arranged, unauthorizedWith;
    private boolean initiatorMother=true;

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
        if(leader==null)return -1;
        var court=leader.courtship();
        return court.courtContactTicks>0?court.courtContactStart:court.courtStart+(court.courtNearTicks<=COURT_ACCEPT_TICKS?0:COURT_ACCEPT_TICKS);
    }

    /** Display-only projection of the real court stage. No second client-side court state exists. */
    CompoundTag state() {
        var tag=new CompoundTag();
        var leader=presentingCourt();if(leader==null)return tag;
        var court=leader.courtship();
        tag.putString("Phase",court.courtContactTicks>0?"CONTACT":court.courtNearTicks<=COURT_ACCEPT_TICKS?"ACCEPT":"ALIGN");
        tag.putLong("Start",courtAnimationStart());
        tag.putString("Role",o.entity()==court.mother(leader.entity(),leader.target())?"MAMU":"PENI");
        tag.putInt("TargetId",o.target().getId());
        return tag;
    }

    String describe() {
        return "phase="+(courtStart<0?"idle":courtContactTicks>0?"contact":courtNearTicks<=COURT_ACCEPT_TICKS?"accept":"align")
                +" nearTicks="+courtNearTicks+" contactTicks="+courtContactTicks+" requiredTicks="+courtRequiredTicks
                +" mother="+(courtStart<0?"pending":initiatorMother?"initiator":"receiver")+" pathFailures="+courtPathFailures;
    }

    void resetCourtProgress(){courtNearTicks=courtContactTicks=courtRequiredTicks=0;courtStart=courtContactStart=-1;}

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
        return o.adult()&&!o.sterile()&&!o.elder()&&o.recovery()==0&&o.entity().isAlive()&&!o.entity().body().dying()&&o.entity().body().coreIntact()&&!o.entity().isPanicking()
                &&!o.entity().isCommandPlaying()&&!o.entity().operations().active()&&!o.entity().predator().active()&&!o.entity().agitation().blocksCourtship()
                &&!o.entity().isNoAi()&&!o.entity().pet().busy()&&!o.entity().life().isSleeping()&&!o.entity().life().sedated()
                &&!o.entity().life().hasUrgentNeed()&&o.entity().life().nutrition()>=65&&o.entity().life().energy()>=50&&o.entity().life().hygiene()>=40
                &&o.entity().getHealth()>=o.entity().getMaxHealth()*.8&&o.entity().body().condition().readyForReproduction()&&o.entity().life().mealTicks()==0&&(!needHome
                        ||o.nestStand()!=null||o.boatHome()!=null||o.shoreCourtStand());
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
                ||o.entity().profile().houseBody()!=other.profile().houseBody()||o.entity().profile().dangerToYukkuri()!=other.profile().dangerToYukkuri()
                        ||o.entity().profile().aquatic()!=other.profile().aquatic())return false;
        var aquatic=SubspeciesLife.Kind.AQUATIC_MARISA;
        return (o.entity().subspecies().kind()==aquatic)==(other.subspecies().kind()==aquatic)&&!Bonds.biological(o.entity(), other);
    }

    /** Rolled once per courtship attempt for owned pets; the unauthorized partner is remembered so that conception can count it. */
    boolean allowCourt(YukkuriEntity other) {
        int gate=Math.max(o.entity().pet().courtshipGate(other),other.pet().courtshipGate(o.entity()));
        GameplayLog.limited(o.entity(),"family","court.gate",200,
                "courtship permission partner={} gate={} allowed={} unauthorized={}",
                other.getUUID(),gate,gate!=2,gate==1);
        if(gate==2){o.waitTicks(600);PetScenes.courtshipHeld(o.entity(),other);return false;}
        unauthorizedWith=gate==1?other.getUUID():null;return true;
    }

    boolean canReproduceWith(YukkuriEntity other) {
        boolean arr=other.getUUID().equals(arranged)||o.entity().getUUID().equals(other.family().arrangedMate());
        if(other==o.entity()||!arr&&(o.entity().dailyLife().active()||other.dailyLife().active())||o.entity().level()!=other.level()||!ready(!arr)||!other.family().ready(!arr)
                ||!arr&&o.entity().distanceToSqr(other)>9
                ||o.entity().profile().houseBody()!=other.profile().houseBody()||o.entity().profile().dangerToYukkuri()!=other.profile().dangerToYukkuri()||!arr
                        &&(!o.entity().life().canRecognize(other)||!other.life().canRecognize(o.entity())))return false;
        if(!FamilyData.get(o.level()).canPair(o.entity(),other,true))return false;
        // The aquatic kind only pairs within itself: aquatic couples are two aquatic Marisa.
        var aquatic=SubspeciesLife.Kind.AQUATIC_MARISA;
        if((o.entity().subspecies().kind()==aquatic)!=(other.subspecies().kind()==aquatic))return false;
        var data=FamilyData.get(o.level());var relations=YukkuriWorldData.get(o.level());
        if(Bonds.biological(o.entity(),
                other)||!arr&&(relations.affinity(o.entity().getUUID(),other.getUUID())<BondLevels.AFFINITY_COOPERATIVE||relations.affinity(other.getUUID(),
                        o.entity().getUUID())<BondLevels.AFFINITY_COOPERATIVE))return false;
        if(arr)return true;
        // Afloat in her own hat boat the boat is the nest: neither a shore nor a claimed nest is needed.
        var a=o.nestStand();var b=other.family().nestStand();
        if(a==null&&o.boatHome()!=null)a=o.entity().blockPosition();
        if(b==null&&other.family().boatHome()!=null)b=other.blockPosition();
        if(a==null&&o.shoreCourtStand())a=o.entity().blockPosition();
        if(b==null&&other.family().shoreCourtStand())b=other.blockPosition();
        return a!=null&&b!=null&&a.distSqr(b)<=64&&(o.boatHome()!=null||other.family().boatHome()!=null||o.entity().life().reachable(other.blockPosition(),false));
    }

    private YukkuriEntity mother(YukkuriEntity first,YukkuriEntity second) {
        if(first.getVariant()==YukkuriVariant.MARISA&&second.getVariant()==YukkuriVariant.REIMU)return second;
        if(first.getVariant()==YukkuriVariant.REIMU&&second.getVariant()==YukkuriVariant.MARISA)return first;
        return initiatorMother?first:second;
    }

    private Vec3 rearStand(YukkuriEntity receiver,YukkuriEntity giver) {
        var forward=Vec3.directionFromRotation(0,receiver.getYRot());
        double spacing=(receiver.getBbWidth()+giver.getBbWidth())*.5*.9;
        return receiver.position().subtract(forward.scale(spacing));
    }

    private boolean rearAligned(FamilyLife leader) {
        var mother=mother(leader.entity(),leader.target());var father=mother==leader.entity()?leader.target():leader.entity();
        return father.distanceToSqr(rearStand(mother,father))<=.36&&father.distanceToSqr(mother)<=2.25;
    }

    /** The receiver turns away and the father really walks behind her; neither side is teleported into contact. */
    private void tickRearApproach(FamilyLife self,FamilyLife leader) {
        var court=leader.courtship();var mother=mother(leader.entity(),leader.target());var father=mother==leader.entity()?leader.target():leader.entity();
        if(self.entity()==mother) {
            self.entity().getNavigation().stop();
            float yaw=(float)(Mth.atan2(mother.getZ()-father.getZ(),mother.getX()-father.getX())*Mth.RAD_TO_DEG)-90;
            self.face(Mth.approachDegrees(mother.getYRot(),yaw,8));
        } else {
            var rear=rearStand(mother,father);
            float yaw=(float)(Mth.atan2(mother.getZ()-father.getZ(),mother.getX()-father.getX())*Mth.RAD_TO_DEG)-90;
            self.face(Mth.approachDegrees(father.getYRot(),yaw,8));
            if(father.distanceToSqr(rear)>.16&&leader.courtship().courtContactTicks==0) {
                if(court.courtNearTicks%10==1) {
                    if(father.life().navigate(net.minecraft.core.BlockPos.containing(rear),.8,false))court.courtPathFailures=0;
                    else if(++court.courtPathFailures>=3){court.courtStopped("rear_path_failed");leader.stop();}
                }
            } else father.getNavigation().stop();
        }
    }

    private void faceRearContact(FamilyLife self,FamilyLife leader) {
        var mother=mother(leader.entity(),leader.target());var father=mother==leader.entity()?leader.target():leader.entity();
        self.entity().getNavigation().stop();
        float yaw=(float)(Mth.atan2(mother.getZ()-father.getZ(),mother.getX()-father.getX())*Mth.RAD_TO_DEG)-90;
        self.face(Mth.approachDegrees(self.entity().getYRot(),yaw,8));
    }

    void tickCourt() {
        if(!canReproduceWith(o.target())){
            courtStopped("pair_requirements_changed");o.stop();return;
        }
        o.entity().getLookControl().setLookAt(o.target(),20,20);
        // The other goal must actually accept this individual, not merely happen to stand nearby.
        if(o.target().family().task()!=Task.RECEIVE_COURT||o.target().family().target()!=o.entity()) {
            resetCourtProgress();o.entity().getNavigation().stop();
            if(o.target().family().task()!=Task.NONE){courtStopped("partner_busy");o.stop();}
            return;
        }
        if(o.entity().distanceToSqr(o.target())>2.25||!o.entity().hasLineOfSight(o.target())||!o.target().hasLineOfSight(o.entity())
                ||!o.supported()||!o.target().family().supported()) {
            resetCourtProgress();
            if(o.taskTicks()%10==1) {
                if(o.entity().life().navigate(o.target().blockPosition(),.8,false))courtPathFailures=0;
                else if(++courtPathFailures>=3){courtStopped("path_failed");o.stop();}
            }
            return;
        }
        if(courtNearTicks==0){
            courtStart=o.level().getGameTime();courtRequiredTicks=o.sexDrive()>0?60:120;
            initiatorMother=!(o.entity().getVariant()==YukkuriVariant.MARISA&&o.target().getVariant()==YukkuriVariant.REIMU);
            GameplayLog.limited(o.entity(),"family","court.accept",200,
                    "courtship acceptance started partner={} requiredTicks={}",o.target().getUUID(),courtRequiredTicks);
        }
        courtNearTicks++;
        if(courtNearTicks<=COURT_ACCEPT_TICKS){o.entity().getNavigation().stop();facePartner();return;}
        if(courtContactTicks==0) {
            tickRearApproach(o,o);
            if(o.task()==Task.COURT&&rearAligned(o)) {
                courtContactTicks=1;courtContactStart=o.level().getGameTime();
                GameplayLog.event(o.entity(),"family","courtship contact started partner={} requiredTicks={} mother={} father={}",
                        o.target().getUUID(),courtRequiredTicks,mother(o.entity(),o.target()).getUUID(),
                        (mother(o.entity(),o.target())==o.entity()?o.target():o.entity()).getUUID());
            }
            return;
        }
        if(!rearAligned(o)) {
            GameplayLog.limited(o.entity(),"family","court.contact_lost",200,"courtship rear contact lost partner={} contactTicks={}",o.target().getUUID(),courtContactTicks);
            courtContactTicks=0;courtContactStart=-1;tickRearApproach(o,o);return;
        }
        faceRearContact(o,o);
        if(++courtContactTicks>=courtRequiredTicks) {
            YukkuriEntity peer=o.target(),mother=mother(o.entity(),peer),father=mother==o.entity()?peer:o.entity();
            ReproductionEffects.release(o.level(),mother);
            boolean unauthorized=peer.getUUID().equals(unauthorizedWith);
            boolean motherHeat=mother.family().inBreedingPeriod(),fatherHeat=father.family().inBreedingPeriod();
            var brood=FamilyData.get(o.level()).conceive(mother,father,mother.family().naturalBirthKind(father),GlobalPos.of(o.level().dimension(),mother.family().birthAnchor()));
            GameplayLog.event(o.entity(),"family","courtship contact ended partner={} mother={} father={} ticks={} conceived={} unauthorized={}",
                    peer.getUUID(),mother.getUUID(),father.getUUID(),courtContactTicks,brood!=null,unauthorized);
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

    private void courtStopped(String reason){
        GameplayLog.limited(o.entity(),"family","court.stop."+reason,200,
                "courtship stopped reason={} partner={} nearTicks={} contactTicks={} requiredTicks={} pathFailures={}",
                reason,o.target()==null?null:o.target().getUUID(),courtNearTicks,courtContactTicks,courtRequiredTicks,courtPathFailures);
    }

    void tickReceiver() {
        if(o.target().family().task()!=Task.COURT||o.target().family().target()!=o.entity()||!canReproduceWith(o.target())){o.stop();return;}
        var leader=o.target().family();
        if(leader.courtship().courtNearTicks<COURT_ACCEPT_TICKS) {
            o.entity().getNavigation().stop();o.entity().getLookControl().setLookAt(o.target(),20,20);return;
        }
        if(leader.courtship().courtContactTicks==0)tickRearApproach(o,leader);
        else faceRearContact(o,leader);
    }
}
