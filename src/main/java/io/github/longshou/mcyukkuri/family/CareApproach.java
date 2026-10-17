package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

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
    private static boolean safe(YukkuriEntity actor) {
        return actor.isAlive() && !actor.isNoAi() && !actor.isPassenger() && !actor.isPanicking()
                && !actor.isOnFire() && !actor.isInWaterOrBubble() && !actor.isCommandPlaying()
                && !actor.operations().active() && !actor.life().isSleeping();
    }
    private boolean valid() {
        return child!=null && child.level()==owner.level() && safe(owner) && safe(child)
                && owner.family().caresFor(child) && !owner.life().hasUrgentNeed()
                && child.life().energy()>=25 && child.life().wetness()<=40
                && (kind==Kind.FEED || !child.life().hasUrgentNeed() || child.childhood().needsAssistedFirstToilet() && child.life().nutrition()>=30);
    }
    public boolean waiting() {
        if (incoming==null) return false;
        if (!incoming.valid() || incoming.lease<owner.level().getGameTime()) { incoming.cancel(); return false; }
        return true;
    }
    public String activity() { return waiting() ? incoming.kind==Kind.REAR ? rearResponse() : "care_wait" : "idle"; }
    private String rearResponse() {
        int contact = incoming.owner.childhood().careProgress();
        if (contact == 0 || !FamilyFeedingContact.rearContact(incoming.owner, owner)) return "first_toilet_wait";
        return contact >= ChildhoodLife.FIRST_TOILET_CONTACT_TICKS ? "ground_toilet" : "first_toilet_stimulated";
    }
    public void cancelIncoming() { if (incoming!=null) incoming.cancel(); }
    public boolean cooperatingWith(YukkuriEntity other){
        return child==other&&valid()&&lease>=owner.level().getGameTime() || waiting()&&incoming.owner==other;
    }
    public net.minecraft.nbt.CompoundTag debug(){
        var tag=new net.minecraft.nbt.CompoundTag();tag.putBoolean("Waiting",waiting());tag.putInt("Stalled",stalled);tag.putInt("Retries",retries);tag.putInt("BodyContacts",bodyContacts);
        if(child!=null)tag.putUUID("Child",child.getUUID());if(kind!=null)tag.putString("Kind",kind.name());
        if(places!=null){tag.putString("ParentStand",places.parent().toString());tag.putString("ChildStand",places.child().toString());}
        return tag;
    }

    /** The receiver owns its MOVE/LOOK goal too; normal wandering cannot fight a live handoff. */
    public void tickReceiver() {
        if (!waiting()) return;
        var session = incoming;
        if (session.places==null || owner.life().mealTicks()>0) SurfaceApproach.halt(owner);
        else if (SurfaceApproach.walk(owner, session.places.child(), .65)) {
            float yaw = SurfaceApproach.yaw(owner.position(), session.owner.position());
            SurfaceApproach.face(owner, session.kind==Kind.REAR ? yaw+180 : yaw);
        }
    }

    public Result tick(YukkuriEntity target, Kind requested) {
        if (child!=target || kind!=requested) {
            cancel();
            if (!mayTry(target) || target.careApproach().waiting()) return Result.FAILED;
            child=target; kind=requested; child.careApproach().incoming=this;
        }
        lease=owner.level().getGameTime()+10;
        if (!valid()) return fail();
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
        if (places!=null && (!SurfaceApproach.supported(owner, places.parent()) || !SurfaceApproach.supported(child, places.child()))) replan();
        if (places==null && (places=choosePlaces())==null) return fail();
        // Vanilla paths do not route around this cooperating receiver. At a ledge they can
        // approach a valid far-side stand through the child, whose real collision then pushes
        // both bodies back and forth. That motion is not route progress. Reject this meeting
        // after repeated body contacts, while retaining normal collision and the real serving.
        boolean meetingReached=SurfaceApproach.arrived(owner, places.parent()) && SurfaceApproach.arrived(child, places.child());
        // Conservative body boxes may overlap during legitimate service. Never let the
        // approach watchdog interrupt its uninterrupted mouth/rear-contact interval.
        if (meetingReached || supportedContact) bodyContacts=0;
        else if (owner.getBoundingBox().intersects(child.getBoundingBox()) && ++bodyContacts >= 12) {
            replan(); if (++retries>=3) return fail(); return Result.APPROACHING;
        }
        if (lastParent!=null && lastParent.distanceToSqr(owner.position())<.0004
                && lastChild.distanceToSqr(child.position())<.0004) stalled++;
        else {
            // A newborn's supported crawl advances millimetres per tick. Measure accumulated
            // progress, not one frame, or every valid slow relocation is mistaken for a blockage.
            stalled=0; lastParent=owner.position(); lastChild=child.position();
        }
        if (stalled>50 && !meetingReached) {
            replan(); if (++retries>=3) return fail(); return Result.APPROACHING;
        }
        // A frightened/pushed child invalidates the meeting rather than becoming an endlessly moving target.
        if (child.position().distanceToSqr(places.child())>16) return fail();
        boolean ready = SurfaceApproach.arrived(child, places.child());
        if (!ready) { SurfaceApproach.halt(owner); return Result.APPROACHING; }
        if (!SurfaceApproach.walk(owner, places.parent(), .9)) return Result.APPROACHING;
        float towardChild = SurfaceApproach.yaw(owner.position(), child.position());
        SurfaceApproach.face(owner, towardChild);
        SurfaceApproach.face(child, kind==Kind.FEED ? towardChild+180 : towardChild);
        boolean contact = kind==Kind.FEED ? FamilyFeedingContact.mouthsMeet(owner, child) : ChildhoodLife.toiletContact(owner, child);
        if (!contact && stalled>70) { replan(); if (++retries>=3) return fail(); }
        return contact ? Result.CONTACT : Result.APPROACHING;
    }

    public void hold() {
        lease=owner.level().getGameTime()+10;
        if (!valid()) { cancel(); return; }
        SurfaceApproach.halt(owner); SurfaceApproach.halt(child);
    }
    private void replan() {
        if (places!=null) rejected.add(places.parent());
        places=null; stalled=bodyContacts=0; lastParent=lastChild=null;
        SurfaceApproach.halt(owner);
    }
    private Result fail() {
        if (child!=null) blocked.put(child.getUUID(), owner.level().getGameTime()+160);
        cancel(); return Result.FAILED;
    }
    public void cancel() {
        if (child!=null && child.careApproach().incoming==this) child.careApproach().incoming=null;
        if (child!=null) SurfaceApproach.halt(owner);
        child=null; places=null; rejected.clear(); retries=stalled=bodyContacts=0; lastParent=lastChild=null;
        blocked.entrySet().removeIf(entry->entry.getValue()<=owner.level().getGameTime());
    }

    private Places choosePlaces() {
        // Prefer the child's current safe position. Relocate by walking only when no parent can stand beside it.
        var current = around(child.position());
        if (current!=null) return current;
        List<Vec3> candidates = new ArrayList<>();
        for (var pos:BlockPos.betweenClosed(child.blockPosition().offset(-3,-1,-3),child.blockPosition().offset(3,1,3))) {
            var at=Vec3.atBottomCenterOf(pos);
            if (at.distanceToSqr(child.position())>12 || !child.childhood().canJump() && at.y>child.getY()+.1) continue;
            if (SurfaceApproach.supported(child, at) && child.life().reachable(pos,false)) candidates.add(at);
        }
        candidates.sort(java.util.Comparator.comparingDouble(at->at.distanceToSqr(child.position())));
        for (var at:candidates) { var plan=around(at); if (plan!=null) return plan; }
        return null;
    }
    private Places around(Vec3 childFeet) {
        if (!SurfaceApproach.supported(child,childFeet)) return null;
        double radius=FamilyFeedingContact.standingDistance(owner,child);
        List<Vec3> candidates=new ArrayList<>();
        for (var direction:net.minecraft.core.Direction.Plane.HORIZONTAL) {
            for (double dy:new double[]{0,-.5,.5}) {
                Vec3 at=childFeet.add(direction.getStepX()*radius,dy,direction.getStepZ()*radius);
                double mouthDelta=(at.y+owner.getBbHeight()*.5)-(childFeet.y+child.getBbHeight()*.5);
                if (kind==Kind.FEED ? Math.abs(mouthDelta)>FamilyFeedingContact.MAX_MOUTH_HEIGHT_DELTA : Math.abs(dy)>.18) continue;
                if (rejected.stream().anyMatch(old->old.distanceToSqr(at)<.04)) continue;
                if (SurfaceApproach.supported(owner,at) && owner.life().reachable(BlockPos.containing(at),false)) candidates.add(at);
            }
        }
        return candidates.stream().min(java.util.Comparator.comparingDouble(at->owner.position().distanceToSqr(at)))
                .map(at->new Places(childFeet,at)).orElse(null);
    }
}
