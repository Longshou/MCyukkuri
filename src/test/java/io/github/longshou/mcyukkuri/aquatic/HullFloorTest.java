package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.headwear.HullBounds;
import io.github.longshou.mcyukkuri.headwear.HullProfile;
import io.github.longshou.mcyukkuri.motor.geom.BodyFootprint;
import io.github.longshou.mcyukkuri.motor.geom.FloorSearch;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HullFloorTest {
    static final double UNIT = HullBounds.UNIT, GAP = .02;
    static final HullProfile HULL = HullProfile.of(HullProfile.BASIN);
    static BodyFootprint body(double size, double belly, double yaw) {
        return BodyFootprint.of("marisa", .8*size*(1+.26*belly)*(1+.16*belly)/UNIT,
                .6*size*(1+.26*belly)/UNIT, Math.toRadians(yaw));
    }
    static Vec home(BodyFootprint captain) {
        var p = HullBounds.HOME.scale(1/UNIT);
        return new Vec(p.x(), HULL.support(p.x(),p.z(),captain), p.z());
    }
    static Vec lap(BodyFootprint young) {
        var captain = body(1,0,0);
        return home(captain).add(new Vec(0,0,-FloorSearch.extent(captain,0,-1)-FloorSearch.extent(young,0,1)-GAP));
    }
    static Vec rest(double size, boolean captainAboard) {
        var shape = body(size,0,0); var captain = body(1,0,0);
        return search(shape, size==1?home(shape):lap(shape), captainAboard?List.of(new FloorSearch.Placed(captain,home(captain))):List.of(),
                2*HullBounds.MOUTH/UNIT,true).scale(UNIT);
    }
    static Vec search(BodyFootprint shape, Vec anchor, List<FloorSearch.Placed> others, double within, boolean floorFirst) {
        var spots = FloorSearch.spots(HULL::support,HULL.floor(),.02/UNIT,shape,others,anchor,within,floorFirst,GAP,1);
        assertFalse(spots.isEmpty());
        return spots.getFirst();
    }
    static Vec brim(double size, double bearing) {
        double r=HullBounds.DATA.number("brim_ring.radius")/UNIT,x=r*Math.cos(bearing),z=r*Math.sin(bearing);
        return new Vec(x,HULL.support(x,z,body(size,0,0)),z).scale(UNIT);
    }
    static List<Vec> brimStops(double size) {
        return HullBounds.brimStops().stream().map(p -> brim(size,Math.atan2(p.z(),p.x()))).toList();
    }
    @Test void clearAnchorIsKeptBeforeSearchingRings() {
        var shape=body(.2,0,0); var anchor=new Vec(.1/UNIT,0,-.2/UNIT);
        var p=search(shape,anchor,List.of(),HullBounds.MOUTH/UNIT,true);
        assertEquals(anchor.x(),p.x()); assertEquals(anchor.z(),p.z());
    }
    @Test void youngRestsNearItsLapFormula() {
        var young=body(.38,0,0); var at=rest(.38,true); var anchor=lap(young).scale(UNIT);
        assertEquals(anchor.x(),at.x(),Math.min(young.radius(),FloorSearch.GRID_CELL)*UNIT);
        assertEquals(anchor.z(),at.z(),Math.min(young.radius(),FloorSearch.GRID_CELL)*UNIT);
        assertEquals(HullBounds.FLOOR,at.y(),.02);
    }
    @Test void twoInfantsRestOnFloorAndCanYieldToEveryCaptainYaw() {
        var captain=body(1,0,0); var home=home(captain);
        var riders=new ArrayList<FloorSearch.Placed>();riders.add(new FloorSearch.Placed(captain,home));
        var infant=body(.2,0,0);
        for(int i=0;i<2;i++) {
            var p=search(infant,lap(infant),riders,2*HullBounds.MOUTH/UNIT,true);
            assertEquals(HullBounds.FLOOR,p.y()*UNIT,.002);
            assertTrue(FloorSearch.clear(infant,p,riders,GAP));
            riders.add(new FloorSearch.Placed(infant,p));
        }
        for(int yaw=15;yaw<=180;yaw+=15) {
            var turned=body(1,0,yaw);
            var proposed=new ArrayList<FloorSearch.Placed>();
            proposed.add(new FloorSearch.Placed(turned,new Vec(home.x(),HULL.support(home.x(),home.z(),turned),home.z())));
            for(int i=1;i<riders.size();i++) {
                var p=search(infant,riders.get(i).at(),proposed,HullBounds.RADIUS/UNIT,false);
                assertTrue(FloorSearch.clear(infant,p,proposed,GAP),"yaw="+yaw);
                assertTrue(Double.isFinite(HULL.support(p.x(),p.z(),infant)));
                proposed.add(new FloorSearch.Placed(infant,p));
            }
        }
    }
    @Test void searchNeverExceedsOneTickPushRadius() {
        var b=body(.2,0,0); var start=lap(b); double within=.01/UNIT;
        var spots=FloorSearch.spots(HULL::support,HULL.floor(),.02/UNIT,b,List.of(),start,within,false,GAP,32);
        assertTrue(spots.size()>1);
        for(var p:spots) assertTrue(p.sub(start).hlen()<=within+1e-9);
    }
    @Test void swollenCaptainAndOverhangingBrimUseRealUndersides() {
        assertEquals(-.1475,home(body(1,1,0)).y()*UNIT,.002);
        assertEquals(-.0095,brim(.3,0).y(),.003);
        assertEquals(-.0033,brim(.45,0).y(),.003);
        assertTrue(Double.isNaN(HULL.support((HullBounds.RADIUS+.01)/UNIT,0,body(.2,0,0))));
    }
    @Test void capacityAndLoadIgnoreCaptainAndIncludeEveryYoungPosition() {
        double limit=HullBounds.youngLimit(1);
        assertEquals(.38,limit);
        assertEquals(HullBounds.weight(1.05*limit),HullBounds.capacity(1),1e-12);
        assertEquals(HullBounds.weight(1.05*HullBounds.youngLimit(1.2)),HullBounds.capacity(1.2),1e-12);
        assertEquals(0,HullBounds.loadRatio(1,List.of()));
        double single=HullBounds.loadRatio(1,List.of(limit));
        assertEquals(Math.pow(1/1.05,3),single,1e-12);
        assertTrue(single>HullPresentation.number("load_warning") && single<1);
        assertTrue(HullBounds.loadRatio(1,List.of(limit,limit))>1);
    }
}
