package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.family.FamilyLife.Task;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.pet.PetLife;
import io.github.longshou.mcyukkuri.pet.PetScenes;
import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.status.GameplayLog;
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
    /** How long a candidate leaves a paired adult alone after her back turned him down: half a heat period, like a held permit. */
    private static final int REFUSAL_PAUSE=600;
    /** How far he walks back to a partner whose own work cut the last round short (the family search range). */
    private static final double COMEBACK_RANGE=12;
    /** How far round from facing him her body has to be before her back is an answer he can read. */
    private static final float REFUSAL_TURN=60;
    /** Temporary arrangement and the owner-unpermitted courtship partner. */
    private UUID arranged, unauthorizedWith;
    /** This round is the refusal he came to hear; this round is his walk back to a partner another goal took from him. */
    private boolean declinedRound, returning;
    /** The partner a cut-short round left him (kept until he can go back), the adult he just asked and when he may ask again. */
    private UUID comeBack, asked, askedUntilBy;
    private long askedUntil;
    private int declineTicks;
    private boolean initiatorMother=true;

    Courtship(FamilyLife o) { this.o=o; }
    UUID arranged() { return arranged; }
    void resetAttempt() { resetCourtProgress();courtPathFailures=0;declinedRound=false;returning=false;declineTicks=0;comeBack=null;asked=null; }
    /**
     * Ending a round keeps the comeback it just earned (the stop that follows the interruption must not erase it); a declined round
     * that ends, answered or not, leaves that adult alone for a while: the same answer is never asked again at once.
     */
    void reset() {
        if(declinedRound&&asked!=null){askedUntilBy=asked;askedUntil=o.level().getGameTime()+REFUSAL_PAUSE;}
        resetCourtProgress();courtPathFailures=0;declinedRound=false;returning=false;declineTicks=0;asked=null;unauthorizedWith=null;
    }

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
                        ||o.nestStand()!=null);
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
        return !Bonds.biological(o.entity(), other);
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
        return pairingAllowed(other)&&withinCourtReach(other);
    }

    /**
     * Everything the pairing itself requires of the two, before the walk: readiness, recognition, the ledger's pairing and the
     * shared nest area. Split out so a comeback can walk back to a partner it is no longer within the court's reach of.
     */
    private boolean pairingAllowed(YukkuriEntity other) {
        boolean arr=other.getUUID().equals(arranged)||o.entity().getUUID().equals(other.family().arrangedMate());
        if(other==o.entity()||!arr&&(o.entity().dailyLife().active()||other.dailyLife().active())||o.entity().level()!=other.level()||!ready(!arr)||!other.family().ready(!arr)
                ||o.entity().profile().houseBody()!=other.profile().houseBody()||o.entity().profile().dangerToYukkuri()!=other.profile().dangerToYukkuri()||!arr
                        &&(!o.entity().life().canRecognize(other)||!other.life().canRecognize(o.entity())))return false;
        if(!FamilyData.get(o.level()).canPair(o.entity(),other,true))return false;
        // The aquatic kind only pairs within itself: aquatic couples are two aquatic Marisa.
        if(o.entity().profile().aquatic()!=other.profile().aquatic())return false;
        var relations=YukkuriWorldData.get(o.level());
        if(Bonds.biological(o.entity(),
                other)||!arr&&(relations.affinity(o.entity().getUUID(),other.getUUID())<BondLevels.AFFINITY_COOPERATIVE||relations.affinity(other.getUUID(),
                        o.entity().getUUID())<BondLevels.AFFINITY_COOPERATIVE))return false;
        if(arr)return true;
        var a=o.nestStand();var b=other.family().nestStand();
        return a!=null&&b!=null&&a.distSqr(b)<=64;
    }

    /** In touch: close enough to court with a real route between them (an arranged pairing never needed the walk). */
    private boolean withinCourtReach(YukkuriEntity other) {
        boolean arr=other.getUUID().equals(arranged)||o.entity().getUUID().equals(other.family().arrangedMate());
        return other!=o.entity()&&o.entity().level()==other.level()
                &&(arr||o.entity().distanceToSqr(other)<=9&&o.entity().life().reachable(other.blockPosition(),false));
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
        if(!stillWanted()){
            courtStopped("pair_requirements_changed");o.stop();return;
        }
        o.entity().getLookControl().setLookAt(o.target(),20,20);
        if(declinedRound){tickDeclined();return;}
        // The other goal must actually accept this individual, not merely happen to stand nearby.
        if(o.target().family().task()!=Task.RECEIVE_COURT||o.target().family().target()!=o.entity()) {
            resetCourtProgress();o.entity().getNavigation().stop();
            if(o.target().family().task()!=Task.NONE){courtStopped("partner_busy");o.stop();return;}
            // Free again and not yet answering: he really walks back over before her answer can begin.
            if(returning&&o.taskTicks()%10==1) {
                if(o.entity().life().navigate(o.target().blockPosition(),.8,false))courtPathFailures=0;
                else if(++courtPathFailures>=3){courtStopped("path_failed");o.stop();}
            }
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
            o.entity().social().noteContact(peer);peer.social().noteContact(o.entity());
            peer.family().stop();peer.family().waitTicks(200);
            o.stop();o.waitTicks(200);
        }
    }

    /**
     * The round lives while the pair can really pair, while he is still walking back to a partner another goal took from him, or
     * while it is the refusal he came to hear (her partner still stands, so no pairing can come of it).
     */
    boolean stillWanted() {
        var peer=o.target();
        if(peer==null)return false;
        if(canReproduceWith(peer))return true;
        if(declinedRound)return declinedPairing(peer);
        return returning&&pairingAllowed(peer)&&inComebackRange(peer);
    }

    /** The candidate searches for an adult whose recorded partner still stands: her back is the whole of the answer. */
    boolean refusedPairing(YukkuriEntity other) {
        return declinedPairing(other)&&(o.level().getGameTime()>=askedUntil||!other.getUUID().equals(askedUntilBy));
    }

    /** A pairing that only the ledger's standing partner blocks, both of them ready, in heat and free to answer. */
    private boolean declinedPairing(YukkuriEntity other) {
        return other.isAlive()&&other.level()==o.entity().level()&&!other.dailyLife().active()
                &&!standingPartner(o.entity())&&standingPartner(other)
                &&ready()&&other.family().ready()&&o.inBreedingPeriod()&&other.family().inBreedingPeriod();
    }

    /** A partner the ledger still holds as not dead: it blocks the pairing whether or not that partner is loaded right now. */
    private boolean standingPartner(YukkuriEntity other) {
        UUID mate=other.family().partner();
        if(mate==null||mate.equals(o.entity().getUUID()))return false;
        var person=FamilyData.get(o.level()).person(mate);
        return person!=null&&person.status!=FamilyData.Status.DEAD;
    }

    /** He walks up to her and waits where she stands: her back is the answer he came for, and he reads it from her body. */
    private void tickDeclined() {
        var peer=o.target();
        if(o.entity().distanceToSqr(peer)>2.25||!o.entity().hasLineOfSight(peer)) {
            if(o.taskTicks()%10==1) {
                if(o.entity().life().navigate(peer.blockPosition(),.8,false))courtPathFailures=0;
                else if(++courtPathFailures>=3){courtStopped("path_failed");o.stop();}
            }
            return;
        }
        o.entity().getNavigation().stop();
        float facing=(float)(Mth.atan2(peer.getZ()-o.entity().getZ(),peer.getX()-o.entity().getX())*Mth.RAD_TO_DEG)-90;
        if(peer.family().task()==Task.RECEIVE_COURT&&peer.family().target()==o.entity()
                &&Math.abs(Mth.wrapDegrees(facing-peer.getYRot()))>=REFUSAL_TURN) {
            o.entity().showReaction("confused","",80);
            o.stop();
        }
    }

    /** The paired adult's own answer: she turns her back on him and keeps one face until the turn is complete. */
    void tickDeclinedAnswer(FamilyLife leader) {
        o.entity().getNavigation().stop();
        if(declineTicks++==0) {
            o.entity().showReaction("dull","",80);
            GameplayLog.limited(o.entity(),"family","court.refused.partner_alive",200,
                    "court refused reason=partner_alive partner={} distance={}",
                    leader.entity().getUUID(),Math.round(o.entity().distanceTo(leader.entity())*10)/10.0);
        }
        float away=(float)(Mth.atan2(o.entity().getZ()-leader.entity().getZ(),o.entity().getX()-leader.entity().getX())*Mth.RAD_TO_DEG)-90;
        if(Math.abs(Mth.wrapDegrees(away-o.entity().getYRot()))<=Face.FACE_ARRIVED)o.stop();
        else o.face(away);
    }

    /** Her round is the answer to a refusal he came to get: it stands without the pairing ever being possible. */
    boolean answeringDecline() {
        return o.target()!=null&&o.target().family().task()==Task.COURT&&o.target().family().target()==o.entity()
                &&o.target().family().courtship().declinedRound;
    }

    /** The partner whose own work cut the last round short, once she is free, in reach and both are still in heat. */
    YukkuriEntity comeback() {
        if(comeBack==null)return null;
        var mate=o.level().getEntity(comeBack);
        if(!(mate instanceof YukkuriEntity peer)||!peer.isAlive()||!pairingAllowed(peer)||!peer.family().inBreedingPeriod()) {
            comeBack=null;return null;
        }
        // Busy or far off just now: he keeps it in mind until the heat period ends.
        return o.inBreedingPeriod()&&peer.family().task()==Task.NONE&&inComebackRange(peer)?peer:null;
    }

    /** He leaves for a partner another goal took him away from: a real walk back, never a second try at the same spot. */
    void beginComeback() { resetAttempt();returning=true; }

    /** This round is a refusal he will hear: her standing partner, not the walk, is what keeps the pair apart. */
    void beginDecline() { resetAttempt();declinedRound=true;asked=o.target()==null?null:o.target().getUUID(); }

    /** In sight and within reach for the walk back; the court's own contact still needs the touch range. */
    private boolean inComebackRange(YukkuriEntity peer) {
        return o.entity().distanceToSqr(peer)<=COMEBACK_RANGE*COMEBACK_RANGE&&o.entity().hasLineOfSight(peer);
    }

    void courtStopped(String reason){
        var peer=o.target();
        var mother=peer==null?null:mother(o.entity(),peer);var father=mother==null?null:mother==o.entity()?peer:o.entity();
        // A round another goal cut short leaves him a comeback: he walks back over once she is free and both are still in heat.
        if(!returning&&!declinedRound&&peer!=null&&peer.isAlive()&&o.inBreedingPeriod()&&peer.family().inBreedingPeriod()
                &&(reason.equals("partner_busy")||reason.equals("time_cap")||reason.equals("pair_requirements_changed"))
                &&pairingAllowed(peer))comeBack=peer.getUUID();
        GameplayLog.limited(o.entity(),"family","court.stop."+reason,200,
                "courtship stopped reason={} partner={} nearTicks={} contactTicks={} requiredTicks={} pathFailures={} distance={} rearDistance={} fatherNavDone={}",
                reason,peer==null?null:peer.getUUID(),courtNearTicks,courtContactTicks,courtRequiredTicks,courtPathFailures,
                peer==null?null:Math.round(o.entity().distanceTo(peer)*100)/100.0,
                mother==null?null:Math.round(Math.sqrt(father.distanceToSqr(rearStand(mother,father)))*100)/100.0,
                father==null?null:father.getNavigation().isDone());
    }

    void tickReceiver() {
        if(o.target().family().task()!=Task.COURT||o.target().family().target()!=o.entity()
                ||!canReproduceWith(o.target())&&!answeringDecline()){o.stop();return;}
        var leader=o.target().family();
        if(leader.courtship().declinedRound){tickDeclinedAnswer(leader);return;}
        if(leader.courtship().courtNearTicks<COURT_ACCEPT_TICKS) {
            o.entity().getNavigation().stop();o.entity().getLookControl().setLookAt(o.target(),20,20);return;
        }
        if(leader.courtship().courtContactTicks==0)tickRearApproach(o,leader);
        else faceRearContact(o,leader);
    }
}
