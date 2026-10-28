package io.github.longshou.mcyukkuri.intent;

import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Reason;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The switch budget counts one failure once and excludes a method only under the same signature (spec 11.2). */
class AttemptsTest {
    private static Method method(String id) {
        return new Method() {
            @Override public String id() { return id; }
            @Override public Feasibility applicable() { return Feasibility.pass(); }
            @Override public Set<Reason> reduces() { return Set.of(); }
            @Override public Step next() { return null; }
        };
    }

    @Test
    void oneFailureCountsOnceUnderItsSignature() {
        Attempts attempts = new Attempts();
        Method walk = method("walk_closer");
        attempts.fail(walk, Reason.TOO_FAR, 0.4, null);
        assertEquals(1, attempts.used());
        assertEquals(5, attempts.remaining());
        assertTrue(attempts.excluded(walk, null));
        assertFalse(attempts.excluded(method("hop_from_here"), null));
    }

    @Test
    void anotherSignatureMakesTheMethodSelectableAgain() {
        Attempts attempts = new Attempts(2);
        Method walk = method("walk_closer");
        WorldSignature here = new WorldSignature(0, 0, 0, 0, 0, 0, Set.of(), 1);
        attempts.fail(walk, Reason.NO_PATH, 0, here);
        assertTrue(attempts.excluded(walk, here));
        assertFalse(attempts.excluded(walk, new WorldSignature(2, 0, 0, 0, 0, 0, Set.of(), 1)));
    }

    @Test
    void incapableStaysExcludedAndSpendsOneSwitch() {
        Attempts attempts = new Attempts(2);
        Method climb = method("climb");
        attempts.incapable(climb, Reason.TOO_HEAVY);
        assertEquals(1, attempts.used());
        assertTrue(attempts.excluded(climb, null));
    }

    @Test
    void budgetRunsOutAtMaxSwitches() {
        Attempts attempts = new Attempts(2);
        attempts.fail(method("a"), Reason.NO_PATH, 0, null);
        attempts.fail(method("b"), Reason.NO_PATH, 0, null);
        assertTrue(attempts.exhausted());
        assertEquals(0, attempts.remaining());
    }

    @Test
    void reasonsAreOneLinePerReasonWithTheLargestShortfall() {
        Attempts attempts = new Attempts();
        attempts.fail(method("a"), Reason.NO_ROOM, 0.1, null);
        attempts.fail(method("b"), Reason.NO_ROOM, 0.35, null);
        attempts.fail(method("c"), Reason.NO_CONTACT, 0.3, null);
        List<Attempts.Failure> reasons = attempts.reasons();
        assertEquals(2, reasons.size());
        assertEquals("[NO_ROOM:0.35, NO_CONTACT:0.3]", attempts.describe());
    }
}
