package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.motor.geom.FloorShape;
import io.github.longshou.mcyukkuri.motor.geom.FlightCourse;
import io.github.longshou.mcyukkuri.motor.geom.OccupancyMath;
import io.github.longshou.mcyukkuri.motor.geom.SeparationStep;
import io.github.longshou.mcyukkuri.motor.geom.SeparationStep.Turn;
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

    private OccupancyMath.Result occupancy(NestLayout.Place feet) {
        return OccupancyMath.classify(feet.x(),feet.z(),0,feet.width()/2+ARRIVAL,floor,0,0,true);
    }

    private final class Walker {
        final NestLayout.Place stand;
        final int id;
        final SeparationStep separation = new SeparationStep();
        NestLayout.Place feet;
        Integer bed = 1;
        boolean walking, sleeping, pressed;

        Walker(int id, NestLayout.Place stand, double x, double z, boolean walking) {
            this.id = id;
            this.stand = stand;
            this.feet = stand.at(x,z);
            this.walking = walking;
        }

        float distance() {
            if (sleeping) return NestLayout.SETTLED;
            double dx = stand.x()-feet.x(), dz = stand.z()-feet.z();
            return walking ? (float)(dx*dx + dz*dz) : NestLayout.INACTIVE;
        }

        Turn turn(Walker peer) {
            var own = bedAtFeet(bed,feet);
            return own != null && own.equals(bedAtFeet(peer.bed,peer.feet))
                    ? NestLayout.turn(distance(),peer.distance(),Integer.compare(id,peer.id)) : Turn.ORDINARY;
        }

        void tick(Walker peer) {
            var turn = turn(peer);
            double dx = stand.x()-feet.x(), dz = stand.z()-feet.z();
            double length = Math.hypot(dx,dz), scale = length > .03 ? .03/length : 1;
            var wanted = walking && turn != Turn.WAIT ? new Vec(dx*scale,0,dz*scale) : Vec.ZERO;
            double x = feet.x()-peer.feet.x(), z = feet.z()-peer.feet.z();
            double width = (feet.width()+peer.feet.width())*.5;
            boolean touching = !SeparationStep.clear(x,z,turn.clearance(width,pressed),wanted.x(),wanted.z());
            var step = separation.step(turn,x,z,width,pressed,Integer.compare(id,peer.id),1,wanted,
                    v -> Math.max(Math.abs(x+v.x()),Math.abs(z+v.z())) >= width
                            || FlightCourse.clearsNeighbour(Math.max(Math.abs(x),Math.abs(z))<width,x,z,v.x(),v.z()));
            pressed = touching;
            feet = feet.at(feet.x()+step.x(),feet.z()+step.z());
            if (walking && feet.equals(stand)) {
                sleeping = true;
                walking = false;
            }
        }

        double clearance(Walker peer) {
            return Math.max(Math.abs(feet.x()-peer.feet.x()),Math.abs(feet.z()-peer.feet.z()))
                    -(feet.width()+peer.feet.width())*.5;
        }
    }

    @Test void bothAwakeResidentsBoardInOrderWithoutCrossingEachOthersBoxes() {
        // DN-12, two awake entrants at t=4908. Repeat in both entity tick orders.
        for (boolean leaderTicksFirst : new boolean[]{true,false}) {
            var mother = new Walker(0,admit(0,.88),.085087,.587615,true);
            var father = new Walker(1,admit(1,.88),-.630073,-.099507,true);
            replayEntry(mother,father,leaderTicksFirst);
        }
    }

    private void replayEntry(Walker first, Walker second, boolean leaderTicksFirst) {
        assertEquals(Turn.PASS,first.turn(second));
        assertEquals(Turn.WAIT,second.turn(first));
        int firstArrival = -1, secondArrival = -1;
        var a = leaderTicksFirst ? first : second;
        var b = leaderTicksFirst ? second : first;
        for (int tick=0;tick<200;tick++) {
            a.tick(b);
            assertTrue(first.clearance(second)>=0,"overlap after first move at "+tick);
            b.tick(a);
            assertTrue(first.clearance(second)>=0,"overlap after second move at "+tick);
            if (first.sleeping && firstArrival<0) firstArrival=tick;
            if (second.sleeping && secondArrival<0) secondArrival=tick;
        }
        assertTrue(firstArrival>=0 && firstArrival<secondArrival, "arrivals "+firstArrival+", "+secondArrival+" feet "+first.feet+", "+second.feet);
        assertEquals(first.stand,first.feet);
        assertEquals(second.stand,second.feet);
        assertEquals(OccupancyMath.Result.INSIDE_SUPPORTED,occupancy(first.feet));
        assertEquals(OccupancyMath.Result.INSIDE_SUPPORTED,occupancy(second.feet));
        assertTrue(first.feet.gap(second.feet)>=2*ARRIVAL);
    }

    @Test void anEntrantPassesTheSettlingFatherWithoutMovingHim() {
        var mother = new Walker(0,admit(0,.88),.066507,1.215741,true); // DN-12 t=4373, before the boxes crossed.
        var stand = admit(1,.88);
        var father = new Walker(1,stand,stand.x(),stand.z(),false);
        father.sleeping = true;
        for (int tick=0;tick<200;tick++) {
            mother.tick(father);
            father.tick(mother);
            assertTrue(father.clearance(mother)>=0);
            assertEquals(stand,father.feet);
        }
        assertEquals(mother.stand,mother.feet);
    }

    @Test void aBoarderWaitingAcrossTheLeadersStandMakesRoom() {
        var a = new Walker(0,admit(0,.88),-.48,.26,true);
        var b = new Walker(1,admit(1,.88),a.stand.x(),a.stand.z(),true);
        replayEntry(a,b,true);
    }

    @Test void idleResidentsSeparateOnTheShopRimAndStayApart() {
        // SP-01 t=7330, relative to the occupied bed. Both 0.16-wide bodies have zero horizontal velocity.
        var baby = new NestLayout.Place(0,0,.16f,.1375);
        var a = new Walker(0,baby,.020215,-.570515,false);
        var b = new Walker(1,baby,-.102284,-.547033,false);
        assertEquals(1,bedAtFeet(a.bed,a.feet));
        assertEquals(1,bedAtFeet(b.bed,b.feet));
        assertEquals(Turn.ORDINARY,a.turn(b));
        assertTrue(a.clearance(b)<0);
        for (int tick=0;tick<578;tick++) {
            a.tick(b);
            b.tick(a);
            assertTrue(a.clearance(b)>=0);
        }
    }

    @Test void aSleeperKeepsItsStandAndOnlyItsOwnBedGrantsPriority() {
        var a = new Walker(0,admit(0,.88),.085087,.587615,true);
        var b = new Walker(1,admit(1,.88),-.630073,-.099507,false);
        assertEquals(Turn.PASS,a.turn(b));
        assertEquals(Turn.YIELD,b.turn(a));
        b.bed = 2;
        assertEquals(Turn.ORDINARY,a.turn(b));
        b.bed = null;
        assertEquals(Turn.ORDINARY,b.turn(a));
        b.feet = b.feet.at(a.feet.x()+.68,a.feet.z());
        double before = a.clearance(b);
        b.tick(a);
        assertTrue(a.clearance(b)>before);
        b.bed = 1;
        b.feet = b.stand;
        b.sleeping = true;
        a.feet = a.feet.at(b.feet.x()-.68,b.feet.z());
        b.tick(a);
        assertEquals(b.stand,b.feet);
        assertEquals(Turn.AROUND,a.turn(b));
        assertEquals(Turn.PASS,NestLayout.turn(.5f,.5f,-1));
        assertEquals(Turn.WAIT,NestLayout.turn(.5f,.5f,1));
    }

    @Test void theBedReleasesResidentsBeyondItsRimOrOnAnotherFloor() {
        var baby=admit(0,.18);
        assertNull(bedAtFeet(1,baby.at(1.4,0)));
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
