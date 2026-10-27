package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.Dip;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.State;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import static io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.ROCK_TICKS;
import static io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.CAPSIZE_TICKS;
import static io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.MAX_DIP;
import static io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.DIP_RENEW_TICKS;
import static io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.MAX_AGITATION;

/** Bounded rocking, rim dips and delayed falls; the entity keeps the original tick schedule. */
final class HatHullMotion {
    /** A capsized hat ends crown-up: a half turn about its length. */
    static final float CAPSIZED_ROLL = 180f;
    private final HatBoatEntity boat;
    long dipRenewed=Long.MIN_VALUE;
    int agitation;
    final Map<UUID,Long> delayedDrops=new HashMap<>();

    HatHullMotion(HatBoatEntity boat) { this.boat = boat; }

    boolean drifting() { return boat.isAfloat() && !boat.paddling() && boat.currentStrength()>.08f; }

    boolean rocking(){var m=boat.motionData();return boat.isAfloat()&&m.contains("RockAt")&&motionAge(m,"RockAt",0)<ROCK_TICKS;}

    boolean capsizing(){var m=boat.motionData();return boat.state()==State.CAPSIZED&&m.contains("CapAt")&&motionAge(m,"CapAt",0)<CAPSIZE_TICKS;}

    boolean deploying(){var m=boat.motionData();return boat.isAfloat()&&m.contains("DeployAt")&&motionAge(m,"DeployAt",0)<20;}

    boolean selfRescueHeld(){return boat.motionData().getBoolean("SelfHeld");}

    Vec3 motionPosition(float partial){
        var m=boat.motionData();
        if((selfRescueHeld()||m.contains("LooseAt")&&motionAge(m,"LooseAt",partial)<14)&&m.contains("HeldCentreX"))
            return HatBoatEntity.point(m,"PreviousCentre").lerp(HatBoatEntity.point(m,"HeldCentre"),partial);
        if(selfRighting()&&m.contains("RightGripX")&&m.contains("RightAnchorX")){
            // The q interpolation is eased, so interpolating the two hull centres would make the rendered brim slide
            // past the swimmer's fixed bite between ticks.  Rebuild it from the one synced real pivot instead.
            var grip=HatBoatEntity.point(m,"RightGrip");
            var offset=selfRightingOffset(grip,selfRightingProgress(partial),interpolatedTurn(partial)).subtract(0,boat.rimLift(grip),0);
            return HatBoatEntity.point(m,"RightAnchor").subtract(offset);
        }
        // A saved in-flight righting has no persistent grip/anchor.  It immediately relaxes through the existing
        // lifecycle, and must retain ordinary entity interpolation until a new real grip writes one.
        return boat.getPosition(partial);
    }

    float hullSquash(float partial){
        var m=boat.motionData();
        if(selfRescueHeld())return Mth.lerp(partial,m.getFloat("PreviousSquash"),m.getFloat("HeldSquash"));
        if(m.contains("LooseAt")){
            float t=(float)Math.clamp(motionAge(m,"LooseAt",partial)/14,0,1);t=t*t*(3-2*t);
            return Mth.lerp(t,m.getFloat("LooseSquash"),(float)HatVessel.squash(boat.mode()));
        }
        return (float)HatVessel.squash(boat.mode());
    }

    boolean selfRighting(){return boat.state()==State.CAPSIZED&&boat.motionData().contains("RightProgress");}

    float selfRightingProgress(float partial){var m=boat.motionData();return Mth.lerp(partial,m.getFloat("RightPrevious"),m.getFloat("RightProgress"));}

    /**
     * The same yaw * roll * pitch offset used by the renderer while a swimmer rights the hull.  Hazard handling uses
     * this instead of reproducing the easing or transform, so its fixed brim anchor remains the rendered brim anchor.
     */
    Vec3 selfRightingOffset(Vec3 local,float progress){
        return selfRightingOffset(local,progress,boat.turn());
    }

    private Vec3 selfRightingOffset(Vec3 local,float progress,float turn){
        int side=boat.motionData().getInt("Side")<0?-1:1;
        double t=Math.clamp(progress,0,1);
        return yawRollPitchOffset(local,selfRightingRoll(side,t),selfRightingPitch(t),turn);
    }

    private float interpolatedTurn(float partial){return (float)Math.toRadians(180-Mth.rotLerp(partial,boat.previousYaw(),boat.getYRot()));}

    private static double ease(double t){t=Math.clamp(t,0,1);return t*t*(3-t*2);}

    private static float selfRightingRoll(int side,double progress){return (float)(side*CAPSIZED_ROLL*(1-ease(progress)));}

    private static float selfRightingPitch(double progress){return (float)(7*Math.sin(progress*Math.PI));}

    private Vec3 yawRollPitchOffset(Vec3 local,float rollDegrees,float pitchDegrees,float turn){
        var p=local.scale(boat.unit());double roll=Math.toRadians(rollDegrees),pitch=Math.toRadians(pitchDegrees);
        double py=p.y*Math.cos(pitch)-p.z*Math.sin(pitch),pz=p.y*Math.sin(pitch)+p.z*Math.cos(pitch);
        return new Vec3(p.x*Math.cos(roll)-py*Math.sin(roll),p.x*Math.sin(roll)+py*Math.cos(roll),pz).yRot(turn);
    }

    boolean unsteadyMotion(){return rocking()||capsizing()||deploying()||selfRighting()||selfRescueHeld();}

    float dipValue(float partial){
        var m=boat.dipData();if(m.isEmpty())return 0;
        double t=Math.clamp((boat.level().getGameTime()-m.getLong("At")+partial)/Math.max(1,m.getInt("Ticks")),0,1);t=t*t*(3-2*t);
        return (float)(m.getFloat("From")+(m.getFloat("To")-m.getFloat("From"))*t);
    }

    float dipDegrees(float partial){return dipValue(partial);}

    float dipYaw(){return boat.dipData().getFloat("Yaw");}

    Dip dip(){return new Dip(dipValue(0),dipYaw());}

    double rimLift(Vec3 localPoint){return rimLift(localPoint,dip(),boat.unit());}

    static double rimLift(Vec3 localPoint,Dip dip,double unit){
        if(dip==null||dip.degrees()<=.01f)return 0;
        double yaw=Math.toRadians(dip.yaw()),along=-localPoint.x*Math.sin(yaw)+localPoint.z*Math.cos(yaw);
        return Math.max(0,along*Math.tan(Math.toRadians(dip.degrees()))*unit);
    }

    boolean dippable(YukkuriEntity actor){
        if(boat.level().isClientSide||!boat.isAfloat()||unsteadyMotion()||boat.flooding()>0)return false;
        var captain=boat.owner();
        return captain==null||captain==actor||captain.isAlive()&&!captain.isNoAi()&&!captain.life().isSleeping()&&!captain.isCommandPlaying()&&captain.getVehicle()==boat;
    }

    Dip planDip(Vec3 worldTarget,float maxDegrees){
        if(worldTarget==null)return Dip.NONE;
        var local=boat.toLocal(worldTarget);double length=Math.hypot(local.x,local.z);
        if(length<1e-6)return Dip.NONE;
        float yaw=(float)Math.toDegrees(Math.atan2(-local.x,local.z)),current=dipValue(0);
        if(current>.5f&&Math.abs(Mth.wrapDegrees(yaw-dipYaw()))<=25f)yaw=dipYaw();
        double dx=-local.x/length,dz=local.z/length;
        var brim=HatVessel.brim(boat.mode());
        double reach=Math.min(Math.abs(dx)<1e-6?Double.MAX_VALUE:brim[0]/Math.abs(dx),Math.abs(dz)<1e-6?Double.MAX_VALUE:brim[1]/Math.abs(dz));
        float cap=(float)Math.toDegrees(Math.atan(.6*HatVessel.freeboard(boat.mode())/reach));
        float degrees=Math.min(Math.min(maxDegrees,MAX_DIP),cap);
        return degrees<=.05f?Dip.NONE:new Dip(degrees,yaw);
    }

    boolean dipToward(Vec3 worldTarget,float maxDegrees,int rampTicks){
        if(boat.level().isClientSide||!boat.isAfloat()||unsteadyMotion()||boat.flooding()>0||boat.catches())return false;
        var plan=planDip(worldTarget,maxDegrees);if(plan.degrees()<=0)return false;
        var m=boat.dipData();boolean leaning=m.getFloat("To")>0;float held=m.getFloat("Yaw");
        // While tilted toward one side, a weight on the opposite side waits for the hull to level instead of snapping it across.
        if(dipValue(0)>.5f&&Math.abs(Mth.wrapDegrees(plan.yaw()-held))>25f)return false;
        dipRenewed=boat.level().getGameTime();
        if(leaning&&m.getFloat("To")==plan.degrees())return true;
        var next=new CompoundTag();next.putFloat("From",dipValue(0));next.putFloat("To",plan.degrees());
        next.putLong("At",boat.level().getGameTime());next.putInt("Ticks",Math.max(1,rampTicks));next.putFloat("Yaw",leaning?held:plan.yaw());
        boat.syncDip(next);return true;
    }

    void releaseDip(int ticks){
        if(boat.level().isClientSide)return;
        dipRenewed=Long.MIN_VALUE;var m=boat.dipData();
        if(m.isEmpty()||m.getFloat("To")<=0)return;
        float current=dipValue(0);
        if(current<.01f){boat.syncDip(new CompoundTag());return;}
        var next=new CompoundTag();next.putFloat("From",current);next.putFloat("To",0);next.putLong("At",boat.level().getGameTime());
        next.putInt("Ticks",Math.max(1,ticks));next.putFloat("Yaw",m.getFloat("Yaw"));boat.syncDip(next);
    }

    void tickDip(){
        var m=boat.dipData();if(m.isEmpty()||m.getFloat("To")<=0)return;
        boolean unsettled=!boat.isAfloat()||unsteadyMotion()||boat.catches();
        if(unsettled)releaseDip(4);
        else if(boat.level().getGameTime()-dipRenewed>DIP_RENEW_TICKS)releaseDip(14);
    }

    long motionStartTick(){var m=boat.motionData();return m.contains("CapAt")?m.getLong("CapAt"):m.contains("RockAt")?m.getLong("RockAt"):0;}

    double motionAge(CompoundTag motion,String key,float partial){return Math.max(0,boat.level().getGameTime()-motion.getLong(key)+partial);}

    float motionRoll(float partial){
        var m=boat.motionData();int side=m.getInt("Side")<0?-1:1;
        if(selfRescueHeld())return 0;
        if(m.contains("LooseAt")){
            double t=Math.clamp(motionAge(m,"LooseAt",partial)/14,0,1);return (float)(side*CAPSIZED_ROLL*t*t*(3-2*t));
        }
        if(deploying()||selfRighting()){
            double t=deploying()?Math.clamp(motionAge(m,"DeployAt",partial)/20,0,1):Math.clamp(selfRightingProgress(partial),0,1);
            return selfRighting()?selfRightingRoll(side,t):(float)(side*CAPSIZED_ROLL*(1-ease(t)));
        }
        if(boat.state()==State.CAPSIZED){
            double t=m.contains("CapAt")?Math.clamp(motionAge(m,"CapAt",partial)/CAPSIZE_TICKS,0,1):1;
            return (float)(side*CAPSIZED_ROLL*t*t*(3-2*t));
        }
        if(!rocking())return drifting()?(float)(Math.sin((boat.level().getGameTime()+partial)*.19)*boat.currentStrength()*2.5):0;
        double age=motionAge(m,"RockAt",partial);
        return (float)(side*m.getFloat("Amplitude")*Math.exp(-age/14)*Math.sin(age*.62));
    }

    float motionPitch(float partial){
        var m=boat.motionData();
        if(selfRescueHeld())return Mth.lerp(partial,m.getFloat("PreviousPitch"),m.getFloat("HeldPitch"));
        if(m.contains("LooseAt")){
            double t=Math.clamp(motionAge(m,"LooseAt",partial)/14,0,1);return (float)(m.getFloat("LoosePitch")*(1-t*t*(3-2*t)));
        }
        if(deploying()||selfRighting()){
            double t=deploying()?Math.clamp(motionAge(m,"DeployAt",partial)/20,0,1):Math.clamp(selfRightingProgress(partial),0,1);
            return selfRighting()?selfRightingPitch(t):(float)(7*Math.sin(t*Math.PI));
        }
        if(boat.state()==State.CAPSIZED){
            if(!m.contains("CapAt"))return 0;
            double t=Math.clamp(motionAge(m,"CapAt",partial)/CAPSIZE_TICKS,0,1);
            return (float)(7*Math.sin(t*Math.PI));
        }
        if(!rocking())return drifting()?(float)(Math.sin((boat.level().getGameTime()+partial)*.13)*boat.currentStrength()):0;
        double age=motionAge(m,"RockAt",partial);
        return (float)(m.getFloat("Amplitude")*.24*Math.exp(-age/14)*Math.sin(age*.44));
    }

    float rockingAmplitude(){var m=boat.motionData();return rocking()?(float)(m.getFloat("Amplitude")*Math.exp(-motionAge(m,"RockAt",0)/14)):0;}

    int agitation(){return agitation;}

    boolean shake(Entity source,int strength){
        if(boat.level().isClientSide||!boat.isAfloat()||boat.catches()||boat.isRemoved())return false;
        if(selfRescueHeld()){boat.releaseSelfRescueCarry();return true;}
        var before=boat.motionData();double age=before.contains("RockAt")?motionAge(before,"RockAt",0):ROCK_TICKS;
        if(age<6)return false;
        long now=boat.level().getGameTime();
        agitation=Math.min(MAX_AGITATION,agitation+Math.max(1,strength));
        var m=new CompoundTag();m.putLong("RockAt",now);
        m.putInt("Side",source!=null?(boat.toLocal(source.position()).x>=0?-1:1):(((now/7)&1)==0?1:-1));
        // A full hull rolls more: the load's roll factor (hat_frames.json roll_by_load) scales the sway, up to 18 degrees.
        float roll=(float)boat.rollFactor();
        float amplitude=Math.min(18f,Math.min(12f,8f+agitation*.8f)*roll);
        m.putFloat("Amplitude",amplitude);boat.syncMotion(m);
        // In a roll of 15 degrees or more the stowed stick may slide off the left brim into the water (project design, ecology A9).
        if(amplitude>=15f&&boat.cargo().paddle()&&!boat.paddling()&&boat.level().random.nextDouble()<.10)boat.cargo().slideStickOverboard(amplitude);
        boat.helm().shaken(12);boat.markHullHurt();
        // Only a captain who really has children to gather calls them (the alarm itself checks); everyone else just reacts to the shove.
        var captain=boat.owner();if(captain!=null)captain.aquaticLife().familyLife().alarm().start(boat,source,source==null?null:source.position());
        var riders=List.copyOf(boat.getPassengers());
        for(var p:riders)if(p instanceof YukkuriEntity y){
            boolean adult=y.family().adult();
            y.getNavigation().stop();
            // Waiting for the family is only for a parent who has one; a childless captain is just startled.
            String adultLine=captain!=null&&y==captain&&!captain.aquaticLife().familyLife().hasDependents()?"boat_rock_startle":"wait_family";
            if (!adult) y.mind().felt(EmotionEvent.FRIGHTENED); y.showReaction(adult?"surprise":"teary",adult?adultLine:"afraid",ROCK_TICKS);
        }
        if(agitation>=3&&boat.level() instanceof ServerLevel server){
            for(var p:riders){
                if(!(p instanceof YukkuriEntity y)||p.getUUID().equals(boat.ownerYukkuri())||!boat.crowdMovable(y))continue;
                double chance=(boat.onBrim(y)?.14:.03)*(agitation-2)*boat.rollFactor();
                if(server.random.nextDouble()>=chance)continue;
                var local=boat.toLocal(y.position());var outward=new Vec3(local.x,0,local.z);
                if(outward.lengthSqr()<1e-6)outward=new Vec3(0,0,-1);
                if(boat.crowdFall(y,local,outward,source==null?null:source.getUUID()))agitation=Math.max(0,agitation-1);
            }
            if(agitation>=5&&server.random.nextDouble()<.2*boat.rollFactor()){boat.capsize("shaken");return true;}
        }
        return true;
    }

    boolean dropRiderAfter(YukkuriEntity rider,int ticks){
        if(boat.level().isClientSide||rider==null||rider.getVehicle()!=boat||rider.getUUID().equals(boat.ownerYukkuri()))return false;
        delayedDrops.put(rider.getUUID(),boat.level().getGameTime()+Math.max(1,ticks));return true;
    }

    void tickDelayedDrops(){
        if(delayedDrops.isEmpty()||!(boat.level() instanceof ServerLevel server))return;
        long now=boat.level().getGameTime();
        for(var entry:List.copyOf(delayedDrops.entrySet())){
            if(entry.getValue()>now)continue;
            if(!(server.getEntity(entry.getKey()) instanceof YukkuriEntity y)||!y.isAlive()||y.getVehicle()!=boat){delayedDrops.remove(entry.getKey());continue;}
            // A rider in the middle of a seat change waits (for at most 10 s) rather than being torn out of its path.
            if(!boat.crowdMovable(y)&&now-entry.getValue()<200)continue;
            delayedDrops.remove(entry.getKey());
            var local=boat.toLocal(y.position());var outward=new Vec3(local.x,0,local.z);
            if(outward.lengthSqr()<1e-6)outward=new Vec3(0,0,-1);
            boat.crowdFall(y,local,outward,null);
        }
    }
}
