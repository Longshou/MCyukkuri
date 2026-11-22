package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.social.SeveranceVerdict.Inputs;
import org.junit.jupiter.api.Test;
import static io.github.longshou.mcyukkuri.social.SeveranceVerdict.Verdict.*;
import static org.junit.jupiter.api.Assertions.*;

class SeveranceVerdictTest {
    private void check(Temperament t, int tier, boolean knows, int bond, boolean siblings,
                       double resolve, SeveranceVerdict.Verdict verdict) {
        var in = new Inputs(t, SeveranceVerdict.mold(tier, knows), bond, siblings);
        assertEquals(resolve, SeveranceVerdict.resolve(in), .005);
        assertEquals(verdict, SeveranceVerdict.decide(in));
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
        assertEquals(KEEP, SeveranceVerdict.decide(harsh, .399));
        assertEquals(DRIVE_COLD, SeveranceVerdict.decide(harsh, .400));
        assertEquals(DRIVE_TEARFUL, SeveranceVerdict.decide(new Temperament(50,15,50,85,20), .4));
        assertEquals(DRIVE_TEARFUL, SeveranceVerdict.decide(new Temperament(50,85,50,25,20), .4));
    }

    @Test void overloadSeverityGrowsWithTheLoadAndIsCapped() {
        assertEquals(.2, SeveranceVerdict.overload(1.25), 1e-9);
        assertEquals(.5, SeveranceVerdict.overload(2), 1e-9);
        assertEquals(.65, SeveranceVerdict.overload(10), 1e-9);
        assertEquals(0, SeveranceVerdict.overload(1), 1e-9);
    }

    @Test void anAverageParentKeepsAtALightOverloadAndDropsAtAHeavyOne() {
        var ordinary = new Temperament(55,70,55,60,70);
        assertEquals(KEEP, SeveranceVerdict.decide(new Inputs(ordinary, SeveranceVerdict.overload(1.25), 30, true)));
        var cool = new Temperament(60,50,45,50,65);
        assertEquals(DRIVE_TEARFUL, SeveranceVerdict.decide(new Inputs(cool, SeveranceVerdict.overload(2), 30, true)));
    }
}
