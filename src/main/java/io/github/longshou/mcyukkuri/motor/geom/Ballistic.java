package io.github.longshou.mcyukkuri.motor.geom;

import java.util.ArrayList;
import java.util.List;

/** Vanilla discrete air motion (per tick pos += v, then v.y = (v.y - .08) * .98, v.xz *= .91); no Minecraft types. */
public final class Ballistic {
    /** Vanilla gravity per tick, vertical drag and air horizontal drag. */
    public static final double GRAVITY=.08,VERTICAL_DRAG=.98,AIR_DRAG=.91;
    /** Longest air time considered, in ticks, and the height tolerance of a grazing crossing. */
    static final int MAX_TICKS=80;
    static final double TOUCH=1e-9;

    private Ballistic(){}

    /** Ticks from a launch at {@code vy} until the fall passes relative height {@code dy}, interpolated inside the last tick. */
    public static double airTicks(double vy,double dy){
        if(vy<=0)return 0;
        double y=0,v=vy;
        for(int n=1;n<=MAX_TICKS;n++){
            double prev=y;
            y+=v;
            v=(v-GRAVITY)*VERTICAL_DRAG;
            if(prev>=dy-TOUCH&&y<=dy)return n-1+Math.max(0,Math.min(1,(prev-dy)/(prev-y)));
        }
        return MAX_TICKS;
    }

    /** Horizontal speed that covers {@code dx} in {@code n} ticks against air drag; {@code n} may be fractional. */
    public static double speedFor(double dx,double n){
        return dx*(1-AIR_DRAG)/(1-Math.pow(AIR_DRAG,n));
    }

    /** Per-tick positions from the origin for launch velocity {@code v0} over {@code n} ticks, start included, landing interpolated. */
    public static List<Vec> samples(Vec v0,double n){
        var out=new ArrayList<Vec>();
        Vec p=Vec.ZERO,v=v0;
        out.add(p);
        int whole=(int)Math.floor(n);
        for(int i=0;i<whole;i++){
            p=p.add(v);
            v=new Vec(v.x()*AIR_DRAG,(v.y()-GRAVITY)*VERTICAL_DRAG,v.z()*AIR_DRAG);
            out.add(p);
        }
        double frac=n-whole;
        if(frac>1e-9)out.add(out.get(out.size()-1).add(v.scale(frac)));
        return out;
    }

    /** Highest point of the discrete trajectory launched upward at {@code vy}. */
    static double peak(double vy){
        double y=0,v=vy,p=0;
        for(int n=1;n<=MAX_TICKS;n++){
            y+=v;
            v=(v-GRAVITY)*VERTICAL_DRAG;
            if(y>p)p=y;
            if(y<=0)break;
        }
        return p;
    }
}
