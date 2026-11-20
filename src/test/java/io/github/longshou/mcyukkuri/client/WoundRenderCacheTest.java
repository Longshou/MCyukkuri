package io.github.longshou.mcyukkuri.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.longshou.mcyukkuri.body.BodyWound;
import io.github.longshou.mcyukkuri.body.WoundKind;
import io.github.longshou.mcyukkuri.body.WoundShape;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WoundRenderCacheTest {
    @Test
    void evictsTheLeastRecentlyUsedBodyAndClearsLevelState() {
        var cache = new BoundedRenderCache<String, Integer>(2);
        cache.put("near", 1);
        cache.put("far", 2);
        assertEquals(1, cache.get("near"));
        cache.put("new", 3);
        assertNull(cache.get("far"));
        assertEquals(1, cache.get("near"));
        assertEquals(3, cache.get("new"));
        cache.clear();
        assertNull(cache.get("near"));
        assertNull(cache.get("new"));
    }

    @Test
    void reusesEverySurfaceInAThirtyThreeBodyCrowd() {
        var cache = new DrawnBodyCache<Integer,Integer>(DrawnBodyCache.MAX_BODIES);
        for (int body=0; body<33; body++) cache.put(body, body, 1);
        for (int body=0; body<33; body++) assertEquals(body, cache.get(body, 2));
        assertEquals(33, cache.size());
    }

    @Test
    void protectsLastFramesFitsFromOverflowAndAdmitsThemWhenAPlaceOpens() {
        int capacity = DrawnBodyCache.MAX_BODIES;
        var cache = new DrawnBodyCache<Integer,Integer>(capacity);
        for (int body=0; body<=capacity; body++) cache.put(body, body, 1);
        assertEquals(capacity, cache.size());
        assertNull(cache.get(capacity, 2));
        cache.put(capacity, capacity, 2);
        for (int body=capacity-1; body>0; body--) assertEquals(body, cache.get(body, 2));
        assertNull(cache.get(capacity, 2));

        cache.put(capacity, capacity, 3);
        assertNull(cache.get(0, 3));
        assertEquals(capacity, cache.get(capacity, 3));
        cache.put(1, -1, 3);
        assertEquals(-1, cache.get(1, 3));
        assertEquals(capacity, cache.size());
        cache.remove(1);
        assertNull(cache.get(1, 3));
        cache.clear();
        assertEquals(0, cache.size());
    }

    @Test
    void changesToDrawnWoundInvalidateWhileLeakTicksReuseTheMesh() {
        var id = new UUID(0, 1);
        var wound = new BodyWound(id, WoundKind.TEAR, 6, .25f, .5f, 2, 1, 0, 0, 2, WoundShape.CUT);
        var visible = new HairWoundRenderer.VisibleWound(wound);
        assertTrue(visible.same(wound.released(100)));
        assertFalse(visible.same(wound.withSeverity(3)));
        assertFalse(visible.same(new BodyWound(id, WoundKind.TEAR, 9, .25f, .5f, 2, 1, 0, 0, 2, WoundShape.CUT)));
        assertFalse(visible.same(new BodyWound(id, WoundKind.TEAR, 6, .25f, .5f, 2, 1, 0, 0, 2, WoundShape.PUNCTURE)));
    }
}
