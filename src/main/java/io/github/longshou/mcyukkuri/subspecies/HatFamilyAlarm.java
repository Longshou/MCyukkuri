package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Direction;

/** An audible recall followed by physical gathering and escape, rather than a remote family teleport. */
public final class HatFamilyAlarm {
    private static final int RECALL_TICKS=240, EVENT_TICKS=420, SAME_SOURCE_GAP=1200;
    private final YukkuriEntity entity;
    private UUID source, caller, calledHat, rescueTarget;
    private Vec3 sourcePosition;
    private long began, until, phaseAt, nextCall, nextEvent, heardAt, answerAt, heardUntil, heardEvent, nextRoute;
    private int calmTicks;
    private boolean answered;
    private String phase=Behaviors.IDLE;
    private String pendingSpeech="";
    private long speechRetryAt,speechUntil;
    /** Only children that actually heard this event are waited for. Deadlines never renew on a repeated call. */
    private final Map<UUID,Long> expected=new HashMap<>();
    private BlockPos shoreMeeting;

    public HatFamilyAlarm(YukkuriEntity entity){this.entity=entity;}
    private ServerLevel level(){return (ServerLevel)entity.level();}
    private long now(){return level().getGameTime();}
    private boolean controllable(){return entity.isAlive()&&!entity.isNoAi()&&!entity.isCommandPlaying()&&!entity.operations().active();}
    private YukkuriEntity person(UUID id){return id!=null&&level().getEntity(id) instanceof YukkuriEntity y&&y.isAlive()?y:null;}
    private HatBoatEntity home(){return calledHat!=null&&level().getEntity(calledHat) instanceof HatBoatEntity h&&h.isAfloat()?h:null;}
    public boolean active(){return sourcePosition!=null&&now()<until;}
    public boolean responding(){return caller!=null&&now()<heardUntil;}
    public boolean answerPending(){return responding()&&!answered;}
    public String activity(){return phase;}
    public long phaseStart(){return phaseAt;}
    public UUID sourceId(){return source;}
    public int rescueTargetId(){var child=person(rescueTarget);return active()&&child!=null?child.getId():-1;}
    public boolean awaiting(YukkuriEntity child){return active()&&expected.getOrDefault(child.getUUID(),0L)>now()&&child.getVehicle()!=HatBoatEntity.owned(entity);}
    public boolean preventsOuting(){return active()||responding();}
    private List<YukkuriEntity> unreturnedChildren(List<YukkuriEntity> children,HatBoatEntity boat){
        return children.stream().filter(child->child!=entity&&child.isAlive()&&(boat==null||child.getVehicle()!=boat)).toList();
    }
    private void set(String next){
        if(!phase.equals(next)){
            String before=phase;phase=next;phaseAt=now();
            GameplayLog.limited(entity,"family-alarm","phase."+next,200,
                    "phase={} -> {} source={} caller={} calledHat={} rescueTarget={} expected={}",
                    before,next,source,caller,calledHat,rescueTarget,expected.size());
        }
    }
    private void face(Vec3 target){
        var d=target.subtract(entity.position());if(d.horizontalDistanceSqr()<1e-6)return;
        float yaw=(float)(Math.toDegrees(Math.atan2(d.z,d.x))-90);
        entity.setYRot(yaw);entity.yBodyRot=yaw;entity.setYHeadRot(yaw);
    }
    /** The source is the actual interacting player or observed attacker, never the family member being rescued. */
    public void start(HatBoatEntity boat,Entity danger,Vec3 observed){
        if(!controllable()||!boat.isAfloat()||entity.getVehicle()!=boat||!entity.getUUID().equals(boat.ownerYukkuri()))return;
        // A full boat still follows the existing emergency escape path, but only real loaded children away from it are called.
        if(!entity.aquaticLife().familyLife().hasDependents())return;
        boolean recall=entity.aquaticLife().familyLife().hasUnreturnedDependents(boat);
        if(active()){
            if(danger!=null&&danger.getUUID().equals(source))sourcePosition=danger.position();
            return;
        }
        // The same rocker shoving again within a minute gets a startle, not a second family alarm (alarms far rarer, user 2026-10-18).
        if(now()<nextEvent||danger!=null&&danger.getUUID().equals(source)&&now()<until+SAME_SOURCE_GAP)return;
        source=danger==null?null:danger.getUUID();sourcePosition=observed==null?boat.position():observed;
        began=now();until=began+EVENT_TICKS;nextEvent=until+100;nextCall=recall?began+8:Long.MAX_VALUE;nextRoute=began;calmTicks=0;expected.clear();
        entity.life().wakeInHat();boat.holdPosition();set(recall?Behaviors.BOAT_ALARM_CALL:Behaviors.BOAT_ALARM_WAIT);
        entity.showReaction("surprise","boat_rock_startle",24);
        entity.aquaticLife().firstVoyage().suspendMentoring();
        GameplayLog.event(entity,"family-alarm","started hat={} source={} sourcePosition={} recall={} event={} until={}",
                boat.getUUID(),source,sourcePosition,recall,began,until);
    }
    public void clock(List<YukkuriEntity> children){
        if(!controllable())return;
        if(active()){
            var danger=source==null?null:level().getEntity(source);
            if(danger!=null&&danger.isAlive()
                    &&entity.distanceToSqr(danger)<=256&&entity.hasLineOfSight(danger))sourcePosition=danger.position();
            var home=HatBoatEntity.owned(entity);
            var unreturned=unreturnedChildren(children,home);
            expected.entrySet().removeIf(e->{
                var child=person(e.getKey());
                String reason=e.getValue()<=now()?"deadline":child==null?"child_unavailable":home!=null&&child.getVehicle()==home?"aboard"
                        :child.independence().independent()?"independent":child.aquaticLife().firstVoyage().independent()?"independent_voyage":null;
                if(reason!=null)GameplayLog.event(entity,"family-alarm",
                        "child wait ended child={} reason={} event={} homeHat={}",e.getKey(),reason,began,home==null?null:home.getUUID());
                return reason!=null;
            });
            if(unreturned.isEmpty()){
                // The alarm may still steer away from a real threat, but no settled crew receives another recall.
                expected.clear();nextCall=Long.MAX_VALUE;if(phase.equals(Behaviors.BOAT_ALARM_CALL))set(Behaviors.BOAT_ALARM_WAIT);
            }else{
                if(nextCall==Long.MAX_VALUE)nextCall=now()+8;
                if(now()>=nextCall&&now()-began<RECALL_TICKS){
                    var callable=unreturned.stream().filter(this::audible).toList();
                    if(callable.isEmpty()){
                        if(phase.equals(Behaviors.BOAT_ALARM_CALL))set(Behaviors.BOAT_ALARM_WAIT);
                        nextCall=now()+20;
                    }else{
                        // An unheard/rejected speech attempt does not create a telepathic response or waiting obligation.
                        set(Behaviors.BOAT_ALARM_CALL);
                        boolean spoken=entity.speak("family_alarm_call");nextCall=now()+(spoken?80:10);
                        if(spoken)for(var child:callable){
                            var response=child.aquaticLife().familyLife().alarm();
                            if(response.hear(entity,home,began,began+RECALL_TICKS))expected.put(child.getUUID(),began+RECALL_TICKS);
                        }
                    }
                }
            }
        }else if(sourcePosition!=null){
            GameplayLog.event(entity,"family-alarm","ended source={} event={} elapsed={} reason={} remainingWaits={}",
                    source,began,now()-began,calmTicks>=80?"calm":"deadline",expected.size());
            sourcePosition=null;expected.clear();set(Behaviors.IDLE);
        }
        if(caller!=null){
            var parent=person(caller);var boat=home();
            String reason=!responding()?"response_expired":parent==null?"parent_unavailable":boat==null?"hat_unavailable":!boat.young(entity)?"child_berth_lost":null;
            if(reason!=null){clearResponse(reason);return;}
            if(entity.getVehicle()==boat&&answered){
                var parentAlarm=parent.aquaticLife().familyLife().alarm();
                if(parentAlarm.active())heardUntil=Math.max(heardUntil,parentAlarm.until);
                else if(now()-heardAt>24){clearResponse("aboard_alarm_ended");return;}
            }
            if(!answered&&now()>=answerAt){
                boolean spoken=entity.speak("family_alarm_answer");answerAt=now()+8;
                if(spoken||now()-heardAt>=60){
                    answered=true;set(Behaviors.BOAT_RETURN_CALLED);entity.showReaction("teary",null,36);
                    GameplayLog.event(entity,"family-alarm","response accepted caller={} hat={} event={} audibleAnswer={} waited={}",
                            caller,calledHat,heardEvent,spoken,now()-heardAt);
                }
            }
            if(entity.getVehicle()==boat&&answered&&phase.equals(Behaviors.BOAT_RETURN_CALLED))boat.requestInnerRefuge(entity);
        }
        if(!pendingSpeech.isEmpty()&&now()>=speechRetryAt){
            if(now()>speechUntil||entity.speak(pendingSpeech))pendingSpeech="";
            else speechRetryAt=now()+10;
        }
    }
    private boolean audible(YukkuriEntity child){
        double distance=entity.distanceToSqr(child);
        // A short-range voice passes around a nearby obstruction, but cannot summon through a distant wall.
        return distance<=144&&(distance<=16||entity.hasLineOfSight(child));
    }
    private boolean hear(YukkuriEntity parent,HatBoatEntity boat,long event,long expiry){
        if(!controllable()||boat==null||!boat.young(entity)||entity.independence().independent()
                ||entity.aquaticLife().firstVoyage().independent()||entity.getVehicle()==boat)return false;
        if(parent.getUUID().equals(caller)&&heardEvent==event)return true;
        // A dependent captain hands the family escape decision back to the parent it actually heard.
        sourcePosition=null;expected.clear();pendingSpeech="";
        caller=parent.getUUID();calledHat=boat.getUUID();heardEvent=event;heardAt=now();heardUntil=expiry;
        answerAt=heardAt+8+Math.floorMod(entity.getUUID().hashCode(),13);answered=false;shoreMeeting=null;nextRoute=now();
        entity.life().wakeInHat();set(Behaviors.BOAT_ANSWER_CALL);
        entity.showReaction("surprise",null,(int)(answerAt-now()));
        if(entity.getVehicle() instanceof HatBoatEntity own&&own!=boat)own.holdPosition();
        GameplayLog.event(entity,"family-alarm","call heard parent={} hat={} event={} expiry={}",caller,calledHat,heardEvent,heardUntil);
        return true;
    }
    private void clearResponse(String reason){
        GameplayLog.event(entity,"family-alarm","response cleared caller={} hat={} event={} reason={} answered={} vehicle={}",
                caller,calledHat,heardEvent,reason,answered,entity.getVehicle()==null?null:entity.getVehicle().getUUID());
        caller=calledHat=null;shoreMeeting=null;answered=false;if(!active())set(Behaviors.IDLE);
    }
    private void stageReaction(String expression,String speech,int ticks){
        entity.showReaction(expression,null,ticks);pendingSpeech=speech;speechRetryAt=now();speechUntil=now()+40;
    }
    public boolean shoreResponse(){return responding()&&controllable()&&!entity.isPassenger()&&!entity.family().flying()
            &&!entity.isInWaterOrBubble()&&!entity.isOnFire()&&!entity.life().hasUrgentNeed()&&entity.life().mealTicks()==0;}
    /** Crew turn with the real escape heading; their expressions settle only after the captain enters calm. */
    public boolean tickOnboard(HatBoatEntity boat){
        if(!responding()||!controllable()||!answered||home()!=boat||boat.unsteadyMotion())return false;
        var parent=person(caller);if(parent==null)return false;
        var parentAlarm=parent.aquaticLife().familyLife().alarm();
        if(parentAlarm.activity().equals(Behaviors.BOAT_CALM)){
            if(!phase.equals(Behaviors.BOAT_CALM)){set(Behaviors.BOAT_CALM);stageReaction("sad","family_alarm_calm",60);}
        }else{
            set(Behaviors.BOAT_REASSURE);
            if(parentAlarm.activity().equals(Behaviors.BOAT_FLEE))face(entity.position().add(Vec3.directionFromRotation(0,boat.getYRot())));
            else entity.getLookControl().setLookAt(parent,30,30);
        }
        return true;
    }
    /** Used before ordinary launching; a lesson already on the water returns by its existing physical state machine. */
    public boolean tickResponse(){
        if(!responding()||!controllable())return false;
        var boat=home();var parent=person(caller);if(boat==null||parent==null)return false;
        if(entity.getVehicle()==boat){
            if(answered)boat.requestInnerRefuge(entity);
            entity.getLookControl().setLookAt(parent,30,30);return false;
        }
        if(!answered){entity.getNavigation().stop();entity.getLookControl().setLookAt(parent,35,35);return true;}
        if(entity.family().flying()||entity.family().braidRescueActive())return true;
        if(entity.getVehicle() instanceof HatBoatEntity own&&entity.getUUID().equals(own.ownerYukkuri())){
            entity.aquaticLife().firstVoyage().recallFromParent(boat);return true;
        }
        if(!shoreResponse())return false;
        if(boat.unsteadyMotion()){entity.getNavigation().stop();return true;}
        // Find a dry reachable bank beside actual calm water. Walking never targets a point over open water.
        if(shoreMeeting==null||!entity.life().dryStand(shoreMeeting)||boat.distanceToSqr(Vec3.atBottomCenterOf(shoreMeeting))>100){
            if(now()<nextRoute)return true;nextRoute=now()+20;
            var options=new ArrayList<BlockPos>();
            for(var p:BlockPos.betweenClosed(entity.blockPosition().offset(-8,-2,-8),entity.blockPosition().offset(8,2,8))){
                if(!level().hasChunkAt(p)||!entity.life().dryStand(p)||boat.distanceToSqr(Vec3.atBottomCenterOf(p))>100)continue;
                boolean water=false;for(var d:Direction.Plane.HORIZONTAL)
                    if(HatBoatRoute.calmSurface(level(),p.relative(d).below())){water=true;break;}
                if(water)options.add(p.immutable());
            }
            options.sort(Comparator.comparingDouble(p->boat.distanceToSqr(Vec3.atBottomCenterOf(p))+.2*entity.distanceToSqr(Vec3.atBottomCenterOf(p))));
            shoreMeeting=options.stream().limit(12).filter(p->entity.life().reachable(p,false)).findFirst().orElse(null);
        }
        if(shoreMeeting==null)return true;
        if(entity.distanceToSqr(Vec3.atBottomCenterOf(shoreMeeting))>.25){
            if(now()>=nextRoute){nextRoute=now()+20;entity.life().navigate(shoreMeeting,1.05,false);}return true;
        }
        entity.getNavigation().stop();entity.getLookControl().setLookAt(parent,35,35);
        // No takeoff relocation: trained children use embark's actual position; babies wait for the braid.
        if(entity.childhood().canJump())entity.family().boardFromShore(boat,true);
        else entity.family().rescueIntoHat(boat);
        return true;
    }
    /** Parent gathering outranks escape. A finite audible recall can expire without declaring a missing child dead. */
    public boolean tickOwner(HatBoatEntity boat,List<YukkuriEntity> children){
        if(!active()||!controllable()||entity.getVehicle()!=boat)return false;
        entity.life().wakeInHat();
        if(boat.unsteadyMotion()){boat.holdPosition();return true;}
        var returning=unreturnedChildren(children,boat).stream().filter(y->awaiting(y)||y.family().flying()||y.family().braidRescueActive()
                ||!y.isPassenger()&&y.isInWaterOrBubble()&&entity.distanceToSqr(y)<=144)
                .sorted(Comparator.<YukkuriEntity>comparingInt(y->y.family().braidRescueActive()?0:y.isInWaterOrBubble()&&!y.isPassenger()?1:2)
                        .thenComparingDouble(entity::distanceToSqr)).toList();
        boolean swimmer=returning.stream().anyMatch(y->!y.isPassenger()&&y.isInWaterOrBubble());
        if(now()-began<24&&!swimmer){boat.holdPosition();face(sourcePosition);return true;}
        if(!returning.isEmpty()){
            var child=returning.getFirst();boolean changedTarget=!child.getUUID().equals(rescueTarget);rescueTarget=child.getUUID();
            if(changedTarget)GameplayLog.limited(entity,"family-alarm","rescue.target",200,
                    "gather target selected child={} hat={} water={} event={}",rescueTarget,boat.getUUID(),child.isInWaterOrBubble(),began);
            if(!child.isInWaterOrBubble())set(Behaviors.BOAT_ALARM_WAIT);else if(!boat.paddling())set(Behaviors.BOAT_RESCUE_YOUNG);
            entity.getLookControl().setLookAt(child,35,35);
            if(child.family().flying()||child.family().braidRescueActive()){boat.holdPosition();return true;}
            if(!child.isPassenger()&&child.isInWaterOrBubble()&&entity.distanceToSqr(child)<=9&&entity.hasLineOfSight(child))
                boat.prepareCargoSpace(child,true);
            if(!child.isPassenger()&&child.family().rescueIntoHat(boat)){boat.holdPosition();return true;}
            if(child.aquaticLife().firstVoyage().returningToParent()&&entity.distanceToSqr(child)<=64){boat.holdPosition();return true;}
            // Approach a swimmer or the child's actual shore rendezvous through navigable water.
            var braidApproach=!child.isPassenger()?child.family().braidRescueApproach(boat):null;
            if(braidApproach!=null){approachExact(boat,braidApproach);rescueApproachPose(boat,child);return true;}
            Vec3 target=child.aquaticLife().familyLife().alarm().meetingPoint();boolean shore=target!=null;
            if(target==null)target=child.position();
            if(entity.distanceToSqr(child)>2.25)approach(boat,target,shore);else boat.holdPosition();
            if(child.isInWaterOrBubble())rescueApproachPose(boat,child);
            return true;
        }
        rescueTarget=null;
        double distance=boat.position().subtract(sourcePosition).horizontalDistance();
        if(source==null||distance>=7){
            if(!entity.aquaticLife().familyLife().alarmSafe(boat)){
                calmTicks=0;set(Behaviors.BOAT_ALARM_WAIT);boat.holdPosition();return true;
            }
            if(!phase.equals(Behaviors.BOAT_CALM)){set(Behaviors.BOAT_CALM);stageReaction("sad","family_alarm_calm",80);}
            boat.holdPosition();if(++calmTicks>=80)until=now();return true;
        }
        calmTicks=0;
        if(now()<nextRoute)return true;nextRoute=now()+20;
        if(!boat.cargo().paddle()||boat.towed()||entity.life().energy()<10){boat.holdPosition();set(Behaviors.BOAT_ALARM_WAIT);return true;}
        var origin=BlockPos.containing(boat.position().add(0,-.12,0));var candidates=new ArrayList<BlockPos>();
        for(var p:BlockPos.betweenClosed(origin.offset(-6,0,-6),origin.offset(6,0,6)))
            if(p.distSqr(origin)>=4&&p.distSqr(origin)<=36&&HatBoatRoute.calmSurface(level(),p)
                    &&HatBoatRoute.surface(level(),p).subtract(sourcePosition).horizontalDistance()>distance+1)candidates.add(p.immutable());
        candidates.sort(Comparator.comparingDouble(p->-HatBoatRoute.surface(level(),p).distanceToSqr(sourcePosition)));
        for(var p:candidates.stream().limit(10).toList()){
            var path=HatBoatRoute.find(boat,HatBoatRoute.surface(level(),p),point->point.subtract(sourcePosition).horizontalDistance()>=distance-.15
                    &&entity.aquaticLife().familyLife().allowsDestination(boat,point));
            if(!path.isEmpty()&&boat.paddleAlong(path)){
                if(!phase.equals(Behaviors.BOAT_FLEE)){set(Behaviors.BOAT_FLEE);stageReaction("teary","family_alarm_flee",60);}return true;
            }
        }
        boat.holdPosition();set(Behaviors.BOAT_ALARM_WAIT);return true;
    }
    private Vec3 meetingPoint(){return shoreMeeting==null?null:Vec3.atBottomCenterOf(shoreMeeting);}
    private void approach(HatBoatEntity boat,Vec3 target,boolean shore){
        if(now()<nextRoute)return;nextRoute=now()+20;
        if(!boat.cargo().paddle()||boat.towed()||entity.life().energy()<10){boat.holdPosition();return;}
        var origin=BlockPos.containing(target.add(0,-.12,0));var options=new ArrayList<BlockPos>();
        for(var p:BlockPos.betweenClosed(origin.offset(-2,-1,-2),origin.offset(2,1,2)))
            if(HatBoatRoute.calmSurface(level(),p))options.add(p.immutable());
        options.sort(Comparator.comparingDouble(p->HatBoatRoute.surface(level(),p).distanceToSqr(target)));
        for(var p:options.stream().limit(8).toList()){
            Vec3 berth=HatBoatRoute.surface(level(),p);
            if(shore){
                var toward=target.subtract(berth).multiply(1,0,1).normalize();
                berth=berth.add(toward.scale(Math.min(0,.5-boat.getBbWidth()*.5-.03)));
            }
            if(!level().noCollision(boat,boat.getBoundingBox().move(berth.subtract(boat.position()))))continue;
            var path=new ArrayList<>(HatBoatRoute.find(boat,berth,point->entity.aquaticLife().familyLife().allowsDestination(boat,point)));
            Vec3 from=path.isEmpty()?boat.position():path.getLast();
            if(finalApproachClear(boat,from,berth)){path.add(berth);if(boat.paddleAlong(path,.0025))return;}
            else if(!path.isEmpty()&&boat.paddleAlong(path))return;
        }
        boat.holdPosition();
    }
    private boolean finalApproachClear(HatBoatEntity boat,Vec3 from,Vec3 to){
        var start=BlockPos.containing(from.add(0,-.12,0));var end=BlockPos.containing(to.add(0,-.12,0));
        if(start.distManhattan(end)>1||from.distanceToSqr(to)>2.25)return false;
        int samples=Math.max(1,(int)Math.ceil(from.distanceTo(to)/.1));
        for(int i=1;i<=samples;i++){
            var at=from.lerp(to,(double)i/samples);var cell=BlockPos.containing(at.add(0,-.12,0));
            if(!HatBoatRoute.calmSurface(level(),cell)||!level().getWorldBorder().isWithinBounds(cell)
                    ||!entity.aquaticLife().familyLife().allowsDestination(boat,at)
                    ||!level().noCollision(boat,boat.getBoundingBox().move(at.subtract(boat.position()))))return false;
        }
        return true;
    }
    private void approachExact(HatBoatEntity boat,Vec3 berth){
        // The last stretch is a bounded kinematic drive (it follows a swimmer's drift every tick); paddle routes only bring the hull near.
        if(boat.isAfloat()&&!boat.unsteadyMotion()&&boat.position().subtract(berth).horizontalDistanceSqr()<=6.25){
            boat.driveToward(berth,boat.getYRot(),.06,3f);return;
        }
        if(now()<nextRoute)return;nextRoute=now()+20;
        if(!boat.cargo().paddle()||boat.towed()||entity.life().energy()<10){boat.holdPosition();return;}
        if(boat.position().subtract(berth).horizontalDistanceSqr()<=.0009){boat.holdPosition();return;}
        var path=new ArrayList<>(HatBoatRoute.find(boat,berth,point->entity.aquaticLife().familyLife().allowsDestination(boat,point)));
        Vec3 from=path.isEmpty()?boat.position():path.getLast();
        if(finalApproachClear(boat,from,berth)){path.add(berth);boat.paddleAlong(path,.0009);}
        else if(!path.isEmpty())boat.paddleAlong(path);
        else boat.holdPosition();
    }
    public boolean approachRescue(HatBoatEntity boat,YukkuriEntity child){
        if(!child.isPassenger()&&child.isInWaterOrBubble()&&entity.distanceToSqr(child)<=9&&entity.hasLineOfSight(child))
            boat.prepareCargoSpace(child,true);
        var berth=child.family().braidRescueApproach(boat);if(berth==null)return false;
        approachExact(boat,berth);return true;
    }
    private void rescueApproachPose(HatBoatEntity boat,YukkuriEntity child){
        if(!boat.paddling()){set(Behaviors.BOAT_RESCUE_YOUNG);return;}
        if(!phase.equals(Behaviors.BOAT_RESCUE_APPROACH))stageReaction("teary","braid_rescue_offer",40);
        set(Behaviors.BOAT_RESCUE_APPROACH);entity.getLookControl().setLookAt(child,35,35);
    }
    public CompoundTag save(){
        var t=new CompoundTag();if(source!=null)t.putUUID("Source",source);
        if(active()){t.putDouble("X",sourcePosition.x);t.putDouble("Y",sourcePosition.y);t.putDouble("Z",sourcePosition.z);t.putLong("Began",began);t.putLong("Until",until);}
        t.putLong("NextEvent",nextEvent);t.putString("Phase",phase);t.putLong("PhaseAt",phaseAt);t.putInt("Calm",calmTicks);
        if(caller!=null){t.putUUID("Caller",caller);if(calledHat!=null)t.putUUID("Hat",calledHat);t.putLong("Heard",heardAt);t.putLong("Answer",
                answerAt);t.putLong("HeardUntil",heardUntil);t.putLong("Event",heardEvent);t.putBoolean("Answered",answered);}
        var list=new ListTag();for(var e:expected.entrySet()){var c=new CompoundTag();c.putUUID("Id",e.getKey());c.putLong("Until",e.getValue());list.add(c);}t.put("Expected",list);return t;
    }
    public void load(CompoundTag t){
        source=t.hasUUID("Source")?t.getUUID("Source"):null;sourcePosition=t.contains("Until")?new Vec3(t.getDouble("X"),t.getDouble("Y"),t.getDouble("Z")):null;
        began=t.getLong("Began");until=t.getLong("Until");nextEvent=t.getLong("NextEvent");phase=t.contains("Phase")?t.getString("Phase"):Behaviors.IDLE;
                phaseAt=t.getLong("PhaseAt");calmTicks=Math.clamp(t.getInt("Calm"),0,80);
        caller=t.hasUUID("Caller")?t.getUUID("Caller"):null;calledHat=t.hasUUID("Hat")?t.getUUID("Hat"):null;
        heardAt=t.getLong("Heard");answerAt=t.getLong("Answer");heardUntil=t.getLong("HeardUntil");heardEvent=t.getLong("Event");answered=t.getBoolean("Answered");
        expected.clear();for(var raw:t.getList("Expected",Tag.TAG_COMPOUND)){var c=(CompoundTag)raw;if(c.hasUUID("Id"))expected.put(c.getUUID("Id"),c.getLong("Until"));}
        shoreMeeting=null;rescueTarget=null;nextCall=now()+20;nextRoute=now();
    }
}
