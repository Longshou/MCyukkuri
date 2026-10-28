package io.github.longshou.mcyukkuri.motor.geom;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BallisticTest {
    /** BirthLaunch's launch math copied here so the test needs no Minecraft classes. */
    private static final double GRAVITY=.08,VERTICAL_DRAG=.98,AIR_DRAG=.91;
    private static final double[] SPEEDS={.22,.3,.42,.6};

    private static int birthLaunchAirTicks(double vy){
        double y=0,v=vy;int n=0;
        do{y+=v;v=(v-GRAVITY)*VERTICAL_DRAG;n++;}while(y>0&&n<80);
        return n;
    }

    private static double birthLaunchSpeedFor(double distance,double vy){
        return distance*(1-AIR_DRAG)/(1-Math.pow(AIR_DRAG,birthLaunchAirTicks(vy)));
    }

    /** Tick-by-tick integration of the vanilla model; returns the horizontal distance where the fall passes {@code dy}. */
    private static double discreteRange(double vh,double vy,double dy){
        double y=0,x=0,v=vy,vx=vh;
        for(int n=1;n<80;n++){
            double prevY=y,prevX=x;
            x+=vx;
            y+=v;
            vx*=AIR_DRAG;
            v=(v-GRAVITY)*VERTICAL_DRAG;
            if(prevY>=dy&&y<=dy)return prevX+(x-prevX)*(prevY-dy)/(prevY-y);
        }
        return x;
    }

    @Test void airTicksMatchesBirthLaunchAtGroundLevel(){
        for(double vy:SPEEDS){
            int reference=birthLaunchAirTicks(vy);
            double ticks=Ballistic.airTicks(vy,0);
            // BirthLaunch counts whole ticks; the solver refines the landing inside the last tick.
            assertEquals(reference,(int)Math.ceil(ticks),"vy="+vy);
            assertTrue(ticks>reference-1&&ticks<=reference,"vy="+vy);
        }
    }

    @Test void speedForMatchesBirthLaunch(){
        for(double vy:SPEEDS)for(double dx:new double[]{.3,.5,1})
            assertEquals(birthLaunchSpeedFor(dx,vy),Ballistic.speedFor(dx,birthLaunchAirTicks(vy)),1e-12);
    }

    @Test void discreteRangeMatchesSolvedSpeed(){
        for(double vy:new double[]{.08,.22,.42})for(double dx:new double[]{.3,.5,.9}){
            double ticks=Ballistic.airTicks(vy,0);
            double range=discreteRange(Ballistic.speedFor(dx,ticks),vy,0);
            assertEquals(dx,range,.02,"vy="+vy+" dx="+dx);
        }
    }

    @Test void samplesFollowTheDiscreteModel(){
        List<Vec> whole=Ballistic.samples(new Vec(.1,.2,0),3);
        assertEquals(4,whole.size());
        assertEquals(Vec.ZERO,whole.get(0));
        assertEquals(.1,whole.get(1).x(),1e-12);
        assertEquals(.2,whole.get(1).y(),1e-12);
        assertEquals(.191,whole.get(2).x(),1e-12);
        assertEquals(.3176,whole.get(2).y(),1e-12);
        assertEquals(.27381,whole.get(3).x(),1e-12);
        assertEquals(.354448,whole.get(3).y(),1e-12);
        List<Vec> half=Ballistic.samples(new Vec(.1,.2,0),2.5);
        assertEquals(4,half.size());
        assertEquals(.232405,half.get(3).x(),1e-12);
        assertEquals(.336024,half.get(3).y(),1e-12);
        assertEquals(1,Ballistic.samples(new Vec(.1,.2,0),0).size());
    }

    @Test void airTicksLandsOnTheRequestedHeight(){
        for(double dy:new double[]{.1,.3,.5}){
            double vy=.42,ticks=Ballistic.airTicks(vy,dy);
            List<Vec> pts=Ballistic.samples(new Vec(0,vy,0),ticks);
            assertEquals(dy,pts.get(pts.size()-1).y(),1e-9,"dy="+dy);
            assertNotEquals(Math.floor(ticks),ticks,"dy="+dy);
            assertTrue(ticks>1&&ticks<80);
        }
    }
}
