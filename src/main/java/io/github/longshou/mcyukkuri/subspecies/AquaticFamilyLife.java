package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;

/** Living family flotillas after the first lesson; ancestry and departure remain separate. */
public final class AquaticFamilyLife {
    private static final double FOLLOW_START=4.5, FOLLOW_STOP=3.8, WAIT_START=6, WAIT_STOP=4.2;
    private final YukkuriEntity entity;
    private final HatFamilyAlarm alarm;
    private UUID parentId, parentHat, waitingOn, steeringHat, distressedKin;
    private GlobalPos lastParent;
    private final Set<UUID> knownFleet=new HashSet<>();
    private final Map<UUID,Integer> missingYoung=new HashMap<>();
    private int missingParent, safeTicks, routeWait, returnWait, reactionTicks;
    private boolean following, guarding, returnRequested, gathering, seekingQuiet;
    private String activity="idle";
    private String rescuePhase="idle";
    private long rescuePhaseAt;
    private UUID rescueTarget;
    private UUID recoveringHat, missedHat;
    private long recoveryStarted, missedHatUntil;
    private ServerLevel cacheLevel;
    private long cacheTick=Long.MIN_VALUE;
    private boolean parentResolved;
    private YukkuriEntity cachedParent;
    private List<YukkuriEntity> cachedChildren;
    private final Map<UUID,LivingEntity> cachedThreats=new HashMap<>();

    public AquaticFamilyLife(YukkuriEntity entity){this.entity=entity;alarm=new HatFamilyAlarm(entity);}
    public HatFamilyAlarm alarm(){return alarm;}
    public String alarmActivity(){return !alarm.active()&&activity.startsWith("boat_rescue_")?activity:alarm.activity();}
    public long alarmPhaseStart(){return !alarm.active()&&activity.startsWith("boat_rescue_")?rescuePhaseAt:alarm.phaseStart();}
    public int alarmSourceId(){var id=alarm.sourceId();var source=id==null?null:level().getEntity(id);return source==null?-1:source.getId();}
    public int rescueTargetId(){
        if(alarm.active())return alarm.rescueTargetId();
        var child=activity.startsWith("boat_rescue_")?person(rescueTarget):null;return child==null?-1:child.getId();
    }
    private void rescueActivity(YukkuriEntity child,String phase){
        if(!phase.equals(rescuePhase)||!child.getUUID().equals(rescueTarget)){
            rescuePhase=phase;rescuePhaseAt=level().getGameTime();
            if(phase.equals("boat_rescue_approach"))entity.showReaction("teary","braid_rescue_offer",40);
        }
        rescueTarget=child.getUUID();activity=phase;
    }
    boolean alarmSafe(HatBoatEntity boat){return boat.protects(entity)&&boat.flooding()==0&&!boat.towed()
            &&HatBoatRoute.calmSurface(level(),water(boat))&&!entity.isOnFire()&&nearestThreat(entity)==null;}
    private ServerLevel level(){return (ServerLevel)entity.level();}
    private void cacheTick(){
        var current=level();long now=current.getGameTime();
        if(cacheLevel==current&&cacheTick==now)return;
        cacheLevel=current;cacheTick=now;parentResolved=false;cachedParent=null;cachedChildren=null;cachedThreats.clear();
    }
    private YukkuriEntity person(UUID id){
        return id!=null&&level().getEntity(id) instanceof YukkuriEntity y&&y.isAlive()?y:null;
    }
    /** Being grown enough to sail is not a completed departure from the household. */
    public boolean followsParent(){
        return HatVessel.aquatic(entity)&&entity.aquaticLife().firstVoyage().completed()
                &&!entity.aquaticLife().firstVoyage().independent()&&!entity.independence().independent()
                &&(entity.family().mother()!=null||entity.family().father()!=null);
    }
    private boolean dependentChild(){
        return followsParent()||HatVessel.aquatic(entity)&&!entity.family().adult()
                &&!entity.aquaticLife().firstVoyage().independent()&&!entity.independence().independent()
                &&(entity.family().mother()!=null||entity.family().father()!=null);
    }
    public boolean busy(){return alarm.preventsOuting()||following||guarding||returnRequested||gathering||waitingOn!=null;}
    public String activity(){return activity;}
    /** A swimmer must actually grip the near brim and climb; water is not a jump takeoff. */
    public boolean tryWaterBoard(HatBoatEntity home){
        if(!entity.isInWaterOrBubble()||!entity.childhood().canJump()||!home.young(entity))return false;
        if(!entity.family().scrambleIntoHat(home))return false;
        entity.showReaction("teary","afraid",40);return true;
    }
    /** Called only by an actual nearby, visible distressed relative or established friend. */
    public void witnessDistress(YukkuriEntity young){
        if(young==entity||!young.isAlive()||entity.isNoAi()||entity.isCommandPlaying()||entity.operations().active()
                ||entity.distanceToSqr(young)>144||!entity.hasLineOfSight(young))return;
        boolean fresh=!young.getUUID().equals(distressedKin)||reactionTicks==0;
        distressedKin=young.getUUID();reactionTicks=60;
        if(entity.getVehicle() instanceof HatBoatEntity boat&&boat.protects(entity)&&!boat.unsteadyMotion()){
            entity.life().wakeInHat();entity.getLookControl().setLookAt(young,35,35);
            if(!entity.getUUID().equals(boat.ownerYukkuri()))boat.requestInnerRefuge(entity);
            if(fresh)entity.showReaction("teary",entity.family().caresFor(young)?"caregiver":"afraid",45);
        }
    }
    /** Real turn, wait and supported inward movement; urgent care remains above this response. */
    public boolean reactInHat(HatBoatEntity boat){
        var young=reactionTicks>0?person(distressedKin):null;
        if(young==null||entity.life().hasUrgentNeed()||entity.life().mealTicks()>0||entity.family().laboring()
                ||!boat.protects(entity)||boat.unsteadyMotion()||entity.distanceToSqr(young)>144||!entity.hasLineOfSight(young))return false;
        if(!young.isInWaterOrBubble()&&young.waterDistress().phase()!=io.github.longshou.mcyukkuri.life.WaterDistress.Phase.RECOVER)return false;
        entity.life().wakeInHat();entity.getLookControl().setLookAt(young,35,35);
        if(entity.getUUID().equals(boat.ownerYukkuri()))boat.holdPosition();
        activity=boat.overcrowded()?"boat_crowd_guard":"boat_reassure";return true;
    }
    private void alertCrew(YukkuriEntity young,HatBoatEntity boat){
        for(var passenger:boat.getPassengers())if(passenger instanceof YukkuriEntity member&&member!=young)
            member.aquaticLife().familyLife().witnessDistress(young);
    }
    private HatBoatEntity ownHat(YukkuriEntity actor){
        var hat=HatBoatEntity.owned(actor);
        return hat!=null&&hat.isAlive()&&hat.isAfloat()&&actor.getVehicle()==hat?hat:null;
    }
    private boolean parentOf(YukkuriEntity parent){
        return parent!=null&&(parent.getUUID().equals(entity.family().mother())||parent.getUUID().equals(entity.family().father()));
    }
    /** Resolve only live entities already present in this dimension; an absent UUID is not a death. */
    private YukkuriEntity contactParent(){
        cacheTick();if(parentResolved)return cachedParent;parentResolved=true;
        var remembered=person(parentId);
        if(parentOf(remembered)&&ownHat(remembered)!=null&&entity.distanceToSqr(remembered)<=36
                &&(ownHat(remembered).rocking()||!danger(remembered,ownHat(remembered))))return cachedParent=remembered;
        YukkuriEntity best=null;
        double bestScore=Double.POSITIVE_INFINITY;
        for(var id:new UUID[]{entity.family().mother(),entity.family().father()}){
            var candidate=person(id);if(candidate==null||!HatVessel.aquatic(candidate)||ownHat(candidate)==null)continue;
            // Nearby refuge matters during rescue; a safe but distant other parent must
            // not make the parent beside the swimmer believe the child belongs elsewhere.
            double score=entity.distanceToSqr(candidate)+(danger(candidate,ownHat(candidate))?16:0);
            if(score<bestScore){best=candidate;bestScore=score;}
        }
        if(best!=null)parentId=best.getUUID();
        return cachedParent=best;
    }
    public boolean nearParent(){
        if(!followsParent())return false;
        var parent=contactParent();var home=parent==null?null:ownHat(parent);
        return home!=null&&entity.position().subtract(home.position()).horizontalDistanceSqr()<=16;
    }
    public boolean safeForOuting(){
        if(!followsParent()||busy()||level().isNight()||entity.life().hasUrgentNeed()
                ||entity.life().mealTicks()>0||entity.life().isSleeping()||entity.life().energy()<45
                ||entity.aquaticLife().firstVoyage().active())return false;
        var own=ownHat(entity);var parent=contactParent();var home=parent==null?null:ownHat(parent);
        return own!=null&&home!=null&&nearParent()&&!danger(entity,own)&&!danger(parent,home)
                &&!parent.life().isSleeping()&&!parent.aquaticLife().care().active();
    }

    /** Bounds autonomous food/shore trips, while a missing parent still permits finite self-rescue. */
    public boolean allowsDestination(HatBoatEntity boat,Vec3 point){
        if(!followsParent())return true;
        var parent=contactParent();var home=parent==null?null:ownHat(parent);
        if(home!=null&&refuge(parent,home))return point.subtract(home.position()).horizontalDistanceSqr()<=36;
        if(point.subtract(boat.position()).horizontalDistanceSqr()>64)return false;
        if(lastParent==null||!lastParent.dimension().equals(level().dimension()))return true;
        if(entity.life().nutrition()<25||entity.life().energy()<20||danger(entity,boat))return true;
        // Search nearby first. After a real loaded wait, widen the remembered family area;
        // do not hold a hungry orphan indefinitely at an unloaded parent's exact coordinate.
        double radius=missingParent<100?8:Math.min(24,8+(missingParent-100)/40.0);
        return point.subtract(Vec3.atCenterOf(lastParent.pos())).horizontalDistanceSqr()<=radius*radius;
    }

    public void clock(){
        if(entity.level().isClientSide||!HatVessel.aquatic(entity))return;
        if(routeWait>0)routeWait--;if(returnWait>0)returnWait--;if(reactionTicks>0)reactionTicks--;
        var boat=ownHat(entity);
        if(boat!=null&&entity.tickCount%20==0&&!entity.isNoAi()&&!entity.isCommandPlaying()&&!entity.operations().active()){
            var threat=nearestThreat(entity);
            if(threat!=null)alarm.start(boat,threat,threat.position());
            else if(boat.flooding()>0||loadedChildren().stream().anyMatch(child->!child.isPassenger()&&child.isInWaterOrBubble()&&entity.distanceToSqr(child)<=144))
                alarm.start(boat,null,boat.position());
            else for(var child:loadedChildren()){
                var childThreat=nearestThreat(child);
                if(childThreat!=null&&entity.distanceToSqr(child)<=144&&entity.hasLineOfSight(child)){alarm.start(boat,childThreat,childThreat.position());break;}
                var childHat=ownHat(child);
                if(childHat!=null&&childHat.rocking()&&entity.distanceToSqr(child)<=144&&entity.hasLineOfSight(child)){
                    var sourceId=child.aquaticLife().familyLife().alarm().sourceId();var rocker=sourceId==null?null:level().getEntity(sourceId);
                    if(rocker!=null)alarm.start(boat,rocker,rocker.position());
                    break;
                }
            }
        }
        alarm.clock(loadedChildren());
        if(followsParent()){
            var parent=contactParent();var home=parent==null?null:ownHat(parent);
            if(home!=null){
                parentHat=home.getUUID();lastParent=GlobalPos.of(level().dimension(),home.blockPosition());missingParent=0;
                if(!danger(entity,ownHat(entity))&&!danger(parent,home))safeTicks=Math.min(200,safeTicks+1);else safeTicks=0;
            }else{missingParent=Math.min(2400,missingParent+1);safeTicks=0;}
        }else{following=returnRequested=false;safeTicks=0;}
        // Remember ships actually seen belonging to this parent. Unloading keeps their identity;
        // it grants neither a death nor an independent-household result.
        if(entity.tickCount%20==0)for(var child:loadedChildren()){
            var own=ownHat(child);
            if(own!=null||child.aquaticLife().firstVoyage().returningToParent())knownFleet.add(child.getUUID());
        }
        for(var id:List.copyOf(knownFleet)){
            var child=person(id);var record=FamilyData.get(level()).person(id);
            if(child!=null&&!child.aquaticLife().familyLife().dependentChild()
                    ||record!=null&&(record.status==FamilyData.Status.DEAD||record.status==FamilyData.Status.LOST)){
                knownFleet.remove(id);missingYoung.remove(id);continue;
            }
            if(child==null)missingYoung.merge(id,1,(a,b)->Math.min(2400,a+b));else missingYoung.remove(id);
        }
    }

    /** May hold an idle parent for its family, but never consumes the caller's basic-needs turn. */
    public void beforeNeeds(HatBoatEntity boat){
        guarding=gathering=false;activity="idle";
        if(!entity.getUUID().equals(boat.ownerYukkuri())||!boat.isAfloat())return;
        if(alarm.active())return;
        var children=freeChildren();
        var selected=children.stream().max(Comparator.<YukkuriEntity>comparingInt(y->danger(y,ownHat(y))?2:y.aquaticLife().firstVoyage().returningToParent()||y.family().flying()?1:0)
                .thenComparingDouble(entity::distanceToSqr)).orElse(null);
        if(selected!=null){
            double distance=horizontal(boat.position(),selected.position());
            // Keep the fleet-wide stop threshold when the furthest sibling changes; otherwise
            // two similarly distant children make the captain repeatedly clear her wait latch.
            boolean waiting=waitingOn!=null;
            double limit=selected.life().isSleeping()?WAIT_START:waiting?WAIT_STOP:WAIT_START;
            if(danger(selected,ownHat(selected))||selected.aquaticLife().firstVoyage().returningToParent()||selected.family().flying()
                    ||distance>limit)waitingOn=selected.getUUID();
            else waitingOn=null;
        }else waitingOn=null;
        boolean missing=missingYoung.values().stream().anyMatch(ticks->ticks<=100);
        boolean sleeping=children.stream().anyMatch(y->y.life().isSleeping());
        if(!danger(entity,boat)&&HatBoatRoute.quietSurface(level(),water(boat))
                &&!entity.life().hasUrgentNeed()&&(waitingOn!=null||sleeping||missing)
                &&!(boat.getUUID().equals(steeringHat)&&boat.paddling()))boat.holdPosition();
    }

    /** Called after the passenger has had its meal, drink, toilet and rest opportunity. */
    public boolean tickPassenger(HatBoatEntity home){
        if(!followsParent()||entity.aquaticLife().firstVoyage().active()||!parentOf(person(home.ownerYukkuri())))return false;
        var parent=person(home.ownerYukkuri());
        parentId=home.ownerYukkuri();parentHat=home.getUUID();lastParent=GlobalPos.of(level().dimension(),home.blockPosition());
        if(home.unsteadyMotion()){safeTicks=0;return false;}
        var abandoned=HatBoatEntity.owned(entity);
        if(abandoned!=null&&!abandoned.unsteadyMotion()&&abandoned.retrieveAfloat(entity,home)){
            entity.showReaction("happy","hat_recovered",40);
            safeTicks=0;activity="boat_return_family";return true;
        }
        if(parent==null||alarm.preventsOuting()||parent.aquaticLife().familyLife().alarm.preventsOuting()||safeTicks<80||level().isNight()||danger(entity,home)||danger(parent,home)
                ||entity.life().hasUrgentNeed()||entity.life().mealTicks()>0||entity.life().isSleeping()
                ||entity.life().nutrition()<65||entity.life().energy()<50||parent.life().isSleeping()||parent.life().hasUrgentNeed()
                ||parent.aquaticLife().care().active())return false;
        if(entity.aquaticLife().firstVoyage().tryRoutineLaunch()){
            activity="voyage_prepare";return true;
        }
        return false;
    }

    /** Runs after immediate food/drink handling and before autonomous travel or the owner's panic hold. */
    public boolean tickOwner(HatBoatEntity boat){
        if(!entity.getUUID().equals(boat.ownerYukkuri())||!boat.isAfloat()||entity.aquaticLife().firstVoyage().active())return false;
        if(boat.unsteadyMotion()){hold(boat,"boat_wait_young");return true;}
        if(alarm.responding()&&alarm.tickResponse()){activity=alarm.activity();return true;}
        if(alarm.tickOwner(boat,loadedChildren())){guarding=true;activity=alarm.activity();return true;}
        if(followsParent()&&tickYoungOwner(boat))return true;
        if(tickParentOwner(boat))return true;
        steeringHat=null;return false;
    }

    private boolean tickYoungOwner(HatBoatEntity boat){
        var parent=contactParent();var home=parent==null?null:ownHat(parent);
        if(home==null){
            following=gathering=returnRequested=false;steeringHat=null;return false;
        }
        if(home.unsteadyMotion()){
            safeTicks=0;returnWait=0;
            if(immediateDanger(entity,boat))return yieldSteering(boat);
            following=true;hold(boat,"boat_wait_young");return true;
        }
        boolean rest=level().isNight()||parent.life().isSleeping()||entity.life().isSleeping()||entity.life().energy()<=35;
        // Rain calls for nearby shelter at bedtime. It must not keep gathering boats in
        // exposed water forever while hiding the ordinary owner's shelter search.
        boolean alarm=immediateDanger(entity,boat)||immediateDanger(parent,home)
                ||!rest&&(danger(entity,boat)||danger(parent,home));
        boolean needsHomeFood=entity.life().nutrition()<60&&boat.cargo().items().stream().noneMatch(entity.life()::acceptsFood);
        if(alarm||needsHomeFood){
            safeTicks=0;returnRequested=true;
            if(!refuge(parent,home)){
                returnRequested=following=false;steeringHat=null;return false;
            }
            if(returnWait==0){
                returnWait=20;
                if(entity.aquaticLife().firstVoyage().requestReturnToParent(home,alarm)){
                    hold(boat,"boat_return_family");return true;
                }
            }
            // If transfer is temporarily unavailable, close via the same checked water route.
            // The ordinary emergency goal remains available when this small hat is no longer safe.
            if(!boat.protects(entity)||boat.flooding()>0||boat.towed())return false;
            entity.life().wakeInHat();following=true;
            steer(boat,home.position(),false);activity="boat_return_family";return true;
        }
        returnRequested=false;
        if(entity.life().hasUrgentNeed()&&!entity.life().isSleeping())return false;
        Vec3 slot=familySlot(parent,home);
        double distance=horizontal(boat.position(),home.position()),error=horizontal(boat.position(),slot);
        if(rest){
            gathering=true;
            if(!HatBoatRoute.quietSurface(level(),water(boat))){
                if(distance<=6&&seekQuietWater(boat,home,List.of()))return true;
                if(distance>6&&steer(boat,slot,false)){activity="boat_follow_family";return true;}
                return yieldSteering(boat);
            }
            if(distance>4.2||!entity.life().isSleeping()&&error>1.2){
                if(steer(boat,slot,true)){entity.life().wakeInHat();activity="boat_follow_family";return true;}
                if(distance>WAIT_START)return yieldSteering(boat);
            }
            hold(boat,"boat_wait_young");
            if(HatBoatRoute.quietSurface(level(),water(boat))){
                entity.life().restInHat(boat);
                if(entity.life().isSleeping()||level().isNight())activity="boat_sleep";
            }
            return true;
        }
        gathering=false;
        if(distance>FOLLOW_START||error>1.8)following=true;
        if(following&&distance<FOLLOW_STOP&&error<.9)following=false;
        if(following){steer(boat,slot,true);activity="boat_follow_family";return true;}
        steeringHat=null;return false;
    }

    private boolean tickParentOwner(HatBoatEntity boat){
        var children=freeChildren();
        if(!refuge(entity,boat)||entity.life().nutrition()<15||entity.life().energy()<10||entity.life().mealTicks()>0)return false;
        boolean needsCare=boat.getPassengers().stream().anyMatch(passenger->passenger instanceof YukkuriEntity child
                &&child!=entity&&!child.family().adult()&&(child.life().nutrition()<60||child.life().hasUrgentNeed()
                ||child.childhood().needsAssistedFirstToilet()));
        boolean rescue=children.stream().anyMatch(child->child.family().flying()||child.family().braidRescueActive()||!child.isPassenger()&&child.isInWaterOrBubble()
                ||child.isOnFire()||nearestThreat(child)!=null);
        if(entity.aquaticLife().care().active()){
            if(!rescue)return false;
            entity.aquaticLife().care().cancel();
        }
        // A sleeping flotilla must not indefinitely hide an infant's real meal or first toilet.
        // A committed rescue still has a brief, concrete physical task to finish first.
        if(needsCare&&!rescue)return yieldSteering(boat);
        if(children.isEmpty()&&missingYoung.values().stream().noneMatch(ticks->ticks<=100))return recoverNearbyHat(boat);
        var troubled=children.stream().filter(y->danger(y,ownHat(y))||y.aquaticLife().firstVoyage().returningToParent()||y.family().flying()||y.family().braidRescueActive()||y.getUUID().equals(waitingOn))
                .max(Comparator.<YukkuriEntity>comparingInt(y->y.family().flying()?4:y.isInWaterOrBubble()?3
                        :y.aquaticLife().firstVoyage().returningToParent()?2:danger(y,ownHat(y))?1:0)
                        .thenComparingDouble(y->y.isInWaterOrBubble()?-y.getHealth():0)
                        .thenComparingDouble(y->y.isInWaterOrBubble()?-entity.distanceToSqr(y):entity.distanceToSqr(y))).orElse(null);
        if(troubled!=null){
            if(troubled.isInWaterOrBubble()||troubled.family().flying())alertCrew(troubled,boat);
            boolean together=children.stream().allMatch(y->horizontal(boat.position(),y.position())<=WAIT_STOP);
            if(together&&!HatBoatRoute.quietSurface(level(),water(boat))
                    &&children.stream().noneMatch(y->immediateDanger(y,ownHat(y))||y.aquaticLife().firstVoyage().returningToParent())){
                gathering=true;
                if(seekQuietWater(boat,null,children))return true;
                return yieldSteering(boat);
            }
            entity.life().wakeInHat();entity.getLookControl().setLookAt(troubled,30,30);
            if(troubled.aquaticLife().firstVoyage().returningToParent()||troubled.family().flying()||troubled.family().braidRescueActive()){
                hold(boat,"boat_rescue_young");rescueActivity(troubled,"boat_rescue_young");return true;
            }
            boolean threatened=danger(troubled,ownHat(troubled));guarding=threatened;waitingOn=troubled.getUUID();
            if(threatened){
                if(!troubled.isPassenger()&&troubled.isInWaterOrBubble()){
                    // A capsize ejects its owner, so the ordinary own-boat return state
                    // cannot handle this case. Reach the swimmer before starting a real lift.
                    if(troubled.family().rescueIntoHat(boat))hold(boat,"boat_return_family");
                    else if(this.alarm.approachRescue(boat,troubled))steeringHat=boat.getUUID();
                    else if(horizontal(boat.position(),troubled.position())>.8)steer(boat,troubled.position(),false);
                    else hold(boat,"boat_guard_young");
                    rescueActivity(troubled,boat.paddling()?"boat_rescue_approach":"boat_rescue_young");return true;
                }
                var threat=nearestThreat(troubled);
                Vec3 toward=threat==null?Vec3.ZERO:threat.position().subtract(troubled.position()).multiply(1,0,1).normalize();
                Vec3 guard=troubled.position().add(toward.scale(1.5));
                if(horizontal(boat.position(),guard)>2.2)steer(boat,guard,false);else hold(boat,"boat_guard_young");
                activity="boat_guard_young";
                if(horizontal(boat.position(),troubled.position())<5
                        &&troubled.aquaticLife().firstVoyage().requestReturnToParent(boat,true))hold(boat,"boat_return_family");
            }else if(horizontal(boat.position(),troubled.position())>8){
                steer(boat,troubled.position(),true);activity="boat_wait_young";
            }else hold(boat,"boat_wait_young");
            return true;
        }
        boolean sleep=children.stream().anyMatch(y->y.life().isSleeping());
        boolean separated=children.stream().anyMatch(y->horizontal(boat.position(),y.position())>WAIT_START);
        boolean missing=missingYoung.values().stream().anyMatch(ticks->ticks<=100);
        if(level().isNight()||sleep||entity.life().isSleeping()||missing){
            gathering=true;
            if(!HatBoatRoute.quietSurface(level(),water(boat))){
                if(!separated&&!missing&&seekQuietWater(boat,null,children))return true;
                return yieldSteering(boat);
            }
            hold(boat,"boat_wait_young");
            if(!separated&&!missing&&HatBoatRoute.quietSurface(level(),water(boat))){
                entity.life().restInHat(boat);if(entity.life().isSleeping()||level().isNight())activity="boat_sleep";
            }
            return true;
        }
        gathering=false;return false;
    }

    /** After lifting a swimmer, collect its actual nearby hat before trying another launch. */
    private boolean recoverNearbyHat(HatBoatEntity home){
        for(var child:loadedChildren()){
            if(child.getVehicle()!=home||!home.protects(child)||child.family().flying())continue;
            var abandoned=HatBoatEntity.owned(child);
            if(abandoned==null||abandoned.unsteadyMotion()||abandoned.isVehicle()||abandoned.hasIncoming()
                    ||!(abandoned.isAfloat()||abandoned.state()==HatBoatEntity.State.CAPSIZED||abandoned.state()==HatBoatEntity.State.BEACHED)
                    ||horizontal(home.position(),abandoned.position())>8)continue;
            if(abandoned.retrieveAfloat(child,home)){
                child.showReaction("happy","hat_recovered",40);recoveringHat=null;
                hold(home,"boat_return_family");return true;
            }
            if(approachDriftingHat(child,home,abandoned))return true;
        }
        recoveringHat=null;
        return false;
    }

    /** Move the occupied parent hat beside the real abandoned brim; only the child may reclaim it at contact. */
    public boolean approachDriftingHat(YukkuriEntity child,HatBoatEntity home,HatBoatEntity abandoned){
        if(child==null||abandoned==null||child.getVehicle()!=home||!home.protects(child)||!home.young(child)
                ||!entity.getUUID().equals(home.ownerYukkuri())||entity.getVehicle()!=home
                ||!home.protects(entity)||!entity.family().caresFor(child)||child.family().flying()
                ||abandoned.isVehicle()||abandoned.hasIncoming()||abandoned.unsteadyMotion()
                ||!(abandoned.isAfloat()||abandoned.state()==HatBoatEntity.State.CAPSIZED||abandoned.state()==HatBoatEntity.State.BEACHED)
                ||entity.life().hasUrgentNeed()||entity.life().isSleeping()||entity.life().mealTicks()>0
                ||entity.aquaticLife().care().active()||alarm.preventsOuting()||danger(entity,home)
                ||!entity.hasLineOfSight(abandoned))return false;
        long now=level().getGameTime();
        if(abandoned.getUUID().equals(missedHat)&&now<missedHatUntil)return false;
        if(!abandoned.getUUID().equals(recoveringHat)){
            recoveringHat=abandoned.getUUID();recoveryStarted=now;
            child.showReaction("teary","hat_drifting",40);
        }
        if(horizontal(home.position(),abandoned.position())>8||now-recoveryStarted>160){
            missedHat=abandoned.getUUID();missedHatUntil=now+240;recoveringHat=null;
            child.showReaction("teary","hat_out_of_reach",40);hold(home,"boat_wait_young");return false;
        }
        home.approachBoardingEdge(child,abandoned.position());
        child.getLookControl().setLookAt(abandoned,30,30);
        if(abandoned.retrievalGrip(child)!=null){hold(home,"boat_return_family");return true;}
        var target=abandoned.retrievalApproach(home,child);
        if(target==null)return false;
        guarding=true;activity="boat_guard_young";
        if(horizontal(home.position(),target)<=.05){hold(home,"boat_return_family");return true;}
        if(routeWait>0)return home.paddling()||home.changingSeat(child);
        routeWait=8;
        if(!home.cargo().paddle()||home.towed())return false;
        var path=new ArrayList<>(HatBoatRoute.find(home,target,point->recoveryWater(home,point)));
        var from=path.isEmpty()?home.position():path.getLast();
        // The final metre uses the same finite hull clearance as docking, not a block-centre stop short of reach.
        if(from.distanceToSqr(target)>2.25)return false;
        int steps=Math.max(1,(int)Math.ceil(from.distanceTo(target)/.08));
        for(int step=0;step<=steps;step++)if(!recoveryWater(home,from.lerp(target,(double)step/steps)))return false;
        path.add(target);steeringHat=home.getUUID();seekingQuiet=false;
        return home.paddleAlong(path,.0025);
    }
    private boolean recoveryWater(HatBoatEntity boat,Vec3 point){
        var at=BlockPos.containing(point.x,point.y-.12,point.z);
        return HatBoatRoute.calmSurface(level(),at)&&level().getWorldBorder().isWithinBounds(at)
                &&allowsDestination(boat,point)&&level().noCollision(boat,boat.getBoundingBox().move(point.subtract(boat.position())));
    }

    private List<YukkuriEntity> loadedChildren(){
        cacheTick();if(cachedChildren!=null)return cachedChildren;
        var result=new HashMap<UUID,YukkuriEntity>();
        for(var id:FamilyData.get(level()).children(entity.getUUID())){
            var child=person(id);if(child!=null)result.put(id,child);
        }
        // Generated encounters can establish blood relation before the ledger's next periodic update.
        for(var child:level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(32),
                y->entity.family().caresFor(y)))result.put(child.getUUID(),child);
        return cachedChildren=result.values().stream().filter(y->y.aquaticLife().familyLife().dependentChild()
                &&entity.getUUID().equals(y.aquaticLife().familyLife().selectedParentId())).toList();
    }
    private UUID selectedParentId(){var parent=contactParent();return parent==null?parentId:parent.getUUID();}
    private List<YukkuriEntity> freeChildren(){
        return loadedChildren().stream().filter(y->ownHat(y)!=null||y.family().flying()
                ||!y.isPassenger()&&(y.isPanicking()||y.isInWaterOrBubble())
                ||y.getVehicle() instanceof HatBoatEntity hat&&!hat.protects(y)).toList();
    }
    private Vec3 familySlot(YukkuriEntity parent,HatBoatEntity home){
        var siblings=new ArrayList<>(parent.aquaticLife().familyLife().freeChildren());
        siblings.sort(Comparator.comparing(YukkuriEntity::getUUID));
        int index=0;for(int i=0;i<siblings.size();i++)if(siblings.get(i)==entity){index=i;break;}
        double spread=Math.min(80,Math.max(0,siblings.size()-1)*20);
        double angle=siblings.size()<=1?0:-spread+2*spread*index/(siblings.size()-1);
        Vec3 behind=Vec3.directionFromRotation(0,home.getYRot()).scale(-1).yRot((float)Math.toRadians(angle));
        return home.position().add(behind.scale(2.8+Math.min(2,index/5)*.5));
    }
    private boolean danger(YukkuriEntity actor,HatBoatEntity boat){
        return immediateDanger(actor,boat)||boat!=null&&level().isRainingAt(boat.blockPosition());
    }
    private boolean immediateDanger(YukkuriEntity actor,HatBoatEntity boat){
        return actor==null||actor.isPanicking()||actor.isOnFire()||actor.isInWaterOrBubble()
                ||boat!=null&&(boat.unsteadyMotion()||boat.flooding()>0||boat.towed()||!boat.isAfloat()||!boat.protects(actor)
                ||!HatBoatRoute.calmSurface(level(),water(boat)))
                ||actor!=null&&nearestThreat(actor)!=null;
    }
    /** Seeing a threat is exactly when a sound parent hat must remain available as refuge. */
    private boolean refuge(YukkuriEntity actor,HatBoatEntity boat){
        return actor!=null&&actor.isAlive()&&!actor.isOnFire()&&!actor.isInWaterOrBubble()&&boat!=null
                &&!boat.unsteadyMotion()&&boat.isAfloat()&&boat.protects(actor)&&boat.flooding()==0&&!boat.towed();
    }
    private LivingEntity nearestThreat(YukkuriEntity actor){
        cacheTick();if(cachedThreats.containsKey(actor.getUUID()))return cachedThreats.get(actor.getUUID());
        var threat=findThreat(actor);cachedThreats.put(actor.getUUID(),threat);return threat;
    }
    private LivingEntity findThreat(YukkuriEntity actor){
        var attacker=actor.getLastHurtByMob();
        if(attacker!=null&&attacker.isAlive()&&actor.distanceToSqr(attacker)<=64)return attacker;
        return level().getEntitiesOfClass(LivingEntity.class,actor.getBoundingBox().inflate(7,3,7),
                y->y!=actor&&y.isAlive()&&(y instanceof Enemy||y instanceof YukkuriEntity other&&other.getVariant().isPredator())
                        &&actor.hasLineOfSight(y)).stream().min(Comparator.comparingDouble(actor::distanceToSqr)).orElse(null);
    }
    private static double horizontal(Vec3 a,Vec3 b){return a.subtract(b).horizontalDistance();}
    private static BlockPos water(HatBoatEntity boat){return BlockPos.containing(boat.getX(),boat.getY()-.12,boat.getZ());}
    private void hold(HatBoatEntity boat,String state){boat.holdPosition();steeringHat=boat.getUUID();seekingQuiet=false;activity=state;}
    private boolean steer(HatBoatEntity boat,Vec3 target,boolean quiet){
        seekingQuiet=false;
        if(boat.unsteadyMotion()){hold(boat,"boat_wait_young");return false;}
        if(routeWait>0)return boat.paddling();routeWait=20;
        if(!boat.cargo().paddle()||boat.towed()){hold(boat,"boat_wait_young");return false;}
        Vec3 delta=target.subtract(boat.position()).multiply(1,0,1);
        Vec3 next=delta.lengthSqr()>64?boat.position().add(delta.normalize().scale(8)):target;
        var path=HatBoatRoute.find(boat,next);
        if(!routeSafe(path,quiet)){
            var candidates=new ArrayList<BlockPos>();var origin=water(boat);
            for(var pos:BlockPos.betweenClosed(origin.offset(-8,0,-8),origin.offset(8,0,8)))
                if(pos.distSqr(origin)<=64&&pos.distSqr(origin)>=1&&HatBoatRoute.calmSurface(level(),pos)
                        &&(!quiet||HatBoatRoute.quietSurface(level(),pos))
                        &&horizontal(HatBoatRoute.surface(level(),pos),target)<horizontal(boat.position(),target)-.3)candidates.add(pos.immutable());
            candidates.sort(Comparator.comparingDouble(p->HatBoatRoute.surface(level(),p).distanceToSqr(target)));
            path=List.of();
            for(var candidate:candidates.stream().limit(6).toList()){
                var alternative=HatBoatRoute.find(boat,HatBoatRoute.surface(level(),candidate));
                if(routeSafe(alternative,quiet)){path=alternative;break;}
            }
        }
        if(path.isEmpty()){hold(boat,"boat_wait_young");return false;}
        steeringHat=boat.getUUID();return boat.paddleAlong(path);
    }
    private boolean routeSafe(List<Vec3> path,boolean quiet){
        return !path.isEmpty()&&path.stream().allMatch(point->{var at=BlockPos.containing(point.x,point.y-.12,point.z);
            return HatBoatRoute.calmSurface(level(),at)&&(!quiet||HatBoatRoute.quietSurface(level(),at));});
    }
    /** Sail out of exposed water through a calm route; only its resting endpoint needs cover. */
    private boolean seekQuietWater(HatBoatEntity boat,HatBoatEntity home,List<YukkuriEntity> children){
        if(boat.unsteadyMotion())return false;
        if(routeWait>0&&seekingQuiet&&boat.getUUID().equals(steeringHat)&&boat.paddling()){
            activity="boat_follow_family";return true;
        }
        routeWait=20;
        if(!boat.cargo().paddle()||boat.towed())return false;
        var origin=water(boat);var candidates=new ArrayList<BlockPos>();
        for(var pos:BlockPos.betweenClosed(origin.offset(-6,0,-6),origin.offset(6,0,6)))
            if(pos.distSqr(origin)<=36&&HatBoatRoute.quietSurface(level(),pos))candidates.add(pos.immutable());
        candidates.sort(Comparator.comparingDouble(pos->pos.distSqr(origin)));
        for(var pos:candidates.stream().limit(12).toList()){
            var path=HatBoatRoute.find(boat,HatBoatRoute.surface(level(),pos));
            if(!routeSafe(path,false)||!HatBoatRoute.quietSurface(level(),BlockPos.containing(path.getLast().add(0,-.12,0))))continue;
            if(home!=null&&path.stream().anyMatch(point->horizontal(point,home.position())>6))continue;
            if(path.stream().anyMatch(point->children.stream().anyMatch(child->horizontal(point,child.position())>6)))continue;
            if(boat.paddleAlong(path)){
                steeringHat=boat.getUUID();seekingQuiet=true;activity="boat_follow_family";return true;
            }
        }
        return false;
    }
    private boolean yieldSteering(HatBoatEntity boat){
        if(boat.getUUID().equals(steeringHat))boat.stopPaddling();
        steeringHat=null;seekingQuiet=following=gathering=false;activity="idle";return false;
    }
    public void stop(){
        var boat=HatBoatEntity.owned(entity);if(boat!=null&&boat.getUUID().equals(steeringHat))boat.stopPaddling();
        steeringHat=null;waitingOn=null;recoveringHat=null;following=guarding=gathering=returnRequested=seekingQuiet=false;activity="idle";routeWait=0;
    }
    public CompoundTag save(){
        var tag=new CompoundTag();tag.putInt("Version",1);
        tag.put("Alarm",alarm.save());
        if(parentId!=null)tag.putUUID("Parent",parentId);if(parentHat!=null)tag.putUUID("ParentHat",parentHat);
        FamilyData.putPosition(tag,"LastParent",lastParent);tag.putInt("MissingParent",missingParent);
        var fleet=new ListTag();for(var id:knownFleet){var entry=new CompoundTag();entry.putUUID("Id",id);fleet.add(entry);}tag.put("KnownFleet",fleet);
        return tag;
    }
    public void load(CompoundTag tag){
        stop();parentId=tag.hasUUID("Parent")?tag.getUUID("Parent"):null;parentHat=tag.hasUUID("ParentHat")?tag.getUUID("ParentHat"):null;
        reactionTicks=0;distressedKin=null;
        cacheLevel=null;cacheTick=Long.MIN_VALUE;parentResolved=false;cachedParent=null;cachedChildren=null;cachedThreats.clear();
        lastParent=FamilyData.position(tag,"LastParent");missingParent=Math.clamp(tag.getInt("MissingParent"),0,2400);
        knownFleet.clear();missingYoung.clear();for(var raw:tag.getList("KnownFleet",Tag.TAG_COMPOUND)){
            var entry=(CompoundTag)raw;if(entry.hasUUID("Id"))knownFleet.add(entry.getUUID("Id"));
        }
        safeTicks=returnWait=0;returnRequested=false;
        alarm.load(tag.getCompound("Alarm"));
    }
}
