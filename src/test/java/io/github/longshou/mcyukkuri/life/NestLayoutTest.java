package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.motor.geom.FloorShape;
import io.github.longshou.mcyukkuri.motor.geom.OccupancyMath;
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
        var spot=NestLayout.choose(id,body,HALF,occupants,p->OccupancyMath.classify(p.x(),p.z(),0,p.width()/2+ARRIVAL,
                floor,0,.2,true)==OccupancyMath.Result.INSIDE_SUPPORTED,false,false,ARRIVAL);
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
