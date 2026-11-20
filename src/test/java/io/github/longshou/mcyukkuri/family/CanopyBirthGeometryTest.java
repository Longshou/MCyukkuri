package io.github.longshou.mcyukkuri.family;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.entity.SpeciesProfile.BreedingPool;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.motor.geom.Ballistic;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CanopyBirthGeometryTest {
    @Test void aLostEntranceNeedsAnotherAlignedBranchOrGroundUnderTheWholeBrood() {
        assertFalse(CanopyBirthGeometry.canRelease(true,false,true,false));
        assertFalse(CanopyBirthGeometry.canRelease(false,false,true,false));
        assertFalse(CanopyBirthGeometry.canRelease(true,true,false,false));
        assertTrue(CanopyBirthGeometry.canRelease(true,true,true,false));
        var cliff=CanopyBirthGeometry.groundUnderFruit(-.1,.5,.2,(x,z)->x<0);
        assertFalse(CanopyBirthGeometry.canRelease(false,false,false,cliff));
        var clearing=CanopyBirthGeometry.groundUnderFruit(-.1,.5,.2,(x,z)->x>=-1 && x<=0 && z==0);
        assertTrue(CanopyBirthGeometry.canRelease(false,false,false,clearing));
    }
    private final JsonObject family = JsonParser.parseReader(new InputStreamReader(getClass().getResourceAsStream(
            "/assets/mcyukkuri/yukkuri_presentation/family.json"), StandardCharsets.UTF_8)).getAsJsonObject();
    private final JsonObject stem = family.getAsJsonObject("stem");

    @Test void predatorsDrawOneExtraFruitAtConception() {
        assertEquals(1, Litter.size(0, BreedingPool.PREDATOR, new Random(7)));
        assertEquals(2, Litter.size(1, BreedingPool.PREDATOR, new Random(7)));
        assertEquals(5, Litter.size(1, BreedingPool.LAND, new Random(7)));
    }

    private record Fruit(double reach, double height) {}
    private Fruit fruit(JsonObject body, int count, int index, double mother, double variation, double baby) {
        var socket = stem.getAsJsonObject("socket_layouts").getAsJsonArray(Integer.toString(count)).get(index).getAsJsonArray();
        double units = stem.get("reference_units_to_model").getAsDouble()/16;
        double extent = Math.max(1,mother);
        double length = family.getAsJsonArray("lengthScale").get(count-1).getAsDouble()*variation*extent;
        double rise = (socket.get(1).getAsDouble()-stem.getAsJsonArray("reference_origin").get(1).getAsDouble())*units*length;
        var root = body.getAsJsonArray("root");
        assertTrue(rise >= body.get("clearance_rise").getAsDouble()*mother/16);
        return new Fruit(-root.get(2).getAsDouble()*mother/16 - socket.get(0).getAsDouble()*units*length
                + body.get("depth_clearance").getAsDouble()*extent/16 + body.getAsJsonArray("hanging_anchor").get(2).getAsDouble()*baby/16,
                root.get(1).getAsDouble()*mother/16 + rise - stem.get("pendant_length_reference").getAsDouble()*units*mother
                - body.get("hang_clearance_height").getAsDouble()*baby/16 - 2.4/16);
    }

    @Test void wholeNewbornLandsOnPadThroughoutTheAllowedRanges() {
        assertEquals(0, family.get("pedicelReach").getAsDouble());
        for (var variant : new YukkuriVariant[]{YukkuriVariant.REMILIA, YukkuriVariant.FLANDRE}) {
            var body = family.getAsJsonObject("variants").getAsJsonObject(variant.assetId());
            for (int count=1; count<=BreedingPool.PREDATOR.maxYoung; count++)
                for (int m=800; m<1200; m+=5)
                    for (int v=930; v<1070; v+=5)
                        for (int index=0; index<count; index++) for (int b=100; b<190; b+=5) {
                            // Reach and floor clearance are monotone on these intervals (the extent bends at scale 1).
                            double lo=m/1000., hi=(m+5)/1000., lowV=v/1000., highV=(v+5)/1000.;
                            double babyLo = b/1000., babyHi = (b+5)/1000.;
                            double near=fruit(body,count,index,lo,lowV,babyLo).reach, far=fruit(body,count,index,hi,highV,babyHi).reach;
                            double lowStand=CanopyBirthGeometry.depth(fruit(body,count,0,lo,lowV,index==0 ? babyLo : .10).reach,
                                    fruit(body,count,count-1,lo,lowV,index==count-1 ? babyLo : .10).reach,variant.width()*lo);
                            double highStand=CanopyBirthGeometry.depth(fruit(body,count,0,hi,highV,index==0 ? babyHi : .19).reach,
                                    fruit(body,count,count-1,hi,highV,index==count-1 ? babyHi : .19).reach,variant.width()*hi);
                            double drop=fruit(body,count,index,hi,highV,babyLo).height, vy=BirthLaunch.STEM_DROP;
                            int ticks=0;
                            while(drop>0){drop+=vy;vy=(vy-Ballistic.GRAVITY)*Ballistic.VERTICAL_DRAG;ticks++;}
                            double toss=Ballistic.reachAt(BirthLaunch.STEM_MAX,ticks);
                            double yaw=Math.toRadians(3);
                            double longitudinal=Math.max(highStand-near*Math.cos(yaw),far-lowStand);
                            double extent=Math.max(longitudinal,far*Math.sin(yaw))+toss+.02+variant.width()*babyHi/2;
                            assertTrue(extent<=.5, "count="+count+" maternal="+lo+" variance="+lowV+" extent="+extent);
                        }
        }
    }
}
