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
            assertTrue(loaded.draft()>empty.draft());assertTrue(loaded.freeboard()>0);
            assertTrue(loaded.plane().area()>empty.plane().area());
            // A central finite difference is an independent check on the measured waterplane area.
            double delta=1e-6;
            assertEquals(loaded.plane().area(),(h.sample(u,0,0,loaded.draft()+delta).volume()-h.sample(u,0,0,loaded.draft()-delta).volume())/(2*delta),1e-7);
            System.out.printf("HYDRO %s empty=%.6f loaded=%.6f area=%.6f mass=%.6f%n",id,empty.draft(),loaded.draft(),loaded.plane().area(),loaded.volume());
            var eq=h.equilibrium(u,h.hull(u));
            assertTrue(eq.stable(),id+" empty equilibrium");
            System.out.printf("HYDRO %s empty trim=%.3f capacity=%.6f%n",id,eq.heel(),capacity.volume());
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
            assertTrue(dry.freeboard()>0);assertTrue(dry.ratio()<1);
            assertTrue(wet.freeboard()<0);assertTrue(wet.ratio()>1);
            assertTrue(h.waterline(u,flood-.1,0,dry.draft()).size()>3);
            assertTrue(h.waterline(u,flood+.1,0,wet.draft()).isEmpty());
            assertTrue(h.balance(u,2,0,0).freeboard()<=0,"overload remains real");
            System.out.printf("HYDRO %s flood=%.3f pitchFlood=%.3f roll5=%.6f pitch5=%.6f%n",id,flood,floodAngle(h,u,load.mass(),0,1),
                    h.balance(u,load.mass(),5,0).moment(load,5,0).x(),h.balance(u,load.mass(),0,5).moment(load,0,5).z());
        }
    }

    @Test void studyOwnerAndSixNewbornsStayBelowFiveDegreesInTheBasin() {
        // layout.py owner_6n / body / day, zero and twelve stores: total mass and mesh-derived G, in model units.
        double[][] fixtures={{.1285,-.07,.52,.91,3.86,23.5},{.1502,-.06,.13,.78,3.99,21.75}};
        for(var id:FORMS)for(var row:fixtures) {
            var p=HullProfile.of(id);var h=HullHydro.of(p);double u=p.measuredUnit();
            var load=Buoyancy.Load.ZERO.add(row[0],new Vec(row[1]/16,p.top()*u+row[2]/16,row[3]/16));
            var eq=h.equilibrium(u,load);
            if(id.equals(HullProfile.BASIN)) {
                assertTrue(eq.stable());assertEquals(row[4],eq.heel(),.75);assertTrue(eq.heel()<=5);
                double flood=floodAngle(h,u,load.mass(),eq.roll()/eq.heel(),eq.pitch()/eq.heel());
                assertEquals(row[5],flood,1.5);assertTrue(eq.water().freeboard()>0);
            }
            System.out.printf("HYDRO %s study mass=%.4f heel=%.3f roll=%.3f pitch=%.3f draft=%.6f freeboard=%.6f stable=%s%n",
                    id,row[0],eq.heel(),eq.roll(),eq.pitch(),eq.water().draft(),eq.water().freeboard(),eq.stable());
        }
    }

    @Test void rampedRimLoadingStillFloodsAndMovingLoadsRemainContinuous() {
        for(var id:FORMS) {
            var p=HullProfile.of(id);var h=HullHydro.of(p);double u=p.measuredUnit();
            var hanging=h.hull(u).add(ADULT*.25,new Vec(0,p.rimY()*u,-p.reach(-Math.PI/2)*u));
            assertFalse(h.equilibrium(u,hanging).stable(),id+" adult hanging 25% on the rim");
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

    private static double floodAngle(HullHydro h,double unit,double mass,double roll,double pitch) {
        double lo=0,hi=70;
        for(int i=0;i<24;i++) {
            double angle=(lo+hi)/2;
            if(h.balance(unit,mass,angle*roll,angle*pitch).freeboard()>0)lo=angle;else hi=angle;
        }
        return (lo+hi)/2;
    }
}
