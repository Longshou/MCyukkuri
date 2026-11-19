package io.github.longshou.mcyukkuri.predator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NestSiteScoreTest {
    @Test void temperamentMovesBothPreferences() {
        assertEquals(6, NestSiteScore.preferredHeight(0));
        assertEquals(8, NestSiteScore.preferredHeight(.5));
        assertEquals(10, NestSiteScore.preferredHeight(1));
        assertEquals(8, NestSiteScore.minimumPathDistance(0));
        assertEquals(16, NestSiteScore.minimumPathDistance(.5));
        assertEquals(24, NestSiteScore.minimumPathDistance(1));
        assertTrue(NestSiteScore.score(6, 1, 32, 0, 0) > NestSiteScore.score(10, 1, 32, 0, 0));
        assertTrue(NestSiteScore.score(10, 1, 32, 0, 1) > NestSiteScore.score(6, 1, 32, 0, 1));
    }

    @Test void weightedTermsCloseAtTheDesignEndpoints() {
        assertEquals(0, NestSiteScore.score(12, 0, 0, 0, 0));
        assertEquals(0, NestSiteScore.score(4, 0, 0, 0, 1));
        assertEquals(.6, NestSiteScore.score(6, 1, 8, 0, 0), 1e-9);
        assertEquals(.8, NestSiteScore.score(6, 1, 12, 0, 0), 1e-9);
        assertEquals(1, NestSiteScore.score(6, 1, 16, 0, 0), 1e-9);
        assertEquals(1.2, NestSiteScore.score(10, 1, 32, 0, 1), 1e-9);
        assertEquals(1, NestSiteScore.score(10, 1, 40, 10, 1), 1e-9);
    }

    @Test void ordersByScoreThenDistance() {
        assertTrue(NestSiteScore.compare(.9, 7, .8, 4) > 0);
        assertTrue(NestSiteScore.compare(.8, 4, .8, 7) > 0);
        assertEquals(0, NestSiteScore.compare(.8, 7, .8, 7));
    }
}
