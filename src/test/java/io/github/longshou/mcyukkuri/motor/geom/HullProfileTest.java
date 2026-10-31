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
        assertEquals(.6344,basin.mouth(.37)*basin.measuredUnit(),.002);
        assertThrows(IllegalArgumentException.class,()->HullProfile.of("missing"));
    }
    @Test void supportRestsOnTheUndersideAtEveryRadius() {
        for(var id:new String[]{HullProfile.CURRENT,HullProfile.BASIN}){
            var p=HullProfile.of(id);double u=p.measuredUnit();
            for(double scale:new double[]{.145,.2,.45,1,1.2}){
                var body=BodyFootprint.of("marisa",.8*scale/u,.6*scale/u,0);
                for(double radius:new double[]{0,.075,.15}){
                    double y=p.support(radius/u,0,body);
                    assertTrue(Double.isFinite(y),id+" scale="+scale+" radius="+radius);
                    double gap=Double.POSITIVE_INFINITY;
                    for(var c:body.columns())gap=Math.min(gap,y+c.bottom()-p.surface(radius/u+c.x(),c.z()));
                    assertEquals(0,gap,1e-9);
                }
            }
            assertTrue(Double.isNaN(p.support(2/u,0,BodyFootprint.of("marisa",.8/u,.6/u,0))));
            double owner=p.support(0,0,BodyFootprint.of("marisa",.8/u,.6/u,0));
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
            // The study uses model rotation; entity yaw runs in the opposite direction in the host frame.
            var body=BodyFootprint.of("marisa",.8*row[3]/u,.6*row[3]/u,Math.toRadians(-row[2]));
            double[] seat=null;
            for(double dx:new double[]{0,-.25,.25})for(double dz:new double[]{0,-.25,.25}){
                double x=(row[0]+dx)/16/u,z=(row[1]+dz)/16/u,y=p.support(x,z,body);
                boolean clear=Double.isFinite(y);
                for(int i=0;clear&&i<bodies.size();i++){var q=spots.get(i);clear=!body.overlaps(x,y,z,bodies.get(i),q[0],q[1],q[2],.03/16/u);}
                if(clear&&(seat==null||Math.abs((y-p.top())*u+row[4]/16)<Math.abs((seat[1]-p.top())*u+row[4]/16)))seat=new double[]{x,y,z};
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
        var newborn=BodyFootprint.of("marisa",.8*.145/u,.6*.145/u,0);
        assertEquals((3.2-7.029*.2935)*u,p.support(0,0,newborn)*u,tolerance);
        assertTrue(p.inOpening(0,5));assertFalse(p.inOpening(0,7));
    }
    @Test void cornersAndOffAxisCatchPointsKeepTheAuthoredOutline() {
        var p=HullProfile.of(HullProfile.CURRENT);double u=p.measuredUnit();
        double[][] boxes={{-3.0763,1.9488,-3.7167,3.5033,7.029},{-3.7223,3.1586,-4,4.7833,4.8136},
                {-4.3683,4.3683,-6.01,6.01,0}};
        var floor=new FloorShape.Circle(Math.min(p.reach(0),p.reach(Math.PI/2))/16);
        double newbornRadius=.8*.145/2+.02;
        var newborn=BodyFootprint.of("marisa",.8*.145*16,.6*.145*16,0);
        for(var b:boxes)for(double x=b[0]+newbornRadius*16;x<=b[1]-newbornRadius*16;x+=.25)
            for(double z=b[2]+newbornRadius*16;z<=b[3]-newbornRadius*16;z+=.25)
                assertEquals(OccupancyMath.Result.INSIDE_SUPPORTED,OccupancyMath.classify(x/16,z/16,p.support(x,z,newborn)/16,
                        newbornRadius,floor,p.floor()/16,p.rimY()/16,true),"catch interior "+x+","+z);
        for(var b:boxes)for(double x:new double[]{b[0]+.03,b[1]-.03})for(double z:new double[]{b[2]+.03,b[3]-.03}){
            assertTrue(p.inOpening(x,z),"opening corner "+x+","+z);
            if(b[4]>0)assertEquals((3.2-b[4]*.2935)/16,p.surface(x,z)/16,.02,"well corner "+x+","+z);
            double px=x-Math.copySign(newbornRadius*16,x),pz=z-Math.copySign(newbornRadius*16,z);
            assertEquals(OccupancyMath.Result.INSIDE_SUPPORTED,OccupancyMath.classify(px/16,pz/16,p.floor()/16,
                    newbornRadius,floor,p.floor()/16,p.rimY()/16,true));
        }
        // Off-axis outer corners of the centre and side brim rectangles, measured from the exported prop.
        for(double[] v:new double[][]{{7.2132,7.7867},{-6.5143,7.7867},{6.5307,-9.1733},{-6.0583,-9.1733},
                {5.1657,10.1333},{-5.0703,10.1333},{5.1657,-10.1333},{-5.0703,-10.1333}}){
            double a=Math.atan2(v[1],v[0]),r=Math.hypot(v[0],v[1]);
            assertEquals(r*u,p.reach(a)*u,.02,"brim corner");
        }
        for(var id:new String[]{HullProfile.CURRENT,HullProfile.BASIN})for(var point:HullProfile.of(id).rimPoints())
            assertTrue(Double.isFinite(point.y()),id+" rim "+point);
    }
    @Test void authoredWornGripsAndBothFormsStandClearOfTheirBrims() {
        var data=GeometryJson.read("yukkuri_presentation/hat_vessel.json").data();
        double[][] grips={{6,10.85,-6.9},{7.5,10.85,-6.9},{10,10.85,-3.4},{5.5,10.85,-6.9}};
        int i=0;
        for(var kind:List.of("land","aquatic"))for(var role:List.of("helper","self")){
            var p=data.getAsJsonObject("placements").getAsJsonObject(kind).getAsJsonObject(role);
            var grip=p.getAsJsonArray("grip_worn");
            for(int k=0;k<3;k++)assertEquals(grips[i][k],grip.get(k).getAsDouble(),1e-6);
            i++;
            if(!kind.equals("land"))continue;
            double x=p.getAsJsonArray("offset").get(0).getAsDouble(),z=p.getAsJsonArray("offset").get(2).getAsDouble(),yaw=p.get("yaw").getAsDouble();
            for(var id:List.of(HullProfile.CURRENT,HullProfile.BASIN)){
                var form=HullProfile.of(id);var offset=form.placementOffset(x,z,yaw);
                if(id.equals(HullProfile.CURRENT)){assertEquals(x,offset.x(),1e-9);assertEquals(z,offset.z(),1e-9);}
                double a=Math.toRadians(yaw-180),c=Math.cos(a),sin=Math.sin(a);
                double lx=-offset.x()*c-offset.z()*sin,lz=-offset.z()*c+offset.x()*sin,length=Math.hypot(lx,lz);
                double r=(.8/2+.02+.13)/form.measuredUnit(),distance=form.standDistance(lx/length,lz/length,r,length);
                assertTrue(form.clearance(lx/length*distance,lz/length*distance)>=r-1e-6);
                double originalAngle=Math.atan2(-z*c+x*sin,-x*c-z*sin);
                assertEquals(Math.hypot(x,z)+form.reach(originalAngle)-HullProfile.of(HullProfile.CURRENT).reach(originalAngle),length,1e-6);
            }
        }
    }
    @Test void shapeCacheQuantizesYawAndUsesEachSpeciesBody() {
        var a=BodyFootprint.of("marisa",.8,.6,.001);
        assertSame(a,BodyFootprint.of("marisa",.8,.6,.002));
        var reimu=BodyFootprint.of("reimu",.8,.6,0);
        assertNotSame(a.shape(),reimu.shape());
        assertFalse(a.columns().equals(BodyFootprint.of("reimu_bean",.8,.6,0).columns()));
        for(var id:List.of("reimu_bean","house_marisa","remilia"))
            assertFalse(BodyFootprint.of(id,.8,.6,0).columns().isEmpty());
    }
    @Test void malformedResourceReportsItsFileAndField() {
        var data=new com.google.gson.JsonObject();var source=new GeometryJson("fixture.json",data);
        var error=assertThrows(IllegalArgumentException.class,()->source.number("keel.y"));
        assertTrue(error.getMessage().contains("fixture.json: keel.y"));
    }
    @Test void bodyCollisionExcludesSoftAccessoriesAndSeparatesOnlyRealContact() {
        var adult=BodyFootprint.of("marisa",.8,.6,0);var baby=BodyFootprint.of("marisa",.116,.087,0);
        assertTrue(adult.radius()>.42,"the broad phase must include the body's diagonal corners");
        assertTrue(new Disc(0,0,.42).overlaps(new Disc(0,-.4,.078),0));
        assertFalse(adult.overlaps(0,0,0,baby,0,0,-.4,.002));
        assertTrue(adult.overlaps(0,0,0,baby,0,0,0,.002));
        assertFalse(adult.overlaps(0,0,0,baby,0,1,0,.002));
        assertTrue(adult.overlaps(0,0,0,baby,0,1,0,.002,1));
        var moves=Squeeze.separate(List.of(new Squeeze.Body(0,0,.42,1,.1),new Squeeze.Body(0,-.4,.078,.1,.1)),
                List.of(),0,0,0,(a,b,dx,dz)->adult.overlaps(a.x()+dx,0,a.z()+dz,baby,b.x(),0,b.z(),.002));
        assertEquals(0,moves[0][2]);assertEquals(0,moves[1][2]);
    }

    @Test void fullBasinFoodContactSettlesInsteadOfOscillating() {
        var hull=HullProfile.of(HullProfile.BASIN);
        double unit=.08749999850988388,r=.09/unit;
        var food=new ArrayList<Disc>();
        // The finite larder layout and mother's dimensions from the full-basin smoke readout.
        for(double[] p:new double[][]{{5.278571446,-3.221428554},{-3.221428554,5.278571446},{3.278571446,-5.221428554},
                {-5.221428554,3.278571446},{3.778571446,4.778571446},{-5.721428554,-2.221428554},{1.778571446,5.778571446},
                {-2.221428554,-5.721428554},{5.778571446,1.778571446},{-1.721428554,5.778571446},{5.778571446,-1.721428554},
                {-5.721428554,-1.721428554},{2.778571446,-5.221428554},{-1.721428554,-5.721428554},{.778571446,-5.721428554},
                {-4.221428554,-4.221428554}}){
            double floor=hull.footprint(p[0],p[1],r);
            food.add(new Disc(p[0],p[1],r,floor,floor+.06/unit));
        }
        double max=0;
        for(int yaw:new int[]{10,30,145,155}){
            double x=0,z=0,y=1.7988122548867118;
            for(double belly:new double[]{1,.74,.48}){
                var body=BodyFootprint.of("marisa",.8*(1+.26*belly)*(1+.16*belly)/unit,.6*(1+.26*belly)/unit,Math.toRadians(yaw));
                for(int tick=0;tick<400;tick++){
                    var move=Squeeze.separate(List.of(new Squeeze.Body(x,z,body.radius(),1,.06/unit,y,y+body.height())),food,.02,0,0,
                            (a,b,dx,dz)->body.overlaps(a.x()+dx,dx==0&&dz==0?a.bottom():hull.support(a.x()+dx,a.z()+dz,body),a.z()+dz,b,.02))[0];
                    double nextY=Math.hypot(move[0],move[1])<1e-6?y:hull.support(x+move[0],z+move[1],body);
                    double moved=unit*Math.sqrt(move[0]*move[0]+move[1]*move[1]+(nextY-y)*(nextY-y));
                    max=Math.max(max,moved);
                    if(tick>=350)assertTrue(moved<.001,"stationary after real separation yaw="+yaw+" belly="+belly+": "+moved);
                    x+=move[0];z+=move[1];y=nextY;
                }
            }
        }
        assertTrue(max>.02,"initial real displacement still exceeds the delivery hold threshold");
    }
}
