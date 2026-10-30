package io.github.longshou.mcyukkuri.motor.geom;

/** Plain 3D vector so geom/ stays pure Java and unit testable without Minecraft. */
public record Vec(double x,double y,double z){
    public static final Vec ZERO=new Vec(0,0,0);

    public Vec add(Vec o){return new Vec(x+o.x,y+o.y,z+o.z);}

    public Vec sub(Vec o){return new Vec(x-o.x,y-o.y,z-o.z);}

    public Vec scale(double f){return new Vec(x*f,y*f,z*f);}

    /** Length in the XZ plane. */
    public double hlen(){return Math.sqrt(x*x+z*z);}
}
