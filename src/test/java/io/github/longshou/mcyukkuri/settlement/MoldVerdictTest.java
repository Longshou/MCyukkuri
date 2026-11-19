package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.settlement.MoldVerdict.Inputs;
import io.github.longshou.mcyukkuri.social.Temperament;
import org.junit.jupiter.api.Test;
import static io.github.longshou.mcyukkuri.settlement.MoldVerdict.Verdict.*;
import static org.junit.jupiter.api.Assertions.*;

class MoldVerdictTest {
    private void check(Temperament t, int tier, boolean knows, int bond, boolean siblings,
                       double resolve, MoldVerdict.Verdict verdict) {
        var in = new Inputs(t, tier, knows, bond, siblings);
        assertEquals(resolve, MoldVerdict.resolve(in), .005);
        assertEquals(verdict, MoldVerdict.decide(in));
    }

    @Test void householdChoices() {
        check(new Temperament(50,25,60,30,40), 2,true,20,true, .67, DRIVE_COLD);
        check(new Temperament(40,15,70,25,20), 1,true,10,false, .46, DRIVE_COLD);
        check(new Temperament(50,30,50,40,55), 2,false,20,true, .46, DRIVE_COLD);
        check(new Temperament(60,50,45,50,65), 2,true,30,true, .47, DRIVE_TEARFUL);
        var attached = new Temperament(55,60,50,65,80);
        check(attached, 2,true,30,true, .39, KEEP);
        check(attached, 3,true,30,true, .59, DRIVE_TEARFUL);
        var ordinary = new Temperament(55,70,55,60,70);
        check(ordinary, 2,true,30,false, .22, KEEP);
        check(ordinary, 3,true,30,false, .42, DRIVE_TEARFUL);
        check(new Temperament(50,70,40,85,35), 2,true,40,true, .055, KEEP);
        check(new Temperament(50,50,50,50,50), 2,false,30,true, .25, KEEP);
    }

    @Test void aColdPushRequiresBothHardnessAndLowAttachment() {
        var harsh = new Temperament(50,15,50,25,20);
        assertEquals(KEEP, MoldVerdict.decide(harsh, .399));
        assertEquals(DRIVE_COLD, MoldVerdict.decide(harsh, .400));
        assertEquals(DRIVE_TEARFUL, MoldVerdict.decide(new Temperament(50,15,50,85,20), .4));
        assertEquals(DRIVE_TEARFUL, MoldVerdict.decide(new Temperament(50,85,50,25,20), .4));
    }
}
