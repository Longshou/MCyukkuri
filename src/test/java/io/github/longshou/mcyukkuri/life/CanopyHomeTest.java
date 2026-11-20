package io.github.longshou.mcyukkuri.life;

import java.util.LinkedHashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CanopyHomeTest {
    private final UUID mother = new UUID(0, 1), father = new UUID(0, 2), chick = new UUID(0, 3), stranger = new UUID(0, 4);
    private final LinkedHashMap<UUID,Integer> residents = new LinkedHashMap<>();

    @Test void eitherMateCanClaimFirstButAnUnrelatedPredatorCannotJoin() {
        for (var first : new UUID[]{mother, father}) {
            var mate = first.equals(mother) ? father : mother;
            residents.clear();
            assertTrue(NestOccupancy.acceptsCanopy(residents, first, 2, mate, id -> false));
            residents.put(first, 2);
            assertFalse(NestOccupancy.acceptsCanopy(residents, stranger, 2, null, id -> false));
            assertTrue(NestOccupancy.acceptsCanopy(residents, mate, 2, first, id -> false));
            residents.put(mate, 2);
            assertTrue(NestOccupancy.acceptsCanopy(residents, first, 2, mate, id -> false));
            assertTrue(NestOccupancy.acceptsCanopy(residents, mate, 2, first, id -> false));
            assertFalse(NestOccupancy.acceptsCanopy(residents, stranger, 2, first, id -> false));
        }
    }

    @Test void youngBelongToAResidentsBroodAndLeaveTheClaimAtIndependence() {
        assertFalse(NestOccupancy.acceptsCanopy(residents, chick, 0, null, mother::equals));
        residents.put(father, 2);
        assertTrue(NestOccupancy.acceptsCanopy(residents, chick, 0, null, father::equals));
        residents.put(chick, 0);
        assertTrue(NestOccupancy.acceptsCanopy(residents, mother, 2, father, id -> false));
        residents.put(mother, 2);
        assertTrue(NestOccupancy.acceptsCanopy(residents, chick, 1, null, mother::equals));
        assertFalse(NestOccupancy.acceptsCanopy(residents, stranger, 1, null, id -> false));
        assertFalse(NestOccupancy.acceptsCanopy(residents, chick, 2, null, mother::equals));
    }

    @Test void aRemainingChickKeepsThePadInTheFamily() {
        residents.put(chick, 0);
        assertTrue(NestOccupancy.acceptsCanopy(residents, chick, 1, null, id -> false));
        assertFalse(NestOccupancy.acceptsCanopy(residents, stranger, 2, null, id -> false));
        assertTrue(NestOccupancy.acceptsCanopy(residents, mother, 2, null, chick::equals));
    }
}
