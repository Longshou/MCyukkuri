package io.github.longshou.mcyukkuri.play;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriGroundNavigation;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.TrampolineMotion;
import io.github.longshou.mcyukkuri.pet.rules.HouseRule;
import io.github.longshou.mcyukkuri.pet.rules.RulesMath.Choice;
import io.github.longshou.mcyukkuri.social.Temperament;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Interruptible physical routes. No route teleports the body or advances while unloaded. */
public final class PlayLife {
    /** Where a finished pet stands clear of the exit lane: a body width (.8) plus margin beside it and half a body past it, in facility-local blocks. */
    private static final double ASIDE_X=1.1,ASIDE_Z=.6;
    /** Side stands beside the trampoline mat for a waiter, clear of both the north entry and south exit lanes. */
    private static final double WAIT_SIDE_X=2.1;
    private static final double[] WAIT_OFFSETS_Z={0,-.8,.8};
    private record Beat(String name,String declared,String line) {}
    private final YukkuriEntity entity;
    private PlayBlockEntity facility,waitingFor;
    private List<Vec3> route=List.of();
    private Vec3 waitStand;
    private int cooldown,scanWait,elapsed,waypoint,bounces,stalled;
    private int waitTicks,waitLimit,waitCooldown,waitElapsed,lastWaitElapsed;
    /** A tub session: the route index of the seat (-1 elsewhere), the ticks soaked there and the state at the start of the soak. */
    private int soakAt=-1,soakTicks;
    private float soakHealth,soakUnease;
    private int soakWounds,soakJuice;
    /** Whether this body entered hurt (it carries a wound orange juice treats) and the house rule it broke to do so. */
    private boolean soakHurt;
    private HouseRule soakRule;
    private int lastElapsed;
    private String refused,stopReason;
    private Beat pendingBeat;
    private double lastDistance=Double.MAX_VALUE;
    private boolean completed,springing,peakVoiced,waitWatching,waitFaced;
    public PlayLife(YukkuriEntity entity){this.entity=entity;}
    public boolean active(){return facility!=null;}
    public boolean waiting(){return waitingFor!=null;}
    public boolean completed(){return completed;}
    public int bounces(){return bounces;}
    public String refused(){return refused;}
    public String activityFace(){
        if(waiting()&&waitWatching)return entity.dailyLife().boredom()>=75?"sparkle":"dull";
        return "";
    }
    /** Logs each trampoline beat on the same tick with the actual face published by {@code PresentationArbiter}. */
    public void observeFace(String shown){
        if(pendingBeat==null)return;
        var beat=pendingBeat;
        pendingBeat=null;
        GameplayLog.event(entity,"play","trampoline beat={} face={} declared={} line={} clip={} bounces={} energy={} boredom={} unease={}",
                beat.name(),shown,beat.declared(),beat.line(),entity.getAnimationId(),bounces,
                entity.life().energy(),entity.dailyLife().boredom(),entity.mind().unease());
    }
    public String activity(){return !active()?Behaviors.IDLE:facility.kind()==PlayBlock.Kind.SLIDE?(waypoint>=6?Behaviors.SLIDE_DOWN:Behaviors.SLIDE_CLIMB):
            facility.kind()==PlayBlock.Kind.TRAMPOLINE?Behaviors.TRAMPOLINE:facility.kind()==PlayBlock.Kind.POOL?(soaking()?Behaviors.JUICE_SOAK:Behaviors.TUB_CLIMB):Behaviors.TUNNEL;}
    private boolean soaking(){return soakAt>=0&&waypoint==soakAt;}
    /** {@code tub}: the juice tub is the one facility a wounded body may use; every other play needs unhurt skin. */
    private boolean available(boolean tub){return entity.outing().permitsPerformance(true)&&(active() || entity.childhood().canExercise())&&entity.isAlive()&&!entity.isRemoved()&&!entity.isNoAi()&&!entity.isPanicking()&&!entity.isCommandPlaying()
            &&!entity.isPassenger()
            &&!entity.operations().active()&&!entity.groupInteraction().active()
            &&!(active()?entity.life().nutrition()<15||entity.life().energy()<15||entity.isOnFire()||entity.isInWaterOrBubble():entity.life().hasUrgentNeed())&&!entity.life().isSleeping()
            &&entity.life().mealTicks()==0&&entity.life().energy()>=(active()?15:35)&&(tub||entity.body().condition().readyForPlay())
            &&!entity.family().active()&&!entity.house().active();}
    private int playBoredomFloor(){
        double timid=entity.life().personality().temperament().timid();
        return Math.max(10,(int)Math.round(15+20*timid)-(entity.family().adult()?0:5));
    }
    private double stopEnergy(){
        return entity.outing().onOuting()?Math.max(35,entity.outing().restBand()):35;
    }
    private double scareCeiling(){
        return 75-45*entity.life().personality().temperament().timid();
    }
    private boolean wantsTrampolinePlay(){
        return entity.dailyLife().boredom()>playBoredomFloor()&&entity.life().energy()>=stopEnergy()
                &&entity.mind().unease()<scareCeiling();
    }
    private int waitBudget(){
        var personality=entity.life().personality();
        return Math.max(60,(int)Math.round(personality.waitingTicks()*(1.5+personality.temperament().patient())));
    }
    private void emitBeat(String name,String declaredFace,String line,int holdTicks){
        entity.showReaction(declaredFace,"",holdTicks);
        if(!"none".equals(line))entity.speakCue(line);
        pendingBeat=new Beat(name,declaredFace,line);
    }
    public void clock(){
        if(cooldown>0)cooldown--;
        if(scanWait>0)scanWait--;
        if(waitCooldown>0)waitCooldown--;
        if(active()){
            if(elapsed==lastElapsed||!valid())stop();
            lastElapsed=elapsed;
        }else if(waiting()){
            if(waitElapsed==lastWaitElapsed||!waitValid())stopWait("interrupted");
            lastWaitElapsed=waitElapsed;
        }
    }
    public boolean canUse(){
        if(active())return valid();
        if(waiting())return waitValid();
        if(entity.outing().onOuting())return entity.outing().tryParkPlay();
        if(cooldown>0||scanWait>0||!available(true)||entity.isNoAi())return false;
        scanWait=160;
        if(entity.dailyLife().active()||entity.social().active())return false;
        var center=entity.blockPosition();
        for(var pos:BlockPos.betweenClosed(center.offset(-6,-1,-6),center.offset(6,1,6))){
            if(!entity.level().hasChunkAt(pos))continue;
            if(entity.level().getBlockEntity(pos) instanceof PlayBlockEntity found){
                if(found.kind()==PlayBlock.Kind.TRAMPOLINE){
                    if(!wantsTrampolinePlay())continue;
                    if(found.occupiedByOther(entity)){
                        if(beginWait(found))return true;
                        continue;
                    }
                }
                if(begin(found))return true;
            }
        }
        return false;
    }
    /** Stand beside an occupied trampoline and watch the bouncer without reserving the mat. */
    public boolean beginWait(PlayBlockEntity target){
        if(active()||waiting()||cooldown>0||waitCooldown>0)return false;
        if(target.kind()!=PlayBlock.Kind.TRAMPOLINE||!available(false))return false;
        if(target.getLevel()!=entity.level()||target.isRemoved())return false;
        if(!entity.childhood().canJump()||BodyAbility.cannotSpring(entity)||!wantsTrampolinePlay())return false;
        if(entity.distanceToSqr(Vec3.atCenterOf(target.getBlockPos()))>81)return false;
        Vec3 stand=findWaitStand(target);
        if(stand==null)return false;
        entity.social().stop();
        waitingFor=target;waitStand=stand;waitTicks=0;waitLimit=waitBudget();
        waitWatching=false;waitFaced=false;waitElapsed=0;lastWaitElapsed=-1;
        return true;
    }
    private Vec3 findWaitStand(PlayBlockEntity target){
        int preferred=(entity.getUUID().hashCode()&1)==0?1:-1;
        var level=entity.level();
        for(int s:new int[]{preferred,-preferred}){
            for(double z:WAIT_OFFSETS_Z){
                var candidate=PlayGeometry.world(target.getBlockPos(),target.facing(),s*WAIT_SIDE_X,0,z);
                var below=BlockPos.containing(candidate).below();
                if(!level.noCollision(entity,entity.getBoundingBox().move(candidate.subtract(entity.position())))
                        ||!level.getBlockState(below).isFaceSturdy(level,below,Direction.UP))continue;
                var path=entity.getNavigation().createPath(BlockPos.containing(candidate),0);
                if(path!=null&&path.canReach())return candidate;
            }
        }
        return null;
    }
    private boolean waitValid(){
        return waiting()&&available(false)&&!BodyAbility.cannotSpring(entity)&&!waitingFor.isRemoved()
                &&waitingFor.getLevel()==entity.level()
                &&entity.level() instanceof ServerLevel level&&level.hasChunkAt(waitingFor.getBlockPos())
                &&level.getBlockEntity(waitingFor.getBlockPos())==waitingFor
                &&(!entity.outing().onOuting()||(entity.outing().parkFacility()==waitingFor&&!entity.outing().calledByOwner()&&!entity.outing().tired()))
                &&entity.distanceToSqr(Vec3.atCenterOf(waitingFor.getBlockPos()))<144;
    }
    private void tickWait(){
        if(!waitValid()){stopWait("interrupted");return;}
        waitElapsed++;
        if(!waitingFor.occupiedByOther(entity)){
            var target=waitingFor;
            stopWait("free");
            if(!begin(target))waitCooldown=40;
            return;
        }
        double dist=waitStand.subtract(entity.position()).horizontalDistance();
        if(dist>.35){
            var result=SurfaceApproach.walkResult(entity,waitStand,null,1.0);
            if(result==SurfaceApproach.Result.BLOCKED){waitCooldown=160;stopWait("blocked");return;}
            return;
        }
        SurfaceApproach.stopWalking(entity);
        Vec3 focus=watchFocus(waitingFor);
        entity.getLookControl().setLookAt(focus.x,focus.y,focus.z,30,35);
        if(!waitFaced){
            Vec3 toFocus=focus.subtract(entity.position()).multiply(1,0,1);
            if(toFocus.lengthSqr()>1e-4)face(toFocus.normalize());
            waitFaced=true;
        }
        if(!waitWatching){
            waitWatching=true;
            String face=entity.dailyLife().boredom()>=75?"sparkle":"dull";
            String line=entity.life().personality().temperament().patient()>=Temperament.TOP35?"wait_turn":"none";
            emitBeat("wait_watch",face,line,50);
        }
        if(++waitTicks>=waitLimit){
            double patient=entity.life().personality().temperament().patient();
            emitBeat("wait_give_up",patient<Temperament.LOW35?"hmph":"dull","mood_bad",60);
            waitCooldown=400;
            stopWait("patience");
        }
    }
    private Vec3 watchFocus(PlayBlockEntity target){
        UUID occupant=target.occupant();
        if(occupant!=null&&entity.level() instanceof ServerLevel server&&server.getEntity(occupant) instanceof YukkuriEntity bouncer&&bouncer.isAlive()){
            return bouncer.getEyePosition();
        }
        return target.getBlockPos().getCenter().add(0,PlayBlock.BED_HEIGHT+.5,0);
    }
    private void stopWait(String reason){
        if(waitingFor!=null&&reason!=null){
            GameplayLog.limited(entity,"play","wait_ended:"+reason,600,
                    "trampoline wait ended reason={} at={} waited={}/{} boredom={}",
                    reason,waitingFor.getBlockPos(),waitTicks,waitLimit,entity.dailyLife().boredom());
        }
        waitingFor=null;waitStand=null;waitTicks=waitLimit=waitElapsed=0;lastWaitElapsed=-1;waitWatching=waitFaced=false;
        SurfaceApproach.stopWalking(entity);
    }
    public boolean begin(PlayBlockEntity target){
        if(active()||cooldown>0)return refuse("cooldown");
        if(!available(target.kind()==PlayBlock.Kind.POOL))return refuse("unfit");
        if(target.getLevel()!=entity.level()||target.isRemoved())return refuse("gone");
        if(target.kind()==PlayBlock.Kind.TRAMPOLINE&&(!entity.childhood().canJump()||BodyAbility.cannotSpring(entity)))return refuse("cannot_jump");
        if(entity.distanceToSqr(Vec3.atCenterOf(target.getBlockPos()))>81)return refuse("far");
        JuiceTub.Seat seat=null;
        boolean hurt=false;
        if(target.kind()==PlayBlock.Kind.POOL){
            // Each refusal has its remedy: pour juice, wait to grow, or sit on the bench once the juice reaches it.
            if(target.juice()==0)return refuse("tub_empty");
            if(JuiceTub.tooBig(entity.getBbWidth()))return refuse("too_big");
            var seats=JuiceTub.seatsFor(JuiceTub.layout(),entity.getBbWidth(),target.juice());
            if(seats.isEmpty())return refuse("tub_shallow");
            hurt=entity.body().hasJuiceTreatableWound();
            soakRule=null;
            if(!tubPermitted(hurt))return refuse("house_rule");
            seat=seats.get(Math.floorMod(entity.getUUID().hashCode(),seats.size()));
        }
        var tub=seat==null?null:JuiceTub.route(JuiceTub.layout(),seat);
        int seatAt=tub==null?-1:tub.seat();
        var local=switch(target.kind()){
            case CERAMIC->List.of(new Vec3(0,0,-2),new Vec3(0,.125,-1),new Vec3(0,.125,0),new Vec3(0,.125,1),new Vec3(0,0,2));
            case SLIDE->List.of(new Vec3(0,0,-2.8),new Vec3(0,.25,-1.9),new Vec3(0,.5,-1.4),new Vec3(0,.75,-.9),new Vec3(0,1,-.4),new Vec3(0,1,0),new Vec3(0,0,2.8));
            case TRAMPOLINE->List.of(new Vec3(0,0,-1.7),new Vec3(0,PlayBlock.BED_HEIGHT,0),
                    new Vec3(0,0,Math.max(1.7,PlayBlock.RIM_RADIUS+entity.getBbWidth()/2.0+.22)));
            case POOL->tub.points().stream().map(p->new Vec3(p[0],p[1],p[2])).toList();
        };
        var points=new ArrayList<>(local.stream().map(v->PlayGeometry.world(target.getBlockPos(),target.facing(),v.x,v.y,v.z)).toList());
        if(target.kind()==PlayBlock.Kind.CERAMIC||target.kind()==PlayBlock.Kind.TRAMPOLINE){
            // A finished pet steps out and aside so the exit lane is free for the next one; a side with no room is skipped.
            var exit=local.getLast();int side=(entity.getUUID().hashCode()&1)==0?1:-1;
            for(int s:new int[]{side,-side}){
                var aside=PlayGeometry.world(target.getBlockPos(),target.facing(),s*ASIDE_X,exit.y,exit.z+ASIDE_Z);
                var level=entity.level();
                if(level.noCollision(entity,entity.getBoundingBox().move(aside.subtract(entity.position())))
                        &&level.getBlockState(BlockPos.containing(aside).below()).isFaceSturdy(level,BlockPos.containing(aside).below(),Direction.UP)){
                    points.add(aside);break;
                }
            }
        }
        var start=points.getFirst();var path=entity.getNavigation().createPath(BlockPos.containing(start),0);
        if(path==null||!path.canReach())return refuse("blocked");
        if(!target.reserve(entity))return refuse("occupied");
        entity.social().stop();facility=target;route=points;elapsed=waypoint=bounces=stalled=0;lastDistance=Double.MAX_VALUE;
        completed=springing=peakVoiced=false;stopReason=null;soakAt=seatAt;soakTicks=0;soakHurt=hurt;cooldown=1600;
        lastElapsed=-1;
        entity.getNavigation().moveTo(path,.8);
        GameplayLog.limited(entity,"play","started",1200,"facility session started kind={} at={} energy={} boredom={}",
                target.kind(),target.getBlockPos(),entity.life().energy(),entity.dailyLife().boredom());
        return true;
    }
    /** A pet weighs its master's ban on the tub before climbing in; a wild or stray body has no master to obey. */
    private boolean tubPermitted(boolean hurt){
        HouseRule rule=JuiceTub.tubRule(hurt);
        var choice=entity.pet().conscience().tempted(rule,tubPressure(hurt),1);
        if(!JuiceTub.permitsSoak(choice)){
            GameplayLog.limited(entity,"play","tub_rule:"+rule.key(),1200,"tub refused by house rule rule={} choice={} hurt={} obedience={}",
                    rule.key(),choice,hurt,entity.pet().obedience());
            return false;
        }
        if(choice==Choice.BREAK)soakRule=rule;
        return true;
    }
    /** How badly the body wants the soak: play and unease for a healthy one, pain and lost health for a hurt one. */
    private double tubPressure(boolean hurt){
        return hurt
                ? Math.max(entity.body().condition().painSeverity()/3.0,1-entity.body().healthRatio())
                : Math.max(entity.dailyLife().boredom()/100.0,entity.mind().unease()/100.0);
    }
    private boolean refuse(String reason){refused=reason;return false;}
    private boolean valid(){return active()&&available(facility.kind()==PlayBlock.Kind.POOL)&&!(facility.kind()==PlayBlock.Kind.TRAMPOLINE&&BodyAbility.cannotSpring(entity))&&elapsed<(facility.kind()==PlayBlock.Kind.POOL?600+JuiceTub.SOAK_TICKS:600)&&!facility.isRemoved()&&facility.getLevel()==entity.level()
            &&entity.level() instanceof ServerLevel level&&level.hasChunkAt(facility.getBlockPos())&&level.getBlockEntity(facility.getBlockPos())==facility
            &&(!entity.outing().onOuting()||entity.outing().parkFacility()==facility)
            &&entity.distanceToSqr(Vec3.atCenterOf(facility.getBlockPos()))<144&&facility.reserve(entity);}
    public boolean canContinue(){return active()?valid():waitValid();}

    boolean springingAt(BlockPos root) {
        return springing && stopReason == null && facility != null && facility.getBlockPos().equals(root);
    }

    boolean stoppingAt(BlockPos root) {
        return springing && stopReason != null && facility != null && facility.getBlockPos().equals(root);
    }

    private String trampolineStopReason() {
        if (BodyAbility.cannotSpring(entity)) return "frail";
        if (entity.outing().onOuting() && (entity.outing().parkFacility() != facility || entity.outing().calledByOwner())) return "called";
        if (entity.life().energy() < stopEnergy()) return "tired";
        if (entity.mind().unease() >= scareCeiling()) return "scared";
        if (entity.dailyLife().boredom() <= playBoredomFloor()) return "enough";
        return null;
    }

    private void markTrampolineStop(String reason) {
        if (stopReason != null) return;
        stopReason = reason;
        String face = switch (reason) {
            case "enough" -> "satisfied";
            case "tired" -> "sleepy";
            case "scared" -> "afraid";
            case "called" -> "surprise";
            default -> "teary";
        };
        String line = "scared".equals(reason) ? "uneasy" : "none";
        emitBeat("stop_" + reason, face, line, 50);
    }

    void trampolineBounced() {
        bounces++;
        entity.life().spendEnergy(2);
        entity.dailyLife().entertained(4);
        double timid = entity.life().personality().temperament().timid();
        if (timid >= Temperament.TOP35) entity.mind().add((float) (timid * 5), "trampoline_height");
        entity.mind().felt(EmotionEvent.PLAYED);
        String reason = trampolineStopReason();
        if (reason != null) {
            markTrampolineStop(reason);
        } else if (bounces == 3 && !peakVoiced) {
            peakVoiced = true;
            String peakFace = entity.life().personality().temperament().is(Temperament.Gate.BOLD) ? "open_smile" : "happy";
            emitBeat("peak", peakFace, "glow", 50);
        } else {
            emitBeat("bounce", "happy", "none", 45);
        }
    }

    public void tickGoal(){
        if(waiting()){tickWait();return;}
        if(!valid()){stop();return;}elapsed++;
        Vec3 target=route.get(waypoint);double distance=target.subtract(entity.position()).horizontalDistance();
        entity.getLookControl().setLookAt(target.x,entity.getEyeY(),target.z,25,25);
        boolean trampoline = facility.kind() == PlayBlock.Kind.TRAMPOLINE;
        if (soaking() && distance < .22 && Math.abs(entity.getY() - target.y) < .6) {
            if (soak()) { waypoint++; lastDistance = Double.MAX_VALUE; stalled = 0; }
            return;
        }
        if (trampoline && waypoint == 1) {
            if (springing) {
                if (stopReason == null) {
                    String liveStop = trampolineStopReason();
                    if ("called".equals(liveStop) || "frail".equals(liveStop)) markTrampolineStop(liveStop);
                }
                if (!entity.onGround() || stopReason == null) {
                    if (entity.onGround() && entity.getDeltaMovement().y <= 0 && stopReason == null) {
                        TrampolineMotion.hop(entity);
                    } else {
                        TrampolineMotion.steady(entity);
                    }
                    return;
                }
                springing = false;
                // Hop cleanly off the settled bed toward the exit waypoint.
                if (entity.getDeltaMovement().y <= 0) TrampolineMotion.hop(entity);
                waypoint++;lastDistance=Double.MAX_VALUE;stalled=0;
                target=route.get(waypoint);distance=target.subtract(entity.position()).horizontalDistance();
            } else if (distance < .22 && PlayBlock.onTrampolineBed(entity)) {
                entity.getNavigation().stop();
                if (entity.motor().current() == null) {
                    springing = true;
                    stopReason = null;
                    peakVoiced = false;
                    TrampolineMotion.hop(entity);
                    String mountFace = entity.life().personality().temperament().timid() >= Temperament.TOP35 ? "confused" : "happy";
                    emitBeat("mount", mountFace, "none", 40);
                }
                return;
            }
        }
        boolean leavingTrampoline = trampoline && waypoint == 2;
        if (leavingTrampoline && distance < .22 && !entity.onGround()) {
            TrampolineMotion.steady(entity);
            return;
        }
        if(distance<.22&&Math.abs(entity.getY()-target.y)<.6&&!(trampoline&&waypoint==1)
                &&(!leavingTrampoline||PlayBlock.facility(entity.level(),entity.getOnPos())!=facility)){
            waypoint++;lastDistance=Double.MAX_VALUE;stalled=0;
            if(waypoint>=route.size()){
                completed=!trampoline||bounces>0;
                if(completed){
                    if(trampoline){
                        entity.dailyLife().entertained(5);
                        if(entity.outing().onOuting())entity.outing().finishedParkPlay();
                        entity.mind().felt(EmotionEvent.PLAYED);
                        String dismountFace = switch (stopReason == null ? "" : stopReason) {
                            case "enough" -> "satisfied";
                            case "tired" -> "sleepy";
                            case "scared" -> "teary";
                            default -> "happy";
                        };
                        emitBeat("dismount", dismountFace, "none", 70);
                    }else{
                        entity.dailyLife().entertained(Math.min(30,20+2*bounces));
                        if(entity.outing().onOuting())entity.outing().finishedParkPlay();
                        entity.mind().felt(EmotionEvent.PLAYED);entity.showReaction("happy","",70);
                    }
                }
                stop();return;
            }
            if(!entity.life().spendEnergy(.5f)){stop();return;}target=route.get(waypoint);
        }
        if(entity.horizontalCollision)askToYield(target);
        if(distance<lastDistance-.015){stalled=0;lastDistance=distance;}else if(++stalled>100){
            GameplayLog.limited(entity,"play","stalled",1200,"facility route stalled waypoint={} at={} target={} collided={} onGround={} navDone={} navTarget={} wanted={} speed={} motor={} leashed={} riding={}",
                    waypoint,GameplayLog.at(entity.position()),GameplayLog.at(target),entity.horizontalCollision,entity.onGround(),
                    entity.getNavigation().isDone(),entity.getNavigation().getTargetPos(),entity.getMoveControl().hasWanted(),entity.getSpeed(),
                    entity.motor().owner()==null?"none":entity.motor().owner().ownerId(),entity.isLeashed(),entity.isPassenger());
            stop();return;}
        if(facility.kind()==PlayBlock.Kind.SLIDE&&waypoint==6){
            entity.getNavigation().stop();Vec3 direction=target.subtract(entity.position()).multiply(1,0,1).normalize();
            face(direction);
            Motor.velocity(entity,new Vec3(direction.x*.13,entity.getDeltaMovement().y,direction.z*.13),"slide_momentum");
        }else if(distance<2.5){
            entity.getNavigation().stop();entity.getMoveControl().setWantedPosition(entity.getX(),entity.getY(),entity.getZ(),0);
            // Vanilla MoveControl interprets the roof in a hollow cell as a step and jumps into it.
            // For the short authored route, drive horizontal motion; collision and gravity still resolve each step.
            Vec3 direction=target.subtract(entity.position()).multiply(1,0,1).normalize();
            face(direction);
            // Authored short-route speed: the facility route carries the body at its own pace, not the walk ability's.
            Motor.velocity(entity,new Vec3(direction.x*.065,entity.getDeltaMovement().y,direction.z*.065),"play_route");
        }else if(elapsed%10==1&&!entity.getNavigation().moveTo(target.x,target.y,target.z,.8)){stop();}
    }
    /** Pushing against a body that is not on the facility (a pet that finished and idles at the tunnel's end): ask it to step aside, as staff do. */
    private void askToYield(Vec3 target){
        var line=target.subtract(entity.position()).multiply(1,0,1);
        if(line.lengthSqr()<1e-6)return;
        var ahead=entity.getBoundingBox().expandTowards(line.normalize().scale(.6)).inflate(.1,0,.1);
        for(var other:entity.level().getEntitiesOfClass(YukkuriEntity.class,ahead,o->o!=entity&&!o.playLife().active()))
            YukkuriGroundNavigation.giveWay(other,entity,target);
    }
    /**
     * One tick in the juice at the seat. Every {@link JuiceTub#STEP_TICKS} a hurt body regains a little health and the oldest wound
     * orange juice treats closes a grade now and then; every body gets cleaner and the warm soak eases its unease. A whole soak uses
     * up one level. Done (true) when the soak ran its length or the juice ran out, and the route carries the body back out over the
     * rim and stool.
     */
    private boolean soak(){
        var back=PlayGeometry.world(facility.getBlockPos(),facility.facing(),0,0,1).subtract(PlayGeometry.world(facility.getBlockPos(),facility.facing(),0,0,0));
        entity.getNavigation().stop();entity.getMoveControl().setWantedPosition(entity.getX(),entity.getY(),entity.getZ(),0);
        face(back);
        if(soakTicks==0){
            soakHealth=entity.getHealth();soakWounds=entity.body().wounds().size();soakJuice=facility.juice();soakUnease=entity.mind().unease();
            if(soakRule!=null)entity.pet().conscience().breach(soakRule);
            GameplayLog.event(entity,"play","tub soak started at={} juice={} health={}/{} wounds={} hygiene={} hurt={} unease={}",facility.getBlockPos(),
                    soakJuice,entity.getHealth(),entity.getMaxHealth(),soakWounds,entity.life().hygiene(),soakHurt,soakUnease);
        }
        if(facility.juice()==0){soakEnded(false);return true;}
        soakTicks++;
        if(soakTicks%JuiceTub.STEP_TICKS==0){
            int step=soakTicks/JuiceTub.STEP_TICKS;
            var payout=JuiceTub.payout(soakHurt,step);
            if(payout.heal())entity.heal(JuiceTub.healthStep(entity.getMaxHealth()));
            if(payout.mend())entity.body().soakMend();
            entity.life().juiceSoakStep(JuiceTub.washesMarks(step));
            entity.mind().add(-JuiceTub.uneaseStep(entity.life().personality().temperament().timid()),"juice_soak");
        }
        if(soakTicks<JuiceTub.SOAK_TICKS)return false;
        facility.soaked();soakEnded(true);return true;
    }
    private void soakEnded(boolean used){
        GameplayLog.event(entity,"play","tub soak ended at={} used={} juice={} -> {} health={} -> {} wounds={} -> {} hygiene={} unease={} -> {}",
                facility.getBlockPos(),used,soakJuice,facility.juice(),soakHealth,entity.getHealth(),soakWounds,entity.body().wounds().size(),
                entity.life().hygiene(),soakUnease,entity.mind().unease());
    }
    public void stop(){stop(true);}
    private void stop(boolean log){
        if(waitingFor!=null)stopWait(log?"interrupted":null);
        if(facility!=null){
            if(soaking()&&soakTicks>0&&soakTicks<JuiceTub.SOAK_TICKS)soakEnded(false);
            facility.release(entity);
            if(log)GameplayLog.limited(entity,"play","ended",1200,
                    "facility session ended kind={} at={} completed={} reason={} elapsed={} waypoint={} bounces={} stalled={} nutrition={} energy={} boredom={}",
                    facility.kind(),facility.getBlockPos(),completed,stopReason==null?(completed?"done":"interrupted"):stopReason,
                    elapsed,waypoint,bounces,stalled,entity.life().nutrition(),entity.life().energy(),entity.dailyLife().boredom());
        }
        springing=false;peakVoiced=false;stopReason=null;facility=null;route=List.of();soakAt=-1;soakTicks=0;soakHurt=false;soakRule=null;
        if(!log)pendingBeat=null;
        entity.getNavigation().stop();
    }
    private void face(Vec3 direction){Motor.face(entity,(float)(Math.atan2(-direction.x,direction.z)*180/Math.PI));}
    public CompoundTag save(){var tag=new CompoundTag();tag.putInt("Cooldown",cooldown);return tag;}
    public void load(CompoundTag tag){stop(false);cooldown=Math.clamp(tag.getInt("Cooldown"),0,24000);completed=false;}
}
