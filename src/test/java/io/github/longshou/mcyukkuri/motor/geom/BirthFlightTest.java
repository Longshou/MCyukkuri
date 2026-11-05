package io.github.longshou.mcyukkuri.motor.geom;

import io.github.longshou.mcyukkuri.headwear.HullProfile;
import java.util.function.BiFunction;
import java.util.function.ToDoubleFunction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BirthFlightTest {
    /** The first real cloth contact under the moving host, as the shared sweep finds it. */
    private static FlightContact.Hit contact(Vec start,BirthFlight.Drop plan,double unit,ToDoubleFunction<Vec> surface){
        return contact(start,plan,unit,(tick,p)->p,surface);
    }
    private static FlightContact.Hit contact(Vec start,BirthFlight.Drop plan,double unit,BiFunction<Integer,Vec,Vec> local,ToDoubleFunction<Vec> surface){
        var path=Ballistic.samples(plan.velocity(),plan.ticks()+1);
        for(int tick=1;tick<path.size();tick++){
            var hit=FlightContact.first(local.apply(tick-1,start.add(path.get(tick-1))),local.apply(tick,start.add(path.get(tick))),
                    FlightContact.STEP/unit,0,surface);
            if(hit!=null)return hit;
        }
        return null;
    }
    @Test void zeroLiftInterceptUsesTheDiscreteFallRatherThanContinuousGravity(){
        var start=new Vec(0,.2367684949769,0);
        var target=new Vec(.12976854994735,0,-.0133892792852);
        var plan=BirthFlight.drop(start,tick->target);
        assertNotNull(plan);
        assertEquals(4,plan.ticks());
        assertEquals(0,plan.velocity().y());
        assertTrue(plan.velocity().hlen()<.05);
        var landed=contact(start,plan,1,p->0);
        assertNotNull(landed);
        assertFalse(landed.wall());
        assertEquals(target.x(),landed.point().x(),1e-9);
        assertEquals(target.z(),landed.point().z(),1e-9);
        // The old two-tick impulse crosses the floor beyond the intended newborn's half-width.
        var old=new Vec(Ballistic.speedFor(target.x(),2),0,Ballistic.speedFor(target.z(),2));
        var missed=contact(start,new BirthFlight.Drop(target,old,4),1,p->0);
        assertTrue(missed.point().x()-target.x()>.05);
    }
    @Test void wholeNewbornLandsInsideTheHollowAcrossDriftDirectionsAndBirthSizes(){
        var hull=HullProfile.of(HullProfile.BASIN);
        double unit=hull.measuredUnit();
        for(double size:new double[]{.12,.125,.13})for(int side:new int[]{-1,1})
            for(double driftX:new double[]{-.04,0,.04})for(double driftZ:new double[]{-.04,0,.04}){
                var body=BodyFootprint.of("marisa",.8*size/unit,.6*size/unit,0);
                var target=new Vec(side*.125/unit,0,-.4766/unit);
                target=new Vec(target.x(),hull.support(target.x(),target.z(),body),target.z());
                var floor=target;
                var start=new Vec(0,.02,-.54);
                var drift=new Vec(driftX,0,driftZ);
                var plan=BirthFlight.drop(start,tick->floor.scale(unit).add(drift.scale(tick)));
                assertNotNull(plan);
                var landed=contact(start,plan,unit,
                        (tick,p)->p.sub(drift.scale(tick)).scale(1/unit),p->hull.support(p.x(),p.z(),body));
                assertNotNull(landed,"size="+size+" drift="+drift);
                assertFalse(landed.wall());
                double half=.8*size/2/unit;
                for(int x:new int[]{-1,1})for(int z:new int[]{-1,1})
                    assertTrue(hull.inOpening(landed.point().x()+x*half,landed.point().z()+z*half));
            }
    }
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
    @Test void risingPastARimIsAWallContactRatherThanALanding(){
        ToDoubleFunction<Vec> rim=p->p.x()*2;
        var hit=FlightContact.first(new Vec(0,0,0),new Vec(.1,.1,0),.05,.05,rim);
        assertNotNull(hit);assertTrue(hit.wall());
        var falling=FlightContact.first(new Vec(0,.2,0),new Vec(.1,.05,0),.05,.05,rim);
        assertNotNull(falling);assertFalse(falling.wall());
    }
    @Test void ordinaryFlightRetainsItsPenetrationTolerance(){
        assertNull(FlightContact.first(new Vec(0,.08,0),new Vec(0,.02,0),.05,.05,p->.03));
        assertNotNull(FlightContact.first(new Vec(0,.08,0),new Vec(0,.02,0),.05,0,p->.03));
    }
}
