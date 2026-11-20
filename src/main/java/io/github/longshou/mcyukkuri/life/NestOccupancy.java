package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** Identical bed capacities for a pet facility and a wild sleeping cell. */
public final class NestOccupancy {
    private NestOccupancy(){}
    /** The first adult holds the canopy home. Its mate and their dependent young share that address. */
    static boolean acceptsCanopy(Map<UUID,Integer> residents, UUID incoming, int stage, UUID mate, Predicate<UUID> careRelative) {
        var adults = residents.entrySet().stream().filter(entry -> entry.getValue() == 2).map(Map.Entry::getKey).toList();
        if (adults.isEmpty()) return residents.containsKey(incoming) || stage == 2 && residents.keySet().stream().allMatch(careRelative);
        if (stage < 2) return adults.stream().anyMatch(careRelative);
        return adults.getFirst().equals(incoming) || adults.getFirst().equals(mate)
                && adults.stream().allMatch(id -> id.equals(mate) || id.equals(incoming));
    }
    public static int stage(YukkuriEntity e){return e.family().stage()==FamilyLife.Stage.BABY?0:e.family().adult()||e.family().subadult()?2:1;}
    public static boolean accepts(boolean large,Map<UUID,Integer> occupants,YukkuriEntity incoming){
        return accepts(large,occupants,incoming.getUUID(),stage(incoming));
    }
    public static boolean accepts(boolean large,Map<UUID,Integer> occupants,UUID incoming,int incomingStage){
        int[] counts=new int[3];
        occupants.forEach((id,stage)->{if(!id.equals(incoming))counts[Math.clamp(stage,0,2)]++;});
        counts[incomingStage]++;
        if(large)return counts[1]>0?counts[1]<=2&&counts[0]==0&&counts[2]==0:counts[0]<=4&&counts[2]<=2;
        return counts[0]>0?counts[0]<=4&&counts[1]==0&&counts[2]==0:counts[1]>0?counts[1]<=2&&counts[2]==0:counts[2]<=1;
    }
}
