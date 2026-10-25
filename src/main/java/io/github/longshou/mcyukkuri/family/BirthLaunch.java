package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.NestResting;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * The randomized launch of a newborn on land (project design: the numbers below are this project's, not the source material's).
 * A live kit is pushed out with a random strength and a small yaw error; a stem fruit is tossed sideways a little as it comes
 * away. Only the first impulse is decided here; the rest is vanilla physics, so where the kit comes down (hat, nest cushion or
 * bare ground) is decided by real contact in {@link FamilyLife}.
 */
final class BirthLaunch {
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

    enum Target { HAT, NEST, GROUND }

    /** What was decided for one kit; {@code aim} is the planned landing point, {@code nominal} the unscaled horizontal speed. */
    record Plan(Target target,Vec3 start,Vec3 aim,double room,double nominal,double push,double yaw,Vec3 velocity){}

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
                return new Plan(Target.NEST,start,start.add(best.scale(distance)),room,vh/push,push,yaw,velocity);
            }
        }
        var forward=Vec3.directionFromRotation(0,mother.yBodyRot);
        var direction=new Vec3(forward.x,0,forward.z).normalize();
        var velocity=impulse(random,direction,GROUND_REACH,push,yaw);
        return new Plan(Target.GROUND,start,start.add(direction.scale(GROUND_REACH)),Double.NaN,Math.hypot(velocity.x,velocity.z)/push,push,yaw,velocity);
    }

    /** A stem fruit coming away: a small toss in a random direction while it drops; where it lands is left to physics. */
    static Plan stem(RandomSource random,Vec3 start,Target target){
        double angle=random.nextDouble()*Math.PI*2;
        double speed=Mth.clamp(STEM_MEDIAN*Math.exp(random.nextGaussian()*STEM_SIGMA),0,STEM_MAX);
        var velocity=new Vec3(Math.cos(angle)*speed,STEM_DROP,Math.sin(angle)*speed);
        return new Plan(target,start,start,Double.NaN,speed,1,0,velocity);
    }

    /** Distance from {@code from} to the inner wall of the square nest along a horizontal unit direction. */
    static double roomAlong(Vec3 from,Vec3 centre,double half,Vec3 direction){
        double best=Double.MAX_VALUE;
        if(Math.abs(direction.x)>1e-6)best=Math.min(best,((direction.x>0?centre.x+half:centre.x-half)-from.x)/direction.x);
        if(Math.abs(direction.z)>1e-6)best=Math.min(best,((direction.z>0?centre.z+half:centre.z-half)-from.z)/direction.z);
        return Math.max(0,best);
    }
}
