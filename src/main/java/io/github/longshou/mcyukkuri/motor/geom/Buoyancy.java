package io.github.longshou.mcyukkuri.motor.geom;

/** A floating hull's statics and its damped return to them: blocks, masses in block³ of water, degrees and ticks. Roll lifts +x, pitch lowers +z. No Minecraft types. */
public final class Buoyancy {
    /** A yukkuri body relative to water: a ball floats neutral with 56% of its height under ({@link #bodyLift}), d²(3-2d). */
    public static final double BODY_DENSITY=.56*.56*(3-2*.56);
    /** One block is one metre; gravity times dt² at 20 ticks/second converts torque/inertia into radians/tick². */
    private static final double ANGULAR_STEP=9.81/(20*20);

    private Buoyancy(){}

    /** Waterplane area and central second moments in the yaw-free world frame. */
    public record Plane(double area,double rollInertia,double pitchInertia,double cross) {}

    /** Mass, first moments and inertias about the keel, in blocks and water-volume mass units. */
    public record Load(double mass,Vec moment,double rollInertia,double pitchInertia) {
        public static final Load ZERO=new Load(0,Vec.ZERO,0,0);
        public Load add(double weight,Vec at) {
            return new Load(mass+weight,moment.add(at.scale(weight)),rollInertia+weight*(at.x()*at.x()+at.y()*at.y()),
                    pitchInertia+weight*(at.z()*at.z()+at.y()*at.y()));
        }
        public Load add(Load other) { return new Load(mass+other.mass,moment.add(other.moment),rollInertia+other.rollInertia,pitchInertia+other.pitchInertia); }
        public Load shifted(Vec by) {
            return new Load(mass,moment.add(by.scale(mass)),rollInertia+2*(by.x()*moment.x()+by.y()*moment.y())+mass*(by.x()*by.x()+by.y()*by.y()),
                    pitchInertia+2*(by.z()*moment.z()+by.y()*moment.y())+mass*(by.z()*by.z()+by.y()*by.y()));
        }
    }

    /** A body's lift per tick at this immersed share and vertical speed: neutral near 56% against water gravity, damped; effort and fatigue never take it away. */
    public static double bodyLift(double fraction,double verticalVelocity){
        return Math.clamp(.005+.045*(fraction-.56)-.08*verticalVelocity,-.018,.032);
    }

    /** The mass of a round body inscribed in its {@code width}² × {@code height} box. */
    public static double bodyMass(double width,double height,double density){return Math.PI/6*width*width*height*density;}

    /** Weight borne by a grip as a round body emerges: the surrounding water still supports its submerged volume. */
    public static double supported(double immersed,double overDryHull) {
        double d=Math.clamp(immersed,0,1);
        return Math.max(0,1-d*d*(3-2*d)*(1-overDryHull)/BODY_DENSITY);
    }
    /** Floodwater takes over support smoothly across the body's height as the rim submerges. */
    public static double covered(double overHull,double clearance,double height) {
        double wet=Math.clamp(-clearance/height,0,1);
        return overHull*(1-wet*wet*(3-2*wet));
    }
    /** Implicit water damping, proportional to the local restoring stiffness; no angular-rate clamp. */
    public static double angular(double moment,double inertia,double planeInertia,double velocity,double damping) {
        return (velocity+Math.toDegrees(ANGULAR_STEP*moment/inertia))/(1+damping+2*Math.sqrt(ANGULAR_STEP*planeInertia/inertia));
    }

    /** Share of a round body's footprint inside the gripped edge's tangent, signed centre distance in radii. */
    public static double overEdge(double inward) {
        double d=Math.clamp(inward,-1,1);
        return (Math.acos(-d)+d*Math.sqrt(1-d*d))/Math.PI;
    }

    /** HostFrame's roll(Z) . pitch(X), without yaw. */
    public static Vec rotate(Vec at,double roll,double pitch) {
        double r=Math.toRadians(roll),p=Math.toRadians(pitch),y=at.y()*Math.cos(p)-at.z()*Math.sin(p);
        return new Vec(at.x()*Math.cos(r)-y*Math.sin(r),at.x()*Math.sin(r)+y*Math.cos(r),at.y()*Math.sin(p)+at.z()*Math.cos(p));
    }

    /** The world-up part of the hull axis after roll and pitch; negative while it floats upside down. */
    public static double up(double roll,double pitch){return Math.cos(Math.toRadians(roll))*Math.cos(Math.toRadians(pitch));}

    /** What a tilt springs back to: the load's heel while upright, flat upside down once past a quarter turn. */
    public static double rest(double angle,double heel){return Math.abs(angle)>90?Math.copySign(180,angle):heel;}

    /** One tick of a damped spring: the new velocity for an offset {@code toRest} from where it rests. */
    public static double spring(double toRest,double velocity,double stiffness,double damping){
        return velocity*(1-damping)+stiffness*toRest;
    }

    /** An angle wrapped into (-180, 180]. */
    public static double wrap(double angle){
        double a=angle%360;
        if(a>180)a-=360;
        if(a<=-180)a+=360;
        return a;
    }
}
