package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * One family leaves a floating hull onto a bank, then the captain turns back and takes her hat in. The whole sequence is a
 * single chain of short, individually bounded steps, with no stopping point that has to be hit exactly:
 *
 * <ol>
 * <li>DOCK: the hull is eased alongside the bank (parallel, near brim at the wall; see {@link HatDock}).</li>
 * <li>CREW: each child in turn walks to the deck patch nearest free ground, then hops down, or, when too small to jump, is
 * lifted over the brim in the captain's braid (CradlePickup, boat mode).</li>
 * <li>CAPTAIN: she follows the same way, last.</li>
 * <li>WALK / HAT: she walks to the cell the authored hat clip is made for, turns back to face her hat, the hat returns to her
 * and the hat-on clip plays; the hat-care clip follows from {@code AquaticLife.landed()}.</li>
 * </ol>
 *
 * Nothing here teleports anybody. If a step cannot be completed the landing is abandoned in place (everyone is left exactly
 * where they stand, aboard or ashore) and the caller tries again later.
 */
final class AquaticLanding {
    private enum Stage { DOCK, CREW, CAPTAIN, WALK, HAT }
    private static final int MAX_TICKS=2600, DOCK_TICKS=320, ACTOR_TICKS=420;
    private final HatBoatEntity boat;
    private boolean active;
    private Stage stage=Stage.DOCK;
    private UUID ownerId,rescuer,current;
    private HatDock.Plan plan;
    private BlockPos hint;
    private long started,stageSince,actorSince,airSince;
    private int blocked,replans,respots,refusals;
    private final ArrayDeque<UUID> queue=new ArrayDeque<>();
    private final HashMap<UUID,Vec3> spots=new HashMap<>();
    private final ArrayList<AABB> used=new ArrayList<>();

    AquaticLanding(HatBoatEntity boat){this.boat=boat;}
    boolean active(){return active;}
    boolean begin(YukkuriEntity owner,BlockPos stand,UUID helper){
        if(active)return true;
        if(!boat.isAfloat()&&boat.state()!=HatBoatEntity.State.BEACHED||boat.unsteadyMotion()||boat.towed()||owner.getVehicle()!=boat||owner.family().flying()
                ||owner.family().braidRescueActive()||owner.aquaticLife().familyLife().alarm().preventsOuting())return false;
        var crew=new ArrayDeque<UUID>();
        for(var passenger:boat.getPassengers())if(passenger instanceof YukkuriEntity child&&child!=owner){
            if(child.family().flying()||child.family().braidRescueActive())return false;
            crew.add(child.getUUID());
        }
        var found=HatDock.plan(boat,owner,crew.size(),stand);
        if(found==null){
            AquaDiag.log(owner,"landing.noplan",40,"no bank with a free place for the hull near "+AquaDiag.at(boat.position()));
            return false;
        }
        for(var passenger:boat.getPassengers())if(passenger instanceof YukkuriEntity rider)rider.life().wakeInHat();
        queue.clear();queue.addAll(crew);spots.clear();used.clear();
        plan=found;hint=stand;ownerId=owner.getUUID();rescuer=helper;current=null;
        started=stageSince=actorSince=boat.level().getGameTime();stage=Stage.DOCK;blocked=replans=respots=refusals=0;airSince=0;
        active=true;boat.holdPosition();
        AquaDiag.log(owner,"landing.begin",0,"owner="+owner.getUUID()+" bank="+plan.bank()+" toWater="+plan.toWater()+" crew="+crew.size()
                +" boat="+AquaDiag.at(boat.position())+" yaw="+boat.getYRot()+" dock="+AquaDiag.at(plan.center())+"/"+plan.yaw());
        return true;
    }

    void tick(){
        if(!active||!(boat.level() instanceof ServerLevel level))return;
        long now=level.getGameTime();
        var owner=level.getEntity(ownerId) instanceof YukkuriEntity y&&y.isAlive()?y:null;
        if(owner==null||!boat.isAfloat()&&!(stage==Stage.DOCK&&boat.state()==HatBoatEntity.State.BEACHED)||boat.unsteadyMotion()){
            abort("owner gone or hull unsteady afloat="+boat.isAfloat()+" unsteady="+boat.unsteadyMotion());return;
        }
        if(now-started>MAX_TICKS){abort("took too long in stage "+stage);return;}
        if(owner.aquaticLife().familyLife().alarm().preventsOuting()){abort("family alarm");return;}
        switch(stage){
            case DOCK->dock(owner,now);
            case CREW->{hold();crew(owner,level,now);}
            case CAPTAIN->{hold();captain(owner,now);}
            case WALK->walk(owner,now);
            case HAT->hat(owner,now);
        }
    }
    private void next(Stage to,long now){stage=to;stageSince=now;actorSince=now;refusals=0;respots=0;current=null;airSince=0;AquaDiag.log(boat,"landing.stage",0,"-> "+to);}
    private void hold(){boat.driveToward(plan.center(),plan.yaw(),.05,4f);}

    private void dock(YukkuriEntity owner,long now){
        var result=boat.driveToward(plan.center(),plan.yaw(),.07,5f);
        double gap=HatDock.rimGap(boat,plan);
        if(result==HatBoatEntity.Drive.ARRIVED){next(Stage.CREW,now);AquaDiag.log(boat,"landing.docked",0,"arrived boat="+AquaDiag.at(boat.position())+" yaw="+boat.getYRot()+" rimGap="+gap);return;}
        blocked=result==HatBoatEntity.Drive.BLOCKED?blocked+1:0;
        boolean stuck=blocked>=15||now-stageSince>DOCK_TICKS;
        if(!stuck)return;
        // Pressed against the bank, or a stray current: close enough is enough when the near brim is within a hop of the wall.
        boolean turned=Math.abs(Mth.wrapDegrees(plan.yaw()-boat.getYRot()))<=12;
        if(gap<=.4&&turned){next(Stage.CREW,now);AquaDiag.log(boat,"landing.docked",0,"close enough rimGap="+gap+" boat="+AquaDiag.at(boat.position())+" yaw="+boat.getYRot());return;}
        if(replans++<2){
            var again=HatDock.plan(boat,owner,queue.size(),hint);
            if(again!=null){plan=again;blocked=0;stageSince=now;AquaDiag.log(boat,"landing.replan",0,"bank="+plan.bank());return;}
        }
        abort("could not lie alongside the bank gap="+gap);
    }

    private void crew(YukkuriEntity owner,ServerLevel level,long now){
        if(current==null){
            current=queue.poll();
            if(current==null){next(Stage.CAPTAIN,now);return;}
            actorSince=now;respots=refusals=0;airSince=0;
        }
        var actor=level.getEntity(current) instanceof YukkuriEntity y&&y.isAlive()?y:null;
        if(actor==null){AquaDiag.log(boat,"landing.crew",0,"child gone");current=null;return;}
        if(actor.getVehicle()!=boat){
            if(actor.family().flying()||actor.family().cradleHeld()||owner.family().cradlePickup().active()){airSince=0;return;}
            if(actor.isInWaterOrBubble()){abort("child fell into the water");return;}
            if(actor.onGround()){
                used.add(actor.getBoundingBox());actor.getNavigation().stop();
                AquaDiag.log(boat,"landing.crew",0,"ashore "+actor.getUUID()+" at "+AquaDiag.at(actor.position()));
                current=null;return;
            }
            if(airSince==0)airSince=now;
            else if(now-airSince>40)abort("child left the hull but never reached ground");
            return;
        }
        airSince=0;
        if(now-actorSince>ACTOR_TICKS){abort("child "+actor.getUUID()+" took too long");return;}
        if(actor.family().flying()||actor.family().cradleHeld()||owner.family().cradlePickup().active())return;
        var spot=spots.get(current);
        if(spot==null){
            if(actor.family().canStepOut())spot=HatDock.spotFor(plan,actor,actor.position(),used,respots);
            else{
                // One the captain must lift lands near her (alternately near itself), on ground nobody stands on.
                var taken=new ArrayList<AABB>(used);
                for(var other:level.getEntitiesOfClass(YukkuriEntity.class,boat.getBoundingBox().inflate(3)))if(other!=actor&&!other.isPassenger())taken.add(other.getBoundingBox());
                spot=HatDock.spotFor(plan,actor,respots%2==0?owner.position():actor.position(),taken,respots/2);
            }
            if(spot==null){abort("no free ground beside the hull for "+actor.getUUID());return;}
            spots.put(current,spot);
        }
        if(!actor.family().canStepOut()){
            // A child too small to jump cannot even walk up onto the brim: the captain's braid carries it, in one lift if her
            // reach allows, otherwise first onto the brim place nearest the bank and from there over to the ground.
            if(boat.changingSeat(actor)||!owner.family().cradlePickup().ready())return;
            if(liftInfant(owner,actor,spot))return;
            AquaDiag.log(boat,"landing.nolift",40,"infant="+actor.getUUID()+" "+AquaDiag.at(actor.position())+" spot="+AquaDiag.at(spot));
            // Each refusal already tried every stand and brim place for this landing: try another landing soon.
            if(++refusals>=2)respot(actor);
            return;
        }
        if(!boat.approachBoardingEdge(actor,spot)){
            if(++refusals>=40)respot(actor);
            return;
        }
        if(boat.changingSeat(actor))return;
        if(actor.family().startHatLanding(boat,spot))return;
        AquaDiag.log(boat,"landing.refused",40,"child="+actor.getUUID()+" "+AquaDiag.at(actor.position())+" spot="+AquaDiag.at(spot)+" reason="+actor.family().hatLandingRefusal());
        if(++refusals>=24)respot(actor);
    }
    private boolean liftInfant(YukkuriEntity owner,YukkuriEntity infant,Vec3 spot){
        // Both plans are tried in the same tick (a refused lift is waited out once, so a second separate call would never run).
        var pickup=owner.family().cradlePickup();
        // Already set on the brim (the first stage done): only the step over to the bank is left.
        boolean onBrim=boat.toLocal(infant.position()).y>=HatVessel.FREEBOARD-.02;
        // Brim places lying between the child and its landing first: two short lifts rather than one long one.
        var near=new ArrayList<>(onBrim?java.util.List.<Vec3>of():boat.brimSeatsToward(infant,spot,96));
        near.sort(java.util.Comparator.comparingDouble(at->{var w=boat.toWorld(at);return w.distanceTo(infant.position())+w.distanceTo(spot);}));
        var seats=spread(near,6,2.5);
        if(!pickup.startBoatLanding(boat,infant,spot,seats))return false;
        if(pickup.liftingToHull()){
            AquaDiag.log(boat,"landing.stage1",0,"carry "+infant.getUUID()+" to a brim seat first");
        }else AquaDiag.log(boat,"landing.liftashore",0,"lift "+infant.getUUID()+" over the brim to "+AquaDiag.at(spot));
        return true;
    }
    /** Up to {@code count} of these places, nearest first, each at least {@code apart} hull units from those already taken. */
    private static java.util.List<Vec3> spread(java.util.List<Vec3> places,int count,double apart){
        var out=new java.util.ArrayList<Vec3>();
        for(var at:places){
            boolean near=false;for(var kept:out)if(Math.hypot(kept.x-at.x,kept.z-at.z)<apart){near=true;break;}
            if(!near)out.add(at);if(out.size()>=count)break;
        }
        return out;
    }
    private void respot(YukkuriEntity actor){
        spots.remove(actor.getUUID());refusals=0;
        if(++respots>6)abort("no way found to set "+actor.getUUID()+" ashore");
    }

    private void captain(YukkuriEntity owner,long now){
        if(owner.getVehicle()!=boat){
            if(owner.family().flying()){airSince=0;return;}
            if(owner.isInWaterOrBubble()){abort("captain in the water");return;}
            if(owner.onGround()){owner.getNavigation().stop();next(Stage.WALK,now);return;}
            if(airSince==0)airSince=now;else if(now-airSince>40)abort("captain left the hull but never reached ground");
            return;
        }
        airSince=0;
        if(owner.family().flying()||owner.family().cradleHeld())return;
        if(now-actorSince>ACTOR_TICKS){abort("captain took too long");return;}
        var spot=spots.get(ownerId);
        if(spot==null){
            spot=HatDock.spotFor(plan,owner,owner.position(),used,respots);
            if(spot==null){abort("no free ground beside the hull for the captain");return;}
            spots.put(ownerId,spot);
        }
        if(!boat.approachBoardingEdge(owner,spot)){if(++refusals>=40)respot(owner);return;}
        if(boat.changingSeat(owner))return;
        if(!owner.family().startHatLanding(boat,spot)){
            AquaDiag.log(boat,"landing.refused",40,"captain "+AquaDiag.at(owner.position())+" spot="+AquaDiag.at(spot)+" reason="+owner.family().hatLandingRefusal());
            if(++refusals>=24)respot(owner);
        }
    }

    /** The captain walks to the cell the hat-on clip is authored for. */
    private void walk(YukkuriEntity owner,long now){
        boat.driveToward(plan.center(),plan.yaw(),.05,4f);
        if(owner.isInWaterOrBubble()||owner.isPassenger()){abort("captain not on dry land");return;}
        if(owner.family().flying())return;
        double away=owner.position().subtract(plan.stand()).horizontalDistance();
        if(SurfaceApproach.walk(owner,plan.stand(),.8)||away<=.07){next(Stage.HAT,now);return;}
        if(now-stageSince>200){
            // She cannot be exactly on the cell centre (a sibling stands there, say). The clip tolerates a hand's breadth.
            if(away<=.35){next(Stage.HAT,now);return;}
            abort("captain cannot reach the hat place, "+away+" away");
        }
    }

    /** Turn back toward the hat, take it in, and put it on; the landing is over from here. */
    private void hat(YukkuriEntity owner,long now){
        boat.driveToward(plan.center(),plan.yaw(),.05,4f);
        SurfaceApproach.halt(owner);
        var placement=HatVessel.placement(true,HatVessel.Role.SELF);
        float facing=Mth.wrapDegrees(plan.yaw()-placement.yaw());
        SurfaceApproach.face(owner,facing);
        if(Math.abs(Mth.wrapDegrees(owner.yBodyRot-facing))>1.5f&&now-stageSince<80)return;
        if(!boat.finishLanding(owner,rescuer)){abort("the hat could not be returned");return;}
        owner.family().playHatOn();
        AquaDiag.log(boat,"landing.finished",0,"hat returned at "+AquaDiag.at(owner.position())+" facing="+owner.yBodyRot);
        active=false;clearState();
    }

    private void abort(String why){
        AquaDiag.log(boat,"landing.abort",0,why+" (stage "+stage+", nobody is moved)");
        var level=boat.level();
        if(level instanceof ServerLevel server&&server.getEntity(ownerId) instanceof YukkuriEntity owner){
            owner.family().cradlePickup().cancel();
            boat.releaseOwnBoardingSeat(owner);
        }
        cancel();
    }
    /** Drop the operation without side effects. */
    void abort(){cancel();}
    private void clearState(){queue.clear();spots.clear();used.clear();current=null;plan=null;airSince=0;blocked=replans=respots=refusals=0;}
    private void cancel(){active=false;clearState();}
    /** A landing is not resumed after loading: everyone stays where they stand and the caller starts again. */
    CompoundTag save(){return new CompoundTag();}
    void load(CompoundTag tag){cancel();}
}
