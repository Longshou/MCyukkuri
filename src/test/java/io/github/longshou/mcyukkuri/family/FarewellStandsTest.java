package io.github.longshou.mcyukkuri.family;

import java.util.List;
import org.junit.jupiter.api.Test;

import static io.github.longshou.mcyukkuri.family.FarewellStands.Exchange.*;
import static org.junit.jupiter.api.Assertions.*;

class FarewellStandsTest {
    @Test void retrySkipsTheSameStandAndRequiresAReachableAlternative() {
        var stands = new FarewellStands<Integer>((a, b) -> Math.abs(a - b) <= 1);
        var candidates = List.of(10, 11, 20, 30);
        assertEquals(10, stands.next(candidates, stand -> true));
        stands.refuse(10);
        assertEquals(30, stands.next(candidates, stand -> stand != 20));
        assertNull(stands.next(candidates, stand -> stand < 20));
    }

    @Test void failuresBoundTheLegAndASeparateLegStartsFresh() {
        var stands = new FarewellStands<Integer>(Integer::equals);
        var candidates = List.of(1, 2, 3, 4);
        for (int first : List.of(1, 2, 3)) {
            assertEquals(first, stands.next(candidates, stand -> true));
            stands.refuse(first);
        }
        assertNull(stands.next(candidates, stand -> true));
        stands.clear();
        assertEquals(1, stands.next(candidates, stand -> true));
    }

    @Test void aVisibleGoodbyeFollowsFailedApproaches() {
        assertEquals(CLOSE, FarewellStands.exchange(true, false, true));
        assertEquals(WALK, FarewellStands.exchange(false, true, true));
        assertEquals(WALK, FarewellStands.exchange(false, true, false));
        assertEquals(CALL, FarewellStands.exchange(false, false, true));
        assertEquals(UNREACHABLE, FarewellStands.exchange(false, false, false));
    }

    @Test void aMovingParentCannotSendTheTeenBackToAnOldStandOrSpendItsRouteFailures() {
        var stands = new FarewellStands<Integer>(Integer::equals);
        for (int at : List.of(1, 2, 3, 4)) {
            assertEquals(at, stands.next(List.of(1, 2, 3, 4), candidate -> true));
            stands.retire(at);
        }
        assertNull(stands.next(List.of(2, 1), candidate -> true));
        for (int at : List.of(5, 6, 7)) {
            assertEquals(at, stands.next(List.of(at), candidate -> true));
            stands.refuse(at);
        }
        assertNull(stands.next(List.of(8), candidate -> true));
    }
}
