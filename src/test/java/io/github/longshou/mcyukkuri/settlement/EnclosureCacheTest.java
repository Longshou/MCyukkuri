package io.github.longshou.mcyukkuri.settlement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnclosureCacheTest {
    private final EnclosureCache cache = new EnclosureCache();
    private final Object beds = new Object();

    @Test void reusesBothResultsWithTheirOriginalShortCircuitCost() {
        assertEquals(0, cache.lookup(beds, 0, 1, 2, 3, 4));
        cache.remember(beds, 0, 1, 2, 3, 4, 5 << EnclosureCache.CHECK_SHIFT | EnclosureCache.PASS);
        assertEquals(5, cache.lookup(beds, 0, 1, 2, 3, 4));
        cache.remember(beds, 0, 1, 2, 3, 4, 2 << EnclosureCache.CHECK_SHIFT);
        assertEquals(-2, cache.lookup(beds, 0, 1, 2, 3, 4));
    }

    @Test void anyChunkEditOrReplacementInvalidatesTheResult() {
        cache.remember(beds, 0, 1, 2, 3, 4, 5 << EnclosureCache.CHECK_SHIFT);
        assertEquals(0, cache.lookup(beds, 0, 5, 2, 3, 4));
        assertEquals(0, cache.lookup(beds, 0, 1, 5, 3, 4));
        assertEquals(0, cache.lookup(beds, 0, 1, 2, 5, 4));
        assertEquals(0, cache.lookup(beds, 0, 1, 2, 3, 5));
    }

    @Test void layoutReloadTagsAndMissingChunksInvalidateTheResult() {
        cache.remember(beds, 0, 1, 2, 3, 4, 5 << EnclosureCache.CHECK_SHIFT);
        assertEquals(0, cache.lookup(new Object(), 0, 1, 2, 3, 4));
        assertEquals(0, cache.lookup(beds, 1, 1, 2, 3, 4));
        cache.remember(beds, 0, 0, 2, 3, 4, 5 << EnclosureCache.CHECK_SHIFT);
        assertEquals(0, cache.lookup(beds, 0, 0, 2, 3, 4));
        assertEquals(0, cache.lookup(beds, 0, 6, 2, 3, 4));
    }

    @Test void geometryDependentOnLiveStateIsCheckedAgain() {
        cache.remember(beds, 0, 1, 2, 3, 4, 5 << EnclosureCache.CHECK_SHIFT | EnclosureCache.LIVE_SHAPE);
        assertEquals(0, cache.lookup(beds, 0, 1, 2, 3, 4));
        cache.remember(beds, 0, 1, 2, 3, 4, 5 << EnclosureCache.CHECK_SHIFT | EnclosureCache.LIVE_SHAPE | EnclosureCache.PASS);
        assertEquals(0, cache.lookup(beds, 0, 1, 2, 3, 4));
    }
}
