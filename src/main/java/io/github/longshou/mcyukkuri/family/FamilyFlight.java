package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.crew.CrewTransfers;
import io.github.longshou.mcyukkuri.subspecies.crew.WaterIncidents;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Physical launch integration: the newborn's launch arc, its catch by a hat and the falling stem fruit. Boarding is a crew transfer. */
final class FamilyFlight {
    private final FamilyLife o;
    private record Flight(UUID hat,Vec3 from,Vec3 seat,Vec3 to,int ticks){}
    private Flight flight;
    private int flightTick;
    private boolean newbornLaunch,pendingNewbornLaunch;
    /** HatDeck deliberately drops airborne leases on load; only that restored flight needs a fresh claim on contact. */
    private boolean restoredFlight;
    /** A land birth whose push was randomized: when the flight ends off its seat the kit goes on under plain physics. */
    private boolean landLaunch,pendingLandLaunch;
    private double pendingPush=1,pendingYaw;
    private String sweepWhy="";

    FamilyFlight(FamilyLife o) { this.o=o; }
    boolean hasArc() { return flight!=null; }
    boolean flying() { return o.flying(); }
    boolean targets(HatBoatEntity hat) { return flight!=null&&hat!=null&&hat.getUUID().equals(flight.hat()); }
    void save(CompoundTag tag) {
        if (flight != null) {
            var f = new CompoundTag(); if (flight.hat()!=null) f.putUUID("Hat",flight.hat());
            putVector(f,"From",flight.from()); putVector(f,"Seat",flight.seat()); putVector(f,"To",flight.to());
            f.putBoolean("Physical",true);f.putInt("Ticks",flight.ticks()); f.putInt("Elapsed",flightTick);
            // The route alone is not enough: these decide its bob tolerance, collision ownership and missed-water settlement.
            f.putBoolean("Newborn",newbornLaunch);f.putBoolean("Land",landLaunch);tag.put("HatFlight",f);
        }
    }
    void load(CompoundTag tag) {
        flight = null;flightTick=0;newbornLaunch=pendingNewbornLaunch=landLaunch=pendingLandLaunch=restoredFlight=false;pendingPush=1;pendingYaw=0;
        if (tag.contains("HatFlight")) {
            var f=tag.getCompound("HatFlight"); var from=vector(f,"From"); var seat=vector(f,"Seat"); var to=vector(f,"To");
            int ticks=Math.clamp(f.getInt("Ticks"),1,HatVessel.TMAX);
            if (f.getBoolean("Physical") && from!=null && to!=null && (!f.hasUUID("Hat")||seat!=null)) {
                flight=new Flight(f.hasUUID("Hat")?f.getUUID("Hat"):null,from,seat,to,ticks);
                flightTick=Math.clamp(f.getInt("Elapsed"),0,ticks-1);
                // Do not infer new semantics for an old route: a missing tag retains the conservative ordinary-flight default.
                newbornLaunch=f.getBoolean("Newborn");landLaunch=f.getBoolean("Land")&&newbornLaunch;restoredFlight=true;
            }
        }
    }

    void launchInto(HatBoatEntity hat,int tmin,Vec3 prefer){launchInto(hat,tmin,prefer,1.25);}

    void flightLog(String why){if(FamilyLife.FLIGHT_DEBUG)MCYukkuri.LOGGER.info("[flight-debug] {} {}",o.entity().getId(),why);}
    /** A failed newborn flight owns no lingering rescue berth or crowding flag; ordinary transfers retain their existing release. */
    private void cancelFlightAdmission(HatBoatEntity hat){
        if(hat==null)return;
        if(o.awaitingLanding())hat.cancelBirthAdmission(o.entity());else hat.cancelIncoming(o.entity());
    }

    void launchInto(HatBoatEntity hat,int tmin,Vec3 prefer,double reach){launchInto(hat,tmin,prefer,reach,1,0,false);}

    /** A land birth: the nominal arc to the seat is scaled by {@code push} and turned by {@code yawDeg} before it is released. */
    void launchInto(HatBoatEntity hat,int tmin,Vec3 prefer,double reach,double push,double yawDeg,boolean land){
        flightLog("launch awaiting="+o.awaitingLanding()+" onGround="+o.entity().onGround()+" inWater="+o.entity().isInWaterOrBubble()+" at="+o.entity().position()+" hat="
                +(hat==null?null:hat.position()));
        if(hat==null||flying()||hat.unsteadyMotion())return;
        // Only the actual birth push is not a voluntary jump. Landed children must
        // approach on real ground; swimming infants wait for their parent's dipped rim.
        if(!o.awaitingLanding()||o.entity().onGround()||o.entity().isInWaterOrBubble()){
            CrewTransfers.boardFromShore(o.entity(),hat,false);return;
        }
        if(!hat.makeRoom(o.entity(),false)){flightLog("room_pending");birthRefusal(hat,"room_pending",null);return;}
        var seat=hat.reserve(o.entity(),prefer);if(seat==null){flightLog("no_seat");birthRefusal(hat,"no_seat",null);return;}
        var from=o.entity().position();var to=hat.toWorld(seat);
        boolean inReach=from.subtract(to).horizontalDistance()<=reach;
        int duration=inReach?BirthFlightPath.plan(BirthFlightPath.actual(o.entity()),hat,from,to,tmin):-1;
        if(duration<0){
            if(inReach)birthPathClear(hat,from,to,HatVessel.TMAX);
            birthRefusal(hat,inReach?"path_blocked:"+sweepWhy:"out_of_reach",to);cancelFlightAdmission(hat);return;
        }
        flightLog("flight "+duration+" ticks to "+to);
        pendingNewbornLaunch=o.awaitingLanding();
        pendingLandLaunch=land&&pendingNewbornLaunch;pendingPush=push;pendingYaw=yawDeg;
        beginFlight(new Flight(hat.getUUID(),from,seat,to,duration));
        GameplayLog.event(o.entity(),"family","birth flight started hat={} ticks={} from={} target={} land={} push={} yaw={}",
                hat.getUUID(),duration,from,to,land,push,yawDeg);
    }
    private void birthRefusal(HatBoatEntity hat,String why,Vec3 target){
        GameplayLog.limited(o.entity(),"family","birth.flight."+why.split(":",2)[0],100,
                "birth flight refused hat={} reason={} from={} target={}",hat.getUUID(),why,o.entity().position(),target);
    }

    boolean newbornLaunching(){return newbornLaunch;}
    /** Where the current flight is aimed (the reserved seat), null without a flight. */
    Vec3 destination(){return flight==null?null:flight.to();}

    boolean birthSweepClear(HatBoatEntity hat,Vec3 from,Vec3 to){return birthSweepClear(hat,from,to,false);}
    /** {@code dropping}: a stem fruit falling into the hat set under it; the catcher holding the far brim is no obstacle either. */
    boolean birthSweepClear(HatBoatEntity hat,Vec3 from,Vec3 to,boolean dropping){
        sweepWhy=BirthFlightPath.sweep(BirthFlightPath.actual(o.entity()),hat,from,to,dropping);
        return sweepWhy.isEmpty();
    }

    /** Pushed too hard: on along the same line past the hat's far brim, down where it comes to the ground or the water. */
    void launchPast(HatBoatEntity hat){
        var from=o.entity().position();double r=HatVessel.radius(o.entity().getBbWidth());
        var seat=hat.peekSeat(o.entity());
        var far=HatVessel.overshoot(from,seat==null?hat.position():hat.toWorld(seat),hat,r);
        var hit=o.level().clip(new ClipContext(far.add(0,1.5,0),far.add(0,-3,0),ClipContext.Block.COLLIDER,
                ClipContext.Fluid.ANY,o.entity()));
        var to=hit.getType()==HitResult.Type.MISS?new Vec3(far.x,hat.getY(),far.z):hit.getLocation();
        beginFlight(new Flight(null,from,null,to,HatVessel.solve(from,to,hat,r,HatVessel.TMIN)));
    }

    boolean birthPathClear(HatBoatEntity hat,Vec3 from,Vec3 to,int ticks){
        sweepWhy=BirthFlightPath.path(BirthFlightPath.actual(o.entity()),hat,from,to,ticks);
        return sweepWhy.isEmpty();
    }

    void beginFlight(Flight next){
        newbornLaunch=pendingNewbornLaunch;pendingNewbornLaunch=false;
        landLaunch=pendingLandLaunch;pendingLandLaunch=false;
        double push=pendingPush,yaw=pendingYaw;pendingPush=1;pendingYaw=0;
        flight=next;flightTick=0;restoredFlight=false;
        // A single impulse. The subsequent path is velocity integration with real collision,
        // never a series of absolute positions or corrections toward a moving seat.
        var impulse=HatVessel.arc(next.from(),next.to(),next.ticks(),1).subtract(next.from());
        if(push!=1||yaw!=0){
            var across=new Vec3(impulse.x,0,impulse.z).scale(push).yRot((float)Math.toRadians(yaw));
            impulse=new Vec3(across.x,impulse.y,across.z);
        }
        o.entity().setDeltaMovement(impulse);
        o.entity().getNavigation().stop();o.entity().hasImpulse=true;
    }

    /** Called from travel while a checked boarding trajectory or gravity-driven launch owns movement. */
    boolean travelFlight(){
        // No persistent gravity flag: completion/cancellation immediately returns control to vanilla.
        if(flight==null)return catchFalling();
        var hat=flight.hat()!=null&&o.level().getEntity(flight.hat()) instanceof HatBoatEntity h&&!h.isRemoved()?h:null;
        if(flight.seat()!=null&&(hat==null||hat.capsizing()||!hat.isAfloat()&&!hat.catches())){
            cancelFlightAdmission(hat);
            flightLog("hat_gone_or_capsizing");
            flight=null;return false; // The committed leap falls physically; never restart it in midair.
        }
        var f=flight;var to=f.to();
        // A moving-seat-only flight is released instead of resuming a remote pull.
        // A floating hat bobs and drifts a little while a newborn crosses to it: that is followed (see the contact test), not a reason to give up.
        if(to==null||hat!=null&&hat.toWorld(f.seat()).distanceToSqr(to)>(newbornLaunch?.16:.0025)){
            cancelFlightAdmission(hat);flightLog("seat_moved "+(hat==null||f.seat()==null?"":String.valueOf(Math.sqrt(hat.toWorld(f.seat()).distanceToSqr(to)))));
                    flight=null;return false;
        }
        var velocity=o.entity().getDeltaMovement();var before=o.entity().position();var wanted=before.add(velocity);
        if(catchFalling())return true;
        if(!birthSweepClear(hat,before,wanted)){
            cancelFlightAdmission(hat);flightLog("sweep_blocked at tick "+flightTick+" from "+before+" to "+wanted+" because "+sweepWhy);flight=null;
            // A swept body contact stops this step. Returning to vanilla travel here would
            // immediately repeat the rejected move through a non-solid sibling.
            o.entity().setDeltaMovement(-velocity.x*.15,Math.max(.02,-velocity.y*.15),-velocity.z*.15);
            o.entity().hasImpulse=true;return true;
        }
        o.entity().move(MoverType.SELF,velocity);
        o.entity().getNavigation().stop();o.entity().hasImpulse=true;flightTick++;
        o.entity().setDeltaMovement(velocity.add(0,-HatVessel.GRAVITY/16,0));
        if(o.entity().position().distanceToSqr(wanted)>.000001){
            cancelFlightAdmission(hat);flightLog("moved_short at tick "+flightTick+" wanted "+wanted+" got "+o.entity().position());flight=null;return true;
        }
        if(flightTick<f.ticks())return true;
        flight=null;boolean land=landLaunch;newbornLaunch=false;landLaunch=false;
        if(hat!=null) {
            if(hat.unsteadyMotion()){cancelFlightAdmission(hat);o.entity().setDeltaMovement(0,-.03,0);return true;}
            // Newborns board only at the swept surface contact above. Reaching the nominal arc's duration
            // near a seat is not contact; a displaced or missed hat leaves the child falling normally.
            boolean contact=!o.awaitingLanding()&&o.entity().position().distanceToSqr(to)<=.0025&&velocity.y<=0;
            boolean landed=contact&&(o.entity().getUUID().equals(hat.ownerYukkuri())?hat.board(o.entity()):hat.boardYoung(o.entity()));
            if(!landed)flightLog("not_landed contact="+contact+" dist="+Math.sqrt(o.entity().position().distanceToSqr(to))+" vy="+velocity.y+" to="+to+" at="+o.entity().position());
            if(!landed&&(land||o.awaitingLanding())){
                // The push missed its seat: whatever velocity is left carries on under ordinary physics, and contact decides the outcome.
                cancelFlightAdmission(hat);o.entity().hasImpulse=true;
            }else if(!landed){cancelFlightAdmission(hat);o.entity().waterDistress().plunge(hat.getUUID(),null,WaterIncidents.Cause.FALL);o.entity().setDeltaMovement(0,-.03,0);}
        }
        return true;
    }

    /** The own parents' hat (set down to catch, or afloat) with room for this young, within reach. */
    HatBoatEntity nearHat(double reach){
        return o.level().getEntitiesOfClass(HatBoatEntity.class,o.entity().getBoundingBox().inflate(reach,2,reach),b->!b.unsteadyMotion()&&(b.catches()||b.isAfloat())&&b.young(o.entity())
                &&b.canMakeRoom(o.entity(),false))
                .stream().min(Comparator.comparingDouble(b->b.distanceToSqr(o.entity()))).orElse(null);
    }

    private record Contact(HatBoatEntity hat,Vec3 feet){}

    /** A reloaded newborn flight may only reopen the rescue rim of the mother who is presently afloat in this very hull. */
    private boolean maternalWaterBirthHat(HatBoatEntity hat){
        if(flight==null||!newbornLaunch||hat==null||!hat.isAfloat())return false;
        var mother=o.mother();
        return mother!=null&&mother.equals(hat.ownerYukkuri())&&o.level().getEntity(mother) instanceof YukkuriEntity parent
                &&parent.family().boatHome()==hat;
    }

    /** HatDeck intentionally forgets airborne leases on load; rebuild exactly one current birth claim at real surface contact. */
    private HatBoatEntity.NewbornAdmission rebuildBirthAdmission(HatBoatEntity hat){
        var child=o.entity();boolean emergency=maternalWaterBirthHat(hat);
        if(!hat.makeRoom(child,emergency))return HatBoatEntity.NewbornAdmission.NONE;
        return hat.newbornAdmission(child,emergency);
    }

    /** First downward contact along this tick's actual velocity, using the same body and hull as the sweep. */
    private Vec3 fallingContact(HatBoatEntity hat,Vec3 from,Vec3 to){
        int steps=Math.max(1,(int)Math.ceil(from.distanceTo(to)/.025));
        double previous=0;
        if(from.y<support(hat,from)-.002)return null; // Already below: never pull a missed child back up.
        for(int i=1;i<=steps;i++){
            double fraction=(double)i/steps;var at=from.lerp(to,fraction);
            if(at.y<=support(hat,at)){
                double low=previous,high=fraction;
                for(int j=0;j<10;j++){
                    double mid=(low+high)/2;var point=from.lerp(to,mid);
                    if(point.y>support(hat,point))low=mid;else high=mid;
                }
                var contact=from.lerp(to,high);
                // A horizontal entry below a ledge is a side collision, not a landing on its top.
                return Math.abs(contact.y-support(hat,contact))<=.002?contact:null;
            }
            previous=fraction;
        }
        return null;
    }
    private double support(HatBoatEntity hat,Vec3 feet){
        return BraidRescueGeometry.poseSupport(o.entity(),feet,0,0,o.entity().yBodyRot,hat,null);
    }

    /** Called before travel for both a birth arc and ordinary stem/free fall. A catch consumes only the movement
     *  up to contact; ordinary Minecraft movement remains responsible for a miss. */
    private boolean catchFalling(){
        var child=o.entity();var velocity=child.getDeltaMovement();
        if(!o.awaitingLanding()||o.doomed()||child.isNoAi()||child.isPassenger()||o.crew().active()
                ||child.onGround()||child.isInWaterOrBubble()||velocity.y>=0)return false;
        var from=child.position();var wanted=from.add(velocity);
        var contacts=new ArrayList<Contact>();
        for(var hat:o.level().getEntitiesOfClass(HatBoatEntity.class,child.getBoundingBox().expandTowards(velocity).inflate(3,1,3),
                b->!b.unsteadyMotion()&&(b.catches()||b.isAfloat())&&b.young(child))){
            var contact=fallingContact(hat,from,wanted);
            if(contact!=null)contacts.add(new Contact(hat,contact));
            else if(wanted.y<=hat.getY()&&!Double.isFinite(support(hat,wanted)))catchMiss(hat,"outside_footprint");
        }
        contacts.sort(Comparator.comparingDouble(c->from.distanceToSqr(c.feet())));
        for(var hit:contacts){
            var hat=hit.hat();var contact=hit.feet();var local=hat.toLocal(contact);
            if(!birthSweepClear(hat,from,contact,true)){catchMiss(hat,"sweep_blocked "+sweepWhy);continue;}
            // A dry catching hat retains the ordinary board path below; only an afloat recovery needs the birth-purpose rebuild.
            boolean recoveringBirth=restoredFlight&&flight!=null&&newbornLaunch&&hat.isAfloat();
            HatBoatEntity.NewbornAdmission admission=HatBoatEntity.NewbornAdmission.NONE;
            Vec3 seat;
            if(recoveringBirth){
                admission=rebuildBirthAdmission(hat);
                if(admission==HatBoatEntity.NewbornAdmission.NONE){catchMiss(hat,"birth_admission_refused");continue;}
                seat=hat.reserveBirthAdmission(child,admission,contact);
            }else{
                if(!hat.makeRoom(child,false)){catchMiss(hat,"no_room");continue;}
                seat=hat.reserve(child,local);
            }
            if(seat==null){catchMiss(hat,"no_seat");continue;}
            boolean settle=Math.hypot(seat.x-local.x,seat.z-local.z)<=HatVessel.SETTLE;
            var to=hat.toWorld(seat);
            to=new Vec3(to.x,Math.max(to.y,support(hat,to)),to.z);
            int ticks=settle?0:BirthFlightPath.plan(BirthFlightPath.actual(child),hat,contact,to,HatVessel.HOP_TMIN);
            if(!hat.canBoard(child)){cancelFlightAdmission(hat);catchMiss(hat,"admission_refused");continue;}
            if(!settle&&contact.subtract(to).horizontalDistance()>.5){
                cancelFlightAdmission(hat);catchMiss(hat,"seat_beyond_settle_reach");continue;
            }
            if(!settle&&ticks<0){
                birthPathClear(hat,contact,to,HatVessel.TMAX);
                cancelFlightAdmission(hat);catchMiss(hat,"settle_path "+sweepWhy);continue;
            }
            if(flight!=null&&flight.hat()!=null&&!hat.getUUID().equals(flight.hat())&&o.level().getEntity(flight.hat()) instanceof HatBoatEntity previous)
                cancelFlightAdmission(previous);
            flight=null;newbornLaunch=false;landLaunch=false;
            child.move(MoverType.SELF,contact.subtract(from));
            if(child.position().distanceToSqr(contact)>.000001){
                cancelFlightAdmission(hat);catchMiss(hat,"move_blocked");return true;
            }
            if(settle){
                if(!hat.boardYoung(child)){
                    cancelFlightAdmission(hat);catchMiss(hat,"board_refused");
                    child.move(MoverType.SELF,wanted.subtract(contact));
                    child.setDeltaMovement(velocity.add(0,-HatVessel.GRAVITY/16,0));return true;
                }
                child.setDeltaMovement(Vec3.ZERO);
            }else{
                pendingNewbornLaunch=true;pendingLandLaunch=hat.catches();
                beginFlight(new Flight(hat.getUUID(),contact,seat,to,ticks));
            }
            GameplayLog.event(child,"family","newborn hat contact hat={} at={} incomingVy={} result={} seat={}",
                    hat.getUUID(),contact,velocity.y,settle?"boarded":"bounce",seat);
            return true;
        }
        return false;
    }
    private void catchMiss(HatBoatEntity hat,String reason){
        var local=hat.toLocal(o.entity().position());
        GameplayLog.limited(o.entity(),"family","catch_fall_miss",40,"falling newborn not caught hat={} reason={} local={} hatAt={}",
                hat.getUUID(),reason,local,hat.position());
    }

    static void putVector(CompoundTag tag,String key,Vec3 value) {
        if(value==null)return; var v=new CompoundTag();v.putDouble("X",value.x);v.putDouble("Y",value.y);v.putDouble("Z",value.z);tag.put(key,v);
    }

    static Vec3 vector(CompoundTag tag,String key) {
        if(!tag.contains(key))return null;var v=tag.getCompound(key);
        double x=v.getDouble("X"),y=v.getDouble("Y"),z=v.getDouble("Z");
        return Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z)?new Vec3(x,y,z):null;
    }
}
