package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.subspecies.boat.HullHelm.Purpose;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.crew.WaterIncidents;
import io.github.longshou.mcyukkuri.subspecies.crew.CrewTransfers;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

/** Real immersion, finite escape efforts and the breath after reaching safety; never damage immunity. */
public final class WaterDistress {
    public enum Phase { IDLE, IMPACT, STRUGGLE, EXHAUSTED, RECOVER }
    private static final int RESCUE_GUIDE_TICKS=20;
    private final YukkuriEntity entity;
    private Phase phase=Phase.IDLE;
    private int phaseTicks, immersionTicks, fatigue, burstTicks, restTicks, searchWait, callWait, pendingPlunge, boardWait, liftWait, drySupportTicks;
    private UUID sourceBoat, pusher, target, rescueHat, waterRescueParent;
    private BlockPos shore;
    private Vec3 destination;
    private long lastTick=Long.MIN_VALUE;
    private ServerLevel lastLevel;
    private long threatTick=Long.MIN_VALUE;
    private ServerLevel threatLevel;
    private long waterRescueHoldTick=Long.MIN_VALUE;
    /** A selected non-parent refuge has no braid controller to poll. Its live hat permit is the authority. */
    private boolean temporaryRefugeGuide;
    private boolean cachedThreat;
    public WaterDistress(YukkuriEntity entity){this.entity=entity;}
    private ServerLevel level(){return (ServerLevel)entity.level();}
    public Phase phase(){return phase;}
    public int phaseTicks(){return phaseTicks;}
    public int immersionTicks(){return immersionTicks;}
    public boolean active(){return phase!=Phase.IDLE;}
    public boolean effort(){return entity.isInWaterOrBubble()&&burstTicks>0&&(!entity.family().flying()||entity.family().crew().climbing())&&!entity.family().crew().holdsBody()
            &&!entity.family().crew().gripping();}
    public UUID targetId(){return target;}
    public UUID sourceBoatId(){return sourceBoat;}
    public UUID pusherId(){return pusher;}
    /** Server-side status readout; no target search or rescue state changes are performed here. */
    public String statusDetail(){
        return "phase="+phase+" phaseTicks="+phaseTicks+" immersion="+immersionTicks+" fatigue="+fatigue
                +" burst="+burstTicks+" rest="+restTicks+" target="+(target==null?"none":target)
                +" source="+(sourceBoat==null?"none":sourceBoat)+" guided="+(rescueHat!=null)
                +" temporaryRefuge="+temporaryRefugeGuide+" held="+heldByWaterRescue()+" "+WaterIncidents.describe(entity);
    }
    /** The body was thrown out of a hull and is still in the air: it shows the thrown pose, not an ordinary slip. */
    public boolean thrown(){
        if(!airbornePlunge())return false;
        var incident=WaterIncidents.of(entity);
        return incident!=null&&incident.cause().thrown();
    }
    /** The swimmer's call for help (and its kin hear it): also what is left of a rescue whose tries are spent. */
    public void callForHelp(){
        if(entity.level().isClientSide||!entity.isAlive())return;
        if(callWait==0){entity.speak("water_help");callWait=phase==Phase.EXHAUSTED?90:55;}
        alertKin();
    }
    public boolean airbornePlunge(){return pendingPlunge>0&&!entity.isInWaterOrBubble()&&!entity.onGround()
            &&!entity.shelteredInHat()&&!entity.family().flying()&&!entity.family().crew().holdsBody();}
    public int airbornePlungeTicks(){return Math.clamp(12-pendingPlunge,0,12);}
    public boolean canBeLifted(){return liftWait==0;}
    /** A nearby parent has selected this stable hat, before the braid actually reaches the child. */
    public boolean receivingWaterRescue(){return rescueHat!=null;}
    /** A current rendezvous belongs to one rescuer until its controller stops renewing it. */
    public boolean availableForWaterRescue(YukkuriEntity rescuer){
        clearInactiveWaterRescue();
        return waterRescueParent==null||waterRescueParent.equals(rescuer.getUUID());
    }
    public boolean guideToWaterRescue(YukkuriEntity parent,HatBoatEntity hat){
        if(parent!=null&&hat!=null&&parent.isAlive()&&hat.isAlive()&&hat.isAfloat()&&!hat.unsteadyMotion()
                &&hat.young(entity)&&acceptGuide(parent,hat)){
            boolean changed = !parent.getUUID().equals(waterRescueParent) || !hat.getUUID().equals(rescueHat);
            temporaryRefugeGuide=false;waterRescueParent=parent.getUUID();rescueHat=target=hat.getUUID();shore=null;destination=hat.position();searchWait=20;
            waterRescueHoldTick=level().getGameTime();
            if (changed) GameplayLog.limited(entity, "water", "rescue.guide", 200,
                    "rescue guidance assigned parent={} hat={} phase={} immersion={}", waterRescueParent, rescueHat, phase, immersionTicks);
            return true;
        }
        return false;
    }
    /**
     * A selected adult may provide a finite physical refuge without becoming a parent.
     * The temporary boarding permit remains the single source of authority and is checked
     * every tick; this method only points the swimmer at that already-authorized hull.
     */
    public boolean guideToTemporaryRefuge(YukkuriEntity caregiver,HatBoatEntity hat){
        if(caregiver==null||hat==null||!caregiver.isAlive()||!hat.isAlive()||!hat.isAfloat()
                ||hat.unsteadyMotion()||hat.flooding()>0||!caregiver.getUUID().equals(hat.ownerYukkuri())
                ||!refugeCaptain(caregiver,hat)||!hat.admits(entity)||!acceptGuide(caregiver,hat))return false;
        boolean changed=!temporaryRefugeGuide||!caregiver.getUUID().equals(waterRescueParent)||!hat.getUUID().equals(rescueHat);
        temporaryRefugeGuide=true;waterRescueParent=caregiver.getUUID();rescueHat=target=hat.getUUID();shore=null;destination=hat.position();searchWait=20;
        waterRescueHoldTick=level().getGameTime();
        if(changed)GameplayLog.event(entity,"water","temporary refuge guidance assigned caregiver={} hat={} phase={} immersion={}",
                waterRescueParent,rescueHat,phase,immersionTicks);
        return true;
    }
    private boolean refugeCaptain(YukkuriEntity caregiver,HatBoatEntity hat){
        return caregiver.getVehicle()==hat&&hat.protects(caregiver)
                ||caregiver.isInWaterOrBubble()&&hat.ownsPhysicalHat(caregiver);
    }
    private boolean acceptGuide(YukkuriEntity rescuer,HatBoatEntity hat){
        clearInactiveWaterRescue();
        if(!WaterIncidents.guiding(rescuer,entity))return false;
        if(rescueHat!=null&&(!hat.getUUID().equals(rescueHat)||!rescuer.getUUID().equals(waterRescueParent)))return false;
        if(entity.family().crew().active())return false;
        if(entity.aquaticLife().selfRescue().active())entity.aquaticLife().selfRescue().stop();
        return true;
    }
    /** The child is in a lifting braid right now: its own buoyancy and escape work stand aside. */
    public boolean heldByWaterRescue(){
        return !entity.level().isClientSide&&waterRescueParent!=null&&entity.family().crew().holdsBody();
    }
    /** The finite parent-water rescue ended without a board, so resume ordinary own-hat or shore choices next tick. */
    public void clearRescueHat(HatBoatEntity hat){
        if(rescueHat==null||hat!=null&&!rescueHat.equals(hat.getUUID()))return;
        clearWaterRescue();
    }
    private void clearWaterRescue(){
        if (rescueHat != null) GameplayLog.limited(entity, "water", "rescue.clear", 200,
                "rescue guidance cleared parent={} hat={} phase={} immersion={} vehicle={}",
                waterRescueParent, rescueHat, phase, immersionTicks, entity.getVehicle() == null ? null : entity.getVehicle().getUUID());
        var released=rescueHat;rescueHat=waterRescueParent=null;temporaryRefugeGuide=false;waterRescueHoldTick=Long.MIN_VALUE;
        if(released!=null&&target!=null&&target.equals(released)){target=null;destination=null;shore=null;searchWait=0;}
    }
    /** Parent controllers are transient: a vanished controller may never leave this child reserved or marked receiving. */
    private void clearInactiveWaterRescue(){
        if(waterRescueParent==null)return;
        if(!(level().getEntity(waterRescueParent) instanceof YukkuriEntity parent)||!parent.isAlive()){
            clearWaterRescue();return;
        }
        var ticket=entity.family().crew().ticket();
        boolean transferring=ticket!=null&&!ticket.finished()&&entity.getUUID().equals(ticket.bodyId())
                &&parent.getUUID().equals(ticket.carrierId());
        if(!transferring&&(level().getGameTime()-waterRescueHoldTick>RESCUE_GUIDE_TICKS||!WaterIncidents.guiding(parent,entity))){
            clearWaterRescue();return;
        }
        if(temporaryRefugeGuide){
            var hat=rescueHat!=null&&level().getEntity(rescueHat) instanceof HatBoatEntity candidate?candidate:null;
            if(hat==null||!hat.isAlive()||!hat.isAfloat()||hat.unsteadyMotion()||hat.flooding()>0
                    ||!parent.getUUID().equals(hat.ownerYukkuri())||!refugeCaptain(parent,hat)||!hat.admits(entity))
                clearWaterRescue();
            return;
        }
        if(!WaterIncidents.guiding(parent,entity))clearWaterRescue();
    }
    /** The parent's body nudge and rim slide take over the child's position; its bursts pause and it is not lifted again for 60 ticks. */
    public void lifted(){liftWait=60;burstTicks=0;}
    public Vec3 direction(){return destination==null?Vec3.ZERO:destination.subtract(entity.position()).multiply(1,0,1).normalize();}
    public String activity(){return switch(phase){
        case IDLE->Behaviors.IDLE;case IMPACT->Behaviors.WATER_IMPACT;case STRUGGLE->Behaviors.WATER_STRUGGLE;
        case EXHAUSTED->Behaviors.WATER_EXHAUSTED;case RECOVER->Behaviors.WATER_RECOVER;
    };}
    private void set(Phase next){
        if(phase!=next){
            Phase before = phase; int elapsed = phaseTicks;
            phase=next;phaseTicks=0;
            GameplayLog.limited(entity, "water", "phase." + next, 200,
                    "phase={} -> {} previousTicks={} immersion={} fatigue={} energy={} sourceBoat={} pusher={} target={} shore={}",
                    before, next, elapsed, immersionTicks, fatigue, entity.life().energy(), sourceBoat, pusher, target, shore);
        }
    }
    /** Called where a real capsize or shove releases a passenger; being notified is not yet immersion. */
    public void plunge(UUID boat,UUID pushedBy,WaterIncidents.Cause cause){
        if(entity.level().isClientSide)return;
        sourceBoat=boat;pusher=pushedBy;pendingPlunge=12;
        WaterIncidents.open(entity,boat,pushedBy,cause);
        GameplayLog.limited(entity, "water", "plunge", 200,
                "passenger released sourceBoat={} pusher={} cause={} immersed={} phase={}", boat, pushedBy, cause, entity.isInWaterOrBubble(), phase);
        if(entity.isInWaterOrBubble()&&(phase==Phase.IDLE||phase==Phase.RECOVER))enterWater();
    }
    private void enterWater(){
        immersionTicks=0;burstTicks=restTicks=searchWait=0;shore=null;
        if(rescueHat==null)waterRescueHoldTick=Long.MIN_VALUE;
        // A current parent controller may have selected this child just before its first water tick.  Keep only that
        // live rendezvous target; stale controller IDs are removed at the top of every ordinary tick.
        if(rescueHat==null){target=null;destination=null;}else target=rescueHat;
        pendingPlunge=0;set(Phase.IMPACT);
        if(!entity.isNoAi()){entity.life().wakeInHat();entity.getNavigation().stop();alertKin();}
    }
    /** Entity-owned server clock, including disabled AI. YukkuriLife alone applies immersion damage. */
    public void tick(){
        if(entity.level().isClientSide||!entity.isAlive())return;
        var current=level();long now=current.getGameTime();
        if(lastLevel==current&&lastTick==now)return;lastLevel=current;lastTick=now;
        WaterIncidents.tick(entity);
        clearInactiveWaterRescue();
        if(searchWait>0)searchWait--;if(callWait>0)callWait--;if(pendingPlunge>0)pendingPlunge--;
        if(boardWait>0)boardWait--;if(liftWait>0)liftWait--;
        phaseTicks++;
        if(entity.isInWaterOrBubble()){
            drySupportTicks=0;
            if(!entity.family().crew().holdsBody()&&!entity.family().crew().gripping())surfaceBuoyancy();
            if(phase==Phase.IDLE||phase==Phase.RECOVER)enterWater();
            immersionTicks=Math.min(24000,immersionTicks+1);fatigue=Math.min(24000,fatigue+1);
            if(phase==Phase.IMPACT&&phaseTicks>=12)set(Phase.STRUGGLE);
            if(phase!=Phase.IMPACT&&(fatigue>=160||entity.life().energy()<20))set(Phase.EXHAUSTED);
            if(!entity.family().crew().holdsBody()&&!entity.family().crew().gripping()&&!entity.isNoAi()&&!entity.isCommandPlaying()&&!entity.operations().active()){
                if(burstTicks>0)burstTicks--;else if(restTicks>0)restTicks--;
                if(callWait==0&&phase!=Phase.IMPACT){entity.speak("water_help");callWait=phase==Phase.EXHAUSTED?90:55;alertKin();}
            }else burstTicks=0;
            return;
        }
        burstTicks=0;
        // An upward flight or airborne tumble is not a completed rescue.
        if(phase==Phase.IMPACT||phase==Phase.STRUGGLE||phase==Phase.EXHAUSTED){
            if(entity.family().crew().busy()||entity.family().crew().climbing()){drySupportTicks=0;return;}
            if(entity.family().flying()||!entity.onGround()&&!entity.shelteredInHat()){drySupportTicks=0;return;}
            if(++drySupportTicks<3)return;
            set(Phase.RECOVER);shore=null;target=null;destination=null;
            if(!entity.isNoAi())entity.getNavigation().stop();
        }
        if(phase==Phase.RECOVER&&realThreat()){set(Phase.IDLE);return;}
        if(fatigue>0&&now%4==0)fatigue--;
        if(phase==Phase.RECOVER&&phaseTicks>=56){
            set(Phase.IDLE);immersionTicks=0;sourceBoat=pusher=null;
        }
    }
    /** A short recovery may interrupt leisure, but never an urgent need, threat escape or player command. */
    public boolean restingBreath(){
        return phase==Phase.RECOVER&&phaseTicks<32&&!entity.isNoAi()&&!entity.isCommandPlaying()
                &&!entity.operations().active()&&!realThreat()
                &&!entity.life().hasUrgentNeed()&&!entity.family().flying();
    }
    /** Called by the existing highest-priority escape goal, replacing its perpetual jump command. */
    public void escapeTick(){
        if(!entity.level().isClientSide&&!entity.isInWaterOrBubble()&&restingBreath()){
            entity.getNavigation().stop();
            if(entity.getVehicle() instanceof HatBoatEntity hat&&entity.getUUID().equals(hat.ownerYukkuri()))hat.helm().drift(entity, Purpose.SAFETY);
            return;
        }
        if(entity.level().isClientSide||!entity.isInWaterOrBubble()||entity.isNoAi()
                ||entity.isCommandPlaying()||entity.operations().active()||entity.family().flying()||entity.family().crew().holdsBody())return;
        tick();
        if(phase==Phase.IMPACT){entity.getNavigation().stop();return;}
        // While a parent offers the dipped rim the child waits in the water (it still floats, bites and takes real water damage);
        // its own swimming bursts resume at once if the rescue fails and cancels.
        if(entity.family().crew().busy()){burstTicks=0;entity.getNavigation().stop();return;}
        // One valid rescue rendezvous owns the swimming target until it expires or the real ticket takes over.
        if(receivingWaterRescue()){
            if(entity.aquaticLife().selfRescue().active())entity.aquaticLife().selfRescue().stop();
        }else if(entity.family().crew().climbing()||entity.aquaticLife().selfRescue().escapeTick()){burstTicks=0;return;}
        if(searchWait==0){findEscape();searchWait=20;}
        if(target!=null&&level().getEntity(target) instanceof HatBoatEntity hat){
            boolean own=safeOwnHat(hat);
            boolean guided=target.equals(rescueHat)&&safeRescueHat(hat);
            if(!own&&!guided&&!safeHat(hat)){target=null;destination=null;searchWait=0;}
            else{
                var parent=waterRescueParent!=null&&level().getEntity(waterRescueParent) instanceof YukkuriEntity y?y:null;
                var entry=guided?CrewTransfers.entryApproach(hat,entity,null,parent==null?null:WaterIncidents.rescueEntry(parent,entity)):null;
                destination=entry==null?hat.position():entry;entity.getLookControl().setLookAt(hat,30,30);
                // An untrained赤油 is lifted by its parent. It never grants itself a jump.
                if(!own&&!guided&&boardWait==0&&entity.childhood().canJump()&&entity.aquaticLife().familyLife().tryWaterBoard(hat)){
                    boardWait=80;burstTicks=0;return;
                }
            }
        }else if(destination!=null)entity.getLookControl().setLookAt(destination.x,destination.y,destination.z,30,30);
        var incident=WaterIncidents.of(entity);
        boolean weak=phase==Phase.EXHAUSTED||incident!=null&&incident.shoreDrift();
        if(burstTicks==0){
            entity.getNavigation().stop();
            // Exhaustion is not a frozen state.  While waiting for strength (including at zero energy), the body
            // still drifts weakly toward the already chosen own hat or nearby bank; it never grants a jump or grip.
            if(restTicks==0){
                if(entity.life().spendEnergy(weak?.3f:.65f)){
                    burstTicks=weak?3:8;restTicks=weak?61:24;
                    if(shore!=null&&entity.childhood().canJump())entity.life().navigate(shore,weak?.45:.8,true);
                }else{restTicks=60;if(!weak)return;}
            }else if(!weak)return;
        }
        Vec3 velocity=entity.getDeltaMovement(),toward=direction();
        boolean canJump=entity.childhood().canJump();
        double speed=weak?.055:canJump?.13:.045;
        Vec3 push=velocity.horizontalDistanceSqr()<speed*speed?toward.scale(weak?.005:canJump?.016:.006):Vec3.ZERO;
        entity.setDeltaMovement(velocity.add(push.x,0,push.z));entity.hasImpulse=true;
    }
    /** Five real water columns approximate displaced body volume, including shallow or flowing edges. */
    public static double submergedFraction(YukkuriEntity actor){
        var box=actor.getBoundingBox();double height=box.getYsize();if(height<=0)return 0;
        double dx=actor.getBbWidth()*.35,dz=dx,total=0;
        double[][] columns={{actor.getX(),actor.getZ()},{actor.getX()-dx,actor.getZ()-dz},{actor.getX()+dx,actor.getZ()-dz},
                {actor.getX()-dx,actor.getZ()+dz},{actor.getX()+dx,actor.getZ()+dz}};
        for(var column:columns)for(int y=Mth.floor(box.minY);y<=Mth.floor(box.maxY);y++){
            var at=BlockPos.containing(column[0],y,column[1]);if(!actor.level().hasChunkAt(at))continue;
            var fluid=actor.level().getFluidState(at);if(!fluid.is(FluidTags.WATER))continue;
            double top=y+fluid.getHeight(actor.level(),at);
            total+=Math.max(0,Math.min(box.maxY,top)-Math.max(box.minY,y));
        }
        return Math.clamp(total/(columns.length*height),0,1);
    }
    /** Neutral near 56% immersion against vanilla water gravity; effort and fatigue do not erase buoyancy. */
    public static double buoyancyAcceleration(double fraction,double verticalVelocity){
        return Math.clamp(.005+.045*(fraction-.56)-.08*verticalVelocity,-.018,.032);
    }
    private void surfaceBuoyancy(){
        double fraction=submergedFraction(entity);if(fraction<=0)return;
        var velocity=entity.getDeltaMovement();double force=buoyancyAcceleration(fraction,velocity.y);
        if(force>0)force=Math.max(0,Math.min(force,.085-velocity.y));
        entity.setDeltaMovement(velocity.add(0,force,0));entity.hasImpulse=true;
    }
    private boolean realThreat(){
        if(entity.isOnFire())return true;
        var attacker=entity.getLastHurtByMob();
        if(attacker!=null&&attacker.isAlive()&&entity.distanceToSqr(attacker)<=64)return true;
        var current=level();long now=current.getGameTime();
        if(threatLevel==current&&threatTick==now)return cachedThreat;
        threatLevel=current;threatTick=now;
        return cachedThreat=!current.getEntitiesOfClass(LivingEntity.class,entity.getBoundingBox().inflate(6,3,6),
                other->other!=entity&&other.isAlive()&&(other instanceof Enemy
                        ||other instanceof YukkuriEntity y&&y.profile().dangerToYukkuri())&&entity.hasLineOfSight(other)).isEmpty();
    }
    private void alertKin(){
        var relations=YukkuriWorldData.get(level());
        for(var other:level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(12,4,12),y->y!=entity&&y.isAlive())){
            boolean sibling=entity.family().mother()!=null&&entity.family().mother().equals(other.family().mother())
                    ||entity.family().father()!=null&&entity.family().father().equals(other.family().father());
            if(entity.family().caresFor(other)||other.family().caresFor(entity)||sibling
                    ||relations.affinity(other.getUUID(),entity.getUUID())>=BondLevels.AFFINITY_FAMILIAR&&other.life().canRecognize(entity))
                other.aquaticLife().familyLife().witnessDistress(entity);
        }
    }
    private boolean safeHat(HatBoatEntity hat){
        if(!hat.isAlive()||hat.unsteadyMotion()||!hat.isAfloat()||hat.flooding()>0||!hat.admits(entity))return false;
        return level().getEntity(hat.ownerYukkuri()) instanceof YukkuriEntity parent&&parent.isAlive()&&hat.protects(parent);
    }
    /** The individual's own loaded, token-matched hat remains a swimming landmark even if it is upside down or beached.
     *  The lookup never revives declared custody; only the later physical board operation may do that. */
    private boolean safeOwnHat(HatBoatEntity hat){
        return hat!=null&&hat.isAlive()&&hat.ownsPhysicalHat(entity)&&!hat.deploying()
                &&(hat.isAfloat()||hat.state()==HatBoatEntity.State.CAPSIZED||hat.state()==HatBoatEntity.State.BEACHED);
    }
    private boolean safeRescueHat(HatBoatEntity hat){
        return hat!=null&&hat.isAlive()&&hat.isAfloat()&&!hat.unsteadyMotion()&&!hat.deploying()&&hat.admits(entity);
    }
    private void findEscape(){
        target=null;shore=null;destination=null;
        var guided=rescueHat!=null&&level().getEntity(rescueHat) instanceof HatBoatEntity hat?hat:null;
        if(safeRescueHat(guided)){
            target=guided.getUUID();destination=guided.position();
            return;
        }
        clearWaterRescue();
        // A swimmer whose climbs are spent (or who cannot reach any entry) no longer heads for a hull: only the nearest shore.
        var incident=WaterIncidents.of(entity);
        boolean shoreOnly=incident!=null&&incident.shoreDrift();
        var own=shoreOnly?null:entity.aquaticLife().selfRescue().knownOwnHat();
        if(safeOwnHat(own)){
            target=own.getUUID();destination=own.position();
            return;
        }
        var hats=shoreOnly?List.<HatBoatEntity>of():level().getEntitiesOfClass(HatBoatEntity.class,entity.getBoundingBox().inflate(8,2,8),this::safeHat);
        var home=hats.stream().filter(entity::hasLineOfSight).min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
        double best=Double.POSITIVE_INFINITY;
        for(var pos:BlockPos.betweenClosed(entity.blockPosition().offset(-6,-1,-6),entity.blockPosition().offset(6,3,6))){
            double distance=entity.distanceToSqr(Vec3.atBottomCenterOf(pos));
            if(distance<best&&level().hasChunkAt(pos)&&entity.life().dryStand(pos)&&entity.life().reachable(pos,true)){
                best=distance;shore=pos.immutable();
            }
        }
        if(shore==null&&shoreOnly){
            // Nothing dry within six blocks: look farther along sixteen headings, a few rings, nearest first.
            outer:for(int ring=8;ring<=16;ring+=4)for(int k=0;k<16;k++){
                double angle=k*Math.PI/8;
                var pos=BlockPos.containing(entity.getX()+Math.cos(angle)*ring,entity.getY(),entity.getZ()+Math.sin(angle)*ring);
                for(int dy=-1;dy<=2;dy++){
                    var probe=pos.above(dy);
                    if(level().hasChunkAt(probe)&&entity.life().dryStand(probe)&&entity.life().reachable(probe,true)){shore=probe.immutable();break outer;}
                }
            }
        }
        if(home!=null&&(shore==null||entity.distanceToSqr(home)<=best+4)){
            target=home.getUUID();destination=home.position();shore=null;
        }else if(shore!=null)destination=Vec3.atBottomCenterOf(shore);
    }
    public CompoundTag save(){
        var tag=new CompoundTag();tag.putString("Phase",phase.name());tag.putInt("PhaseTicks",phaseTicks);
        tag.putInt("Immersion",immersionTicks);tag.putInt("Fatigue",fatigue);tag.putInt("RestTicks",Math.max(restTicks,burstTicks>0?24:0));
        tag.putInt("BoardWait",boardWait);
        tag.putInt("LiftWait",liftWait);
        tag.putInt("PendingPlunge",pendingPlunge);
        if(sourceBoat!=null)tag.putUUID("SourceBoat",sourceBoat);if(pusher!=null)tag.putUUID("Pusher",pusher);return tag;
    }
    public void load(CompoundTag tag){
        try{phase=Phase.valueOf(tag.getString("Phase"));}catch(IllegalArgumentException ex){phase=Phase.IDLE;}
        phaseTicks=Math.clamp(tag.getInt("PhaseTicks"),0,24000);immersionTicks=Math.clamp(tag.getInt("Immersion"),0,24000);
        fatigue=Math.clamp(tag.getInt("Fatigue"),0,24000);restTicks=Math.clamp(tag.getInt("RestTicks"),0,90);
        sourceBoat=tag.hasUUID("SourceBoat")?tag.getUUID("SourceBoat"):null;pusher=tag.hasUUID("Pusher")?tag.getUUID("Pusher"):null;
        boardWait=Math.clamp(tag.getInt("BoardWait"),0,80);liftWait=Math.clamp(tag.getInt("LiftWait"),0,60);drySupportTicks=0;
        pendingPlunge=Math.clamp(tag.getInt("PendingPlunge"),0,12);
        burstTicks=searchWait=callWait=0;target=rescueHat=waterRescueParent=null;temporaryRefugeGuide=false;waterRescueHoldTick=Long.MIN_VALUE;shore=null;destination=null;lastLevel=threatLevel=null;lastTick=threatTick=Long.MIN_VALUE;
    }
}
