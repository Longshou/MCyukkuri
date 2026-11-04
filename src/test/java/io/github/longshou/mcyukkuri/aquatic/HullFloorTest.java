package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.headwear.HullBounds;
import io.github.longshou.mcyukkuri.headwear.HullProfile;
import io.github.longshou.mcyukkuri.motor.geom.BodyFootprint;
import io.github.longshou.mcyukkuri.motor.geom.FloorSearch;
import io.github.longshou.mcyukkuri.motor.geom.FloorShape;
import io.github.longshou.mcyukkuri.motor.geom.OccupancyMath;
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
    @Test void supportedHollowRidersRestAwayFromTheLapAndOnTheWall() {
        var infant = body(.2,0,0);
        double margin = .02 / UNIT;
        for (double x : new double[]{0, HullBounds.MOUTH - .01}) {
            var at = new Vec(x / UNIT, HULL.support(x / UNIT,0,infant), 0);
            assertTrue(HULL.resting(at,infant,margin));
            assertFalse(HULL.resting(at.add(new Vec(0,2*margin,0)),infant,margin));
            if (x > 0) {
                // This supported wall point was previously misclassified as a reason to hop back in.
                assertNotEquals(OccupancyMath.Result.INSIDE_SUPPORTED,
                        OccupancyMath.classify(x,0,at.y()*UNIT,.10,new FloorShape.Circle(HullBounds.RADIUS),
                                HullBounds.FLOOR,HullBounds.BRIM,true));
            }
        }
        var swollen = body(1,1,0);
        assertTrue(HULL.resting(home(swollen),swollen,margin));
        assertFalse(HULL.resting(brim(.2,0).scale(1/UNIT),infant,margin));
    }
    @Test void youngRestsNearItsLapFormula() {
        var young=body(.38,0,0); var at=rest(.38,true); var anchor=lap(young).scale(UNIT);
        double step = Math.min(young.radius(),FloorSearch.GRID_CELL)*UNIT;
        // Independent design acceptance point; the authored grid and room gap explain its tolerance.
        assertEquals(0,at.x(),step);
        assertEquals(-.249,at.y(),step);
        assertEquals(-.40,at.z(),step);
        assertEquals(anchor.x(),at.x(),step);
        assertEquals(anchor.z(),at.z(),step);
    }
    @Test void twoInfantsRestOnFloorAndCanYieldToEveryCaptainYaw() {
        var captain=body(1,0,0); var home=home(captain);
        var riders=new ArrayList<FloorSearch.Placed>();riders.add(new FloorSearch.Placed(captain,home));
        var infant=body(.2,0,0);
        for(int i=0;i<2;i++) {
            var p=search(infant,lap(infant),riders,2*HullBounds.MOUTH/UNIT,true);
            assertEquals(HullBounds.FLOOR,p.y()*UNIT,.002);
            assertTrue(p.z()*UNIT < -.30, "infant must start in front of the yaw-0 captain");
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
    @Test void brimStopsKeepTheSameAuthoredColumnsWhenBodyAndHatScaleTogether() {
        var adult = HullBounds.brimStops("marisa", .8, .6, HullBounds.SCALE, 0);
        for (double scale : new double[]{.2, .35, .72, 1.2}) {
            var stops = HullBounds.brimStops("marisa", .8 * scale, .6 * scale, HullBounds.SCALE * scale, 0);
            for (int i = 0; i < adult.size(); i++) {
                assertEquals(adult.get(i).x(), stops.get(i).x(), 1e-9);
                assertEquals(adult.get(i).y(), stops.get(i).y(), 1e-9);
                assertEquals(adult.get(i).z(), stops.get(i).z(), 1e-9);
                var local = stops.get(i);
                var body = BodyFootprint.of("marisa", .8 / UNIT, .6 / UNIT, 0);
                double outer = body.columns().stream().mapToDouble(p ->
                        p.x() * local.x() / local.hlen() + p.z() * local.z() / local.hlen()).max().orElseThrow();
                assertTrue(Double.isFinite(local.y()));
                if (local.hlen() < HullBounds.DATA.number("brim_ring.radius") / UNIT - 1e-9)
                    assertEquals(HullBounds.RADIUS * scale, (local.hlen() + outer) * UNIT * scale, 1e-9);
            }
        }
    }

    @Test void fullYoungBrimStillHasSupportedCaptainFootingInsideTheRiders() {
        double scale = 1.2, unit = UNIT * scale;
        var small = BodyFootprint.of("marisa", .08 / unit, .06 / unit, 0);
        var captain = BodyFootprint.of("marisa", .96 / unit, .72 / unit, 0);
        var young = HullBounds.brimStops("marisa", .08, .06, HullBounds.SCALE * scale, 0);
        for (var at : young) assertEquals(HullBounds.DATA.number("brim_ring.radius") / UNIT, at.hlen(), 1e-9,
                "small riders already fit on the preferred ring; moving them inward breaks the full-boat fixture");
        var stops = HullBounds.brimStops("marisa", .96, .72, HullBounds.SCALE * scale, 0);
        for (var at : young) assertTrue(stops.stream().anyMatch(p -> Math.hypot(p.x() - at.x(), p.z() - at.z()) < 1e-9),
                "the supported outer brim must remain available for bank pickup without settling into the hollow");
        assertTrue(stops.stream().anyMatch(at -> Double.isFinite(at.y()) && young.stream().noneMatch(p ->
                captain.overlaps(at.x(), at.y(), at.z(), small, p.x(), p.y(), p.z(), .02))));
        var child = BodyFootprint.of("marisa", .8 * .19 / unit, .6 * .19 / unit, 0);
        assertTrue(HullBounds.brimStops("marisa", .8 * .19, .6 * .19, HullBounds.SCALE * scale, 0).stream()
                .allMatch(at -> young.stream().anyMatch(p -> child.overlaps(at.x(), at.y(), at.z(), small, p.x(), p.y(), p.z(), .02))));
    }
}
