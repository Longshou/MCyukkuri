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

    /** A nominal push along a unit horizontal heading, reaching the aim's height/distance in ticks; no backward target. */
    public static Vec forward(Vec delta,Vec heading,int ticks){
        if(delta.x()*heading.x()+delta.z()*heading.z()<=0)return null;
        double sum=(1-Math.pow(VERTICAL_DRAG,ticks))/(1-VERTICAL_DRAG),fall=GRAVITY*VERTICAL_DRAG/(1-VERTICAL_DRAG);
        double lift=(delta.y()+fall*(ticks-sum))/sum,speed=speedFor(delta.hlen(),ticks);
        return new Vec(heading.x()*speed,lift,heading.z()*speed);
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

    /** A dropped item's own air step (vanilla ItemEntity): v.y -= ITEM_GRAVITY, then pos += v, then v *= ITEM_DRAG on every axis. */
    public static final double ITEM_GRAVITY=.04,ITEM_DRAG=.98;
    /** The slowest item push tried and the step between tries, in blocks per tick; a push the body cannot give is no plan. */
    static final double PUSH_STEP=.005,MAX_PUSH=.4;

    /**
     * Per-tick positions from the origin of a dropped item launched at {@code v0} until it falls through relative height {@code dy}
     * (that crossing interpolated as the last point), start included; empty if it is still above {@code dy} after MAX_TICKS.
     */
    public static List<Vec> itemPath(Vec v0,double dy){
        var out=new ArrayList<Vec>();
        Vec p=Vec.ZERO,v=v0;
        out.add(p);
        for(int n=1;n<=MAX_TICKS;n++){
            v=new Vec(v.x(),v.y()-ITEM_GRAVITY,v.z());
            Vec next=p.add(v);
            if(next.y()<=dy&&v.y()<0){
                double q=p.y()<=dy?0:(p.y()-dy)/(p.y()-next.y());
                out.add(p.add(v.scale(q)));
                return out;
            }
            out.add(p=next);
            v=v.scale(ITEM_DRAG);
        }
        return List.of();
    }

    /**
     * The slowest push along the unit {@code dir} (from none up to MAX_PUSH) whose dropped-item path down to relative height {@code dy}
     * passes {@code path}; null when none does. The item then really flies that path: the same vanilla step, nothing steers it.
     */
    public static Vec itemPush(Vec dir,double dy,ArcSolver.Clearance path){
        for(int i=0;i*PUSH_STEP<=MAX_PUSH+1e-9;i++){
            Vec v0=dir.scale(i*PUSH_STEP);
            var points=itemPath(v0,dy);
            if(!points.isEmpty()&&path.clear(points))return v0;
        }
        return null;
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
