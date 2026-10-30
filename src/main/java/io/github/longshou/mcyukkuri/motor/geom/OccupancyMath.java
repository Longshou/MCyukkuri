package io.github.longshou.mcyukkuri.motor.geom;

/** Container occupancy from nine footprint probes as a pure function. */
public final class OccupancyMath {
    /** Occupancy of one body footprint in one container. */
    public enum Result { INSIDE_SUPPORTED, ON_RIM, PARTIAL, OUTSIDE }

    /** Vertical slack to the floor, slack below the rim, and the width of the rim ring outside the floor. */
    public static final double FLOOR_SLACK=.15,RIM_SLACK=.05,RIM_RING=.25;

    private static final int PROBES=8;

    private OccupancyMath(){}

    /** Classifies a footprint of radius {@code r} centred at plane point (x,z) with its feet at {@code y}. */
    public static Result classify(double x,double z,double y,double r,FloorShape floor,double floorY,double rimY,boolean supported){
        boolean centre=floor.contains(x,z);
        int inside=centre?1:0;
        boolean ring=false;
        for(int i=0;i<PROBES;i++){
            double a=i*Math.PI/4,px=x+Math.cos(a)*r,pz=z+Math.sin(a)*r;
            if(floor.contains(px,pz))inside++;
            else if(floor.containsGrown(px,pz,RIM_RING))ring=true;
        }
        if(inside==PROBES+1&&Math.abs(y-floorY)<=FLOOR_SLACK&&supported)return Result.INSIDE_SUPPORTED;
        boolean high=y>=rimY-RIM_SLACK;
        if(high&&(centre||inside>=3||ring))return Result.ON_RIM;
        if(!high&&(centre||inside>=3))return Result.PARTIAL;
        return Result.OUTSIDE;
    }
}
