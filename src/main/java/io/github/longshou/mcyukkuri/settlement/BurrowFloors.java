package io.github.longshou.mcyukkuri.settlement;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Watched floor cells and support losses still owed to each doorway, independent of chunk lifetime. */
final class BurrowFloors {
    record Watch(Set<Long> floors, Set<Long> lost) {}
    final Map<Long,Watch> homes = new HashMap<>();
    private final Map<Long,Set<Long>> owners = new HashMap<>();

    boolean watch(long core, Set<Long> floors) {
        var old=homes.get(core);
        if(old!=null && old.floors.equals(floors))return false;
        var lost=new HashSet<Long>();
        if(old!=null) {
            lost.addAll(old.lost);
            lost.retainAll(floors);
            for(long floor:old.floors){
                var at=owners.get(floor);
                at.remove(core);
                if(at.isEmpty())owners.remove(floor);
            }
        }
        if(floors.isEmpty())return homes.remove(core)!=null;
        homes.put(core,new Watch(Set.copyOf(floors),lost));
        for(long floor:floors)owners.computeIfAbsent(floor,key->new HashSet<>()).add(core);
        return true;
    }
    Set<Long> owners(long floor){return owners.getOrDefault(floor,Set.of());}
    Set<Long> pending(long core){
        var watch=homes.get(core);
        return watch==null?Set.of():Set.copyOf(watch.lost);
    }
    boolean lose(long core,long floor){return homes.get(core).lost.add(floor);}
}
