package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.family.GrowthTiming;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShopLitterTest {
    @Test void babiesAndJuvenilesFitOneCushion() {
        assertEquals(2, ShopLitter.size(GrowthTiming.BABY_END - 1, false));
        assertEquals(3, ShopLitter.size(GrowthTiming.BABY_END - 1, true));
        assertEquals(2, ShopLitter.size(GrowthTiming.BABY_END, true));
        assertEquals(2, ShopLitter.size(GrowthTiming.SUBADULT_START - 1, false));
    }

    @Test void templateParentsAreStableWithinOnePlacementAndGroup() {
        var shop = new UUID(0, 1);
        var first = ShopLitter.template(shop, 1);
        assertEquals(first, ShopLitter.template(shop, 1));
        assertNotEquals(first.mother(), first.father());
        assertNotEquals(first.mother(), ShopLitter.template(new UUID(0, 2), 1).mother());
        assertNotEquals(first.father(), ShopLitter.template(shop, 2).father());
    }
}
