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

    @Test void runningHopCarriesTheRunBeyondStandingReachAndLandsWithoutAHeightIncrease(){
        double speed=.17;
        double flight=Ballistic.airTicks(ArcSolver.vyFor(HOP_HEIGHT),0);
        double reach=ArcSolver.runningReach(HOP_HEIGHT,HOP_REACH,speed,0);
        assertEquals(HOP_REACH+speed*flight,reach,TOL);
        assertTrue(reach>2,"the running body must not be capped at its standing reach");
        var plan=assertInstanceOf(ArcSolver.Plan.class,
                ArcSolver.running(Vec.ZERO,new Vec(reach,0,0),HOP_HEIGHT,HOP_REACH,new Vec(speed,0,0),OPEN));
        assertEquals(flight,plan.ticks(),TOL);
        assertEquals(HOP_HEIGHT,plan.apex(),TOL);
        assertTrue(plan.vh()>speed,"the spring adds to the retained running speed");
        assertEquals(reach,sweep(plan).getLast().x(),.02);
        assertEquals(0,sweep(plan).getLast().y(),TOL);
    }

    @Test void runningReachDoesNotInventRunSpeedOrAllowOutOfRangeTargets(){
        assertEquals(HOP_REACH,ArcSolver.runningReach(HOP_HEIGHT,HOP_REACH,0,0),TOL);
        var stopped=assertInstanceOf(ArcSolver.Blocked.class,
                ArcSolver.running(Vec.ZERO,new Vec(2,0,0),HOP_HEIGHT,HOP_REACH,Vec.ZERO,OPEN));
        assertEquals(ArcSolver.Kind.TOO_FAR,stopped.kind());
        double reach=ArcSolver.runningReach(HOP_HEIGHT,HOP_REACH,.17,0);
        var far=assertInstanceOf(ArcSolver.Blocked.class,
                ArcSolver.running(Vec.ZERO,new Vec(reach+.1,0,0),HOP_HEIGHT,HOP_REACH,new Vec(.17,0,0),OPEN));
        assertEquals(ArcSolver.Kind.TOO_FAR,far.kind());
    }

    @Test void runningReachStillRejectsAWallAndAnUnreachableLandingHeight(){
        var wall=assertInstanceOf(ArcSolver.Blocked.class,
                ArcSolver.running(Vec.ZERO,new Vec(2,0,0),HOP_HEIGHT,HOP_REACH,new Vec(.17,0,0),gate(.5,1.5,1)));
        assertEquals(ArcSolver.Kind.TOO_HIGH,wall.kind());
        var high=assertInstanceOf(ArcSolver.Blocked.class,
                ArcSolver.running(Vec.ZERO,new Vec(1,1,0),HOP_HEIGHT,HOP_REACH,new Vec(.17,0,0),OPEN));
        assertEquals(ArcSolver.Kind.TOO_HIGH,high.kind());
    }

    @Test void hullLegKeepsTheLowestClearanceAndAGroundHopJumps(){
        var lowest=assertInstanceOf(ArcSolver.Plan.class,ArcSolver.lowest(Vec.ZERO,new Vec(.5,0,0),HOP_HEIGHT,HOP_REACH,1,OPEN));
        assertEquals(Ballistic.GRAVITY,lowest.apex(),TOL);
        var jump=assertInstanceOf(ArcSolver.Plan.class,
                ArcSolver.lowest(Vec.ZERO,new Vec(.5,0,0),HOP_HEIGHT,HOP_REACH,1,OPEN,true,true));
        assertTrue(jump.apex()>.3&&jump.apex()<HOP_HEIGHT);
        assertTrue(Math.ceil(jump.ticks())>=7,"a half-block hop must have a visible ascent and descent");
        assertEquals(1,jump.dir().x(),TOL);
        assertEquals(0,jump.dir().z(),TOL);
        assertTrue(jump.vy()>0&&jump.vh()>0&&jump.ticks()>0);
        List<Vec> pts=sweep(jump);
        assertEquals(.5,pts.get(pts.size()-1).x(),.02);
    }

    @Test void tooHighTargetReportsTheHeightShortfall(){
        var blocked=assertInstanceOf(ArcSolver.Blocked.class,ArcSolver.lowest(Vec.ZERO,new Vec(.5,.9,0),HOP_HEIGHT,HOP_REACH,1,OPEN));
        assertEquals(ArcSolver.Kind.TOO_HIGH,blocked.kind());
        assertEquals(.1,blocked.shortfall(),TOL);
    }

    @Test void reachSlackAdmitsAHopAFewCentimetresOverTheBudget(){
        assertInstanceOf(ArcSolver.Plan.class,ArcSolver.lowest(Vec.ZERO,new Vec(HOP_REACH+.03,0,0),HOP_HEIGHT,HOP_REACH,1,OPEN));
    }

    @Test void tooFarTargetReportsTheReachShortfallBeyondTheSlack(){
        var blocked=assertInstanceOf(ArcSolver.Blocked.class,ArcSolver.lowest(Vec.ZERO,new Vec(1.3,0,0),HOP_HEIGHT,HOP_REACH,1,OPEN));
        assertEquals(ArcSolver.Kind.TOO_FAR,blocked.kind());
        assertEquals(.3-ArcSolver.REACH_SLACK,blocked.shortfall(),TOL);
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

    @Test void aBlockedDepartureCannotBeClearedByProbingHigherArcs(){
        int[] probes={0};
        var wall=gate(-.1,.1,.1);
        var blocked=assertInstanceOf(ArcSolver.Blocked.class,ArcSolver.lowest(Vec.ZERO,new Vec(.5,0,0),HOP_HEIGHT,HOP_REACH,1,
                points->{probes[0]++;return wall.clear(points);}));
        assertEquals(ArcSolver.Kind.TOO_HIGH,blocked.kind());
        assertEquals(1,probes[0],"all trajectories share the same obstructed departure");
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

    @Test void shortBoardingHopNeedsAirDragFromItsTakeoffTick(){
        // afloat_family: a child aims 0.38 blocks ahead and 0.24 up onto the brim.
        var plan=assertInstanceOf(ArcSolver.Plan.class,ArcSolver.lowest(Vec.ZERO,new Vec(.38,.24,0),.51,.63,.4,OPEN));
        var landing=sweep(plan).getLast();
        assertEquals(.38,landing.x(),.005);
        assertEquals(.24,landing.y(),TOL);
        // Vanilla chooses friction before move: a stale onGround applies grass friction to every later horizontal step.
        double grounded=plan.vh()+.6*(landing.x()-plan.vh());
        assertTrue(grounded<.3,"the old grounded takeoff misses the supported brim");
    }

    @Test void apexNeverExceedsTheBudget(){
        for(double dy:new double[]{0,.3,.75}){
            var plan=assertInstanceOf(ArcSolver.Plan.class,ArcSolver.lowest(Vec.ZERO,new Vec(.3,dy,0),HOP_HEIGHT,HOP_REACH,1,OPEN));
            assertTrue(plan.apex()<=HOP_HEIGHT&&plan.apex()>=dy,"dy="+dy);
        }
    }

    @Test void fullReachBankHopUsesTheSameArcAsGround(){
        var from=new Vec(12,63.9,-4);
        var bank=assertInstanceOf(ArcSolver.Plan.class,
                ArcSolver.lowest(from,new Vec(13,64,-4),HOP_HEIGHT,HOP_REACH,1,OPEN,true,true));
        var ground=assertInstanceOf(ArcSolver.Plan.class,
                ArcSolver.lowest(Vec.ZERO,new Vec(1,.1,0),HOP_HEIGHT,HOP_REACH,1,OPEN,true,true));
        assertEquals(ground.ticks(),bank.ticks(),TOL);
        assertEquals(ground.apex(),bank.apex(),TOL);
        assertTrue(Math.ceil(bank.ticks())>=9);
        assertTrue(bank.apex()<=HOP_HEIGHT);
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
