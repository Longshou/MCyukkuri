package io.github.longshou.mcyukkuri.entity.presentation;

import static io.github.longshou.mcyukkuri.entity.presentation.FacePrecedence.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class FacePrecedenceTest {
    @Test void orderIsDeclaredOnce() {
        assertEquals(List.of(OPERATION, CLIP, REACTION, DYING, LEAK, PAIN, LINGER, COURSE, ACTIVITY, AFTERMATH, WEAKNESS, REST),
                List.of(values()));
    }

    @Test void decorativeReactionYieldsToActivityButSignalDoesNot() {
        assertEquals(true, reactionOffers(false, true));
        assertEquals(false, reactionOffers(true, true));
        assertEquals(true, reactionOffers(true, false));
        assertEquals(true, reactionOffers(false, false));
    }

    @Test void signalReactionAndCourseOutrankActivity() {
        assertEquals(REACTION, winner(EnumSet.of(REACTION, ACTIVITY)));
        assertEquals(COURSE, winner(EnumSet.of(COURSE, ACTIVITY)));
        assertEquals(LINGER, winner(EnumSet.of(LINGER, COURSE, ACTIVITY, WEAKNESS)));
        assertEquals(PAIN, winner(EnumSet.of(PAIN, LINGER)));
    }

    @Test void courseBeatsAftermathAndWeakness() {
        assertEquals(COURSE, winner(EnumSet.of(WEAKNESS, AFTERMATH, COURSE)));
        assertEquals(AFTERMATH, winner(EnumSet.of(WEAKNESS, AFTERMATH)));
        assertEquals(REST, winner(EnumSet.noneOf(FacePrecedence.class)));
    }

    @Test void activityBeatsWeaknessButYieldsToPainAndCourse() {
        assertEquals(ACTIVITY, winner(EnumSet.of(WEAKNESS, AFTERMATH, ACTIVITY)));
        assertEquals(PAIN, winner(EnumSet.of(ACTIVITY, PAIN)));
        assertEquals(COURSE, winner(EnumSet.of(ACTIVITY, COURSE)));
        assertEquals(DYING, winner(EnumSet.of(ACTIVITY, DYING)));
    }

    @Test void leakYieldsToOperationReactionAndDying() {
        assertEquals(OPERATION, winner(EnumSet.of(LEAK, OPERATION)));
        assertEquals(REACTION, winner(EnumSet.of(LEAK, REACTION)));
        assertEquals(DYING, winner(EnumSet.of(LEAK, DYING)));
        assertEquals(LEAK, winner(EnumSet.of(LEAK, PAIN, COURSE)));
    }
}
