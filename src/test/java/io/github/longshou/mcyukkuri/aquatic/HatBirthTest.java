package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.headwear.HullBounds;
import io.github.longshou.mcyukkuri.headwear.HullProfile;
import io.github.longshou.mcyukkuri.motor.geom.BodyFootprint;
import io.github.longshou.mcyukkuri.motor.geom.FloorSearch;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HatBirthTest {
    @Test void measuredNewbornsHaveSupportedSpaceAfterEachBellyDrop() {
        var hull=HullProfile.of(HullProfile.BASIN);
        for(int count:new int[]{1,2}) {
            double size=HullBounds.birthLimit(count),unit=HullBounds.UNIT;
            var child=BodyFootprint.envelope("marisa",.8*size/unit,.6*size/unit,0);
            var children=new ArrayList<FloorSearch.Placed>();
            for(int i=0;i<count;i++) {
                double belly=1-.78*(i+1)/count;
                var mother=BodyFootprint.envelope("marisa",.8*(1+.26*belly)*(1+.16*belly)/unit,.6*(1+.26*belly)/unit,0);
                var others=new ArrayList<>(children);
                others.add(new FloorSearch.Placed(mother,new Vec(HullBounds.HOME.x(),HullBounds.birthUnderside(belly),HullBounds.HOME.z()).scale(1/unit)));
                var p=HullBounds.birthSpots(count).get(i).scale(1/unit);
                var at=new Vec(p.x(),hull.support(p.x(),p.z(),child),p.z());
                assertTrue(Double.isFinite(at.y()));
                assertTrue(FloorSearch.clear(child,at,others,.02),"count="+count+" child="+i);
                children.add(new FloorSearch.Placed(child,at));
            }
        }
    }
    @Test void bellySupportSettlesContinuouslyWithinTheAuthoredRange() {
        double last=HullBounds.birthUnderside(0);
        for(int i=1;i<=100;i++) {
            double height=HullBounds.birthUnderside(i/100d);
            assertTrue(height>=last);
            assertTrue(height-last<.01);
            last=height;
        }
        assertTrue(last>HullBounds.HOME.y());
    }
}
