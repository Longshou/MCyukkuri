package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import java.util.Map;
import java.util.UUID;

/** Identical bed capacities for a pet facility and a wild sleeping cell. */
public final class NestOccupancy {
    private NestOccupancy(){}
    public static int stage(YukkuriEntity e){return e.family().stage()==FamilyLife.Stage.BABY?0:e.family().adult()||e.family().subadult()?2:1;}
    public static boolean accepts(boolean large,Map<UUID,Integer> occupants,YukkuriEntity incoming){
        int[] counts=new int[3];
        occupants.forEach((id,stage)->{if(!id.equals(incoming.getUUID()))counts[Math.clamp(stage,0,2)]++;});
        counts[stage(incoming)]++;
        if(large)return counts[1]>0?counts[1]<=2&&counts[0]==0&&counts[2]==0:counts[0]<=4&&counts[2]<=2;
        return counts[0]>0?counts[0]<=4&&counts[1]==0&&counts[2]==0:counts[1]>0?counts[1]<=2&&counts[2]==0:counts[2]<=1;
    }
}
