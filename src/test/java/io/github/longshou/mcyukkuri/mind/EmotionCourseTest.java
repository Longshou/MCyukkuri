package io.github.longshou.mcyukkuri.mind;

import io.github.longshou.mcyukkuri.mind.EmotionCourse.Kind;
import io.github.longshou.mcyukkuri.mind.EmotionCourse.RecallClock;
import io.github.longshou.mcyukkuri.mind.EmotionCourse.Stage;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmotionCourseTest {
    @Test
    void trueStageKeepsItsOwnClock() {
        int length = 500;
        assertEquals(Stage.ONSET, Stage.at(99, length));
        assertEquals(Stage.PEAK, Stage.at(100, length));
        assertEquals(Stage.PEAK, Stage.at(199, length));
        assertEquals(Stage.EASING, Stage.at(200, length));
        assertEquals(Stage.EASING, Stage.at(359, length));
        assertEquals(Stage.FADING, Stage.at(360, length));
        assertEquals(Stage.PEAK, Stage.ONSET.presentation());
        assertEquals(Stage.EASING, Stage.EASING.presentation());
        assertEquals(Stage.FADING, Stage.FADING.presentation());
    }

    @Test
    void onsetSharesThePeakFaceAndFadingUsesAMilderFace() {
        String[][] faces = {
                {"teary", "side_teary", "pursed"},
                {"horrified", "teary", "confused_small"},
                {"angry", "down_angry", "hmph"},
                {"happy", "closed_smile", null},
                {"teary_smile", "closed_smile", null}
        };
        for (Kind kind : Kind.values()) {
            String[] expected = faces[kind.ordinal()];
            assertEquals(expected[0], Stage.ONSET.face(kind));
            assertEquals(expected[0], Stage.PEAK.face(kind));
            assertEquals(expected[1], Stage.EASING.face(kind));
            assertEquals(expected[2], Stage.FADING.face(kind));
        }
        assertEquals(null, Stage.NONE.face(null));
    }

    @Test
    void reloadResumesAPendingRecallAndDoesNotReplayASpentOne() {
        var saved = new HashMap<String, Integer>();
        var clock = new RecallClock();
        clock.schedule(4);
        assertFalse(clock.due());
        clock.save(saved::put);
        assertEquals(3, saved.get("RecallClock"));

        var loaded = new RecallClock();
        loaded.load(key -> saved.getOrDefault(key, 0));
        assertFalse(loaded.due());
        assertFalse(loaded.due());
        assertTrue(loaded.due());
        loaded.save(saved::put);

        clock.load(key -> saved.getOrDefault(key, 0));
        assertFalse(clock.due());
        saved.clear();
        clock.load(key -> saved.getOrDefault(key, 0));
        assertFalse(clock.due());
    }
}
