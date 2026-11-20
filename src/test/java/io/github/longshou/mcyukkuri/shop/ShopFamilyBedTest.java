package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.life.NestLayout;
import io.github.longshou.mcyukkuri.life.NestResting;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ShopFamilyBedTest {
    private static ShopFamilyBed choose(int count, int stage, float width, double reach) {
        var member = new ShopFamilyBed.Member(stage, new NestLayout.Place(0, 0, width, reach));
        return ShopFamilyBed.choose(NestResting.CUSHION, false, Collections.nCopies(count, member));
    }

    @Test void choosesByTheLittersCountAndWornBodySize() {
        assertEquals(ShopFamilyBed.CUSHION, choose(3, 0, .16f, .1373));
        assertEquals(ShopFamilyBed.CUSHION, choose(2, 1, .28f, .22));
        assertEquals(ShopFamilyBed.WOVEN, choose(2, 1, .64f, .549));
    }

    @Test void rejectsAGroupBeyondTheWovenBedsCapacityOrClearance() {
        assertEquals(ShopFamilyBed.TOO_SMALL, choose(3, 1, .32f, .2745));
        assertEquals(ShopFamilyBed.TOO_SMALL, choose(2, 1, .8f, .75));
    }

    @Test void reusesTheInstalledWovenBed() {
        var baby = new ShopFamilyBed.Member(0, new NestLayout.Place(0, 0, .16f, .1373));
        assertEquals(ShopFamilyBed.WOVEN, ShopFamilyBed.choose(NestResting.LARGE, true, List.of(baby, baby, baby)));
    }
}
