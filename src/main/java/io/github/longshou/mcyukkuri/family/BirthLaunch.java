package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.NestResting;
import io.github.longshou.mcyukkuri.motor.geom.Ballistic;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The randomized launch of a newborn on land (project design: the numbers below are this project's, not the source material's).
 * A live kit is pushed out with a random strength and a small yaw error; a stem fruit is tossed sideways a little as it comes
 * away. Only the first impulse is decided here; the rest is vanilla physics, so where the kit comes down (hat, nest cushion or
 * bare ground) is decided by real contact in {@link FamilyLife}. The plan is handed to {@code motor.action.Launch} once and
 * never re-rolled.
 */
public final class BirthLaunch {
    private BirthLaunch(){}
    /** Push strength factor: exp(N(PUSH_LOG_MEAN, PUSH_LOG_SIGMA)) clamped to [PUSH_MIN, PUSH_MAX]. 项目设计 */
    static final double PUSH_LOG_MEAN=.03, PUSH_LOG_SIGMA=.32, PUSH_MIN=.4, PUSH_MAX=2.2;
    /** Yaw error around the aimed direction, degrees: N(0, YAW_SIGMA) clamped to +-YAW_MAX. 项目设计 */
    static final double YAW_SIGMA=8, YAW_MAX=25;
    /** Upward speed of a live kit (blocks per tick) with its relative spread. 项目设计 */
    static final double LIFT=.22, LIFT_SPREAD=.1;
    /** Planned reach into a large nest as a share of the room to the inner wall, and its floor (blocks). 项目设计 */
    static final double NEST_REACH=.7, NEST_MIN_REACH=.2;
    /** Planned reach of a kit pushed out with neither hat nor nest (blocks). 项目设计 */
    static final double GROUND_REACH=.35;
    /** Fixed push of the demonstrated overstrain on land. 项目设计 */
    static final double OVERSTRAIN_PUSH=1.8;
    /** Stem fruit toss: horizontal speed = clamp(STEM_MEDIAN * exp(N(0, STEM_SIGMA)), 0, STEM_MAX) in a random direction, then a gentle fall. 项目设计 */
    static final double STEM_MEDIAN=.055, STEM_SIGMA=.9, STEM_MAX=.25, STEM_DROP=-.015;
    /** Vanilla gravity and drag the launch is solved against (LivingEntity: y = (y - .08) * .98, horizontal * .91 in the air). */
    private static final double GRAVITY=.08, VERTICAL_DRAG=.98, AIR_DRAG=.91;
    /** The longest aim any birth launch is given: a catching hat's catchment reach, the same 1.25 blocks a hat zone is chosen within. */
    private static final double MAX_AIM=1.25;
    /** Highest hat target above the launch point the arc lift is bounded for (blocks). */
    private static final double HAT_RISE=3;
    /**
     * The fastest impulse the draw can produce (blocks per tick), used by {@code Launch.check} as its ceiling: either the live
     * draw (longest aim at the lowest lift, strongest push) or the hat arc (the hull's top speed over its shortest flight with
     * the air-drag compensation, strongest push, and the lift of its longest flight), whichever is larger.
     */
    public static final double MAX_SPEED;
    static {
        double low=LIFT*(1-LIFT_SPREAD),high=LIFT*(1+LIFT_SPREAD);
        double live=Math.hypot(speedFor(MAX_AIM,low)*PUSH_MAX,high);
        double hatHorizontal=HatGeometry.MAX_SPEED/16*Ballistic.speedFor(1,HatGeometry.TMIN)*HatGeometry.TMIN*PUSH_MAX;
        double hatLift=HAT_RISE/HatGeometry.TMAX+HatGeometry.GRAVITY/16/2*(HatGeometry.TMAX-1);
        MAX_SPEED=Math.max(live,Math.hypot(hatHorizontal,hatLift));
    }

    enum Target { HAT, NEST, GROUND }

    /**
     * What was decided for one kit; {@code aim} is the planned landing point, {@code nominal} the unscaled horizontal speed and
     * {@code hat} the hull it was aimed at (null for a nest/ground aim and for a push past the brim).
     */
    record Plan(Target target,UUID hat,Vec3 start,Vec3 aim,double room,double nominal,double push,double yaw,Vec3 velocity){}

    /** Persist the one already-randomized launch decision; loading never rolls a new impulse. */
    static CompoundTag save(Plan plan){
        var tag=new CompoundTag();
        tag.putString("Target",plan.target().name());
        if(plan.hat()!=null)tag.putUUID("Hat",plan.hat());
        putVector(tag,"Start",plan.start());putVector(tag,"Aim",plan.aim());putVector(tag,"Velocity",plan.velocity());
        tag.putDouble("Room",plan.room());tag.putDouble("Nominal",plan.nominal());tag.putDouble("Push",plan.push());tag.putDouble("Yaw",plan.yaw());
        return tag;
    }

    /** Invalid or incomplete saved data has no launch plan; callers keep the ordinary landing path. */
    static Plan load(CompoundTag tag){
        if(tag==null||!tag.contains("Target",Tag.TAG_STRING)||!tag.contains("Room",Tag.TAG_DOUBLE)
                ||!tag.contains("Nominal",Tag.TAG_DOUBLE)||!tag.contains("Push",Tag.TAG_DOUBLE)||!tag.contains("Yaw",Tag.TAG_DOUBLE))return null;
        Target target;
        try { target=Target.valueOf(tag.getString("Target")); }
        catch(IllegalArgumentException ignored){return null;}
        var start=vector(tag,"Start");
        var aim=vector(tag,"Aim");
        var velocity=vector(tag,"Velocity");
        UUID hat=tag.hasUUID("Hat")?tag.getUUID("Hat"):null;
        double room=tag.getDouble("Room"),nominal=tag.getDouble("Nominal"),push=tag.getDouble("Push"),yaw=tag.getDouble("Yaw");
        if(start==null||aim==null||velocity==null||(!Double.isFinite(room)&&!Double.isNaN(room))
                ||!Double.isFinite(nominal)||!Double.isFinite(push)||!Double.isFinite(yaw))return null;
        return new Plan(target,hat,start,aim,room,nominal,push,yaw,velocity);
    }

    private static void putVector(CompoundTag tag,String key,Vec3 value){
        var vector=new CompoundTag();vector.putDouble("X",value.x);vector.putDouble("Y",value.y);vector.putDouble("Z",value.z);tag.put(key,vector);
    }
    private static Vec3 vector(CompoundTag tag,String key){
        if(!tag.contains(key,Tag.TAG_COMPOUND))return null;
        var vector=tag.getCompound(key);
        if(!vector.contains("X",Tag.TAG_DOUBLE)||!vector.contains("Y",Tag.TAG_DOUBLE)||!vector.contains("Z",Tag.TAG_DOUBLE))return null;
        double x=vector.getDouble("X"),y=vector.getDouble("Y"),z=vector.getDouble("Z");
        return Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z)?new Vec3(x,y,z):null;
    }

    static double push(RandomSource random){
        return Mth.clamp(Math.exp(PUSH_LOG_MEAN+random.nextGaussian()*PUSH_LOG_SIGMA),PUSH_MIN,PUSH_MAX);
    }

    static double yaw(RandomSource random){
        return Mth.clamp(random.nextGaussian()*YAW_SIGMA,-YAW_MAX,YAW_MAX);
    }

    /** Ticks a body launched upward at {@code vy} spends in the air before it is back at its launch height. */
    static int airTicks(double vy){
        double y=0,v=vy;int n=0;
        do{y+=v;v=(v-GRAVITY)*VERTICAL_DRAG;n++;}while(y>0&&n<80);
        return n;
    }

    /** Horizontal launch speed that covers {@code distance} in the air against vanilla air drag. */
    static double speedFor(double distance,double vy){
        return distance*(1-AIR_DRAG)/(1-Math.pow(AIR_DRAG,airTicks(vy)));
    }

    /** The first impulse of a push out of the outlet toward {@code direction} (unit, horizontal) for a planned reach. */
    private static Vec3 impulse(RandomSource random,Vec3 direction,double distance,double push,double yawDeg){
        double vy=LIFT*(1-LIFT_SPREAD+random.nextDouble()*2*LIFT_SPREAD);
        var heading=direction.yRot((float)Math.toRadians(yawDeg));
        double vh=speedFor(distance,vy)*push;
        return new Vec3(heading.x*vh,vy,heading.z*vh);
    }

    /** A live kit pushed out with no hat: into the large nest the mother lies in, otherwise a short hop onto the ground. */
    static Plan live(RandomSource random,YukkuriEntity mother,YukkuriEntity kit,Vec3 start){
        double push=push(random),yaw=yaw(random);
        var centre=FamilyNest.largeCentre(mother);
        double half=NestResting.LARGE.half();
        double radius=kit.getBbWidth()*.5;
        if(centre!=null&&Math.abs(start.x-centre.x)<half&&Math.abs(start.z-centre.z)<half){
            var facing=Vec3.directionFromRotation(0,mother.yBodyRot);
            Vec3 best=null;double room=-1;
            for(int i=0;i<8;i++){
                var direction=new Vec3(Math.cos(i*Math.PI/4),0,Math.sin(i*Math.PI/4));
                // Not back through the mother's own body.
                if(direction.dot(new Vec3(facing.x,0,facing.z).normalize())<-.1)continue;
                double reach=roomAlong(start,centre,half,direction)-radius;
                if(reach>room){room=reach;best=direction;}
            }
            if(best!=null){
                double distance=Math.max(NEST_MIN_REACH,NEST_REACH*room);
                var velocity=impulse(random,best,distance,push,yaw);
                double vh=Math.hypot(velocity.x,velocity.z);
                return new Plan(Target.NEST,null,start,start.add(best.scale(distance)),room,vh/push,push,yaw,velocity);
            }
        }
        var forward=Vec3.directionFromRotation(0,mother.yBodyRot);
        var direction=new Vec3(forward.x,0,forward.z).normalize();
        var velocity=impulse(random,direction,GROUND_REACH,push,yaw);
        return new Plan(Target.GROUND,null,start,start.add(direction.scale(GROUND_REACH)),Double.NaN,Math.hypot(velocity.x,velocity.z)/push,push,yaw,velocity);
    }

    /** A stem fruit coming away: a small toss in a random direction while it drops; where it lands is left to physics. */
    static Plan stem(RandomSource random,Vec3 start,Target target){
        double angle=random.nextDouble()*Math.PI*2;
        double speed=Mth.clamp(STEM_MEDIAN*Math.exp(random.nextGaussian()*STEM_SIGMA),0,STEM_MAX);
        var velocity=new Vec3(Math.cos(angle)*speed,STEM_DROP,Math.sin(angle)*speed);
        return new Plan(target,null,start,start,Double.NaN,speed,1,0,velocity);
    }

    /**
     * A live kit pushed out toward a hat: the authored nominal arc keeps its own lift (the tick count the hull's clearance
     * solve asks for), and the horizontal speed is solved for the aim under vanilla air motion. The drawn push scales and the
     * drawn yaw turns only the horizontal part, exactly as the old nominal arc was scaled; nothing here moves the body.
     */
    static Plan intoHat(YukkuriEntity kit,PlacedHatEntity hat,Vec3 aim,double push,double yawDeg){
        var start=kit.position();
        double radius=HatGeometry.radius(kit.getBbWidth());
        int ticks=HatGeometry.solve(start,aim,hat,radius,Math.max(HatGeometry.TMIN,LiveBirthGeometry.CATCH_ARC_TICKS));
        return towards(Target.HAT,hat.getUUID(),start,aim,lift(start,aim,ticks),ticks,push,yawDeg);
    }

    /**
     * A push that misses the hat on purpose (the drawn overstrain afloat): on along the same line past the far brim, at the real
     * ground or water there. Only the aim changes; the impulse is the ordinary nominal arc toward it.
     */
    static Plan past(YukkuriEntity kit,PlacedHatEntity hat,Vec3 aimedAt){
        var start=kit.position();
        double radius=HatGeometry.radius(kit.getBbWidth());
        var far=HatGeometry.overshoot(start,aimedAt,hat,radius);
        var hit=kit.level().clip(new ClipContext(far.add(0,1.5,0),far.add(0,-3,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.ANY,kit));
        var aim=hit.getType()==HitResult.Type.MISS?new Vec3(far.x,hat.getY(),far.z):hit.getLocation();
        int ticks=HatGeometry.solve(start,aim,hat,radius,HatGeometry.TMIN);
        return towards(Target.GROUND,null,start,aim,lift(start,aim,ticks),ticks,1,0);
    }

    /** The vertical part of the nominal arc's first impulse (blocks per tick), the value the old flight started with. */
    private static double lift(Vec3 start,Vec3 aim,int ticks){return HatGeometry.arc(start,aim,ticks,1).y-start.y;}

    /**
     * One plan from a start, an aim and the nominal lift; the horizontal speed is what vanilla air drag needs to cover the same
     * aim in the same air time. An aim at or above the launch point is never crossed while falling, so it keeps the nominal arc's
     * own duration.
     */
    private static Plan towards(Target target,UUID hat,Vec3 start,Vec3 aim,double lift,int nominalTicks,double push,double yawDeg){
        double dx=Math.hypot(aim.x-start.x,aim.z-start.z);
        double air=Ballistic.airTicks(lift,aim.y-start.y);
        // The scan limit is what airTicks reports when the fall never passes the aim; the nominal arc's duration is then honest.
        if(air<=0||air>=Ballistic.airTicks(lift,-Double.MAX_VALUE))air=nominalTicks;
        air=Math.max(1,air);
        double nominal=Ballistic.speedFor(dx,air);
        var forward=new Vec3(aim.x-start.x,0,aim.z-start.z);
        var heading=forward.lengthSqr()<1e-9?new Vec3(0,0,1):forward.normalize();
        heading=heading.yRot((float)Math.toRadians(yawDeg));
        double vh=nominal*push;
        return new Plan(target,hat,start,aim,Double.NaN,nominal,push,yawDeg,new Vec3(heading.x*vh,lift,heading.z*vh));
    }

    /** Distance from {@code from} to the inner wall of the square nest along a horizontal unit direction. */
    static double roomAlong(Vec3 from,Vec3 centre,double half,Vec3 direction){
        double best=Double.MAX_VALUE;
        if(Math.abs(direction.x)>1e-6)best=Math.min(best,((direction.x>0?centre.x+half:centre.x-half)-from.x)/direction.x);
        if(Math.abs(direction.z)>1e-6)best=Math.min(best,((direction.z>0?centre.z+half:centre.z-half)-from.z)/direction.z);
        return Math.max(0,best);
    }
}
