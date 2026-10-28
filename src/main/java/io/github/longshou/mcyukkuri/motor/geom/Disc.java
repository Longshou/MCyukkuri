package io.github.longshou.mcyukkuri.motor.geom;

/** A footprint circle in the plane: centre (x,z) and radius r. */
public record Disc(double x,double z,double r){
    /** Whether the two footprints overlap once {@code slack} is shaved off both radii. */
    public boolean overlaps(Disc o,double slack){
        double d=Math.hypot(x-o.x,z-o.z);
        return d<r+o.r-slack;
    }
}
