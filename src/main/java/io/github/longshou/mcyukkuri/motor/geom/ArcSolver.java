package io.github.longshou.mcyukkuri.motor.geom;

import java.util.List;

/** Lowest hop arc that clears the sweep and stays inside the hop budget; pure Java. */
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
    /** Horizontal slack on the reach budget: a planned stand and the live hull differ by a few centimetres, which never shows on screen,
     *  so a hop that misses the budget by less than this is admitted and still lands on its sampled point. */
    public static final double REACH_SLACK=.05;

    private ArcSolver(){}

    /** A running ground hop uses the body's full spring, retaining the run's travel budget. */
    public static double flightTicks(double hopHeight,double rise){
        return Ballistic.airTicks(vyFor(hopHeight),rise);
    }

    public static double runningReach(double hopHeight,double hopReach,double groundSpeed,double rise){
        return hopReach+groundSpeed*flightTicks(hopHeight,rise);
    }

    /** The takeoff adds the spring to the running body's momentum. The launch speed compensates air drag,
     * so its reachable distance is ground speed times flight time plus the standing hop reach. */
    public static Result running(Vec from,Vec to,double hopHeight,double hopReach,Vec groundMotion,Clearance clearance){
        Vec delta=to.sub(from);
        double distance=delta.hlen(),rise=delta.y();
        if(rise>hopHeight)return new Blocked(Kind.TOO_HIGH,rise-hopHeight);
        double reach=runningReach(hopHeight,hopReach,groundMotion.hlen(),rise);
        if(distance>reach+REACH_SLACK)return new Blocked(Kind.TOO_FAR,distance-reach-REACH_SLACK);
        Vec direction=distance<1e-9?new Vec(1,0,0):new Vec(delta.x()/distance,0,delta.z()/distance);
        Plan plan=attempt(hopHeight,rise,distance,direction,clearance);
        return plan==null?new Blocked(Kind.TOO_HIGH,REPORT_RISE):plan;
    }

    /** Solves the lowest arc from {@code from} to {@code to} for the given hop budget and sweep check. */
    public static Result lowest(Vec from,Vec to,double hopHeight,double hopReach,double scale,Clearance clearance){
        return lowest(from,to,hopHeight,hopReach,scale,clearance,false,true);
    }

    /**
     * As {@link #lowest(Vec,Vec,double,double,double,Clearance)}. {@code visibleRise} spends more of the height budget the
     * further the hop goes, so a hop that ends on the ground reads as a jump; it never exceeds it. {@code diagnose} also probes
     * above the budget for the rise a refused arc would need, which is a report only: every admitted arc is admitted either way.
     */
    public static Result lowest(Vec from,Vec to,double hopHeight,double hopReach,double scale,Clearance clearance,
                                boolean visibleRise,boolean diagnose){
        Vec d=to.sub(from);
        double dx=d.hlen(),dy=d.y();
        if(dx>hopReach+REACH_SLACK)return new Blocked(Kind.TOO_FAR,dx-hopReach-REACH_SLACK);
        if(dy>hopHeight)return new Blocked(Kind.TOO_HIGH,dy-hopHeight);
        // Every arc contains the departure point; raising its apex cannot clear a blocked start.
        if(!clearance.clear(List.of(Vec.ZERO)))return new Blocked(Kind.TOO_HIGH,REPORT_RISE);
        Vec dir=dx<1e-9?new Vec(1,0,0):new Vec(d.x()/dx,0,d.z()/dx);
        // A hop that ends on the ground spends height with distance; a hull leg keeps the lowest clearance that works.
        double rise=Math.max(dy,0)+MIN_CLEAR*scale;
        if(visibleRise)rise=Math.max(rise,hopHeight*dx/(hopReach+REACH_SLACK));
        for(double c=rise;;c+=STEP){
            double apex=Math.min(c,hopHeight);
            Plan plan=attempt(apex,dy,dx,dir,clearance);
            if(plan!=null)return plan;
            if(apex>=hopHeight)break;
        }
        if(diagnose)for(double extra=STEP;extra<=REPORT_RISE;extra+=STEP)if(attempt(hopHeight+extra,dy,dx,dir,clearance)!=null)return new Blocked(Kind.TOO_HIGH,extra);
        return new Blocked(Kind.TOO_HIGH,REPORT_RISE);
    }

    private static Plan attempt(double apex,double dy,double dx,Vec dir,Clearance clearance){
        double vy=vyFor(apex),n=Ballistic.airTicks(vy,dy);
        if(n<=0||n>=Ballistic.MAX_TICKS)return null;
        double vh=Ballistic.speedFor(dx,n);
        return clearance.clear(Ballistic.samples(new Vec(vh*dir.x(),vy,vh*dir.z()),n))?new Plan(vy,vh,dir,n,apex):null;
    }

    /** Smallest upward speed whose discrete trajectory peaks at {@code apex}, bisected on {@link Ballistic#peak}, so a one-impulse gesture reads the same arc model as a solved hop. */
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
