package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.motor.geom.GeometryJson;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HullTriggersTest {
    private static HullTriggers.Flow flow(double x, double z) { return new HullTriggers.Flow(x, z, false); }

    @Test void shakeUsesSlidingWindowAndConsumesEachEpisode() {
        var rules = new HullTriggers();
        assertEquals(1, rules.shake(0));
        assertEquals(2, rules.shake(40));
        assertEquals(2, rules.shake(61));
        assertEquals(HullTriggers.SHAKES, rules.shake(100));
        assertEquals(1, rules.shake(101));
        assertEquals(1, rules.shake(162));
    }
    @Test void fallingOrDisagreeingFlowsMustPersistAndRearmOnlyAfterCalm() {
        var calm = HullTriggers.classify(false, List.of(flow(1, 0), flow(2, 0), flow(0, 0), flow(1, 0)));
        var cross = HullTriggers.classify(false, List.of(flow(1, 0), flow(-1, 0)));
        var falling = HullTriggers.classify(false, List.of(new HullTriggers.Flow(0, 0, true)));
        assertFalse(calm.turbulent()); assertEquals(180, cross.angle()); assertTrue(falling.turbulent());
        assertFalse(HullTriggers.classify(false, List.of(flow(1, 0), flow(1, 1))).turbulent());
        var rules = new HullTriggers();
        for (int i = 1; i < HullTriggers.WET_TICKS; i++) assertFalse(rules.water(cross));
        assertFalse(rules.water(calm));
        for (int i = 1; i < HullTriggers.WET_TICKS; i++) assertFalse(rules.water(falling));
        assertTrue(rules.water(falling));
        for (int i = 0; i < 1000; i++) assertFalse(rules.water(falling));
        assertFalse(rules.water(calm));
        for (int i = 1; i < HullTriggers.WET_TICKS; i++) assertFalse(rules.water(cross));
        assertTrue(rules.water(cross));
    }
    @Test void evenHeadOnTopSpeedPaddlingCannotTriggerAnImpact() {
        var data = new GeometryJson("body/ability.json", JsonParser.parseReader(new InputStreamReader(
                getClass().getResourceAsStream("/data/mcyukkuri/body/ability.json"), StandardCharsets.UTF_8)).getAsJsonObject());
        double pace = data.number("coefficients.swim_speed") * data.number("modifiers.aquatic_marisa.swim_speed")
                * Math.sqrt(data.number("coefficients.size_max"));
        assertTrue(HullTriggers.IMPACT > 1.5 * pace);
        var rules = new HullTriggers();
        assertFalse(rules.impact(pace));
        assertFalse(rules.impact(2 * pace));
        assertFalse(rules.impact(-1));
        assertTrue(rules.impact(HullTriggers.IMPACT));
        assertFalse(rules.impact(1));
        assertFalse(rules.impact(0));
        assertTrue(rules.impact(.8));
    }
    @Test void calmBobbingForADayNeverFiresAndStaysWithinAuthoredBounds() {
        var rules = new HullTriggers();
        var water = HullTriggers.classify(false, List.of(flow(0, 0), flow(0, 0), flow(0, 0), flow(0, 0)));
        for (int t = 0; t < 24000; t++) {
            assertTrue(Math.abs(HullPresentation.bob(t)) <= HullPresentation.number("bob_height"));
            assertTrue(Math.abs(HullPresentation.roll(t)) <= HullPresentation.number("roll_degrees"));
            assertTrue(Math.abs(HullPresentation.pitch(t)) <= HullPresentation.number("pitch_degrees"));
            assertFalse(rules.water(water));
            assertFalse(rules.impact(0));
        }
    }
}
