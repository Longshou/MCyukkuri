package io.github.longshou.mcyukkuri.entity.presentation;

import static io.github.longshou.mcyukkuri.entity.presentation.FacePrecedence.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class FacePrecedenceTest {
    @Test void orderIsDeclaredOnce() {
        assertEquals(List.of(OPERATION, CLIP, REACTION, DYING, LEAK, PAIN, COURSE, AFTERMATH, WEAKNESS, REST),
                List.of(values()));
    }

    @Test void courseBeatsAftermathAndWeakness() {
        assertEquals(COURSE, winner(EnumSet.of(WEAKNESS, AFTERMATH, COURSE)));
        assertEquals(AFTERMATH, winner(EnumSet.of(WEAKNESS, AFTERMATH)));
        assertEquals(REST, winner(EnumSet.noneOf(FacePrecedence.class)));
    }

    @Test void leakYieldsToOperationReactionAndDying() {
        assertEquals(OPERATION, winner(EnumSet.of(LEAK, OPERATION)));
        assertEquals(REACTION, winner(EnumSet.of(LEAK, REACTION)));
        assertEquals(DYING, winner(EnumSet.of(LEAK, DYING)));
        assertEquals(LEAK, winner(EnumSet.of(LEAK, PAIN, COURSE)));
    }
}
