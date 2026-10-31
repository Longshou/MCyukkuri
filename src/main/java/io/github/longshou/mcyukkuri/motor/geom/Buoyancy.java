package io.github.longshou.mcyukkuri.motor.geom;

/** A floating hull's statics and its damped return to them: blocks, masses in block³ of water, degrees and ticks. Roll lifts +x, pitch lowers +z. No Minecraft types. */
public final class Buoyancy {
    /** A yukkuri body relative to water: a ball floats neutral with 56% of its height under ({@link #bodyLift}), d²(3-2d). */
    public static final double BODY_DENSITY=.56*.56*(3-2*.56);

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
    }

    /** A body's lift per tick at this immersed share and vertical speed: neutral near 56% against water gravity, damped; effort and fatigue never take it away. */
    public static double bodyLift(double fraction,double verticalVelocity){
        return Math.clamp(.005+.045*(fraction-.56)-.08*verticalVelocity,-.018,.032);
    }

    /** The mass of a round body inscribed in its {@code width}² × {@code height} box. */
    public static double bodyMass(double width,double height,double density){return Math.PI/6*width*width*height*density;}

    /** Weight borne by a grip as a round body emerges: the surrounding water still supports its submerged volume. */
    public static double supported(double immersed) {
        return supported(immersed,0);
    }
    public static double supported(double immersed,double overDryHull) {
        double d=Math.clamp(immersed,0,1);
        return Math.max(0,1-d*d*(3-2*d)*(1-overDryHull)/BODY_DENSITY);
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
