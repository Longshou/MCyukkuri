package io.github.longshou.mcyukkuri.family;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CanopyMeetingTest {
    @Test void bothReviewedStartsHaveAReachableRimRow() {
        // Pad centre (-307.5, 492.5); compare the observed adults and both chick rows.
        double parent=.8, child=.144, sole=child*.42, clearance=.015;
        double spacing=CanopyMeeting.spacing(parent,child,clearance);
        assertTrue(CanopyMeeting.withinReach(.508,spacing,.02,1,1));
        assertFalse(CanopyMeeting.withinReach(.52,spacing,.02,1,1));
        assertFalse(CanopyMeeting.withinReach(spacing,spacing,.02,1,.98));
        for (double chickSide : new double[]{-.22,.22}) {
            var rows=CanopyMeeting.candidates(parent,parent*.42,sole,spacing,.02,.0101,chickSide);
            var nearest=rows.getFirst();
            assertEquals(.0101,nearest.side());
            assertTrue(Math.abs(nearest.parent()-.9128)<.02);
            assertTrue(rows.size()>1 && rows.size()<=5);
            for (var row : rows) {
                assertTrue(row.parent()-parent/2>.5);
                assertTrue(row.parent()+parent*.42<1.5);
                assertTrue(Math.abs(row.side())+parent*.42+.02<=.5);
                assertTrue(Math.abs(row.child())+sole<=.5);
                assertEquals(spacing,row.parent()-row.child(),1e-8);
                for (int i=0;i<=20;i++) {
                    double f=i/20., at=.22+(row.child()-.22)*f, side=chickSide+(row.side()-chickSide)*f;
                    assertTrue(Math.max(Math.abs(at),Math.abs(side))+sole<=.5);
                    assertTrue(.9128-at>(parent+child)/2+clearance);
                }
            }
        }
    }

    @Test void idleReleaseWaitsForNewContactOrChangedSupport() {
        var refused=new CareReleaseSite(2,.3,1,false);
        assertFalse(refused.changedFrom(refused,.02));
        assertFalse(new CareReleaseSite(2.005,.3,1,false).changedFrom(refused,.02));
        assertTrue(new CareReleaseSite(2,.3,1,true).changedFrom(refused,.02));
        assertTrue(new CareReleaseSite(2,.3,1.2,false).changedFrom(refused,.02));
    }
}
