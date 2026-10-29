package io.github.longshou.mcyukkuri.motor.geom;

/**
 * A floating hull's statics and its damped return to them (refactor WP4a): blocks, masses in block³ of water, degrees and
 * ticks. Roll lifts the +x side and pitch lowers the +z side, as in {@code HostFrame}. No Minecraft types.
 */
public final class Buoyancy {
    /** A yukkuri body relative to water: a ball floats neutral with 56% of its height under (life/WaterDistress), d²(3-2d). */
    public static final double BODY_DENSITY=.56*.56*(3-2*.56);

    private Buoyancy(){}

    /** A rectangular waterplane, width across (x) and length along (z): area and its second moments for roll and pitch. */
    public record Plane(double area,double rollInertia,double pitchInertia){
        public static Plane rect(double width,double length){
            return new Plane(width*length,length*width*width*width/12,width*length*length*length/12);
        }
    }

    /** The mass of a round body inscribed in its {@code width}² × {@code height} box. */
    public static double bodyMass(double width,double height,double density){return Math.PI/6*width*width*height*density;}

    /** How deep the hull sinks: the water it displaces weighs as much as it carries. */
    public static double draft(double mass,Plane plane){return plane.area()<=0?0:Math.max(0,mass)/plane.area();}

    /** The static heel of a load whose moment about the centre is {@code momentX} (mass·blocks toward +x): +x goes down. */
    public static double rollHeel(double momentX,Plane plane){return -Math.toDegrees(Math.atan(momentX/plane.rollInertia()));}

    /** The static heel of a load whose moment is {@code momentZ} toward +z: +z goes down. */
    public static double pitchHeel(double momentZ,Plane plane){return Math.toDegrees(Math.atan(momentZ/plane.pitchInertia()));}

    /** The tilt at which an edge {@code reach} out, {@code freeboard} above the water, goes under; 0 once swamped. */
    public static double flooding(double freeboard,double reach){return freeboard<=0?0:Math.toDegrees(Math.atan2(freeboard,reach));}

    /** How far toward flooding an upright tilt is: 1 when the lowest brim corner meets the water. */
    public static double floodRatio(double roll,double pitch,double floodRoll,double floodPitch){
        if(floodRoll<=0||floodPitch<=0)return Double.POSITIVE_INFINITY;
        return Math.hypot(roll/floodRoll,pitch/floodPitch);
    }

    /** The world-up part of the hull axis after roll and pitch; negative while it floats upside down. */
    public static double up(double roll,double pitch){return Math.cos(Math.toRadians(roll))*Math.cos(Math.toRadians(pitch));}

    /** What a tilt springs back to: the load's heel while upright, flat upside down once past a quarter turn. */
    public static double rest(double angle,double heel){return Math.abs(angle)>90?Math.copySign(180,angle):heel;}

    /** The hull bottom's height over the water surface that floats it: its axis sits {@code draft} deep, or upside down its
     *  brim {@code top} out does. Continuous through the quarter turn. */
    public static double rise(double up,double draft,double top){return -(up>=0?draft:top-draft)*up;}

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
