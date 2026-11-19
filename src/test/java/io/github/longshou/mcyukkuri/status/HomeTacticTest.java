package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.family.FamilyDutyMath;
import io.github.longshou.mcyukkuri.social.Temperament;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.longshou.mcyukkuri.status.HomeTactic.*;

class HomeTacticTest {
    private final Temperament mother = new Temperament(85, 80, 50, 90, 70);

    private void check(Temperament t, double margin, boolean atEight, int remaining, double score, int patience) {
        double need = 40 + 1.5 / .12;
        assertEquals(margin, slack(t), .00001);
        assertTrue(inTime(eta(12, 0), need, t));
        assertEquals(atEight, inTime(eta(8, .10), need, t));
        assertFalse(inTime(eta(16, .30), need, t));
        assertTrue(holdsThroughHit(remaining, 40, t));
        assertFalse(holdsThroughHit(remaining + 1, 40, t));
        assertEquals(score, lead(t, 19, 1, 2), .01);
        assertEquals(score >= LEAD_AT, lead(t, 19, 1, 2) >= LEAD_AT);
        assertEquals(patience, FamilyDutyMath.beckonPatience(t.attachment(), t.selfControl()));
    }

    @Test void familyResponses() {
        check(mother, .315, true, 12, .84, 610);
        check(new Temperament(50, 50, 60, 50, 40), .58, false, 8, .14, 430);
        check(new Temperament(40, 30, 20, 35, 25), .675, false, 7, 0, 355);
        assertEquals(.35, lead(new Temperament(30, 70, 80, 85, 40), 19, 1, 2), .01);
        assertEquals(520, leadTicks(mother));
        assertEquals(18.8, leadRange(mother), .00001);
        assertEquals(7, tauntGap(mother));
    }

    @Test void limits() {
        var low = new Temperament(0, 0, 0, 0, 0);
        var high = new Temperament(100, 100, 100, 100, 100);
        assertEquals(.95, slack(low), .00001);
        assertEquals(.15, slack(high), .00001);
        assertEquals(200, leadTicks(low));
        assertEquals(600, leadTicks(high));
        assertEquals(0, lead(mother, 19, 1, 0));
        assertEquals(0, lead(mother, 19, .49, 2));
    }
}
