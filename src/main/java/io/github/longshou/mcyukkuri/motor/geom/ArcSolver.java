package io.github.longshou.mcyukkuri.motor.geom;

import java.util.List;

/** Lowest hop arc that clears the sweep and stays inside the hop budget (spec 8.4); pure Java. */
public final class ArcSolver {
    /** Sweep check for one candidate arc; true when every sampled point is clear. */
    public interface Clearance { boolean clear(List<Vec> points); }

    /** Result of {@link #lowest}: a {@link Plan} or a {@link Blocked}. */
    public sealed interface Result permits Plan,Blocked {}

    /** One solved arc: vertical speed, horizontal speed, unit XZ direction, air time and apex above the start. */
    public record Plan(double vy,double vh,Vec dir,double ticks,double apex) implements Result {}

    /** No arc inside the budget: which budget was exceeded and by how much. */
    public record Blocked(Kind kind,double shortfall) implements Result {}

    /** Budget that blocked the arc; motor/ maps these onto its own Reason. */
    public enum Kind { TOO_FAR, TOO_HIGH }

    /** Apex step, how far above the budget the apex is probed only to report the shortfall, and the lowest clearance per unit body scale. */
    private static final double STEP=.05,REPORT_RISE=1,MIN_CLEAR=.08;

    private ArcSolver(){}

    /** Solves the lowest arc from {@code from} to {@code to} for the given hop budget and sweep check. */
    public static Result lowest(Vec from,Vec to,double hopHeight,double hopReach,double scale,Clearance clearance){
        Vec d=to.sub(from);
        double dx=d.hlen(),dy=d.y();
        if(dx>hopReach)return new Blocked(Kind.TOO_FAR,dx-hopReach);
        if(dy>hopHeight)return new Blocked(Kind.TOO_HIGH,dy-hopHeight);
        Vec dir=dx<1e-9?new Vec(1,0,0):new Vec(d.x()/dx,0,d.z()/dx);
        for(double c=Math.max(dy,0)+MIN_CLEAR*scale;;c+=STEP){
            double apex=Math.min(c,hopHeight);
            Plan plan=attempt(apex,dy,dx,dir,clearance);
            if(plan!=null)return plan;
            if(apex>=hopHeight)break;
        }
        for(double rise=STEP;rise<=REPORT_RISE;rise+=STEP)if(attempt(hopHeight+rise,dy,dx,dir,clearance)!=null)return new Blocked(Kind.TOO_HIGH,rise);
        return new Blocked(Kind.TOO_HIGH,REPORT_RISE);
    }

    private static Plan attempt(double apex,double dy,double dx,Vec dir,Clearance clearance){
        double vy=vyFor(apex),n=Ballistic.airTicks(vy,dy);
        if(n<=0||n>=Ballistic.MAX_TICKS)return null;
        double vh=Ballistic.speedFor(dx,n);
        return clearance.clear(Ballistic.samples(new Vec(vh*dir.x(),vy,vh*dir.z()),n))?new Plan(vy,vh,dir,n,apex):null;
    }

    /**
     * Smallest upward speed whose discrete trajectory peaks at {@code apex}, bisected on {@link Ballistic#peak}. Pure, so a
     * one-impulse gesture (spec 9.4 HOP_SMALL) reads the same arc model as a solved hop instead of naming a speed.
     */
    public static double vyFor(double apex){
        if(apex<=0)return 0;
        double lo=0,hi=Math.max(apex,.01);
        while(Ballistic.peak(hi)<apex)hi*=2;
        for(int i=0;i<64;i++){
            double mid=(lo+hi)/2;
            if(Ballistic.peak(mid)<apex)lo=mid;else hi=mid;
        }
        return (lo+hi)/2;
    }
}
