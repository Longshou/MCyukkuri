package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.family.GrowthTiming;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShopLitterTest {
    @Test void everySplitBetweenLiveAndBasketedSiblingsKeepsTheWholeLitterForReflow() {
        var parents = ShopLitter.template(new UUID(0, 1), 1);
        var other = new ShopLitter(parents.mother(), new UUID(0, 2));
        record Body(String name, ShopLitter parents) {}
        var first = new Body("first", parents);
        var second = new Body("second", parents);
        var third = new Body("third", parents);
        var cousin = new Body("other litter", other);
        var all = List.of(first, second, third);
        for (int live = 0; live <= all.size(); live++) {
            var captives = new ArrayList<>(all.subList(live, all.size()));
            captives.add(cousin);
            var groups = ShopLitter.groups(all.subList(0, live), captives, Body::parents);
            assertEquals(all, groups.get(parents).all());
            assertEquals(all.subList(0, live), groups.get(parents).live());
            assertEquals(List.of(cousin), groups.get(other).inTransit());
        }
    }
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
