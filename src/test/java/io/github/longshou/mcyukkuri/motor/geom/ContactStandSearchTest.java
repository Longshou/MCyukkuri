package io.github.longshou.mcyukkuri.motor.geom;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContactStandSearchTest {
    @Test void findsAnAccessibleStandAtThePartnersSoleHeight() {
        var floors = Arrays.asList(null, 64.0, 65.0, 65.00001);
        var found = ContactStandSearch.firstReachable(List.of(0, 1, 2, 3), 65, floors::get, Double::doubleValue,
                height -> height == 65 ? null : height);
        assertEquals(65.00001, found);
    }

    @Test void aReachableLowerSlabDoesNotCountAsTheSameLevel() {
        var found = ContactStandSearch.firstReachable(List.of(64.875), 65, height -> height, Double::doubleValue,
                height -> height);
        assertNull(found);
    }
}
