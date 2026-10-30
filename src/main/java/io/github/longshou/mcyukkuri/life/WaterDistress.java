package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.action.Climb;
import io.github.longshou.mcyukkuri.motor.action.Swim;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;

/**
 * Real immersion, its phases and calls for help, and the breath after reaching safety; never damage immunity (YukkuriLife
 * applies the immersion damage). It never moves the body: the water's lift is the motor's, the way out is the EscapeWater
 * intent's ({@link #struggles}).
 */
public final class WaterDistress {
    public enum Phase { IDLE, IMPACT, STRUGGLE, EXHAUSTED, RECOVER }
    private final YukkuriEntity entity;
    private Phase phase=Phase.IDLE;
    private int phaseTicks, immersionTicks, fatigue, callWait, pendingPlunge, drySupportTicks;
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
    /** In a paid swim burst (the presentation's effort flag): read from the body's running Swim. */
    public boolean effort(){return entity.isInWaterOrBubble()&&!entity.predatorFlying()&&!entity.family().flying()
            &&entity.motor().current() instanceof Swim swim&&swim.pushing();}
    /** Server-side status readout; no target search or rescue state changes are performed here. */
    public String statusDetail(){
        return "phase="+phase+" phaseTicks="+phaseTicks+" immersion="+immersionTicks+" fatigue="+fatigue;
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
    public String activity(){return switch(phase){
        case IDLE->Behaviors.IDLE;case IMPACT->Behaviors.WATER_IMPACT;case STRUGGLE->Behaviors.WATER_STRUGGLE;
        case EXHAUSTED->Behaviors.WATER_EXHAUSTED;case RECOVER->Behaviors.WATER_RECOVER;
    };}
    private void set(Phase next){
        if(phase!=next){
            Phase before = phase; int elapsed = phaseTicks;
            phase=next;phaseTicks=0;
            GameplayLog.limited(entity, "water", "phase." + next, 200,
                    "phase={} -> {} previousTicks={} immersion={} fatigue={} energy={}",
                    before, next, elapsed, immersionTicks, fatigue, entity.life().energy());
        }
    }
    private void enterWater(){
        immersionTicks=0;pendingPlunge=0;set(Phase.IMPACT);
        if(!entity.isNoAi()){entity.life().wakeInHat();if(!entity.predatorFlying())entity.getNavigation().stop();}
    }
    /** Entity-owned server clock, including disabled AI. YukkuriLife alone applies immersion damage. */
    public void tick(){
        if(entity.level().isClientSide||!entity.isAlive())return;
        var current=level();long now=current.getGameTime();
        if(lastLevel==current&&lastTick==now)return;lastLevel=current;lastTick=now;
        if(callWait>0)callWait--;if(pendingPlunge>0)pendingPlunge--;
        phaseTicks++;
        if(entity.isInWaterOrBubble()){
            drySupportTicks=0;
            if(phase==Phase.IDLE||phase==Phase.RECOVER)enterWater();
            immersionTicks=Math.min(24000,immersionTicks+1);fatigue=Math.min(24000,fatigue+1);
            if(phase==Phase.IMPACT&&phaseTicks>=12)set(Phase.STRUGGLE);
            if(phase!=Phase.IMPACT&&(fatigue>=160||entity.life().energy()<20))set(Phase.EXHAUSTED);
            if(!entity.isNoAi()&&!entity.isCommandPlaying()&&!entity.operations().active()
                    &&callWait==0&&phase!=Phase.IMPACT){entity.speak("water_help");callWait=phase==Phase.EXHAUSTED?90:55;}
            return;
        }
        // An upward flight or airborne tumble is not a completed rescue; standing on dry ground or aboard (a dry hat, a carrier) is.
        if(phase==Phase.IMPACT||phase==Phase.STRUGGLE||phase==Phase.EXHAUSTED){
            if(entity.predatorFlying()||entity.family().flying()||!entity.onGround()&&!entity.isPassenger()){drySupportTicks=0;return;}
            if(++drySupportTicks<3)return;
            set(Phase.RECOVER);
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
    /**
     * The escape goal's question, asked once a tick in water: may the body work its way out now? Not while the plunge's
     * impact lasts, nor with its AI off, a command or an operation playing, while it flies or while something carries it (its
     * carrier or host moves it). Updates the phases first.
     */
    public boolean struggles(){
        if(entity.level().isClientSide||!(entity.isInWaterOrBubble()||climbing())||entity.isPassenger()||entity.isNoAi()||entity.isCommandPlaying()
                ||entity.operations().active()||entity.predatorFlying()||entity.family().flying())return false;
        tick();
        return phase!=Phase.IMPACT;
    }
    /** Mid-climb out of the water: lifting the body clear of it does not end the escape, the climb does. */
    public boolean climbing(){return entity.motor().current() instanceof Climb;}
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
    public CompoundTag save(){
        var tag=new CompoundTag();tag.putString("Phase",phase.name());tag.putInt("PhaseTicks",phaseTicks);
        tag.putInt("Immersion",immersionTicks);tag.putInt("Fatigue",fatigue);
        tag.putInt("PendingPlunge",pendingPlunge);
        return tag;
    }
    public void load(CompoundTag tag){
        try{phase=Phase.valueOf(tag.getString("Phase"));}catch(IllegalArgumentException ex){phase=Phase.IDLE;}
        phaseTicks=Math.clamp(tag.getInt("PhaseTicks"),0,24000);immersionTicks=Math.clamp(tag.getInt("Immersion"),0,24000);
        fatigue=Math.clamp(tag.getInt("Fatigue"),0,24000);
        drySupportTicks=0;
        pendingPlunge=Math.clamp(tag.getInt("PendingPlunge"),0,12);
        callWait=0;lastLevel=threatLevel=null;lastTick=threatTick=Long.MIN_VALUE;
    }
}
