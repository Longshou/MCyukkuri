package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.BraidRescueGeometry;
import io.github.longshou.mcyukkuri.family.HatBoarding;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A capsized captain can keep herself in the water long enough to lift one actually ejected child onto the hat she
 * has already righted.  This deliberately does not reuse {@code BraidRescue}: that controller correctly assumes the
 * parent is supported on a steady deck.  Here each braid contact, child movement and final supported mount is checked
 * separately, so a failed pull leaves both bodies in the ordinary water-distress path.
 */
final class AquaticParentWaterRescue {
    private enum Phase { IDLE, RENDEZVOUS, HOLD, LIFT }
    private static final int CONTACT_TICKS=8, LIMIT=300;
    private final YukkuriEntity parent;
    private final List<UUID> releasedYoung=new ArrayList<>();
    private UUID sourceHat, hatId, childId;
    private Vec3 seat;
    private List<Vec3> liftPath=List.of();
    private Phase phase=Phase.IDLE;
    private boolean decided, childFirst;
    private int ticks, contacts, pathIndex;
    /** A parent who declined this emergency does not reroll when the same capsize is observed again. */
    private long declinedUntil;
    /** A failed physical pull retries the same capsize decision after the water state has had time to change. */
    private long retryAt;
    private boolean retryPending;
    private String lastFailure="";
    private long phaseStart, poseTick=Long.MIN_VALUE;
    private Vec3 tip, joint, previousTip, previousJoint;

    AquaticParentWaterRescue(YukkuriEntity parent){this.parent=parent;}
    private ServerLevel level(){return (ServerLevel)parent.level();}
    boolean active(){return phase!=Phase.IDLE;}
    boolean holds(YukkuriEntity child){return child!=null&&child.getUUID().equals(childId)&&(phase==Phase.HOLD||phase==Phase.LIFT);}
    String statusDetail(){
        String decision=!decided?"none":childFirst?"child_first":"self_first";
        long retry=retryPending?Math.max(0,retryAt-level().getGameTime()):0;
        return "decision="+decision+" phase="+phase+" retry="+retry+" source="+(sourceHat==null?"none":sourceHat)
                +" target="+(childId==null?"none":childId)+" released="+releasedYoung.size()
                +" failure="+(lastFailure.isEmpty()?"none":lastFailure);
    }
    void clear(){
        cancel();
        sourceHat=null;releasedYoung.clear();decided=childFirst=retryPending=false;retryAt=0;lastFailure="";ticks=contacts=pathIndex=0;
    }
    private HatBoatEntity hat(){return hatId!=null&&level().getEntity(hatId) instanceof HatBoatEntity h?h:null;}

    /** The capsize supplies the exact passenger snapshot; no world scan may invent a rescue target. */
    void onFamilyCapsize(HatBoatEntity boat,List<UUID> young){
        if(boat==null||!parent.getUUID().equals(boat.ownerYukkuri()))return;
        clear();
        sourceHat=boat.getUUID();releasedYoung.addAll(young);hatId=childId=null;seat=null;phase=Phase.IDLE;
        long now=level().getGameTime();
        // One real danger receives one friendliness-led decision. Attachment never turns a parent into a rescuer.
        childFirst=now>=declinedUntil&&parent.getRandom().nextFloat()<parent.life().personality().helpProbability(true,true);
        if(!childFirst)declinedUntil=now+1200;
        decided=true;
    }

    /** Called only after the existing self-rescue has physically righted its own real hat. */
    boolean beginAfterRighting(HatBoatEntity hat){
        if(!decisionStillValid(hat)){abandonDecision();return false;}
        if(hat.unsteadyMotion()){scheduleRetry("hull");return false;}
        var child=chooseYoung(hat);
        if(child==null){abandonDecision();return false;}
        if(!BraidRescueGeometry.canLift(parent,child)){scheduleRetry("energy");return false;}
        hatId=hat.getUUID();childId=child.getUUID();seat=null;liftPath=List.of();pathIndex=ticks=contacts=0;setPhase(Phase.RENDEZVOUS);
        retryPending=false;
        updateBraid(child,child.position());
        child.waterDistress().guideToWaterRescue(parent,hat);
        parent.showReaction("teary","braid_rescue_offer",40);
        return true;
    }

    /** Retry only a failed physical pull. The original capsizing decision is retained; no new personality roll occurs. */
    boolean retryAfterFailure(HatBoatEntity hat){
        if(!retryPending||active())return false;
        if(!decisionStillValid(hat)){abandonDecision();return false;}
        // Keep the ordinary self-rescue controller from boarding first while this child-first decision waits for
        // a transient hull/contact condition to clear.  The same real source hat is checked on every wait tick.
        if(level().getGameTime()<retryAt||hat.unsteadyMotion())return true;
        retryPending=false;
        return beginAfterRighting(hat)||retryPending;
    }

    private boolean decisionStillValid(HatBoatEntity hat){
        return decided&&childFirst&&hat!=null&&hat.getUUID().equals(sourceHat)&&hat.isAfloat()
                &&!hat.towed()&&!parent.isPassenger()&&!selfCritical();
    }
    private void scheduleRetry(String failure){
        cancel();
        retryPending=true;retryAt=level().getGameTime()+20;lastFailure=failure;
    }

    private void abandonDecision(){
        cancel();
        decided=childFirst=retryPending=false;retryAt=0;lastFailure="";
    }

    boolean tick(HatBoatEntity hat){
        if(!active())return false;
        var child=child();
        if(hat==null||!hat.getUUID().equals(hatId)||!hat.isAfloat()||hat.towed()
                ||parent.isPassenger()||selfCritical()){abandonDecision();return false;}
        if(hat.unsteadyMotion()){scheduleRetry("hull");return false;}
        if(child==null||child.isPassenger()||!hat.young(child)||++ticks>LIMIT
                ||phase!=Phase.LIFT&&!child.isInWaterOrBubble()){scheduleRetry("target");return false;}
        child.waterDistress().guideToWaterRescue(parent,hat);
        parent.getNavigation().stop();SurfaceApproach.face(parent,SurfaceApproach.yaw(parent.position(),child.position()));
        updateBraid(child,child.position());
        if(phase==Phase.RENDEZVOUS){
            if(contact(child)){
                setPhase(Phase.HOLD);contacts=0;
                if(!child.waterDistress().confirmWaterRescueHold(parent,hat)){scheduleRetry("contact");return false;}
                child.waterDistress().lifted();
            }
            else if(!swimToward(child.position())){scheduleRetry(lastFailure);return false;}
            return true;
        }
        if(!child.waterDistress().confirmWaterRescueHold(parent,hat)){scheduleRetry("contact");return false;}
        if(!contact(child)){scheduleRetry("contact");return false;}
        if(!BraidRescueGeometry.canLift(parent,child)){
            if(!fitsBraidAtFullStrength(child)){skipOversizeCandidate(hat,child);return false;}
            scheduleRetry("energy");return false;
        }
        if(!parent.life().spendEnergy(phase==Phase.HOLD?.025f:.04f)){scheduleRetry("energy");return false;}
        child.setDeltaMovement(Vec3.ZERO);child.hasImpulse=true;
        if(phase==Phase.HOLD){
            if(++contacts<CONTACT_TICKS)return true;
            if(!planLift(child,hat)){scheduleRetry("seat");return false;}
            setPhase(Phase.LIFT);return true;
        }
        return lift(child,hat);
    }

    private YukkuriEntity chooseYoung(HatBoatEntity hat){
        return releasedYoung.stream().map(this::person).filter(child->child!=null&&child.isInWaterOrBubble()&&hat.young(child)
                &&fitsBraidAtFullStrength(child))
                .min(Comparator.comparingDouble(YukkuriEntity::getHealth).thenComparingDouble(parent::distanceToSqr)).orElse(null);
    }
    /** Energy changes the current lift limit; this full-strength volume ceiling cannot become true by waiting in water. */
    private boolean fitsBraidAtFullStrength(YukkuriEntity child){
        double parentVolume=parent.getBbWidth()*parent.getBbWidth()*parent.getBbHeight();
        double childVolume=child.getBbWidth()*child.getBbWidth()*child.getBbHeight();
        return childVolume<=parentVolume*.24;
    }
    /** Keep the already-made child-first decision for another real, liftable ejected child, never for this impossible body. */
    private void skipOversizeCandidate(HatBoatEntity hat,YukkuriEntity child){
        releasedYoung.remove(child.getUUID());cancel();lastFailure="size";
        if(chooseYoung(hat)!=null){retryPending=true;retryAt=level().getGameTime();return;}
        decided=childFirst=retryPending=false;retryAt=0;releasedYoung.clear();
    }
    private YukkuriEntity child(){return person(childId);}
    private YukkuriEntity person(UUID id){return id!=null&&level().getEntity(id) instanceof YukkuriEntity y&&y.isAlive()?y:null;}
    private boolean selfCritical(){return parent.getHealth()<=Math.max(2,parent.getMaxHealth()*.25f)||parent.life().energy()<12;}
    private void setPhase(Phase next){
        if(phase!=next){phase=next;phaseStart=level().getGameTime();}
    }

    /** A reachable, unobstructed braid tip has to enter the child's real body box before it can hold the child. */
    private boolean contact(YukkuriEntity child){
        var root=BraidRescueGeometry.root(parent);var tip=BraidRescueGeometry.rescueContact(child,child.position(),root);
        if(!BraidRescueGeometry.reachable(parent,root,tip)||!BraidRescueGeometry.touches(child,tip))return false;
        var joint=BraidRescueGeometry.joint(parent,root,tip,parent.position().add(0,parent.getBbHeight(),0));
        return joint!=null&&clearBraid(root,joint)&&clearBraid(joint,tip);
    }
    private boolean clearBraid(Vec3 from,Vec3 to){
        return level().clip(new ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,parent)).getType()==HitResult.Type.MISS;
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
    /** Reserve only the water-facing, fully supported brim patch nearest the parent, never an inner berth. */
    private boolean planLift(YukkuriEntity child,HatBoatEntity hat){
        seat=hat.reserveRescueBrim(child,parent.position());
        if(!entrySeat(child,hat,seat))return false;
        var entry=hat.toWorld(seat);
        var outward=entry.subtract(hat.position()).multiply(1,0,1);
        if(outward.horizontalDistanceSqr()<1e-6)outward=parent.position().subtract(hat.position()).multiply(1,0,1);
        if(outward.horizontalDistanceSqr()<1e-6)return false;
        outward=outward.normalize();
        double crestY=entry.y+child.getBbHeight()+.02;
        var rise=new Vec3(child.getX(),crestY,child.getZ());
        var outside=new Vec3(entry.x+outward.x*(child.getBbWidth()*.5+.02),crestY,entry.z+outward.z*(child.getBbWidth()*.5+.02));
        var over=new Vec3(entry.x,crestY,entry.z);
        liftPath=List.of(rise,outside,over,entry);pathIndex=0;
        for(var point:liftPath)if(!braidCanReach(child,point))return false;
        return true;
    }
    private boolean entrySeat(YukkuriEntity child,HatBoatEntity hat,Vec3 candidate){
        if(candidate==null||Math.abs(candidate.y-HatVessel.top(hat.mode()))>1e-4)return false;
        double radius=HatVessel.radius(child.getBbWidth())/hat.unit();
        return HatVessel.supported(hat.mode(),candidate.x,candidate.z,radius,HatVessel.top(hat.mode()));
    }
    /** The braid must remain able to reach every planned centimetre; the brim route is rejected before any pull begins. */
    private boolean braidCanReach(YukkuriEntity child,Vec3 feet){
        var root=BraidRescueGeometry.root(parent);var tip=BraidRescueGeometry.rescueContact(child,feet,root);
        if(!BraidRescueGeometry.reachable(parent,root,tip))return false;
        var joint=BraidRescueGeometry.joint(parent,root,tip,parent.position().add(0,parent.getBbHeight(),0));
        return joint!=null&&clearBraid(root,joint)&&clearBraid(joint,tip);
    }
    /** Pull through checked small slices: rise outside the shell, cross above its rim, then lower onto the reserved brim. */
    private boolean lift(YukkuriEntity child,HatBoatEntity hat){
        hat.holdPosition();
        while(pathIndex<liftPath.size()&&child.position().distanceToSqr(liftPath.get(pathIndex))<=.000625)pathIndex++;
        if(pathIndex>=liftPath.size()){
            if(!supported(child,hat)){scheduleRetry("seat");return false;}
            // The actual, supported brim contact is the only point at which mounting is permitted. Existing seat tending
            // may subsequently walk an aboard child inboard; this water rescue never pulls through the hull to an inner seat.
            if(!hat.boardRescuedYoung(child,seat)){scheduleRetry("seat");return false;}
            child.waterDistress().clearRescueHat(hat);
            child.life().personality().relationshipChanged(parent.getUUID(),10,-8);
            setPhase(Phase.IDLE);releasedYoung.remove(child.getUUID());seat=null;liftPath=List.of();childId=null;clearBraidPose();
            if(chooseYoung(hat)!=null){retryPending=true;retryAt=level().getGameTime();lastFailure="";}
            else abandonDecision();
            return false;
        }
        var from=child.position();var destination=liftPath.get(pathIndex);double distance=from.distanceTo(destination);
        var next=from.add(destination.subtract(from).scale(Math.min(.025,distance)/distance));
        if(!braidCanReach(child,next)){scheduleRetry("contact");return false;}
        if(!HatBoarding.sweepClear(child,null,hat,from,next)){scheduleRetry("sweep");return false;}
        // HatBoarding's sweep owns hull geometry and deliberately excludes the hat's coarse entity box. Applying this
        // checked 2.5 cm pose follows its stepping convention; vanilla move would reintroduce that coarse box as a blocker.
        child.setPos(next.x,next.y,next.z);child.setDeltaMovement(Vec3.ZERO);child.getNavigation().stop();child.fallDistance=0;child.hasImpulse=true;
        updateBraid(child,next);
        if(child.position().distanceToSqr(next)>1e-8){scheduleRetry("sweep");return false;}
        return true;
    }
    private boolean supported(YukkuriEntity child,HatBoatEntity hat){
        var local=hat.toLocal(child.position());double floor=HatVessel.footprint(hat.mode(),local.x,local.z,HatVessel.radius(child.getBbWidth())/hat.unit());
        return Double.isFinite(floor)&&Math.abs(local.y-floor)<.03
                &&BraidRescueGeometry.poseClearance(child,child.position(),0,0,child.yBodyRot,hat,null)<=child.getY()+.002;
    }
    private void cancel(){
        var home=hat();
        var child=child();
        if(child!=null){if(home!=null){home.cancelIncoming(child);home.syncSeats();}child.waterDistress().clearRescueHat(home);}
        setPhase(Phase.IDLE);seat=null;liftPath=List.of();pathIndex=0;childId=null;clearBraidPose();
    }

    /** Server-authoritative braid endpoints for the existing client rescue renderer. */
    private void updateBraid(YukkuriEntity child,Vec3 feet){
        long now=level().getGameTime();
        if(poseTick!=now){previousTip=tip;previousJoint=joint;poseTick=now;}
        var root=BraidRescueGeometry.root(parent);
        var nextTip=BraidRescueGeometry.rescueContact(child,feet,root);
        var nextJoint=BraidRescueGeometry.joint(parent,root,nextTip,parent.position().add(0,parent.getBbHeight(),0));
        if(nextJoint==null){tip=joint=null;return;}
        tip=nextTip;joint=nextJoint;
        if(previousTip==null||previousJoint==null){previousTip=tip;previousJoint=joint;}
    }
    private void clearBraidPose(){tip=joint=previousTip=previousJoint=null;poseTick=Long.MIN_VALUE;}
    CompoundTag state(){
        var tag=new CompoundTag();
        if(!active())return tag;
        var child=child();var home=hat();
        if(child==null||home==null)return tag;
        String visible=switch(phase){case RENDEZVOUS->"REACH";case HOLD->"GRIP";case LIFT->"SLIDE";default->"";};
        int duration=switch(phase){case RENDEZVOUS->20;case HOLD->CONTACT_TICKS;case LIFT->Math.max(1,liftPath.size()*8);default->1;};
        tag.putString("Phase",visible);tag.putLong("Start",phaseStart);tag.putUUID("Parent",parent.getUUID());tag.putUUID("Child",child.getUUID());tag.putUUID("Hat",home.getUUID());
        tag.putInt("ParentId",parent.getId());tag.putInt("ChildId",child.getId());tag.putInt("HatId",home.getId());tag.putFloat("Yaw",parent.yBodyRot);tag.putInt("Duration",duration);
        tag.putDouble("Progress",phase==Phase.HOLD?contacts/(double)CONTACT_TICKS:phase==Phase.LIFT?pathIndex/(double)Math.max(1,liftPath.size()):0);
        boolean holding=holds(child);tag.putInt("Bite",0);tag.putInt("BiteTicks",0);tag.putBoolean("Skip",true);tag.putBoolean("Gripped",holding);tag.putBoolean("Braid",holding);tag.putBoolean("WaterParent",true);
        if(holding&&tip!=null&&joint!=null){point(tag,"Tip",tip);point(tag,"Joint",joint);point(tag,"PrevTip",previousTip);point(tag,"PrevJoint",previousJoint);}
        return tag;
    }
    private static void point(CompoundTag tag,String key,Vec3 value){
        if(value==null)return;
        tag.putDouble(key+"X",value.x);tag.putDouble(key+"Y",value.y);tag.putDouble(key+"Z",value.z);
    }
    CompoundTag save(){var tag=new CompoundTag();tag.putLong("DeclinedUntil",declinedUntil);return tag;}
    void load(CompoundTag tag){declinedUntil=Math.max(0,tag.getLong("DeclinedUntil"));}
}
