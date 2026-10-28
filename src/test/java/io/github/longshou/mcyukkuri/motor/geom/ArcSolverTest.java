package io.github.longshou.mcyukkuri.motor.geom;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArcSolverTest {
    private static final double HOP_HEIGHT=.80,HOP_REACH=1,TOL=1e-6;
    private static final ArcSolver.Clearance OPEN=points->true;

    /** Sweep that rejects every sample below {@code top} while it is over the X range {@code lo..hi}. */
    private static ArcSolver.Clearance gate(double lo,double hi,double top){
        return points->{
            for(Vec p:points)if(p.x()>=lo&&p.x()<=hi&&p.y()<top)return false;
            return true;
        };
    }

    private static List<Vec> sweep(ArcSolver.Plan p){
        return Ballistic.samples(new Vec(p.vh()*p.dir().x(),p.vy(),p.vh()*p.dir().z()),p.ticks());
    }

    @Test void flatGroundGivesTheLowestArc(){
        var plan=assertInstanceOf(ArcSolver.Plan.class,ArcSolver.lowest(Vec.ZERO,new Vec(.5,0,0),HOP_HEIGHT,HOP_REACH,1,OPEN));
        assertEquals(Ballistic.GRAVITY,plan.apex(),TOL);
        assertEquals(1,plan.dir().x(),TOL);
        assertEquals(0,plan.dir().z(),TOL);
        assertTrue(plan.vy()>0&&plan.vh()>0&&plan.ticks()>0);
        List<Vec> pts=sweep(plan);
        assertEquals(.5,pts.get(pts.size()-1).x(),.02);
    }

    @Test void tooHighTargetReportsTheHeightShortfall(){
        var blocked=assertInstanceOf(ArcSolver.Blocked.class,ArcSolver.lowest(Vec.ZERO,new Vec(.5,.9,0),HOP_HEIGHT,HOP_REACH,1,OPEN));
        assertEquals(ArcSolver.Kind.TOO_HIGH,blocked.kind());
        assertEquals(.1,blocked.shortfall(),TOL);
    }

    @Test void tooFarTargetReportsTheReachShortfall(){
        var blocked=assertInstanceOf(ArcSolver.Blocked.class,ArcSolver.lowest(Vec.ZERO,new Vec(1.3,0,0),HOP_HEIGHT,HOP_REACH,1,OPEN));
        assertEquals(ArcSolver.Kind.TOO_FAR,blocked.kind());
        assertEquals(.3,blocked.shortfall(),TOL);
    }

    @Test void obstacleRaisesTheApex(){
        ArcSolver.Clearance gate=gate(.15,.40,.30);
        var plan=assertInstanceOf(ArcSolver.Plan.class,ArcSolver.lowest(Vec.ZERO,new Vec(.5,0,0),HOP_HEIGHT,HOP_REACH,1,gate));
        assertTrue(plan.apex()>Ballistic.GRAVITY+.05,"apex="+plan.apex());
        assertTrue(plan.apex()<=HOP_HEIGHT);
        assertTrue(gate.clear(sweep(plan)));
    }

    @Test void obstacleAboveTheBudgetIsTooHigh(){
        var blocked=assertInstanceOf(ArcSolver.Blocked.class,ArcSolver.lowest(Vec.ZERO,new Vec(.5,0,0),HOP_HEIGHT,HOP_REACH,1,gate(.15,.40,.90)));
        assertEquals(ArcSolver.Kind.TOO_HIGH,blocked.kind());
        assertTrue(blocked.shortfall()>.05,"shortfall="+blocked.shortfall());
    }

    @Test void planPeaksAtItsApexAndArrivesAtTheLedge(){
        var plan=assertInstanceOf(ArcSolver.Plan.class,ArcSolver.lowest(Vec.ZERO,new Vec(.4,.3,0),HOP_HEIGHT,HOP_REACH,1,OPEN));
        List<Vec> pts=sweep(plan);
        double peak=0;
        for(Vec p:pts)peak=Math.max(peak,p.y());
        assertEquals(plan.apex(),peak,TOL);
        assertEquals(.3,pts.get(pts.size()-1).y(),TOL);
        assertEquals(.4,pts.get(pts.size()-1).x(),.02);
    }

    @Test void apexNeverExceedsTheBudget(){
        for(double dy:new double[]{0,.3,.75}){
            var plan=assertInstanceOf(ArcSolver.Plan.class,ArcSolver.lowest(Vec.ZERO,new Vec(.3,dy,0),HOP_HEIGHT,HOP_REACH,1,OPEN));
            assertTrue(plan.apex()<=HOP_HEIGHT&&plan.apex()>=dy,"dy="+dy);
        }
    }

    /** {@link ArcSolver#vyFor} is what a one-impulse gesture uses: the smallest speed whose discrete peak reaches the apex. */
    @Test void vyForIsTheSmallestSpeedThatReachesTheApex(){
        for(double apex:new double[]{.05,.25,.80}){
            double vy=ArcSolver.vyFor(apex);
            assertTrue(Ballistic.peak(vy)>=apex-1e-9,"apex="+apex+" peak="+Ballistic.peak(vy));
            assertTrue(Ballistic.peak(vy)<=apex+.02,"apex="+apex+" peak="+Ballistic.peak(vy));
            assertTrue(Ballistic.peak(vy-1e-6)<apex,"vy="+vy+" is not the smallest speed for apex="+apex);
        }
        assertEquals(0,ArcSolver.vyFor(0),0);
    }
}
