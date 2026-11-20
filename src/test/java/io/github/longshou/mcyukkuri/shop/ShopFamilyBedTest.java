package io.github.longshou.mcyukkuri.shop;

import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.life.NestLayout;
import io.github.longshou.mcyukkuri.life.NestResting;
import io.github.longshou.mcyukkuri.motor.geom.BedSpace;
import io.github.longshou.mcyukkuri.motor.geom.OccupancyMath;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShopFamilyBedTest {
    private static ShopFamilyBed.Pad pad(String mesh, NestResting.Bed bed, int x, int z) {
        var reader = new InputStreamReader(ShopFamilyBedTest.class.getResourceAsStream(
                "/assets/mcyukkuri/yukkuri_presentation/care_collision.json"));
        var boxes = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject(mesh).getAsJsonArray("boxes");
        var shell = new ArrayList<BedSpace.Box>();
        for (var raw : boxes) {
            var b = raw.getAsJsonArray();
            shell.add(new BedSpace.Box(b.get(0).getAsDouble()/16, 0, b.get(1).getAsDouble()/16,
                    b.get(2).getAsDouble()/16, b.get(4).getAsDouble()/16, b.get(3).getAsDouble()/16));
        }
        double floor = shell.stream().filter(b -> b.x0() <= 0 && b.x1() >= 0 && b.z0() <= 0 && b.z1() >= 0)
                .mapToDouble(BedSpace.Box::y1).max().orElseThrow();
        var space = shell.stream().map(b -> new BedSpace.Box(b.x0(), -floor, b.z0(), b.x1(), b.y1()-floor, b.z1())).toList();
        return new ShopFamilyBed.Pad(x, z, bed, (i,p) ->
                OccupancyMath.classify(p.x(), p.z(), 0, p.width()/2 + .02, bed.floor(), 0, 0, true) == OccupancyMath.Result.INSIDE_SUPPORTED
                && BedSpace.fits(space, p.x(), p.z(), p.width(), .45, p.reach(), .4, .65, false));
    }

    private static ShopFamilyBed choose(int count, int stage, float width, double reach, int secondZ) {
        var member = new ShopFamilyBed.Member(stage, new NestLayout.Place(0, 0, width, reach));
        return choose(Collections.nCopies(count, member), secondZ);
    }

    private static ShopFamilyBed choose(List<ShopFamilyBed.Member> members, int secondZ) {
        return ShopFamilyBed.choose(members, layout -> switch (layout) {
            case CUSHION -> List.of(pad("pet_nest_0", NestResting.CUSHION, 0, 0));
            case WOVEN -> List.of(pad("large_yukkuri_nest", NestResting.LARGE, 0, 0));
            case ADJACENT -> List.of(pad("pet_nest_6", NestResting.CUSHION, 0, 0),
                    pad("pet_nest_9", NestResting.CUSHION, 1, secondZ));
        });
    }

    @Test void choosesTheSmallestSupportedArrangement() {
        assertEquals(ShopFamilyBed.CUSHION, choose(3, 0, .16f, .1373, 1));
        assertEquals(ShopFamilyBed.WOVEN, choose(2, 1, .46f, .40, 1));
        assertEquals(ShopFamilyBed.ADJACENT, choose(2, 1, .64f, .549, 1));
        assertNull(choose(2, 1, .64f, .549, 0)); // Centres one block apart cannot clear both ribbons.
    }

    @Test void aDelayedJuvenileKeepsItsAdjacentCushionUntilReleased() {
        var parents = ShopLitter.template(new UUID(0, 1), 1);
        var juvenile = new ShopFamilyBed.Member(1, new NestLayout.Place(0, 0, .64f, .549));
        var siblings = List.of(juvenile, juvenile);
        assertEquals(ShopFamilyBed.CUSHION, choose(List.of(juvenile), 1));
        for (int live = 0; live <= siblings.size(); live++) {
            var family = ShopLitter.groups(siblings.subList(0, live), siblings.subList(live, siblings.size()), body -> parents).get(parents);
            assertEquals(ShopFamilyBed.ADJACENT, choose(family.all(), 1));
        }
    }
}
