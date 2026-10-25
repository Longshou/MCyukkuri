package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.subspecies.boat.HullHelm.Purpose;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.event.WildEvents;

/** Small, interruptible childhood routines; food, care, family navigation and physiology remain their owners' work. */
public final class AquaticDailyLife {
    private enum Task { NONE, WATCH, ANSWER, OUT, RETURN, IMITATE }
    private final YukkuriEntity child;
    private Task task=Task.NONE;
    private UUID companion, routeBoat;
    private Vec3 origin, destination, lastBoatPosition, lastParentPosition, observedDirection;
    private int ticks, contact, cooldown, searchWait, callWait, replyWait;
    private double sailedDistance, observedDistance, sessionDistance;
    private long lastTick=Long.MIN_VALUE, lastEvidence=Long.MIN_VALUE;
    private String activity=Behaviors.IDLE;
    private boolean ownsRoute;

    public AquaticDailyLife(YukkuriEntity child){this.child=child;}
    private ServerLevel level(){return (ServerLevel)child.level();}
    public boolean active(){return task!=Task.NONE;}
    public boolean ownsMovement(){return ownsRoute;}
    public String activity(){return activity;}
    /** Real accumulated powered movement, not time spent aboard a drifting parent's hat. */
    public double sailedDistance(){return sailedDistance;}
    public double observedDistance(){return observedDistance;}
    public void clock(){
        if(child.level().isClientSide)return;
        if(cooldown>0)cooldown--;
        if(searchWait>0)searchWait--;
        if(callWait>0)callWait--;
        if(replyWait>0)replyWait--;
    }
    private YukkuriEntity person(UUID id){return id!=null&&level().getEntity(id) instanceof YukkuriEntity y?y:null;}
    private boolean available(YukkuriEntity y){
        return y!=null&&y.isAlive()&&!y.isNoAi()&&!y.isCommandPlaying()&&!y.operations().active()
                &&!y.isPanicking()&&!y.isOnFire()&&!y.isInWaterOrBubble()&&!y.family().flying()
                &&!y.family().laboring()&&!WildEvents.reserved(y);
    }
    private boolean supported(HatBoatEntity boat){
        return boat!=null&&boat.protects(child)&&boat.flooding()==0
                &&!boat.hasIncoming()&&!boat.transportingInHat(child)&&!boat.changingSeat(child);
    }
    private YukkuriEntity parent(){
        var mother=person(child.family().mother());var father=person(child.family().father());
        return List.of(mother==null?child:mother,father==null?child:father).stream()
                .filter(y->y!=child&&available(y)&&y.getVehicle() instanceof HatBoatEntity hat&&hat.protects(y)
                        &&hat.flooding()==0&&child.distanceToSqr(y)<=25&&child.hasLineOfSight(y)
                        &&child.life().canRecognize(y)&&y.life().canRecognize(child))
                .min(Comparator.comparingDouble(child::distanceToSqr)).orElse(null);
    }
    private HatBoatEntity parentHat(YukkuriEntity parent){
        return parent!=null&&parent.getVehicle() instanceof HatBoatEntity boat?boat:null;
    }
    private boolean pleasantWater(HatBoatEntity boat){
        return boat.isAfloat()&&!level().isNight()&&!level().isThundering()
                &&HatBoatRoute.quietSurface(level(),BlockPos.containing(boat.getX(),boat.getY()-.12,boat.getZ()));
    }
    private void face(YukkuriEntity target){
        child.getLookControl().setLookAt(target,25,25);
        SurfaceApproach.face(child,SurfaceApproach.yaw(child.position(),target.position()));
    }
    /** Invoke after feeding/toilet/care and family-follow have yielded. Call stop before another owner takes the boat route. */
    public boolean tick(HatBoatEntity boat){
        if(child.level().isClientSide)return false;
        long now=level().getGameTime();
        if(now==lastTick)return active();
        if(lastTick!=Long.MIN_VALUE&&now!=lastTick+1)stop();
        lastTick=now;
        if(child.family().adult()||!child.profile().aquatic()||!available(child)||!supported(boat)
                ||child.life().mealTicks()>0||child.life().bladder()>=70&&!child.life().constipated()
                ||child.life().dryness()>=35||child.life().wetness()>35||child.childhood().needsAssistedFirstToilet()){
            stop();return false;
        }
        boolean captain=child.getUUID().equals(boat.ownerYukkuri());
        var parent=parent();
        // A request is only a request: existing finite cargo/chewing/contact code supplies every meal.
        if(child.life().nutrition()<60&&(!child.life().isSleeping()||child.life().nutrition()<30)){
            stop();
            if(child.life().nutrition()<30)child.life().wakeInHat();
            if(parent!=null){face(parent);if(callWait==0){child.speak("hungry");callWait=100;}activity=Behaviors.BOAT_BEG;return true;}
            return false;
        }
        if(child.life().isSleeping()||child.life().energy()<=(child.family().needsMash()?65:35)){
            if(level().isRainingAt(child.blockPosition())){stop();return false;}
            stop();if(captain)boat.helm().drift(child, Purpose.ROUTINE);child.life().restInHat(boat);activity=Behaviors.BOAT_SLEEP;return true;
        }
        if(!pleasantWater(boat)||parent==null||parent.life().isSleeping()
                ||parent.aquaticLife().care().active()||parent.aquaticLife().firstVoyage().active()){
            stop();return false;
        }
        if(captain&&(!child.aquaticLife().firstVoyage().completed()||!child.aquaticLife().familyLife().safeForOuting())){
            stop();return false;
        }
        if(active())return run(boat,parent,now);
        activity=Behaviors.IDLE;
        if(searchWait>0||cooldown>0){
            // A young captain waits nearby between bouts, rather than inheriting the adult's long random voyage.
            if(captain){boat.helm().hold(child, Purpose.ROUTINE);activity=Behaviors.BOAT_DRIFT;return true;}return false;
        }
        searchWait=40;
        var peer=sibling(boat);
        boolean parentWorking=working(parent);
        if(peer!=null&&(child.dailyLife().affectionNeed()>=30||child.dailyLife().boredom()>=35)
                &&(!parentWorking||child.getRandom().nextInt(3)==0)){
            begin(Task.ANSWER,peer);return run(boat,parent,now);
        }
        if(parentWorking&&(observedDistance<1||child.getRandom().nextBoolean())){
            begin(Task.WATCH,parent);lastParentPosition=parentHat(parent).position();return run(boat,parent,now);
        }
        if(captain&&child.childhood().canLearn()&&child.life().nutrition()>=65&&child.life().energy()>=55
                &&boat.cargo().paddle()&&(child.dailyLife().boredom()>=25||parentWorking)){
            boolean imitate=parentWorking&&observedDirection!=null&&observedDistance>=.15;
            if(startTrip(boat,parent,imitate))return run(boat,parent,now);
        }
        if(captain){boat.helm().hold(child, Purpose.ROUTINE);activity=Behaviors.BOAT_DRIFT;return true;}
        if(child.dailyLife().affectionNeed()>=25){begin(Task.ANSWER,parent);return run(boat,parent,now);}
        return false;
    }
    private void begin(Task next,YukkuriEntity peer){
        task=next;companion=peer.getUUID();ticks=contact=0;lastEvidence=Long.MIN_VALUE;
    }
    private boolean working(YukkuriEntity parent){
        var home=parentHat(parent);
        return home!=null&&(home.paddling()||parent.aquaticLife().shoreForage().harvesting());
    }
    private boolean sibling(YukkuriEntity peer){
        return child.family().mother()!=null&&child.family().mother().equals(peer.family().mother())
                ||child.family().father()!=null&&child.family().father().equals(peer.family().father());
    }
    private YukkuriEntity sibling(HatBoatEntity boat){
        return level().getEntitiesOfClass(YukkuriEntity.class,boat.getBoundingBox().inflate(3.5,1.5,3.5),
                y->y!=child&&!y.family().adult()&&sibling(y)&&available(y)&&y.getVehicle() instanceof HatBoatEntity hat
                        &&hat.protects(y)&&hat.flooding()==0&&!hat.transportingInHat(y)
                        &&!y.life().isSleeping()&&y.life().nutrition()>=60&&y.life().energy()>=40&&y.life().mealTicks()==0
                        &&!y.aquaticLife().care().active()&&!y.aquaticLife().firstVoyage().active()
                        &&child.distanceToSqr(y)<=12.25&&child.hasLineOfSight(y)&&child.life().canRecognize(y)&&y.life().canRecognize(child))
                .stream().min(Comparator.comparingDouble(child::distanceToSqr)).orElse(null);
    }
    private boolean run(HatBoatEntity boat,YukkuriEntity parent,long now){
        if(++ticks>180){finish();return true;}
        if(task==Task.OUT||task==Task.RETURN||task==Task.IMITATE)return sail(boat,person(companion),now);
        var peer=person(companion);
        if(!available(peer)||peer.life().isSleeping()||peer.life().mealTicks()>0||peer.life().nutrition()<40
                ||child.distanceToSqr(peer)>12.25||!child.hasLineOfSight(peer)||!peer.hasLineOfSight(child)){
            finish();return false;
        }
        face(peer);
        if(task==Task.WATCH){
            activity=Behaviors.AQUATIC_WATCH_PARENT;
            var home=parentHat(peer);
            if(home==null||!working(peer)){finish();return true;}
            Vec3 displacement=lastParentPosition==null?Vec3.ZERO:home.position().subtract(lastParentPosition).multiply(1,0,1);
            double movement=displacement.horizontalDistance();
            lastParentPosition=home.position();
            if(home.paddling()&&movement>.001&&movement<.25){
                observedDirection=displacement.normalize();
                observedDistance=Math.min(64,observedDistance+movement);
                if(lastEvidence==now-1)contact++;else contact=1;
                lastEvidence=now;
            }else if(peer.aquaticLife().shoreForage().harvesting()){
                // Looking at real sustained mouth/plant work is observation, never a food or sailing reward.
                if(lastEvidence==now-1)contact++;else contact=1;
                lastEvidence=now;
            }else contact=0;
            if(ticks==1)child.showReaction("neutral","hello",30);
            if(contact>=30){child.dailyLife().entertained(3);finish();}
            return true;
        }
        activity=Behaviors.AQUATIC_ANSWER_FAMILY;
        if(peer.life().energy()<40||peer.isPanicking()||peer.getVehicle() instanceof HatBoatEntity hat&&hat.transportingInHat(peer)){
            finish();return false;
        }
        // Both calls happen in actual sight/hearing range. Never turn or stop the peer's boat.
        if(ticks==1){child.speak("hello");peer.speak("hello");}
        if(replyWait==0&&(ticks==21||ticks==41)){
            child.speak("song");peer.speak("song");replyWait=18;
            level().sendParticles(ParticleTypes.NOTE,child.getX(),child.getEyeY()+.1,child.getZ(),1,.05,.02,.05,0);
            child.dailyLife().entertained(3);child.dailyLife().comfort(2);
            peer.dailyLife().entertained(2);peer.dailyLife().comfort(1);
        }
        if(ticks>=60)finish();return true;
    }
    private boolean startTrip(HatBoatEntity boat,YukkuriEntity parent,boolean imitate){
        if(boat.paddling())return false;
        var home=parentHat(parent);if(home==null)return false;
        double reach=.85+Math.min(.75,sailedDistance/24);
        double base=imitate?Math.atan2(observedDirection.z,observedDirection.x):child.getRandom().nextDouble()*Math.PI*2;
        for(int side=0;side<6;side++){
            double angle=base+side*Math.PI/3;
            var point=boat.position().add(Math.cos(angle)*reach,0,Math.sin(angle)*reach);
            if(!clearLeg(boat,boat.position(),point,home))continue;
            origin=boat.position();destination=point;lastBoatPosition=boat.position();sessionDistance=0;
            routeBoat=boat.getUUID();ownsRoute=boat.helm().route(child, Purpose.ROUTINE, List.of(point),.01);
            if(!ownsRoute)return false;
            begin(imitate?Task.IMITATE:Task.OUT,parent);return true;
        }
        return false;
    }
    private boolean sail(HatBoatEntity boat,YukkuriEntity parent,long now){
        var home=parentHat(parent);
        if(home==null||!boat.getUUID().equals(routeBoat)||boat.distanceToSqr(home)>16
                ||!boat.cargo().paddle()||destination==null||!clearLeg(boat,boat.position(),destination,home)){
            finish();return false;
        }
        var here=boat.position();double moved=lastBoatPosition==null?0:here.subtract(lastBoatPosition).horizontalDistance();
        if(ownsRoute&&boat.paddling()&&moved>.001&&moved<.20){
            sailedDistance=Math.min(128,sailedDistance+moved);sessionDistance+=moved;
            child.life().spendEnergy((float)(moved*.3));
        }
        lastBoatPosition=here;
        activity=task==Task.IMITATE?Behaviors.AQUATIC_IMITATE_PADDLE:Behaviors.AQUATIC_PADDLE_PRACTICE;
        if(here.subtract(destination).horizontalDistanceSqr()<=.025){
            boat.helm().drift(child, Purpose.ROUTINE);
            if(task!=Task.RETURN&&origin!=null&&clearLeg(boat,here,origin,home)){
                task=Task.RETURN;destination=origin;ownsRoute=boat.helm().route(child, Purpose.ROUTINE, List.of(origin),.01);return true;
            }
            if(sessionDistance>=.6){child.dailyLife().entertained(Math.min(8,(int)(sessionDistance*4)));face(parent);}
            finish();return true;
        }
        if(!boat.paddling()){finish();return true;}
        return true;
    }
    /** Only a short fully sampled water segment, never an unchecked straight shortcut through a bank or sibling. */
    private boolean clearLeg(HatBoatEntity boat,Vec3 from,Vec3 to,HatBoatEntity home){
        double distance=from.subtract(to).horizontalDistance();
        if(distance>1.65||Math.abs(from.y-to.y)>.12)return false;
        int samples=Math.max(1,(int)Math.ceil(distance/.12));double r=boat.getBbWidth()*.48;
        for(int i=0;i<=samples;i++){
            var at=from.lerp(to,(double)i/samples);
            if(at.distanceToSqr(home.position())>3.8*3.8||!level().getWorldBorder().isWithinBounds(BlockPos.containing(at))
                    ||!level().noCollision(boat,boat.getBoundingBox().move(at.subtract(boat.position()))))return false;
            for(double dx:new double[]{-r,0,r})for(double dz:new double[]{-r,0,r}){
                var water=BlockPos.containing(at.x+dx,at.y-.12,at.z+dz);
                if(!HatBoatRoute.quietSurface(level(),water)||Math.abs(HatBoatRoute.surface(level(),water).y-at.y)>.12)return false;
            }
        }
        return true;
    }
    private void finish(){stop();cooldown=120+child.getRandom().nextInt(121);}
    /** The caller invokes this before issuing a care/follow/foraging route, so only our still-owned route is cancelled. */
    public void stop(){
        if(ownsRoute&&child.getVehicle() instanceof HatBoatEntity boat&&child.getUUID().equals(boat.ownerYukkuri())
                &&boat.getUUID().equals(routeBoat))boat.helm().drift(child, Purpose.ROUTINE);
        yieldToOtherActivity();
    }
    /** Use after a higher-priority system has already written its own route: retire our intention without touching that route. */
    public void yieldToOtherActivity(){
        ownsRoute=false;routeBoat=companion=null;task=Task.NONE;ticks=contact=0;
        origin=destination=lastBoatPosition=lastParentPosition=null;sessionDistance=0;lastEvidence=Long.MIN_VALUE;activity=Behaviors.IDLE;
    }
    public CompoundTag save(){
        var tag=new CompoundTag();tag.putInt("Cooldown",cooldown);tag.putInt("CallWait",callWait);
        tag.putDouble("SailedDistance",sailedDistance);tag.putDouble("ObservedDistance",observedDistance);
        if(observedDirection!=null){tag.putDouble("ObservedX",observedDirection.x);tag.putDouble("ObservedZ",observedDirection.z);}
        return tag;
    }
    public void load(CompoundTag tag){
        stop();lastTick=Long.MIN_VALUE;searchWait=40;replyWait=0;
        cooldown=Math.clamp(tag.getInt("Cooldown"),0,240);callWait=Math.clamp(tag.getInt("CallWait"),0,100);
        double sailed=tag.getDouble("SailedDistance"),observed=tag.getDouble("ObservedDistance");
        sailedDistance=Double.isFinite(sailed)?Math.clamp(sailed,0,128):0;
        observedDistance=Double.isFinite(observed)?Math.clamp(observed,0,64):0;
        double x=tag.getDouble("ObservedX"),z=tag.getDouble("ObservedZ");
        observedDirection=Double.isFinite(x)&&Double.isFinite(z)&&x*x+z*z>.001?new Vec3(x,0,z).normalize():null;
    }
}
