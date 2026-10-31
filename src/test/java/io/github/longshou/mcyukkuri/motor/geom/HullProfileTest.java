package io.github.longshou.mcyukkuri.motor.geom;

import io.github.longshou.mcyukkuri.headwear.HullProfile;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HullProfileTest {
    @Test void loadsBothExportedProfilesInTheirOwnFrame() {
        var current=HullProfile.of(HullProfile.CURRENT);var basin=HullProfile.of(HullProfile.BASIN);
        assertEquals("marisa_hat_turned",current.hull());assertEquals("aquatic_basin_hat_turned",basin.hull());
        assertEquals(.28,current.top()*current.measuredUnit(),1e-6);
        assertEquals(.4095,basin.top()*basin.measuredUnit(),1e-6);
        assertEquals(.0787,(basin.rimY()-basin.top())*basin.measuredUnit(),1e-6);
        assertEquals(.6344,basin.mouth(.37)*basin.measuredUnit(),1e-6);
        assertThrows(IllegalArgumentException.class,()->HullProfile.of("missing"));
    }
    @Test void supportRestsOnTheUndersideAtEveryRadius() {
        for(var id:new String[]{HullProfile.CURRENT,HullProfile.BASIN}){
            var p=HullProfile.of(id);double u=p.measuredUnit();
            for(double scale:new double[]{.145,.2,.45,1,1.2}){
                var body=BodyFootprint.of(.8*scale/u,.6*scale/u,0);
                for(double radius:new double[]{0,.075,.15}){
                    double y=p.support(radius/u,0,body);
                    assertTrue(Double.isFinite(y),id+" scale="+scale+" radius="+radius);
                    double gap=Double.POSITIVE_INFINITY;
                    for(var c:body.columns())gap=Math.min(gap,y+c.bottom()-p.surface(radius/u+c.x(),c.z()));
                    assertEquals(0,gap,1e-9);
                }
            }
            assertTrue(Double.isNaN(p.support(2/u,0,BodyFootprint.of(.8/u,.6/u,0))));
            double owner=p.support(0,0,BodyFootprint.of(.8/u,.6/u,0));
            assertEquals(p.data().getAsJsonObject("anchors").get("owner_y").getAsDouble(),(owner-p.top())*u,
                    p.tolerance()*u,id+" owner underside");
        }
    }
    @Test void basinHoldsTheStudysOwnerAndSixNewborns() {
        var p=HullProfile.of(HullProfile.BASIN);double u=p.measuredUnit();
        // layout.py: owner_6n / day / body / zero stores, model units; yaw is body-to-hull.
        double[][] positions={{0,0,0,1,4.03},{2.5,-6.5,20,.145,4},{-2.5,-6.5,20,.145,3.99},
                {5,-6.5,-20,.145,3.82},{-5,-6.5,20,.145,3.78},{7,-5.5,20,.145,2.73},{-6.5,-6,20,.145,2.76}};
        var bodies=new ArrayList<BodyFootprint>();var spots=new ArrayList<double[]>();
        for(var row:positions){
            var body=BodyFootprint.of(.8*row[3]/u,.6*row[3]/u,Math.toRadians(row[2]));
            double[] seat=null;
            for(double dx:new double[]{0,-.25,.25})for(double dz:new double[]{0,-.25,.25}){
                double x=(row[0]+dx)/16/u,z=(row[1]+dz)/16/u,y=p.support(x,z,body);
                boolean clear=Double.isFinite(y);
                for(int i=0;clear&&i<bodies.size();i++){var q=spots.get(i);clear=!body.overlaps(x,y,z,bodies.get(i),q[0],q[1],q[2],.03/16/u);}
                if(clear&&seat==null)seat=new double[]{x,y,z};
            }
            assertNotNull(seat,"no body room within one profile sample at "+row[0]+","+row[1]);
            double x=seat[0],y=seat[1],z=seat[2];
            assertTrue(Double.isFinite(y));
            assertTrue(body.columns().stream().allMatch(c->p.inOpening(x+c.x(),z+c.z())));
            // Combine the exported surface tolerance with one body-column sample at this child's scale.
            assertEquals(-row[4]/16,(y-p.top())*u,p.tolerance()*u+.8*row[3]/24,"study underside at "+row[0]+","+row[1]);
            for(int i=0;i<bodies.size();i++){
                var q=spots.get(i);
                assertFalse(body.overlaps(x,y,z,bodies.get(i),q[0],q[1],q[2],.03/16/u),"body collision at "+row[0]+","+row[1]);
            }
            bodies.add(body);spots.add(new double[]{x,y,z});
        }
        assertEquals(7,bodies.size());
    }
    @Test void currentLandCatchKeepsTheExistingFloorLedgesOpeningAndBrim() {
        var p=HullProfile.of(HullProfile.CURRENT);double u=1.0/16,tolerance=p.tolerance()*u;
        // Existing hat_vessel.json floor/ledge depths and catch lip, at land scale 1.
        double[][] sections={{0,0,7.029},{1,0,7.029},{2.5,0,4.8136},{3.5,0,2.2484},{5,0,0},{0,4,4.8136},{0,-5.5,.6}};
        for(var s:sections)assertEquals((3.2-s[2]*.2935)*u,p.surface(s[0],s[1])*u,tolerance,"land surface "+s[0]+","+s[1]);
        assertEquals(7.2132*u,p.reach(0)*u,tolerance);
        assertEquals(6.01*u,p.mouth(Math.PI/2)*u,tolerance);
        var newborn=BodyFootprint.of(.8*.145/u,.6*.145/u,0);
        assertEquals((3.2-7.029*.2935)*u,p.support(0,0,newborn)*u,tolerance);
        assertTrue(p.inOpening(0,5));assertFalse(p.inOpening(0,7));
    }
    @Test void bodyCollisionExcludesSoftAccessoriesAndSeparatesOnlyRealContact() {
        var adult=BodyFootprint.of(.8,.6,0);var baby=BodyFootprint.of(.116,.087,0);
        assertTrue(adult.radius()>.42,"the broad phase must include the body's diagonal corners");
        assertTrue(new Disc(0,0,.42).overlaps(new Disc(0,-.4,.078),0));
        assertFalse(adult.overlaps(0,0,0,baby,0,0,-.4,.002));
        assertTrue(adult.overlaps(0,0,0,baby,0,0,0,.002));
        assertFalse(adult.overlaps(0,0,0,baby,0,1,0,.002));
        assertTrue(adult.overlaps(0,0,0,baby,0,1,0,.002,1));
        var moves=Squeeze.separate(List.of(new Squeeze.Body(0,0,.42,1,.1),new Squeeze.Body(0,-.4,.078,.1,.1)),
                List.of(),0,0,0,(a,b)->adult.overlaps(a.x(),0,a.z(),baby,b.x(),0,b.z(),.002));
        assertEquals(0,moves[0][2]);assertEquals(0,moves[1][2]);
    }
}
