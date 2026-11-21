package io.github.longshou.mcyukkuri.mind;

import io.github.longshou.mcyukkuri.social.Temperament;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loneliness rate against the ordinary ease relief that MindLife.sample always puts in (−1 per minute): which
 * babies plateau below the syndrome and which climb to 95, and how long each takes after the grace.
 */
class LonelinessTest {
    /** MindLife.sample's base "ease" relief, per minute. */
    private static final float EASE = 1;
    /** The axes this pressure leaves alone sit at the middle of the wild draw. */
    private static final int MID = 50;

    private static Temperament temperament(int attachment, int boldness) {
        return new Temperament(MID, MID, boldness, attachment, MID);
    }

    /** Minutes alone after the grace until unease reaches 95 from 0, or −1 while the ease relief holds it down. */
    private static float minutesTo95(int attachment, int boldness) {
        float net = Loneliness.ratePerMinute(temperament(attachment, boldness)) - EASE;
        return net <= 1e-6f ? -1 : 95 / net;
    }

    @Test
    void companyNeededSpansTheWholeRangeAndTurnsOnAttachmentAndNerve() {
        assertEquals(Loneliness.LEAST, Loneliness.ratePerMinute(temperament(0, 50)), 1e-4);
        assertEquals(Loneliness.MOST, Loneliness.ratePerMinute(temperament(100, 0)), 1e-4);
        float last = -1;
        for (int attachment = 0; attachment <= 100; attachment += 10) {
            float rate = Loneliness.ratePerMinute(temperament(attachment, 0));
            assertTrue(rate >= last, "more attached, more need: attachment=" + attachment);
            last = rate;
        }
        last = Float.MAX_VALUE;
        for (int boldness = 0; boldness <= 100; boldness += 10) {
            float rate = Loneliness.ratePerMinute(temperament(100, boldness));
            assertTrue(rate <= last, "more nerve, less need: boldness=" + boldness);
            last = rate;
        }
        assertTrue(Loneliness.ratePerMinute(temperament(100, 0)) > Loneliness.ratePerMinute(temperament(0, 0)) + 1);
        assertTrue(Loneliness.ratePerMinute(temperament(100, 0)) > Loneliness.ratePerMinute(temperament(100, 100)) + 1);
        assertEquals(Loneliness.GRACE, 24000);
        assertEquals(Loneliness.SIGHT, 16, 0);
    }

    @Test
    void detachedAndBoldBabiesPlateauWhileAttachedTimidOnesReachTheSyndrome() {
        assertEquals(-1, minutesTo95(0, 0), .001);      // wants nobody: the ease relief wins
        assertEquals(-1, minutesTo95(50, 80), .001);    // too bold to mind: the ease relief wins
        assertEquals(38f, minutesTo95(100, 0), .01);    // the neediest: 3.5/min, 95 in 38 minutes
        assertEquals(95f, minutesTo95(50, 0), .01);     // half attached and fearless: 2/min
        assertEquals(380f, minutesTo95(50, 50), .01);   // the middle of the wild draw: 1.25/min
        assertEquals(3800f, minutesTo95(50, 59), .5);   // a hair short of coping: it climbs, but too slowly to see
    }

    @Test
    void theBreakEvenSitsWhereTheNeedIsOneSixth() {
        // Half attached (attachment 50) and two-thirds timid (boldness 60): need 1/6, exactly the ease relief.
        assertEquals(1f, Loneliness.ratePerMinute(temperament(50, 60)), 1e-5);
        assertEquals(-1, minutesTo95(50, 60), .001);
        assertTrue(minutesTo95(50, 55) > 0);
    }

    @Test
    void theWholeStatRangeSplitsIntoSurvivorsAndClimbersWithNoHiddenInstantDeath() {
        int plateaus = 0, climbers = 0;
        for (int attachment = 0; attachment <= 100; attachment += 5) {
            for (int boldness = 0; boldness <= 100; boldness += 5) {
                float rate = Loneliness.ratePerMinute(temperament(attachment, boldness));
                float minutes = minutesTo95(attachment, boldness);
                if (rate <= EASE + 1e-5f) {
                    plateaus++;
                    assertEquals(-1, minutes, .001, "attachment=" + attachment + " boldness=" + boldness);
                } else {
                    climbers++;
                    assertTrue(minutes >= 38f, "no baby reaches 95 faster than the neediest end");
                }
            }
        }
        // Of the 21x21 grid, 261 combos are held down by the ease relief and 180 climb.
        assertEquals(261, plateaus);
        assertEquals(180, climbers);
    }
}
