package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.motor.geom.FloorShape;
import io.github.longshou.mcyukkuri.motor.geom.OccupancyMath;
import io.github.longshou.mcyukkuri.motor.geom.SeparationStep;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NestLayoutTest {
    private static final double HALF=14.6/16, ARRIVAL=.02;
    private final LinkedHashMap<UUID,NestLayout.Place> occupants=new LinkedHashMap<>();
    private final FloorShape floor=new FloorShape.RoundedRect(HALF,8/16.);

    private NestLayout.Place admit(int member,double scale){
        var id=new UUID(0,member);
        var body=new NestLayout.Place(0,0,(float)(.8*scale),.6875*scale);
        var spot=NestLayout.choose(id,body,HALF,occupants,p->OccupancyMath.classify(p.x(),p.z(),0,p.width()/2+ARRIVAL,
                floor,0,.2,true)==OccupancyMath.Result.INSIDE_SUPPORTED,false,false,ARRIVAL);
        if(spot!=null)occupants.put(id,spot);
        return spot;
    }

    @Test void aFamilyFitsWithRealClearanceAndAnOverfullBedKeepsItsLayout(){
        for(int i=0;i<6;i++)assertNotNull(admit(i,i<2?.88:.20));
        for(var a:occupants.values())for(var b:occupants.values())if(a!=b)assertTrue(a.gap(b)>=2*ARRIVAL);
        var before=new LinkedHashMap<>(occupants);
        assertNull(admit(6,1.8));
        assertEquals(before,occupants);
    }

    @Test void realWidthCanFillTheLargePadAlone(){
        assertNotNull(admit(0,1.8));
        assertNull(admit(1,1.8));
    }

    @Test void returningFatherCanBoardAfterTheBabyLeavesHisStand() {
        // DN-12 night two: the father is pressed on the rim and the awake baby covers his assigned place.
        var father = new NestLayout.Place(-931.26814, 304.11883, .704f, .605);
        var destination = father.at(-931.020, 303.980);
        var baby = new NestLayout.Place(-930.82847, 303.68853, .144f, .124);
        var babyBed = baby.at(-930.855, 302.797);
        var motherBed = father.at(-931.98044, 303.01956);
        var childBed = baby.at(-932.20329, 304.14468);
        assertFalse(NestLayout.ownsSpacing(false, false, () -> true));
        assertFalse(NestLayout.ownsSpacing(false, true, () -> false));
        assertTrue(destination.gap(baby) < 0);
        var wanted = new Vec(.03, 0, -.017);
        var separation = new SeparationStep();
        double width = (father.width() + baby.width()) * .5;
        var diverted = separation.crowd(father.x()-baby.x(), father.z()-baby.z(), width, true, 1, 1, wanted, s -> true);
        assertNotEquals(wanted, diverted);
        assertTrue(destination.gap(babyBed) > 2 * ARRIVAL);
        assertTrue(NestLayout.ownsSpacing(true, false, () -> false)); // The baby holds its assigned stand.
        assertTrue(NestLayout.ownsSpacing(false, true, () -> true));

        // Approach from either the recorded rim or directly alongside the sleeping baby.
        for (var start : new NestLayout.Place[]{father, father.at(-931.25, 302.90)}) {
            var feet = start;
            for (int tick = 0; tick < 60; tick++) {
                double x = destination.x()-feet.x(), z = destination.z()-feet.z();
                if (Math.hypot(x, z) <= ARRIVAL) break;
                double scale = Math.min(1, .03 / Math.hypot(x, z));
                var step = new Vec(x * scale, 0, z * scale);
                if (!NestLayout.ownsSpacing(false, true, () -> true))
                    step = separation.crowd(feet.x()-babyBed.x(), feet.z()-babyBed.z(), width, true, 1, 1, step, s -> true);
                feet = feet.at(feet.x()+step.x(), feet.z()+step.z());
            }
            assertTrue(Math.hypot(destination.x()-feet.x(), destination.z()-feet.z()) <= ARRIVAL);
            for (var sleeper : new NestLayout.Place[]{motherBed, childBed, babyBed})
                assertTrue(feet.gap(sleeper) > 2 * ARRIVAL);
        }
    }

    @Test void aNewcomerCannotMoveAnExistingResidentEvenIfRepackingWouldMakeRoom() {
        var resident=new UUID(0,0);
        var place=new NestLayout.Place(0,0,.7f,.58);
        occupants.put(resident,place);
        assertNull(admit(1,.88));
        assertEquals(place,occupants.get(resident));
        assertNull(NestLayout.choose(resident,place,HALF,occupants,p->p.x()!=0,true,false,ARRIVAL));
        assertEquals(place,occupants.get(resident));
    }
}
