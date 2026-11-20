package io.github.longshou.mcyukkuri.motor.geom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SweptBodyTest {
    private static SweptBody.Box at(Vec feet, double width, double height) {
        double r = width / 2;
        return new SweptBody.Box(feet.add(new Vec(-r, 0, -r)), feet.add(new Vec(r, height, r)));
    }

    @Test void takeoffRejectsTheReviewersAdjacentBranchOverlap() {
        var feet = new Vec(-307.370623026623, 72.15, 492.49999995335406);
        var later = new Vec(-306.53493349170213, 72.15, 492.49999995335406);
        var resident = at(feet, .8, .6);
        var intruder = at(new Vec(-306.5, 72, 492.5), .8, .6);
        assertTrue(SweptBody.clear(resident, Vec.ZERO, intruder, Vec.ZERO));
        assertFalse(SweptBody.clear(resident, later.sub(feet), intruder, Vec.ZERO));
        assertTrue(SweptBody.clear(resident, new Vec(0, 0, -2), intruder, Vec.ZERO));
    }

    @Test void movingPlayerCrossesBetweenTwoClearEndpoints() {
        var resident = at(Vec.ZERO, .8, .6);
        var player = at(new Vec(1, 0, -1), .6, 1.8);
        var flight = new Vec(2, 0, 0);
        assertTrue(SweptBody.clear(resident, flight, player, Vec.ZERO));
        assertFalse(SweptBody.clear(resident, flight, player, new Vec(0, 0, 2)));
        assertTrue(SweptBody.clear(at(new Vec(0, 3.3, 0), .8, .6), flight, player, new Vec(0, 0, 2)));
    }

    @Test void anExistingOverlapCanSeparateWithoutAllowingDeeperContact() {
        var resident = at(Vec.ZERO, .8, .6);
        var peer = at(new Vec(.6, 0, 0), .8, .6);
        assertTrue(SweptBody.clear(resident, new Vec(-.1, 0, 0), peer, Vec.ZERO));
        assertFalse(SweptBody.clear(resident, new Vec(.1, 0, 0), peer, Vec.ZERO));
    }

    @Test void padRouteOpensForPushContactAndStillBlocksAnotherBody() {
        var from = new Vec(-307.42604089095136, 72.15000000596046, 492.5231473971469);
        var stand = new Vec(-307.5, 72, 491.4999999880791);
        var remains = at(new Vec(-307.5, 72.15, 492.1), .30, .138);
        assertNull(ShelfApproach.next(from, stand, (a, b) ->
                SweptBody.clear(at(a, .8, .6), b.sub(a), remains, Vec.ZERO)));
        assertEquals(stand, ShelfApproach.next(from, stand, (a, b) -> true));

        var bystander = at(new Vec(-307.5, 72, 491.5), .8, .6);
        assertNull(ShelfApproach.next(from, stand, (a, b) ->
                SweptBody.clear(at(a, .8, .6), b.sub(a), bystander, Vec.ZERO)));
    }
}
