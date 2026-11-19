package io.github.longshou.mcyukkuri.motor.geom;

import io.github.longshou.mcyukkuri.headwear.HullBounds;
import io.github.longshou.mcyukkuri.headwear.HullProfile;
import io.github.longshou.mcyukkuri.motor.Reason;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BraidGripTest {
    @Test void interruptedCabinHopCanRecoverTheArchivedWaterGrip() {
        // rescue_child tick 1945, instance-3678e4e220fe. The old seat faces 9 degrees. Turning to
        // the newborn needs -78 degrees there, or -71 degrees from the captain's seat after reseating.
        var hull = HullProfile.of(HullProfile.BASIN);
        double unit = .08749999850988388, x = 1.2870173826, z = -.9011792731;
        double width = .8000000715 / unit, height = .6000000238 / unit;
        var home = HullBounds.HOME.scale(1 / HullBounds.UNIT);
        var facing = BodyFootprint.of("marisa", width, height, Math.toRadians(9));
        assertEquals(hull.floor(), hull.support(x, z, facing), 1e-6);
        // The same blocked band also lies on the turn to jump directly to the captain's seat.
        // It is wider than the full 12-degree turn step, including either smaller correction.
        for (int yaw = -15; yaw <= 5; yaw += 5) {
            var envelope = BodyFootprint.envelope("marisa", width, height, Math.toRadians(yaw));
            assertTrue((hull.support(x, z, envelope) - hull.floor()) * unit > .02);
        }
        // The cabin seating action walks to the entry at the old yaw, faces the bow and crosses to home.
        for (int i = 0; i <= 10; i++) {
            double fraction = i / 10.0;
            assertEquals(hull.floor(), hull.support(x * fraction, z * fraction, facing), 1e-6);
            assertEquals(hull.floor(), hull.support(home.x() * fraction, home.z() * fraction,
                    BodyFootprint.of("marisa", width, height, 0)), 1e-6);
        }
        for (int yaw = -75; yaw <= 10; yaw += 5) {
            var envelope = BodyFootprint.envelope("marisa", width, height, Math.toRadians(yaw));
            assertEquals(hull.floor(), hull.support(0, 0, envelope), 1e-6);
            assertEquals(hull.floor(), hull.support(home.x(), home.z(), envelope), 1e-6);
        }
        // Posed roots in the recorded hull frame (yaw -0.85206 rad, roll 0.618°, pitch 0.966°).
        var child = new Vec(-158.206177, 63.725760, 299.689192);
        var low = child.add(new Vec(-.072, 0, -.072));
        var high = child.add(new Vec(.072, .108, .072));
        double length = GeometryJson.read("yukkuri_presentation/braid_rescue.json").number("braid_length") / 16;
        double original = CarryGeometry.reachGap(new Vec(-158.484112, 64.263968, 300.012931), low, high, length);
        assertTrue(original < 0);
        double upright = CarryGeometry.reachGap(new Vec(-158.610340, 64.260633, 300.052556), low, high, length);
        double lean = CarryGeometry.reachGap(new Vec(-158.502864, 64.122121, 299.951475), low, high, length);
        var grip = new BraidGrip(true, upright, lean);
        assertTrue(grip.lean());
        assertTrue(grip.check().ok());
    }

    @Test void reachWithoutFootingRefusesGrip() {
        var grip = new BraidGrip(false, -.1, -.2);
        assertFalse(grip.ready());
        assertEquals(Reason.NO_SUPPORT, grip.check().reason());
        assertEquals("unstable_grip_stand", grip.check().detail());
    }

    @Test void footingWithoutReachReportsTheNearerGap() {
        var grip = new BraidGrip(true, .2, .1);
        assertFalse(grip.ready());
        assertEquals(Reason.NO_CONTACT, grip.check().reason());
        assertEquals(.1, grip.check().shortfall());
    }

    @Test void supportedGripPrefersUprightAndAcceptsTheLowLeanAtContact() {
        var upright = new BraidGrip(true, 0, -.2);
        assertTrue(upright.ready());
        assertTrue(upright.check().ok());
        assertFalse(upright.lean());

        var low = new BraidGrip(true, .1, 0);
        assertTrue(low.ready());
        assertTrue(low.check().ok());
        assertTrue(low.lean());
    }
}
