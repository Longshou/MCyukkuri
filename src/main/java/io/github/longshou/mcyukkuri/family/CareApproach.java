package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
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
    private Vec3 lastParent, lastChild;
    private int stalled, retries, bodyContacts;
    private long lease;

    public CareApproach(YukkuriEntity owner) { this.owner = owner; }
    public boolean mayTry(YukkuriEntity target) {
        return blocked.getOrDefault(target.getUUID(), 0L)<=owner.level().getGameTime();
    }
    private static String unsafeReason(YukkuriEntity actor) {
        return !actor.isAlive()?"not_alive":actor.isNoAi()?"no_ai":actor.isPassenger()?"passenger":actor.isPanicking()?"panic"
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
        if(!(child.life().energy()>=25))return "child_low_energy";
        if(!(child.life().wetness()<=40))return "child_wet";
        return kind==Kind.FEED||!child.life().hasUrgentNeed()||child.childhood().needsAssistedFirstToilet()&&child.life().nutrition()>=30
                ?null:"child_urgent_need";
    }
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
        child=null; places=null; rejected.clear(); retries=stalled=bodyContacts=0; lastParent=lastChild=null;
        blocked.entrySet().removeIf(entry->entry.getValue()<=owner.level().getGameTime());
    }
    private void logBoundary(String boundary,String reason){
        GameplayLog.limited(owner,"care",boundary+"."+reason,200,
                "approach {} child={} kind={} reason={} retries={} stalled={} bodyContacts={} parentStand={} childStand={} childPosition={} childNutrition={} childEnergy={}",
                boundary,child==null?null:child.getUUID(),kind,reason,retries,stalled,bodyContacts,
                places==null?null:places.parent(),places==null?null:places.child(),child==null?null:child.position(),
                child==null?null:child.life().nutrition(),child==null?null:child.life().energy());
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
