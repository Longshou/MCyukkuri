package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.surface.Floor;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.Comparator;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

/** One transient agreement between two living actors, including reachable standing places. */
public final class CareApproach {
    public enum Kind { FEED, REAR }
    public enum Result { APPROACHING, CONTACT, FAILED }
    private record Places(Vec3 child, Vec3 parent) {}
    private final YukkuriEntity owner;
    private final Map<UUID, Long> blocked = new HashMap<>();
    private final List<Vec3> rejected = new ArrayList<>();
    private CareApproach incoming;
    private YukkuriEntity child;
    private Kind kind;
    private Places places;
    private List<Vec3> floorRoute;
    private Vec3 lastParent, lastChild;
    private int stalled, retries, bodyContacts;
    private long lease;

    public CareApproach(YukkuriEntity owner) { this.owner = owner; }
    public boolean mayTry(YukkuriEntity target) {
        return blocked.getOrDefault(target.getUUID(), 0L)<=owner.level().getGameTime();
    }
    /** Dry enough for a parent to come in contact with. */
    public static boolean dry(YukkuriEntity child){return child.life().wetness()<=40;}
    public static boolean sameFloor(YukkuriEntity parent,YukkuriEntity child) {
        return !parent.isPassenger()&&!child.isPassenger()
                || parent.getVehicle() instanceof SurfaceHost&&parent.getVehicle()==child.getVehicle();
    }
    /** A caregiver on a shared floor keeps its place; the young approaches within its reach. */
    public static boolean seatedWith(YukkuriEntity parent,YukkuriEntity child) {
        return parent.getVehicle() instanceof SurfaceHost && sameFloor(parent,child) && parent.family().caresFor(child);
    }
    private static String unsafeReason(YukkuriEntity actor) {
        return !actor.isAlive()?"not_alive":actor.isNoAi()?"no_ai":actor.isPassenger()&&!(actor.getVehicle() instanceof SurfaceHost)?"passenger":actor.isPanicking()?"panic"
                :actor.isOnFire()?"fire":actor.isInWaterOrBubble()?"water":actor.isCommandPlaying()?"command"
                :actor.operations().active()?"operation":actor.life().isSleeping()?"sleeping":null;
    }
    private boolean valid() { return invalidReason()==null; }
    /** Preserve the original predicate order; callers log only when the agreement is actually stopped. */
    private String invalidReason() {
        if(child==null)return "child_missing";
        if(child.level()!=owner.level())return "different_level";
        String reason=unsafeReason(owner);
        if(reason!=null)return "parent_"+reason;
        reason=unsafeReason(child);
        if(reason!=null)return "child_"+reason;
        if(!owner.family().caresFor(child))return "care_relationship_lost";
        if(owner.life().hasUrgentNeed())return "parent_urgent_need";
        // A never-fed newborn is never too tired for its first meal: it is fed rather than put off.
        if(!(child.life().energy()>=25)&&(kind!=Kind.FEED||child.childhood().firstMealDone()))return "child_low_energy";
        if(!dry(child))return "child_wet";
        return kind==Kind.FEED||!child.life().hasUrgentNeed()||child.childhood().needsAssistedFirstToilet()&&child.life().nutrition()>=30
                ?null:"child_urgent_need";
    }
    /** Another caregiver's agreement holds this receiver right now: a second one picks a different child instead. */
    public boolean heldByOther(YukkuriEntity caregiver) { return waiting()&&incoming.owner!=caregiver; }
    public boolean waiting() {
        if (incoming==null) return false;
        String reason=incoming.invalidReason();
        if(reason==null&&incoming.lease<owner.level().getGameTime())reason="lease_expired";
        if(reason!=null){incoming.cancel(reason);return false;}
        return true;
    }
    public String activity() { return waiting() ? incoming.kind==Kind.REAR ? rearResponse() : Behaviors.CARE_WAIT : Behaviors.IDLE; }
    private String rearResponse() {
        // A swaddled child answers the leaf-swaddle care phase of this parent (open, assisted toilet, clean, rewrap).
        if (incoming.owner.leafSwaddle().caringFor(owner)) return incoming.owner.leafSwaddle().receiverActivity(owner);
        int contact = incoming.owner.childhood().careProgress();
        if (contact == 0 || !FamilyFeedingContact.rearContact(incoming.owner, owner)) return Behaviors.FIRST_TOILET_WAIT;
        return contact >= ChildhoodLife.FIRST_TOILET_CONTACT_TICKS ? Behaviors.GROUND_TOILET : Behaviors.FIRST_TOILET_STIMULATED;
    }
    /** The share of the contact cone's half angle a floor meeting point is chosen within. */
    private static final double CONE_MARGIN=.8;
    public void cancelIncoming() { if (incoming!=null) incoming.cancel("receiver_cancelled"); }
    public boolean cooperatingWith(YukkuriEntity other){
        return child==other&&valid()&&lease>=owner.level().getGameTime() || waiting()&&incoming.owner==other;
    }
    public CompoundTag debug(){
        var tag=new CompoundTag();tag.putBoolean("Waiting",waiting());tag.putInt("Stalled",stalled);tag.putInt("Retries",retries);tag.putInt("BodyContacts",bodyContacts);
        if(child!=null)tag.putUUID("Child",child.getUUID());if(kind!=null)tag.putString("Kind",kind.name());
        if(places!=null){tag.putString("ParentStand",places.parent().toString());tag.putString("ChildStand",places.child().toString());}
        return tag;
    }

    /** The receiver owns its MOVE/LOOK goal too; normal wandering cannot fight a live handoff. */
    public void tickReceiver() {
        if (!waiting()) return;
        var session = incoming;
        if(owner.getVehicle() instanceof SurfaceHost host&&sameFloor(owner,session.owner)) {
            if(!Floor.standing(owner)||!Floor.standing(session.owner))return;
            if(session.touching())return;
            if(session.floorRoute==null) {
                // Aim inside the contact cone, not at its edge: a point on the edge reads .9699 against the .97 the contact asks and never touches.
                double limit=CONE_MARGIN*Math.toDegrees(Math.acos(session.kind==Kind.REAR?.97:FamilyFeedingContact.MIN_FACING_DOT));
                var route=Floor.approach(host,owner,session.owner.position(),session.owner.yBodyRot,
                        FamilyFeedingContact.minimumMouthDistance(session.owner,owner),
                        FamilyFeedingContact.mouthDistance(session.owner,owner),limit,session.kind==Kind.FEED?180:0);
                if(route.isEmpty()) {session.fail("no_reachable_meeting");return;}
                session.floorRoute=route;
                GameplayLog.event(owner,"care","floor approach parent={} kind={} target={} waypoints={}",
                        session.owner.getUUID(),session.kind,route.getLast(),route.size());
            }
            if(session.floorRoute.isEmpty()) {
                float facing=SurfaceApproach.yaw(owner.position(),session.owner.position())+(session.kind==Kind.REAR?180:0);
                if(faceOn(host,owner,facing)&&!session.touching()) {
                    if(session.kind==Kind.REAR)GameplayLog.limited(owner,"care","contact_lost."+session.owner.getUUID(),200,
                            "contact lost parent={} child={} why={}",session.owner.getUUID(),owner.getUUID(),FamilyFeedingContact.rearRefusal(session.owner,owner));
                    session.fail("contact_not_reached");
                }
            }
            else if(!Floor.follow(host,owner,session.floorRoute))session.fail("no_reachable_meeting");
            return;
        }
        if (session.places==null || owner.life().mealTicks()>0) SurfaceApproach.halt(owner);
        else if (SurfaceApproach.walkAround(owner, session.places.child(), session.owner, .65)) {
            float yaw = SurfaceApproach.yaw(owner.position(), session.owner.position());
            SurfaceApproach.face(owner, session.kind==Kind.REAR ? yaw+180 : yaw);
        }
    }

    public Result tick(YukkuriEntity target, Kind requested) {
        if (child!=target || kind!=requested) {
            cancel("agreement_changed");
            String reason=!mayTry(target)?"retry_cooldown":target.careApproach().waiting()?"receiver_already_waiting":null;
            if(reason!=null){
                GameplayLog.limited(owner,"care","refused."+reason,200,
                        "agreement refused child={} kind={} reason={}",target.getUUID(),requested,reason);
                return Result.FAILED;
            }
            child=target; kind=requested; child.careApproach().incoming=this;
            GameplayLog.limited(owner,"care","reserved",200,
                    "agreement reserved child={} kind={}",child.getUUID(),kind);
        }
        lease=owner.level().getGameTime()+10;
        String reason=invalidReason();
        if(reason!=null)return fail(reason);
        if(owner.getVehicle() instanceof SurfaceHost host&&sameFloor(owner,child))return aboard(host);
        boolean touching=kind==Kind.FEED ? FamilyFeedingContact.mouthsMeet(owner, child) : ChildhoodLife.toiletContact(owner, child);
        boolean supportedContact=touching && owner.onGround() && child.onGround()
                && SurfaceApproach.supported(owner, owner.position()) && SurfaceApproach.supported(child, child.position());
        // A real meeting reached on the way is better than pushing past the receiver to an
        // obsolete far-side stand. Both participants consume this same Places agreement;
        // the receiver's next tick therefore holds the new position too. Keep the original
        // line-of-sight, mouth separation and facing checks, plus full actual floor support.
        if (kind==Kind.FEED && supportedContact) {
            places=new Places(child.position(), owner.position());
            SurfaceApproach.halt(owner); SurfaceApproach.halt(child);
            stalled=bodyContacts=0; lastParent=owner.position(); lastChild=child.position();
        }
        if (places!=null && (!SurfaceApproach.supported(owner, places.parent()) || !SurfaceApproach.supported(child, places.child()))) replan("support_lost");
        if (places==null && (places=choosePlaces())==null) return fail("no_reachable_meeting");
        // Vanilla paths do not route around this cooperating receiver. At a ledge they can
        // approach a valid far-side stand through the child, whose real collision then pushes
        // both bodies back and forth. That motion is not route progress. Reject this meeting
        // after repeated body contacts, while retaining normal collision and the real serving.
        boolean meetingReached=SurfaceApproach.arrived(owner, places.parent()) && SurfaceApproach.arrived(child, places.child());
        // Conservative body boxes may overlap during legitimate service. Never let the
        // approach watchdog interrupt its uninterrupted mouth/rear-contact interval.
        if (meetingReached || supportedContact) bodyContacts=0;
        else if (owner.getBoundingBox().intersects(child.getBoundingBox()) && ++bodyContacts >= 12) {
            replan("body_contacts"); if (++retries>=3) return fail("body_contact_retry_limit"); return Result.APPROACHING;
        }
        if (lastParent!=null && lastParent.distanceToSqr(owner.position())<.0004
                && lastChild.distanceToSqr(child.position())<.0004) stalled++;
        else {
            // A newborn's supported crawl advances millimetres per tick. Measure accumulated
            // progress, not one frame, or every valid slow relocation is mistaken for a blockage.
            stalled=0; lastParent=owner.position(); lastChild=child.position();
        }
        if (stalled>50 && !meetingReached) {
            replan("stalled"); if (++retries>=3) return fail("stalled_retry_limit"); return Result.APPROACHING;
        }
        // A frightened/pushed child invalidates the meeting rather than becoming an endlessly moving target.
        if (child.position().distanceToSqr(places.child())>16) return fail("child_left_meeting");
        boolean ready = SurfaceApproach.arrived(child, places.child());
        if (!ready) { SurfaceApproach.halt(owner); return Result.APPROACHING; }
        if (!SurfaceApproach.walkAround(owner, places.parent(), child, .9)) return Result.APPROACHING;
        float towardChild = SurfaceApproach.yaw(owner.position(), child.position());
        SurfaceApproach.face(owner, towardChild);
        SurfaceApproach.face(child, kind==Kind.FEED ? towardChild+180 : towardChild);
        boolean contact = kind==Kind.FEED ? FamilyFeedingContact.mouthsMeet(owner, child) : ChildhoodLife.toiletContact(owner, child);
        if (!contact && stalled>70) { replan("contact_not_reached"); if (++retries>=3) return fail("contact_retry_limit"); }
        return contact ? Result.CONTACT : Result.APPROACHING;
    }

    private boolean touching() {
        return kind==Kind.FEED?FamilyFeedingContact.mouthsMeet(owner,child):ChildhoodLife.toiletContact(owner,child);
    }

    /** Contact from the caregiver's existing place; only the receiver's goal moves or turns the young. */
    private Result aboard(SurfaceHost host) {
        if(!Floor.standing(owner)||!Floor.standing(child))return Result.APPROACHING;
        if(!touching())return Result.APPROACHING;
        GameplayLog.limited(owner,"care","aboard_contact."+kind,200,
                "aboard contact child={} kind={} parentLocal={} childLocal={} parentYaw={} childYaw={} parentLocalYaw={}",
                child.getUUID(),kind,host.riders().local(owner.getUUID()),host.riders().local(child.getUUID()),owner.yBodyRot,child.yBodyRot,
                host.riders().yaw(owner.getUUID()));
        return Result.CONTACT;
    }
    private static boolean faceOn(SurfaceHost host,YukkuriEntity body,float yaw) {
        float left=Mth.wrapDegrees(yaw-body.yBodyRot),rate=(float)BodyAbility.of(body).turnRate();
        Floor.turnInPlace(host,body,Math.clamp(left,-rate,rate));
        return Math.abs(Mth.wrapDegrees(yaw-body.yBodyRot))<=Face.FACE_ARRIVED;
    }

    public void hold() {
        lease=owner.level().getGameTime()+10;
        String reason=invalidReason();
        if(reason!=null){cancel(reason);return;}
        SurfaceApproach.halt(owner); SurfaceApproach.halt(child);
    }
    private void replan(String reason) {
        logBoundary("replan",reason);
        if (places!=null) rejected.add(places.parent());
        places=null; stalled=bodyContacts=0; lastParent=lastChild=null;
        SurfaceApproach.halt(owner);
    }
    private Result fail(String reason) {
        if (child!=null) blocked.put(child.getUUID(), owner.level().getGameTime()+160);
        cancel(reason); return Result.FAILED;
    }
    public void cancel() { cancel("external_or_owner_finished"); }
    private void cancel(String reason) {
        if(child!=null)logBoundary("stopped",reason);
        if (child!=null && child.careApproach().incoming==this) child.careApproach().incoming=null;
        if (child!=null) SurfaceApproach.halt(owner);
        child=null; places=null; floorRoute=null; rejected.clear(); retries=stalled=bodyContacts=0; lastParent=lastChild=null;
        blocked.entrySet().removeIf(entry->entry.getValue()<=owner.level().getGameTime());
    }
    private void logBoundary(String boundary,String reason){
        GameplayLog.limited(owner,"care",boundary+"."+reason,200,
                "approach {} child={} kind={} reason={} retries={} stalled={} bodyContacts={} parentStand={} childStand={} childPosition={} childNutrition={} childEnergy={} parentPosition={} parentYaw={} childYaw={}",
                boundary,child==null?null:child.getUUID(),kind,reason,retries,stalled,bodyContacts,
                places==null?null:places.parent(),places==null?null:places.child(),child==null?null:child.position(),
                child==null?null:child.life().nutrition(),child==null?null:child.life().energy(),owner.position(),owner.getYHeadRot(),child==null?null:child.getYHeadRot());
    }

    private Places choosePlaces() {
        // Prefer the child's current safe position. Relocate by walking only when no parent can stand beside it.
        var current = around(child.position());
        if (current!=null) return current;
        List<Vec3> candidates = new ArrayList<>();
        for (var pos:BlockPos.betweenClosed(child.blockPosition().offset(-3,-1,-3),child.blockPosition().offset(3,1,3))) {
            var at=SurfaceApproach.floor(child,Vec3.atBottomCenterOf(pos));
            if(at==null)continue;
            if (at.distanceToSqr(child.position())>12 || !child.childhood().canJump() && at.y>child.getY()+.1) continue;
            if (SurfaceApproach.supported(child, at) && SurfaceApproach.canApproach(child,at)) candidates.add(at);
        }
        candidates.sort(Comparator.comparingDouble(at->at.distanceToSqr(child.position())));
        for (var at:candidates) { var plan=around(at); if (plan!=null) return plan; }
        return null;
    }
    private Places around(Vec3 childFeet) {
        if (!SurfaceApproach.supported(child,childFeet)) return null;
        double radius=FamilyFeedingContact.standingDistance(owner,child);
        List<Vec3> candidates=new ArrayList<>();
        for (var direction:Direction.Plane.HORIZONTAL) {
            for (double dy:new double[]{0,-.5,.5}) {
                Vec3 at=SurfaceApproach.floor(owner,childFeet.add(direction.getStepX()*radius,dy,direction.getStepZ()*radius));
                if(at==null)continue;
                double mouthDelta=(at.y+owner.getBbHeight()*.5)-(childFeet.y+child.getBbHeight()*.5);
                if (kind==Kind.FEED ? Math.abs(mouthDelta)>FamilyFeedingContact.MAX_MOUTH_HEIGHT_DELTA : Math.abs(at.y-childFeet.y)>.18) continue;
                if (rejected.stream().anyMatch(old->old.distanceToSqr(at)<.04)) continue;
                if (SurfaceApproach.supported(owner,at) && SurfaceApproach.canApproach(owner,at)) candidates.add(at);
            }
        }
        return candidates.stream().min(Comparator.comparingDouble(at->owner.position().distanceToSqr(at)))
                .map(at->new Places(childFeet,at)).orElse(null);
    }
}
