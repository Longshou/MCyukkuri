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
