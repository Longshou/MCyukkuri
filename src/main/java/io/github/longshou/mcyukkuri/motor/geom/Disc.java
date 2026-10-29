package io.github.longshou.mcyukkuri.motor.geom;

/** A footprint circle in the plane, centre (x,z) and radius r, over the heights it takes from its bottom to its top (a body from
 *  its feet up its own height, a stored portion up its pile). Two footprints conflict only where both the circles and the
 *  heights overlap, so what lies low in a hollow can sit under a body standing higher over it. */
public record Disc(double x,double z,double r,double bottom,double top){
    /** A footprint that takes every height: it meets any other wherever the circles overlap. */
    public Disc(double x,double z,double r){this(x,z,r,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY);}
    /** Whether the two footprints overlap once {@code slack} is shaved off both radii; footprints at different heights never do. */
    public boolean overlaps(Disc o,double slack){
        double d=Math.hypot(x-o.x,z-o.z);
        return level(bottom,top,o.bottom,o.top)&&d<r+o.r-slack;
    }
    /** Two height spans share some height (touching ends do not). */
    public static boolean level(double bottomA,double topA,double bottomB,double topB){return bottomA<topB&&bottomB<topA;}
}
