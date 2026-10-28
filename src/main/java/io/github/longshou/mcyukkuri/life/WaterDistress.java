package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
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
    private final YukkuriEntity entity;
    private Phase phase=Phase.IDLE;
    private int phaseTicks, immersionTicks, fatigue, burstTicks, restTicks, searchWait, callWait, pendingPlunge, drySupportTicks;
    private BlockPos shore;
    private Vec3 destination;
    private long lastTick=Long.MIN_VALUE;
    private ServerLevel lastLevel;
    private long threatTick=Long.MIN_VALUE;
    private ServerLevel threatLevel;
    private boolean cachedThreat;
    public WaterDistress(YukkuriEntity entity){this.entity=entity;}
    private ServerLevel level(){return (ServerLevel)entity.level();}
    public Phase phase(){return phase;}
    public int phaseTicks(){return phaseTicks;}
    public int immersionTicks(){return immersionTicks;}
    public boolean active(){return phase!=Phase.IDLE;}
    public boolean effort(){return entity.isInWaterOrBubble()&&!entity.predatorFlying()&&burstTicks>0&&!entity.family().flying();}
    /** Server-side status readout; no target search or rescue state changes are performed here. */
    public String statusDetail(){
        return "phase="+phase+" phaseTicks="+phaseTicks+" immersion="+immersionTicks+" fatigue="+fatigue
                +" burst="+burstTicks+" rest="+restTicks;
    }
    /** Called where a body really falls in: it shows the plunge pose until it touches the water. */
    public void plunge(){
        if(entity.level().isClientSide)return;
        pendingPlunge=12;
        GameplayLog.limited(entity, "water", "plunge", 200,
                "body fell immersed={} phase={}", entity.isInWaterOrBubble(), phase);
        if(entity.isInWaterOrBubble()&&(phase==Phase.IDLE||phase==Phase.RECOVER))enterWater();
    }
    public boolean airbornePlunge(){return pendingPlunge>0&&!entity.isInWaterOrBubble()&&!entity.onGround()
            &&!entity.family().flying();}
    public int airbornePlungeTicks(){return Math.clamp(12-pendingPlunge,0,12);}
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
                    "phase={} -> {} previousTicks={} immersion={} fatigue={} energy={} shore={}",
                    before, next, elapsed, immersionTicks, fatigue, entity.life().energy(), shore);
        }
    }
    private void enterWater(){
        immersionTicks=0;burstTicks=restTicks=searchWait=0;shore=null;destination=null;
        pendingPlunge=0;set(Phase.IMPACT);
        if(!entity.isNoAi()){entity.life().wakeInHat();if(!entity.predatorFlying())entity.getNavigation().stop();}
    }
    /** Entity-owned server clock, including disabled AI. YukkuriLife alone applies immersion damage. */
    public void tick(){
        if(entity.level().isClientSide||!entity.isAlive())return;
        var current=level();long now=current.getGameTime();
        if(lastLevel==current&&lastTick==now)return;lastLevel=current;lastTick=now;
        if(searchWait>0)searchWait--;if(callWait>0)callWait--;if(pendingPlunge>0)pendingPlunge--;
        phaseTicks++;
        if(entity.isInWaterOrBubble()){
            drySupportTicks=0;
            if(!entity.predatorFlying())surfaceBuoyancy();
            if(phase==Phase.IDLE||phase==Phase.RECOVER)enterWater();
            immersionTicks=Math.min(24000,immersionTicks+1);fatigue=Math.min(24000,fatigue+1);
            if(phase==Phase.IMPACT&&phaseTicks>=12)set(Phase.STRUGGLE);
            if(phase!=Phase.IMPACT&&(fatigue>=160||entity.life().energy()<20))set(Phase.EXHAUSTED);
            if(!entity.isNoAi()&&!entity.isCommandPlaying()&&!entity.operations().active()){
                if(burstTicks>0)burstTicks--;else if(restTicks>0)restTicks--;
                if(callWait==0&&phase!=Phase.IMPACT){entity.speak("water_help");callWait=phase==Phase.EXHAUSTED?90:55;}
            }else burstTicks=0;
            return;
        }
        burstTicks=0;
        // An upward flight or airborne tumble is not a completed rescue.
        if(phase==Phase.IMPACT||phase==Phase.STRUGGLE||phase==Phase.EXHAUSTED){
            if(entity.predatorFlying()||entity.family().flying()||!entity.onGround()){drySupportTicks=0;return;}
            if(++drySupportTicks<3)return;
            set(Phase.RECOVER);shore=null;destination=null;
            if(!entity.isNoAi())entity.getNavigation().stop();
        }
        if(phase==Phase.RECOVER&&realThreat()){set(Phase.IDLE);return;}
        if(fatigue>0&&now%4==0)fatigue--;
        if(phase==Phase.RECOVER&&phaseTicks>=56){
            set(Phase.IDLE);immersionTicks=0;
        }
    }
    /** A short recovery may interrupt leisure, but never an urgent need, threat escape or player command. */
    public boolean restingBreath(){
        return phase==Phase.RECOVER&&phaseTicks<32&&!entity.isNoAi()&&!entity.isCommandPlaying()
                &&!entity.operations().active()&&!realThreat()
                &&!entity.life().hasUrgentNeed()&&!entity.family().flying()&&!entity.predatorFlying();
    }
    /** Called by the existing highest-priority escape goal, replacing its perpetual jump command. */
    public void escapeTick(){
        if(!entity.level().isClientSide&&!entity.isInWaterOrBubble()&&restingBreath()){
            entity.getNavigation().stop();
            return;
        }
        if(entity.level().isClientSide||!entity.isInWaterOrBubble()||entity.isNoAi()
                ||entity.isCommandPlaying()||entity.operations().active()||entity.predatorFlying()||entity.family().flying())return;
        tick();
        if(phase==Phase.IMPACT){entity.getNavigation().stop();return;}
        if(searchWait==0){findEscape();searchWait=20;}
        if(destination!=null)entity.getLookControl().setLookAt(destination.x,destination.y,destination.z,30,30);
        boolean weak=phase==Phase.EXHAUSTED;
        if(burstTicks==0){
            entity.getNavigation().stop();
            // Exhaustion is not a frozen state.  While waiting for strength (including at zero energy), the body
            // still drifts weakly toward the already chosen bank; it never grants a jump or grip.
            if(restTicks==0){
                if(entity.life().spendEnergy(weak?.3f:.65f)){
                    burstTicks=weak?3:8;restTicks=weak?61:24;
                    if(shore!=null&&entity.childhood().canJump())entity.life().navigate(shore,weak?.45:.8,true);
                }else{restTicks=60;if(!weak)return;}
            }else if(!weak)return;
        }
        Vec3 toward=direction();
        boolean canJump=entity.childhood().canJump();
        double speed=weak?.055:canJump?.13:.045;
        YukkuriLocomotion.swim(entity,entity.position().add(toward),speed);
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
    private void findEscape(){
        shore=null;destination=null;
        double best=Double.POSITIVE_INFINITY;
        for(var pos:BlockPos.betweenClosed(entity.blockPosition().offset(-6,-1,-6),entity.blockPosition().offset(6,3,6))){
            double distance=entity.distanceToSqr(Vec3.atBottomCenterOf(pos));
            if(distance<best&&level().hasChunkAt(pos)&&entity.life().dryStand(pos)&&entity.life().reachable(pos,true)){
                best=distance;shore=pos.immutable();
            }
        }
        if(shore!=null)destination=Vec3.atBottomCenterOf(shore);
    }
    public CompoundTag save(){
        var tag=new CompoundTag();tag.putString("Phase",phase.name());tag.putInt("PhaseTicks",phaseTicks);
        tag.putInt("Immersion",immersionTicks);tag.putInt("Fatigue",fatigue);tag.putInt("RestTicks",Math.max(restTicks,burstTicks>0?24:0));
        tag.putInt("PendingPlunge",pendingPlunge);
        return tag;
    }
    public void load(CompoundTag tag){
        try{phase=Phase.valueOf(tag.getString("Phase"));}catch(IllegalArgumentException ex){phase=Phase.IDLE;}
        phaseTicks=Math.clamp(tag.getInt("PhaseTicks"),0,24000);immersionTicks=Math.clamp(tag.getInt("Immersion"),0,24000);
        fatigue=Math.clamp(tag.getInt("Fatigue"),0,24000);restTicks=Math.clamp(tag.getInt("RestTicks"),0,90);
        drySupportTicks=0;
        pendingPlunge=Math.clamp(tag.getInt("PendingPlunge"),0,12);
        burstTicks=searchWait=callWait=0;shore=null;destination=null;lastLevel=threatLevel=null;lastTick=threatTick=Long.MIN_VALUE;
    }
}
