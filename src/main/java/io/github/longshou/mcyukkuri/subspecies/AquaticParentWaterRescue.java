package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.BraidRescueGeometry;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.crew.CrewTransfers;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * A capsized captain can keep herself in the water long enough to lift one actually ejected child onto the hat she
 * has already righted. This class is the decision only (child first or self first, which child, how many tries inside
 * the window); the lift itself is a crew transfer with the swimming captain as carrier, so a failed pull leaves both
 * bodies in the ordinary water-distress path.
 */
final class AquaticParentWaterRescue {
    private enum Phase { IDLE, RENDEZVOUS, LIFT }
    private static final int LIMIT=300, RETRY_TICKS=20, MAX_PHYSICAL_ATTEMPTS=2;
    /** One existing finite self-rescue physical budget plus two complete parent-pull attempts and their intervening waits. */
    private static final int RESCUE_WINDOW_TICKS=AquaticSelfRescue.MAX_ATTEMPT_TICKS
            +MAX_PHYSICAL_ATTEMPTS*(LIMIT+RETRY_TICKS);
    private final YukkuriEntity parent;
    private final List<UUID> releasedYoung=new ArrayList<>();
    private UUID sourceHat, hatId, childId;
    private Phase phase=Phase.IDLE;
    private boolean decided, childFirst;
    private int ticks, physicalAttempts;
    /** A parent who declined this emergency does not reroll when the same capsize is observed again. */
    private long declinedUntil;
    /** A failed physical pull retries the same capsize decision after the water state has had time to change. */
    private long retryAt;
    /** This capsize's child-first choice shares the self-rescue attempt window, including righting and retry waits. */
    private long rescueDeadline;
    private boolean retryPending;
    private String lastFailure="";
    private long phaseStart;

    AquaticParentWaterRescue(YukkuriEntity parent){this.parent=parent;}
    private ServerLevel level(){return (ServerLevel)parent.level();}
    boolean active(){return phase!=Phase.IDLE;}
    /** True only while this accepted child-first choice still intentionally delays the ordinary self rescue. */
    boolean blockingSelfRescue(){return active()||retryPending;}
    boolean targets(YukkuriEntity child){return active()&&child!=null&&child.getUUID().equals(childId);}
    boolean holds(YukkuriEntity child){return child!=null&&child.getUUID().equals(childId)&&phase==Phase.LIFT&&child.family().crew().holdsBody();}
    String statusDetail(){
        String decision=!decided?"none":childFirst?"child_first":"self_first";
        long retry=retryPending?Math.max(0,retryAt-level().getGameTime()):0;
        long remaining=childFirst?Math.max(0,rescueDeadline-level().getGameTime()):0;
        return "decision="+decision+" phase="+phase+" retry="+retry+" source="+(sourceHat==null?"none":sourceHat)
                +" target="+(childId==null?"none":childId)+" released="+releasedYoung.size()+" attempts="+physicalAttempts+" window="+remaining
                +" failure="+(lastFailure.isEmpty()?"none":lastFailure);
    }
    void clear(){
        if(decided||active()||retryPending)GameplayLog.limited(parent,"aquatic","parent_rescue.reset",200,
                "parent rescue reset hat={} child={} phase={} childFirst={} lastFailure={}",sourceHat,childId,phase,childFirst,lastFailure);
        cancel();
        sourceHat=null;releasedYoung.clear();decided=childFirst=retryPending=false;retryAt=rescueDeadline=0;lastFailure="";ticks=physicalAttempts=0;
    }
    private HatBoatEntity hat(){return hatId!=null&&level().getEntity(hatId) instanceof HatBoatEntity h?h:null;}

    /** The capsize supplies the exact passenger snapshot; no world scan may invent a rescue target. */
    void onFamilyCapsize(HatBoatEntity boat,List<UUID> young){
        if(boat==null||!parent.getUUID().equals(boat.ownerYukkuri()))return;
        clear();
        sourceHat=boat.getUUID();releasedYoung.addAll(young);hatId=childId=null;phase=Phase.IDLE;
        long now=level().getGameTime();
        // One real danger receives one friendliness-led decision. Attachment never turns a parent into a rescuer.
        boolean coolingDown=now<declinedUntil;
        float roll=coolingDown?Float.NaN:parent.getRandom().nextFloat();
        var probability=coolingDown?0:parent.life().personality().helpProbability(true,true);
        childFirst=!coolingDown&&roll<probability;
        if(!childFirst)declinedUntil=now+1200;
        else rescueDeadline=now+RESCUE_WINDOW_TICKS;
        decided=true;
        GameplayLog.event(parent,"aquatic","capsize rescue decision hat={} young={} childFirst={} probability={} coolingDown={} deadline={}",
                sourceHat,releasedYoung,childFirst,probability,coolingDown,rescueDeadline);
    }

    /** Called only after the existing self-rescue has physically righted its own real hat. */
    boolean beginAfterRighting(HatBoatEntity hat){
        if(!decisionStillValid(hat)){abandonDecision("decision_invalid");return false;}
        if(hat.unsteadyMotion()){scheduleRetry("hull");return false;}
        var child=chooseYoung(hat);
        if(child==null){abandonDecision("no_liftable_ejected_child");return false;}
        if(!BraidRescueGeometry.canLift(parent,child)){scheduleRetry("energy");return false;}
        if(physicalAttempts>=MAX_PHYSICAL_ATTEMPTS){abandonDecision("physical_attempt_limit");return false;}
        physicalAttempts++;
        hatId=hat.getUUID();childId=child.getUUID();ticks=0;setPhase(Phase.RENDEZVOUS);
        retryPending=false;
        child.waterDistress().guideToWaterRescue(parent,hat);
        parent.showReaction("teary","braid_rescue_offer",40);
        return true;
    }

    /** Retry only a failed physical pull. The original capsizing decision is retained; no new personality roll occurs. */
    boolean retryAfterFailure(HatBoatEntity hat){
        if(!retryPending||active())return false;
        if(windowElapsed()){
            abandonDecision("retry_window_elapsed");
            return false;
        }
        if(!decisionStillValid(hat)){abandonDecision("decision_invalid");return false;}
        // Keep the ordinary self-rescue controller from boarding first while this child-first decision waits for
        // a transient hull/contact condition to clear.  The same real source hat is checked on every wait tick.
        if(level().getGameTime()<retryAt||hat.unsteadyMotion())return true;
        retryPending=false;
        return beginAfterRighting(hat)||retryPending;
    }

    private boolean decisionStillValid(HatBoatEntity hat){
        return decided&&childFirst&&hat!=null&&hat.getUUID().equals(sourceHat)&&hat.isAfloat()
&&!parent.isPassenger()&&!selfCritical()&&!windowElapsed();
    }
    private boolean windowElapsed(){return rescueDeadline>0&&level().getGameTime()>=rescueDeadline;}
    private void scheduleRetry(String failure){
        if(windowElapsed()){
            lastFailure=failure;
            abandonDecision("rescue_window_elapsed");
            return;
        }
        if(physicalAttempts>=MAX_PHYSICAL_ATTEMPTS){
            lastFailure=failure;
            abandonDecision("physical_attempt_limit");
            return;
        }
        GameplayLog.limited(parent,"aquatic","parent_rescue.retry."+failure,200,
                "parent rescue retry hat={} child={} phase={} reason={} attempt={}/{} energy={} health={} retryTicks={}",
                sourceHat,childId,phase,failure,physicalAttempts,MAX_PHYSICAL_ATTEMPTS,parent.life().energy(),parent.getHealth(),RETRY_TICKS);
        cancel();
        retryPending=true;retryAt=level().getGameTime()+RETRY_TICKS;lastFailure=failure;
    }

    private void abandonDecision(String reason){
        if(decided||active()||retryPending)GameplayLog.event(parent,"aquatic",
                "parent rescue ended hat={} child={} phase={} reason={} lastFailure={} remaining={}",
                sourceHat,childId,phase,reason,lastFailure,releasedYoung.size());
        cancel();
        decided=childFirst=retryPending=false;retryAt=rescueDeadline=0;lastFailure="";
    }

    boolean tick(HatBoatEntity hat){
        if(!active())return false;
        if(windowElapsed()){abandonDecision("rescue_window_elapsed");return false;}
        var child=child();
        if(hat==null||!hat.getUUID().equals(hatId)||!hat.isAfloat()
                ||parent.isPassenger()||selfCritical()){abandonDecision("hat_or_parent_unavailable");return false;}
        if(hat.unsteadyMotion()){scheduleRetry("hull");return false;}
        if(phase==Phase.LIFT){
            // The transfer ticket carries the lift; this class only reads how it ended.
            if(parent.family().crew().active())return true;
            if(child!=null&&child.getVehicle()==hat){boarded(child,hat);return false;}
            var outcome=parent.family().crew().last();
            scheduleRetry(outcome==null||outcome.reason().isEmpty()?"contact":outcome.reason());return false;
        }
        if(child==null||child.isPassenger()||!hat.young(child)||++ticks>LIMIT||!child.isInWaterOrBubble()){scheduleRetry("target");return false;}
        child.waterDistress().guideToWaterRescue(parent,hat);
        parent.getNavigation().stop();SurfaceApproach.face(parent,SurfaceApproach.yaw(parent.position(),child.position()));
        if(!BraidRescueGeometry.canLift(parent,child)){
            if(!fitsBraidAtFullStrength(child)){skipOversizeCandidate(hat,child);return false;}
            scheduleRetry("energy");return false;
        }
        var lift=CrewTransfers.liftFromWater(child,parent,hat);
        if(lift.ok()){
            setPhase(Phase.LIFT);child.waterDistress().lifted();
            return true;
        }
        // Not in reach yet: a small swimming impulse toward the child; the transfer refuses until the braid reaches.
        if(!swimToward(child.position())){scheduleRetry(lastFailure);return false;}
        return true;
    }

    private void boarded(YukkuriEntity child,HatBoatEntity hat){
        child.waterDistress().clearRescueHat(hat);
        child.life().personality().relationshipChanged(parent.getUUID(),10,-8);
        GameplayLog.event(parent,"aquatic","parent rescue boarded hat={} child={} ticks={} remaining={}",
                hat.getUUID(),child.getUUID(),ticks,releasedYoung.size()-1);
        setPhase(Phase.IDLE);releasedYoung.remove(child.getUUID());childId=null;
        physicalAttempts=0;
        if(chooseYoung(hat)!=null){retryPending=true;retryAt=level().getGameTime();lastFailure="";}
        else abandonDecision("all_available_children_resolved");
    }

    private YukkuriEntity chooseYoung(HatBoatEntity hat){
        return releasedYoung.stream().map(this::person).filter(child->child!=null&&child.isInWaterOrBubble()&&hat.young(child)
                &&fitsBraidAtFullStrength(child))
                .min(Comparator.comparingDouble(YukkuriEntity::getHealth).thenComparingDouble(parent::distanceToSqr)).orElse(null);
    }
    /** Energy changes the current lift limit; this full-strength volume ceiling cannot become true by waiting in water. */
    private boolean fitsBraidAtFullStrength(YukkuriEntity child){return BraidRescueGeometry.canLift(parent,child,true);}
    /** Keep the already-made child-first decision for another real, liftable ejected child, never for this impossible body. */
    private void skipOversizeCandidate(HatBoatEntity hat,YukkuriEntity child){
        GameplayLog.event(parent,"aquatic","parent rescue candidate skipped hat={} child={} reason=size",hat.getUUID(),child.getUUID());
        releasedYoung.remove(child.getUUID());cancel();lastFailure="size";
        if(chooseYoung(hat)!=null){retryPending=true;retryAt=level().getGameTime();return;}
        abandonDecision("no_liftable_ejected_child");
    }
    private YukkuriEntity child(){return person(childId);}
    private YukkuriEntity person(UUID id){return id!=null&&level().getEntity(id) instanceof YukkuriEntity y&&y.isAlive()?y:null;}
    private boolean selfCritical(){return parent.getHealth()<=Math.max(2,parent.getMaxHealth()*.25f)||parent.life().energy()<12;}
    private void setPhase(Phase next){
        if(phase!=next){
            GameplayLog.limited(parent,"aquatic","parent_rescue.phase."+next,200,
                    "parent rescue phase {}->{} hat={} child={}",phase,next,sourceHat,childId);
            phase=next;phaseStart=level().getGameTime();
        }
    }

    /** Water movement is a small impulse and consumes effort; it never teleports the parent to the child. */
    private boolean swimToward(Vec3 target){
        if(!parent.life().spendEnergy(.018f)){lastFailure="energy";return false;}
        var delta=target.subtract(parent.position()).multiply(1,0,1);
        if(delta.horizontalDistanceSqr()<.0001)return true;
        var direction=delta.normalize();
        var step=direction.scale(.08);
        if(!level().noCollision(parent,parent.getBoundingBox().move(step))){lastFailure="swim";return false;}
        var velocity=parent.getDeltaMovement();
        if(velocity.horizontalDistanceSqr()<.09*.09){parent.setDeltaMovement(velocity.add(direction.scale(.01)));parent.hasImpulse=true;}
        return true;
    }
    private void cancel(){
        var child=child();
        if(child!=null){
            if(parent.family().crew().active())parent.family().cancelCrew();
            var home=hat();
            if(home!=null){home.cancelIncoming(child);home.syncSeats();}
            child.waterDistress().clearRescueHat(home);
        }
        setPhase(Phase.IDLE);childId=null;
    }
    CompoundTag save(){var tag=new CompoundTag();tag.putLong("DeclinedUntil",declinedUntil);return tag;}
    void load(CompoundTag tag){declinedUntil=Math.max(0,tag.getLong("DeclinedUntil"));}
}
