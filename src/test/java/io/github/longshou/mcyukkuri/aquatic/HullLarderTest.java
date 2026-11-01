package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.headwear.HullProfile;
import io.github.longshou.mcyukkuri.motor.geom.BodyFootprint;
import io.github.longshou.mcyukkuri.motor.geom.Disc;
import io.github.longshou.mcyukkuri.motor.geom.GeometryJson;
import io.github.longshou.mcyukkuri.motor.geom.Squeeze;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HullLarderTest {
    private static final double SLACK = .02;

    @Test void fullBasinFoodContactSettlesInsteadOfOscillating() {
        var hull=HullProfile.of(HullProfile.BASIN);
        double unit=.08749999850988388,r=.09/unit;
        var food=new ArrayList<Disc>();
        // The recipe's 24 portions through the real larder spot selector, round the slim mother. In labour she stands half a block toward one end,
        // against the portions there; her dimensions from the full-basin smoke readout.
        var slim=BodyFootprint.of("marisa",.8/unit,.6/unit,0);double slimY=hull.support(0,0,slim);
        for(int n=0;n<24;n++){
            var pick=HullLarder.Floor.spot(hull,r,.06/unit,n,
                    d->!slim.overlaps(0,slimY,0,d,.02)&&food.stream().noneMatch(f->f.overlaps(d,.02)));
            assertNotNull(pick,"floor for portion "+n);food.add(pick);
        }
        double max=0;
        for(int yaw:new int[]{10,30,145,155}){
            double x=0,z=.5/unit,y=hull.support(x,z,BodyFootprint.of("marisa",.8*1.26*1.16/unit,.6*1.26/unit,Math.toRadians(yaw)));
            for(double belly:new double[]{1,.74,.48}){
                var body=BodyFootprint.of("marisa",.8*(1+.26*belly)*(1+.16*belly)/unit,.6*(1+.26*belly)/unit,Math.toRadians(yaw));
                for(int tick=0;tick<400;tick++){
                    var move=Squeeze.separate(List.of(new Squeeze.Body(x,z,body.radius(),1,.06/unit,y,y+body.height())),food,.02,0,0,
                            (a,b,dx,dz)->body.overlaps(a.x()+dx,hull.support(a.x()+dx,a.z()+dz,body),a.z()+dz,b,.02))[0];
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

    @Test void packedBasinUsesTheRealLarderRuleAndRefusesTheNextPortion() {
        var hull=HullProfile.of(HullProfile.BASIN);double unit=.08749999850988388;
        var food=packedBasin(hull,unit);
        assertEquals(78,food.size(),"the full-hold scenes require the measured oval capacity");
        assertNull(larderSpot(hull,unit,food),"no_room after the last real portion");
    }

    static List<Disc> packedBasin(HullProfile hull,double unit) {
        var food=new ArrayList<Disc>();
        for(Disc next;(next=larderSpot(hull,unit,food))!=null;)food.add(next);
        return food;
    }

    private static Disc larderSpot(HullProfile hull,double unit,List<Disc> food) {
        var slim=BodyFootprint.of("marisa",.8/unit,.6/unit,0);double y=hull.support(0,0,slim);
        double radius=GeometryJson.read("yukkuri_presentation/hat_vessel.json").number("afloat.portion_radius")/unit;
        return HullLarder.Floor.spot(hull,radius,HullLarder.HEIGHT/unit,food.size(),
                d->!slim.overlaps(0,y,0,d,.02)&&food.stream().noneMatch(f->f.overlaps(d,.02)));
    }

    @Test void packedOvalMotherSettlesAtEveryLabourSize() {
        var hull=HullProfile.of(HullProfile.BASIN);double unit=.08749999850988388;
        var food=packedBasin(hull,unit);
        assertEquals(78,food.size());
        for(int yaw:new int[]{0,10,30,145,155}) {
            double x=0,z=0,y=hull.support(0,0,BodyFootprint.of("marisa",.8/unit,.6/unit,0));
            for(double belly:new double[]{1,.74,.48}) {
                var body=BodyFootprint.of("marisa",.8*(1+.26*belly)*(1+.16*belly)/unit,.6*(1+.26*belly)/unit,Math.toRadians(yaw));
                double last=0;
                for(int tick=0;tick<400;tick++) {
                    var move=Squeeze.separate(List.of(new Squeeze.Body(x,z,body.radius(),1,.06/unit,y,y+body.height())),food,SLACK,0,0,
                            (a,b,dx,dz)->body.overlaps(a.x()+dx,hull.support(a.x()+dx,a.z()+dz,body),a.z()+dz,b,SLACK))[0];
                    double nextY=Math.hypot(move[0],move[1])<1e-6?y:hull.support(x+move[0],z+move[1],body);
                    last=unit*Math.sqrt(move[0]*move[0]+move[1]*move[1]+(nextY-y)*(nextY-y));
                    if(tick>=350)assertTrue(last<1e-5,"settled yaw="+yaw+" belly="+belly+" tick="+tick+" displacement="+last);
                    x+=move[0];z+=move[1];y=nextY;
                }
                System.out.printf("packed mother yaw=%d belly=%.2f displacement=%.9f%n",yaw,belly,last);
            }
        }
    }
}
