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
        var spot=NestLayout.choose(id,body,HALF,occupants,p->occupancy(p)==OccupancyMath.Result.INSIDE_SUPPORTED,false,false,ARRIVAL);
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

    private Integer bedAtFeet(Integer assigned, NestLayout.Place feet) {
        return OccupancyMath.touchesBedding(feet.x(), feet.z(), 0, feet.width()/2+ARRIVAL, floor, 0, 0) ? assigned : null;
    }

    private NestLayout.Place walk(NestLayout.Place feet, NestLayout.Place stand, Integer bed,
                                  NestLayout.Place peer, Integer peerBed, SeparationStep separation) {
        double x=stand.x()-feet.x(), z=stand.z()-feet.z(), distance=Math.hypot(x,z);
        double scale=distance>.03 ? .03/distance : 1;
        var step=new Vec(x*scale,0,z*scale);
        if (!NestLayout.ownsSpacing(bedAtFeet(bed,feet),bedAtFeet(peerBed,peer)))
            step=separation.crowd(feet.x()-peer.x(),feet.z()-peer.z(),(feet.width()+peer.width())/2,
                    false,1,1,step,s->true);
        return feet.at(feet.x()+step.x(),feet.z()+step.z());
    }

    @Test void bothAwakeResidentsCrossTheRimAndSleepAtTheirOwnStands() {
        var motherStand=admit(0,.88);
        var fatherStand=admit(1,.88);
        // DN-12 at t=4908, relative to the bed centre. Both are on the rim and driving toward each other.
        var mother=motherStand.at(.085087,.587615);
        var father=fatherStand.at(-.630073,-.099507);
        var motherStep=new SeparationStep();
        var fatherStep=new SeparationStep();
        assertEquals(OccupancyMath.Result.ON_RIM,occupancy(mother));
        assertEquals(OccupancyMath.Result.ON_RIM,occupancy(father));
        for (int tick=0;tick<60;tick++) {
            var nextMother=walk(mother,motherStand,1,father,1,motherStep);
            father=walk(father,fatherStand,1,mother,1,fatherStep);
            mother=nextMother;
        }
        assertEquals(motherStand,mother);
        assertEquals(fatherStand,father);
        assertEquals(OccupancyMath.Result.INSIDE_SUPPORTED,occupancy(mother));
        assertEquals(OccupancyMath.Result.INSIDE_SUPPORTED,occupancy(father));
        assertEquals(mother,NestLayout.choose(new UUID(0,0),mother,HALF,occupants,
                p->occupancy(p)==OccupancyMath.Result.INSIDE_SUPPORTED,true,false,ARRIVAL));
        assertEquals(father,NestLayout.choose(new UUID(0,1),father,HALF,occupants,
                p->occupancy(p)==OccupancyMath.Result.INSIDE_SUPPORTED,true,false,ARRIVAL));
        assertTrue(mother.gap(father)>=2*ARRIVAL);
    }

    private OccupancyMath.Result occupancy(NestLayout.Place feet) {
        return OccupancyMath.classify(feet.x(),feet.z(),0,feet.width()/2+ARRIVAL,floor,0,0,true);
    }

    @Test void aStrangerOnTheSameRimStillYields() {
        var stand=admit(0,.88);
        var walker=stand.at(.085087,.587615);
        var peer=stand.at(-.630073,-.099507);
        var resident=walk(walker,stand,1,peer,1,new SeparationStep());
        var stranger=walk(walker,stand,null,peer,1,new SeparationStep());
        assertTrue(Math.abs(resident.x()-peer.x())<walker.width());
        assertTrue(Math.abs(stranger.x()-peer.x())>=walker.width());
        assertEquals(stranger,walk(walker,stand,2,peer,1,new SeparationStep()));
        assertEquals(stranger,walk(walker,stand,1,peer,null,new SeparationStep()));
        assertFalse(NestLayout.ownsSpacing(null,null));
    }

    @Test void theBedReleasesResidentsBeyondItsRimOrOnAnotherFloor() {
        var baby=admit(0,.18);
        assertNull(bedAtFeet(1,baby.at(1.4,0))); // Within the old proximity radius, clear of the bed.
        assertFalse(NestLayout.ownsSpacing(bedAtFeet(1,baby),bedAtFeet(1,baby.at(1.4,0))));
        assertFalse(OccupancyMath.touchesBedding(0,0,1,baby.width()/2+ARRIVAL,floor,0,.2));
        assertFalse(OccupancyMath.touchesBedding(0,0,-1,baby.width()/2+ARRIVAL,floor,0,.2));
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
