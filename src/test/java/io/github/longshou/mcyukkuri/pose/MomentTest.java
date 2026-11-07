package io.github.longshou.mcyukkuri.pose;

import io.github.longshou.mcyukkuri.entity.presentation.Moments;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MomentTest {
    @Test void intensityScalesDeltasAndLimitsTilt() {
        float[][] rest = {{0, 0, 0, 0, 0, 0, 1, 1, 1}};
        float[][] pose = {{1, 2, 3, 10, 4, -10, .8f, 1.2f, 1}};
        Intensity.apply(pose, rest, 0, 2);
        assertArrayEquals(new float[]{2,4,6,6,8,-6,.6f,1.4f,1}, pose[0], .00001f);
    }
    @Test void stepsChangeAtBoundaryAndHoldTheLastFace() {
        var first = Moments.frame("wet_relief", 0);
        float boundary = ClipMarkers.of(first.clip()).seconds()/first.tempo();
        assertEquals(0, Moments.frame("wet_relief", boundary-.001f).index());
        assertEquals(1, Moments.frame("wet_relief", boundary).index());
        assertEquals("satisfied", Moments.face("wet_relief", Moments.duration("wet_relief")+10, "neutral"));
    }
    @Test void loopClockClosesAndFacePeriodRemainsAuthored() {
        String id = Moments.select("behaviors", "sing", "");
        assertEquals("open_smile", Moments.face(id, .1f, "neutral"));
        assertEquals("closed_smile", Moments.face(id, .4f, "neutral"));
        assertEquals("open_smile", Moments.face(id, .6f, "neutral"));
        assertEquals(0, Moments.frame(id, Moments.duration(id)).seconds(), .00001f);
    }
}
