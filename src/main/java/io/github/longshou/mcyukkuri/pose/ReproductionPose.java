package io.github.longshou.mcyukkuri.pose;

import org.joml.Matrix4f;

/** Shared contact presentation for the running game and deterministic pose previews. */
public final class ReproductionPose {
    public static final int EXTEND_TICKS=5, RETRACT_TICKS=13;
    public static final float GROUND_MARGIN=.006f;
    // Rest clearance for the authored pushes, and the idle/walking clearance for brim and bow.
    private static final double CONTACT_CLEARANCE=.8/16, MAX_OFFSET=.25, ACCESSORY_CLEARANCE=.4/16;

    private ReproductionPose() {}

    public static double close(double distance,double rest,double posed) {
        return Math.clamp((distance-Math.max(rest+CONTACT_CLEARANCE,posed))/2,0,MAX_OFFSET);
    }

    public static double ornamentLimit(double distance,double reach) {
        return (distance-reach-ACCESSORY_CLEARANCE)/2;
    }

    public static double mix(double ticks,boolean extending) {
        double q=Math.clamp(ticks/(extending?EXTEND_TICKS:RETRACT_TICKS),0,1);
        return q*q*(3-2*q);
    }

    public static float extend(float ticks) {
        float q=Math.clamp(ticks/EXTEND_TICKS,0,1);
        return q*q*(3-2*q);
    }

    public record MamuPlacement(boolean turn,float x,float y,float z) {
        public Matrix4f transform(Matrix4f root) {
            var placement=new Matrix4f(root).translate(x,y,z);
            if(turn)placement.scale(-1,1,-1);
            return placement.mul(new Matrix4f(root).invert());
        }
    }

    public static MamuPlacement rearPlacement(boolean turn,float x,float y,float outer,float proud,
                                               float[][] rear,float target) {
        float height=Math.clamp(target,rear[0][0],rear[rear.length-1][0]);
        int i=0;
        while(i+2<rear.length&&height>rear[i+1][0])i++;
        float[] a=rear[i],b=rear[i+1];
        float depth=a[1]+(b[1]-a[1])*((height-a[0])/(b[0]-a[0]));
        return new MamuPlacement(turn,-x,height-y,depth+proud-outer);
    }
}
