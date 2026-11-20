package io.github.longshou.mcyukkuri.body;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WoundLeakTest {
    @Test
    void arrowPunctureLeaksUntilItHealsBelowGradeTwo() {
        var cut = new BodyWound(new UUID(0, 1), WoundKind.TEAR, 0, .25f, .25f, 2, 1, 0, 0, 2, WoundShape.PUNCTURE);
        assertEquals(.5f, cut.leakLoss());
        assertEquals(1f, cut.withSeverity(3).leakLoss());
        assertEquals(0f, cut.heal().leakLoss());
    }

    @ParameterizedTest
    @EnumSource(value = WoundKind.class, names = "TEAR", mode = EnumSource.Mode.EXCLUDE)
    void onlyTearsLoseFillingEvenWhenOtherMarksAreDeep(WoundKind kind) {
        var wound = new BodyWound(new UUID(0, 1), kind, 0, .25f, .25f, 3, 1, 0, 0);
        assertEquals(0f, wound.leakLoss());
    }
}
