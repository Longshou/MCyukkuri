package io.github.longshou.mcyukkuri.social;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class PairContactClockTest {
    @Test void bothRolesSeeEveryFrameBeforeCreditAndKeepTheLastForTheWholeTick() {
        for (int length : new int[]{75, 140}) {
            var clock = new PairContactClock(length);
            for (int tick = 0; tick < length; tick++) {
                var shown = new ArrayList<String>();
                clock.publish(100L + tick,
                        frame -> { assertEquals(frame, clock.ticks()); shown.add("first " + frame); },
                        frame -> { assertEquals(frame, clock.ticks()); shown.add("second " + frame); });
                assertEquals(List.of("first " + tick, "second " + tick), shown);
                assertFalse(clock.finished(100L + tick));
            }
            assertTrue(clock.finished(100L + length));
            assertEquals(length, clock.releasedTicks(100L + length));
        }
    }

    @Test void aSecondCallInTheSameTickCannotAdvanceEitherRole() {
        var clock = new PairContactClock(2);
        clock.publish(10, frame -> assertEquals(0, frame), frame -> assertEquals(0, frame));
        clock.publish(10, frame -> fail("first advanced twice"), frame -> fail("second advanced twice"));
        assertEquals(1, clock.ticks());
        assertFalse(clock.finished(11));
    }

    @Test void releaseCountsOnlyFramesThatSurvivedTheirPublicationTick() {
        var clock = new PairContactClock(140);
        clock.publish(3815, shown -> {}, shown -> {});
        assertEquals(0, clock.releasedTicks(3815));
        for (long tick = 3816; tick <= 3830; tick++) clock.publish(tick, shown -> {}, shown -> {});
        assertEquals(15, clock.releasedTicks(3830));
        assertEquals(16, clock.releasedTicks(3831));
    }
}
