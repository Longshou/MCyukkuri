package io.github.longshou.mcyukkuri.life;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NestQuarterTest {
    @Test void allMirrorsAndRotationsPreserveTheOwningBlockAndOffsets() {
        for (int core=0;core<4;core++) for (int mask : new int[]{0,2,1}) for (int turns=0;turns<4;turns++) {
            int owners=0, cells=0;
            for (int part=0;part<4;part++) {
                var q=NestQuarter.of(part,core);
                int x=q.dx(), z=q.dz();
                if(mask==1)x=-x;
                if(mask==2)z=-z;
                for(int i=0;i<turns;i++){int old=x;x=-z;z=old;}
                var moved=q.mirror(mask).rotate(turns);
                assertEquals(x,moved.dx());
                assertEquals(z,moved.dz());
                assertEquals(part==0,moved.part()==0);
                if(moved.part()==0)owners++;
                cells|=1<<moved.cell();
            }
            assertEquals(1,owners);
            assertEquals(15,cells);
        }
    }
}
