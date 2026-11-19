package io.github.longshou.mcyukkuri.shop;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ShopVacancyTest {
    @Test void singlesAndFamilyRoomsAreIndependent() {
        assertEquals(new ShopVacancy(1, 2, 1), ShopVacancy.plan(2, 2, 1, 1, 0, List.of(), Set.of()));
    }

    @Test void basketsOfOneLitterNeedOneRoomAndCanJoinTheirSettledSibling() {
        var first = new UUID(0, 1);
        var second = new UUID(0, 2);
        assertEquals(new ShopVacancy(0, 0, 0),
                ShopVacancy.plan(1, 0, 1, 2, 1, List.of(first, second, second), Set.of(first)));
        assertEquals(1, ShopVacancy.plan(0, 0, 1, 0, 0, List.of(first, first), Set.of(first)).families());
    }
}
