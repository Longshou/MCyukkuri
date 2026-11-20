package io.github.longshou.mcyukkuri.life;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BedApproachMemoryTest {
    @Test void anotherHomeAttemptNeedsAChangedApproach() {
        assertFalse(BedApproachMemory.retryBoarding(true, true, true, false)); // The baby still covers the destination.
        assertTrue(BedApproachMemory.retryBoarding(true, false, false, false));
        assertFalse(BedApproachMemory.retryBoarding(false, false, false, false));
        assertTrue(BedApproachMemory.retryBoarding(false, false, true, false));
        assertTrue(BedApproachMemory.retryBoarding(false, false, false, true));
    }

    @Test void failuresAtTwoBedsPersistIndependentlyAndADifferentEntranceWorksImmediately() {
        var memory=new BedApproachMemory();
        var north=new BedApproachMemory.Opening("overworld",12,0);
        var other=new BedApproachMemory.Opening("overworld",18,0);
        memory.refuse(north,100);
        assertTrue(memory.available(other,300));
        memory.refuse(other,300);
        assertFalse(memory.available(north,500));
        assertTrue(memory.available(new BedApproachMemory.Opening("overworld",12,2),500));
        assertFalse(memory.available(north,1299));
        assertTrue(memory.available(north,1300));
        assertFalse(memory.available(other,1300));
    }
}
