package io.github.longshou.mcyukkuri.motor.geom;

import io.github.longshou.mcyukkuri.headwear.HullProfile;
import java.util.function.ToDoubleFunction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BirthFlightTest {
    @Test void forwardAimCannotBeBehindOrBesideTheOutlet(){
        var heading=new Vec(1,0,0);
        assertNull(Ballistic.forward(new Vec(-.2,-.1,0),heading,9));
        assertNull(Ballistic.forward(new Vec(0,-.1,.2),heading,9));
        var impulse=Ballistic.forward(new Vec(.6,-.1,0),heading,9);
        var end=Ballistic.samples(impulse,9).getLast();
        assertEquals(.6,end.x(),1e-9);assertEquals(-.1,end.y(),1e-9);assertEquals(0,end.z(),1e-9);
    }
    @Test void nominalPushKeepsFacingRatherThanSteeringAtAnOffAxisAim(){
        var impulse=Ballistic.forward(new Vec(.3,0,.4),new Vec(1,0,0),9);
        var end=Ballistic.samples(impulse,9).getLast();
        assertEquals(.5,end.x(),1e-9);assertEquals(0,end.z(),1e-9);
    }
    @Test void forwardArcMakesItsFirstContactInsideEachExportedCavity(){
        for(var id:new String[]{HullProfile.CURRENT,HullProfile.BASIN}){
            var hull=HullProfile.of(id);double unit=hull.measuredUnit(),radius=.07;
            ToDoubleFunction<Vec> surface=p->hull.footprint(p.x()/unit,p.z()/unit,radius/unit)*unit;
            var from=new Vec(-.2,hull.rimY()*unit+.1,0);
            var aim=new Vec(0,surface.applyAsDouble(Vec.ZERO),0);
            var velocity=Ballistic.forward(aim.sub(from),new Vec(1,0,0),9);
            var path=Ballistic.samples(velocity,10);FlightContact.Hit contact=null;
            for(int i=1;i<path.size()&&contact==null;i++)contact=FlightContact.first(from.add(path.get(i-1)),from.add(path.get(i)),.05,0,surface);
            assertNotNull(contact,id);assertFalse(contact.wall(),id);
            assertTrue(hull.inOpening(contact.point().x()/unit,contact.point().z()/unit),id);
            assertEquals(surface.applyAsDouble(contact.point()),contact.point().y(),.05,id);
        }
    }
    @Test void sidewaysEntryUnderTheSurfaceIsAWallAndAnAlreadyBuriedBodyIsNotALanding(){
        ToDoubleFunction<Vec> ledge=p->p.x()<0?Double.NaN:1;
        var wall=FlightContact.first(new Vec(-.1,.5,0),new Vec(.1,.5,0),.05,.05,ledge);
        assertNotNull(wall);assertTrue(wall.wall());
        assertNull(FlightContact.first(new Vec(.1,.5,0),new Vec(.1,.4,0),.05,.05,ledge));
    }
    @Test void lowCavityContactStopsBeforeTheGroundUnderIt(){
        var hit=FlightContact.first(new Vec(0,.035,0),new Vec(0,-.3,0),.05,0,p->.03);
        assertNotNull(hit);assertFalse(hit.wall());
        assertEquals(.03,hit.point().y(),1e-9);
    }
    @Test void ordinaryFlightRetainsItsPenetrationTolerance(){
        assertNull(FlightContact.first(new Vec(0,.08,0),new Vec(0,.02,0),.05,.05,p->.03));
        assertNotNull(FlightContact.first(new Vec(0,.08,0),new Vec(0,.02,0),.05,0,p->.03));
    }
}
