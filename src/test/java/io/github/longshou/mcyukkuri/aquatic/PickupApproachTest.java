package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.geom.BraidGrip;
import io.github.longshou.mcyukkuri.motor.geom.CarryGeometry;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PickupApproachTest {
    @Test void childInsideRadialReachButOutsidePosedReachNeedsANewStand() {
        // Adult low braid turned toward a child at 75 degrees, in blocks relative to the parent.
        var low = new Vec(.203052, 0, 1.140286);
        var high = new Vec(.483052, .25, 1.420286);
        double length = .5877364;
        var radialRoot = new Vec(.157513, .4444875, .587845);
        var posedRoot = new Vec(.160710, .4444875, .586979);
        assertTrue(CarryGeometry.reachGap(radialRoot, low, high, length) < 0);
        double gap = CarryGeometry.reachGap(posedRoot, low, high, length);
        assertTrue(gap > 0);
        class Pair {
            double parentZ, childZ;
            Feasibility contact() {
                var root = posedRoot.add(new Vec(0, 0, parentZ - childZ));
                double remaining = CarryGeometry.reachGap(root, low, high, length);
                return remaining > 0 ? Feasibility.of(Reason.NO_CONTACT, remaining, "braid_gap") : Feasibility.pass();
            }
        }
        var pair = new Pair();
        pair.childZ = -.12; // Within posed reach before answering the call.
        var call = beat("call");
        var grip = beat("grip");
        var plan = Plan.of("lift", () -> List.of(() -> call,
                new Plan.Approach<>(pair::contact, () -> pair.childZ + .12, at -> beat("walk " + at)), () -> grip));
        assertSame(call, plan.next());
        pair.childZ = 0;
        assertEquals("walk 0.12", ((Step.Wait) plan.next()).why());
        pair.parentZ = .12;
        pair.childZ = .25; // The child moved again while the parent walked.
        assertEquals("walk 0.37", ((Step.Wait) plan.next()).why());
        pair.parentZ = .37;
        assertSame(grip, plan.next());
    }

    @Test void acceptedGripAndCapacityRefusalDoNotSearchForAnotherStand() {
        for (var contact : List.of(Feasibility.pass(), Feasibility.of(Reason.TOO_HEAVY, 0, "too_big"))) {
            var approach = new Plan.Approach<>(() -> contact, () -> fail("unexpected stand search"),
                    at -> fail("unexpected walk"));
            assertNull(approach.get());
        }
        var noStand = new Plan.Approach<>(() -> Feasibility.of(Reason.NO_SUPPORT, 0, "brim_lift_blocked"),
                () -> null, at -> fail("no supported stand"));
        assertNull(noStand.get());
    }

    @Test void movingChildCannotKeepTheApproachQueued() {
        var stands = List.of("left", "right", "left").iterator();
        var plan = Plan.of("lift", () -> List.of(new Plan.Approach<>(
                () -> Feasibility.of(Reason.NO_CONTACT, .05, "braid_gap"), stands::next, PickupApproachTest::beat),
                () -> beat("check grip")));
        assertEquals("left", ((Step.Wait) plan.next()).why());
        assertEquals("right", ((Step.Wait) plan.next()).why());
        assertEquals("check grip", ((Step.Wait) plan.next()).why());
        assertTrue(stands.hasNext());
    }

    @Test void rescueApproachCanChangeFootingWithinTheSamePlan() {
        var contact = List.of(new BraidGrip(true, .5, .4), new BraidGrip(false, -.1, -.2),
                new BraidGrip(true, -.1, -.2)).iterator();
        var grip = beat("grip");
        var plan = Plan.of("lift", () -> List.of(new Plan.Approach<>(3, () -> contact.next().check(),
                reason -> reason == Reason.NO_SUPPORT ? "hop" : "paddle", PickupApproachTest::beat), () -> grip));
        assertEquals("paddle", ((Step.Wait) plan.next()).why());
        assertEquals("hop", ((Step.Wait) plan.next()).why());
        assertSame(grip, plan.next());
        assertFalse(contact.hasNext());
    }

    private static Step.Wait beat(String name) { return new Step.Wait(() -> true, 1, name); }
}
