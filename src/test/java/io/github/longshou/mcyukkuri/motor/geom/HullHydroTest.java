package io.github.longshou.mcyukkuri.motor.geom;

import io.github.longshou.mcyukkuri.headwear.HullProfile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HullHydroTest {
    private static final String[] FORMS={HullProfile.CURRENT,HullProfile.BASIN};
    private static final double ADULT=Buoyancy.bodyMass(.8,.6,Buoyancy.BODY_DENSITY);

    @Test void emptyAndLoadedDraftDisplaceTheirMassInBothRealShells() {
        for(var id:FORMS) {
            var p=HullProfile.of(id);var h=HullHydro.of(p);double u=p.measuredUnit(),mass=h.hull(u).mass();
            var empty=h.balance(u,mass,0,0);var loaded=h.balance(u,mass+ADULT,0,0);
            assertEquals(p.data().getAsJsonObject("volume").get("empty_waterline_y").getAsDouble(),empty.draft()-p.top()*u,.003,id);
            assertEquals(mass,empty.volume(),1e-8);assertEquals(mass+ADULT,loaded.volume(),1e-8);
            var capacity=h.sample(u,0,0,p.rimY()*u);
            assertEquals(p.data().getAsJsonObject("volume").get("to_rim").getAsDouble(),capacity.volume(),.004,id+" capacity");
            assertTrue(loaded.draft()>empty.draft());assertTrue(loaded.clearance()>0);
            assertTrue(loaded.plane().area()>empty.plane().area());
            // A central finite difference is an independent check on the measured waterplane area.
            double delta=1e-6;
            assertEquals(loaded.plane().area(),(h.sample(u,0,0,loaded.draft()+delta).volume()-h.sample(u,0,0,loaded.draft()-delta).volume())/(2*delta),1e-7);
            var eq=equilibrium(h,u,h.hull(u));
            assertTrue(eq.stable(),id+" empty equilibrium");
        }
    }

    @Test void tiltedWaterplanesRestoreAStableLoadAndDetectTheActualWetRim() {
        for(var id:FORMS) {
            var p=HullProfile.of(id);var h=HullHydro.of(p);double u=p.measuredUnit();
            var load=h.hull(u).add(ADULT,new Vec(0,p.floor()*u+.3,0));
            for(double angle:new double[]{-5,5}) {
                var roll=h.balance(u,load.mass(),angle,0);var pitch=h.balance(u,load.mass(),0,angle);
                assertTrue(roll.moment(load,angle,0).x()*angle<0,id+" restoring roll");
                assertTrue(pitch.moment(load,0,angle).z()*angle<0,id+" restoring pitch");
                assertEquals(load.mass(),roll.volume(),1e-8);assertEquals(load.mass(),pitch.volume(),1e-8);
            }
            double flood=floodAngle(h,u,load.mass(),1,0);
            var dry=h.balance(u,load.mass(),flood-.1,0);var wet=h.balance(u,load.mass(),flood+.1,0);
            assertTrue(dry.clearance()>0);assertTrue(dry.ratio()<1);
            assertTrue(wet.clearance()<0);assertTrue(wet.ratio()>1);
            assertTrue(h.waterline().update(u,flood-.1,0,dry.draft())>3);
            assertEquals(0,h.waterline().update(u,flood+.1,0,wet.draft()));
            assertTrue(h.balance(u,2,0,0).clearance()<=0,"overload remains real");
        }
    }

    @Test void studyOwnerAndSixNewbornsStayBelowFiveDegreesInTheBasin() {
        // layout.py owner_6n / body / day, zero and twelve stores: total mass and mesh-derived G, in model units.
        double[][] fixtures={{.1285,-.07,.52,.91,3.86,23.5},{.1502,-.06,.13,.78,3.99,21.75}};
        for(var id:FORMS)for(var row:fixtures) {
            var p=HullProfile.of(id);var h=HullHydro.of(p);double u=p.measuredUnit();
            var load=Buoyancy.Load.ZERO.add(row[0],new Vec(row[1]/16,p.top()*u+row[2]/16,row[3]/16));
            var eq=equilibrium(h,u,load);
            if(id.equals(HullProfile.BASIN)) {
                assertTrue(eq.stable());assertEquals(row[4],Math.hypot(eq.roll(),eq.pitch()),.75);assertTrue(Math.hypot(eq.roll(),eq.pitch())<=5);
                double flood=floodAngle(h,u,load.mass(),eq.roll()/Math.hypot(eq.roll(),eq.pitch()),eq.pitch()/Math.hypot(eq.roll(),eq.pitch()));
                assertEquals(row[5],flood,1.5);assertTrue(eq.water().clearance()>0);
            }
        }
    }

    @Test void rampedRimLoadingStillFloodsAndMovingLoadsRemainContinuous() {
        for(var id:FORMS) {
            var p=HullProfile.of(id);var h=HullHydro.of(p);double u=p.measuredUnit();
            var hanging=h.hull(u).add(ADULT*.25,new Vec(0,p.rimY()*u,-p.reach(-Math.PI/2)*u));
            assertFalse(equilibrium(h,u,hanging).stable(),id+" adult hanging 25% on the rim");
            var owner=h.hull(u).add(ADULT,new Vec(0,p.floor()*u+.3,0));
            double baby=ADULT*Math.pow(.145,3);
            double previous=h.balance(u,owner.mass(),0,0).draft();
            for(int i=1;i<=100;i++) {
                var carried=owner.add(baby,new Vec(.1,p.floor()*u+.1,i*.003));
                var water=h.balance(u,carried.mass(),0,0);
                assertTrue(Math.abs(water.draft()-previous)<.001);previous=water.draft();
                var moved=owner.add(baby,new Vec(.1,p.floor()*u+.1,i*.003+.000001));
                assertEquals(water.moment(carried,0,0).z(),water.moment(moved,0,0).z(),baby*.000002);
            }
        }
    }

    @Test void verticalShellSamplingIsFiniteAndContinuous(){
        for(var id:FORMS){
            var p=HullProfile.of(id);var h=HullHydro.of(p);double u=p.measuredUnit(),mass=h.hull(u).mass()+ADULT;
            for(double pitch:new double[]{0,37,90}){
                var at=h.balance(u,mass,90,pitch);
                for(double roll:new double[]{89.999999,90,90.000001}){
                    var s=h.balance(u,mass,roll,pitch);
                    assertEquals(mass,s.volume(),1e-8);assertEquals(at.draft(),s.draft(),1e-6);
                    assertEquals(at.plane().area(),s.plane().area(),1e-5);
                    assertTrue(Double.isFinite(s.plane().rollInertia()));assertTrue(Double.isFinite(s.plane().pitchInertia()));
                }
            }
            var full=h.balance(u,2,0,0);
            assertEquals(h.bounds(u,0,0).top(),full.draft());assertTrue(full.volume()<2);assertTrue(full.ratio()>=1);
        }
    }

    @Test void maskTrianglesCoverTheTiltedSectionIncludingItsHoles(){
        for(var id:FORMS){
            var p=HullProfile.of(id);var h=HullHydro.of(p);double u=p.measuredUnit();var line=h.waterline();
            for(double roll:new double[]{-5,0,3})for(double pitch:new double[]{-3,0,5}){
                var water=h.balance(u,h.hull(u).mass()+ADULT,roll,pitch);
                int n=line.update(u,roll,pitch,water.draft());
                if(water.clearance()<=0){assertEquals(0,n);continue;}
                double area=0;
                for(int i=0;i<n;i+=3)area+=Math.abs((line.x(i+1)-line.x(i))*(line.z(i+2)-line.z(i))-(line.z(i+1)-line.z(i))*(line.x(i+2)-line.x(i)))/2;
                assertEquals(water.plane().area(),area,1e-9,id+" "+roll+"/"+pitch);
            }
        }
    }

    @Test void candidateScoresStayFiniteAndPreferLessHeelEvenWhenAllAreUnstable(){
        for(var id:FORMS){
            var p=HullProfile.of(id);var h=HullHydro.of(p);double u=p.measuredUnit();var base=h.hull(u);
            var score=h.new Stability(u,base.mass()+2);
            double center=score.ratio(base.add(2,new Vec(0,3,0))),edge=score.ratio(base.add(2,new Vec(1,3,0)));
            assertTrue(Double.isFinite(center));assertTrue(Double.isFinite(edge));assertTrue(center>=1);assertTrue(edge>center);
            var empty=h.balance(u,base.mass(),0,0);var loaded=h.balance(u,base.mass()+ADULT,0,0);
            assertTrue(empty.ratio()>0);assertTrue(loaded.ratio()>empty.ratio());
        }
    }

    @Test void physicalTickDynamicsSettleFromFiveTwentyAndEightyDegrees(){
        for(var id:FORMS){
            var p=HullProfile.of(id);var h=HullHydro.of(p);double u=p.measuredUnit();var load=h.hull(u);var eq=equilibrium(h,u,load);
            for(double start:new double[]{5,20,80}){
                double roll=start,pitch=0,rv=0,pv=0,previous=Double.POSITIVE_INFINITY,envelope=0;
                for(int tick=0;tick<600;tick++){
                    var water=h.balance(u,load.mass(),roll,pitch);var torque=water.moment(load,roll,pitch);
                    rv=Buoyancy.angular(torque.x(),load.rollInertia(),water.plane().rollInertia(),rv,.08);
                    pv=Buoyancy.angular(torque.z(),load.pitchInertia(),water.plane().pitchInertia(),pv,.08);
                    roll+=rv;pitch+=pv;
                    assertTrue(Double.isFinite(roll)&&Double.isFinite(pitch));assertTrue(Buoyancy.up(roll,pitch)>0);
                    envelope=Math.max(envelope,Math.hypot(roll-eq.roll(),pitch-eq.pitch()));
                    if(tick%100==99){assertTrue(envelope<=previous+1e-4,id+" start="+start);previous=envelope;envelope=0;}
                }
                assertEquals(eq.roll(),roll,1e-3);assertEquals(eq.pitch(),pitch,1e-3);
                assertEquals(0,rv,1e-6);assertEquals(0,pv,1e-6);
            }
        }
    }

    private record Equilibrium(double roll,double pitch,HullHydro.Sample water,boolean stable) {}
    private static Equilibrium equilibrium(HullHydro h,double unit,Buoyancy.Load load) {
        double roll=0,pitch=0;
        for(int i=0;i<80;i++) {
            var s=h.balance(unit,load.mass(),roll,pitch);var torque=s.moment(load,roll,pitch);
            double vertical=s.volume()*s.center().y()-Buoyancy.rotate(load.moment(),roll,pitch).y();
            double a=s.plane().rollInertia()+vertical,b=s.plane().pitchInertia()+vertical,c=-s.plane().cross(),det=a*b-c*c;
            if(s.clearance()<=0||a<=0||b<=0||det<=0)return new Equilibrium(roll,pitch,s,false);
            if(Math.hypot(torque.x(),torque.z())<1e-6*load.mass())return new Equilibrium(roll,pitch,s,true);
            double dr=Math.toDegrees((b*torque.x()-c*torque.z())/det),dp=Math.toDegrees((a*torque.z()-c*torque.x())/det),step=Math.min(.7,2/Math.hypot(dr,dp));
            roll+=dr*step;pitch+=dp*step;
        }
        return new Equilibrium(roll,pitch,h.balance(unit,load.mass(),roll,pitch),false);
    }

    private static double floodAngle(HullHydro h,double unit,double mass,double roll,double pitch) {
        double lo=0,hi=70;
        for(int i=0;i<24;i++) {
            double angle=(lo+hi)/2;
            if(h.balance(unit,mass,angle*roll,angle*pitch).clearance()>0)lo=angle;else hi=angle;
        }
        return (lo+hi)/2;
    }
}
