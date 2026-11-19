package io.github.longshou.mcyukkuri.settlement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Each delivered portion is in the lining or in exactly one finished nest. Legacy counts have no known item identity. */
final class BurrowBedding<P,T> {
    final List<T> lining=new ArrayList<>();
    final Map<P,List<T>> beds=new HashMap<>();

    void build(P bed,List<T> portions){beds.computeIfAbsent(bed,key->new ArrayList<>()).addAll(portions);}
    void enlarge(P bed){
        var spent=lining.subList(0,Math.min(2,lining.size()));
        build(bed,spent);
        spent.clear();
    }
    List<T> take(P bed){
        var portions=beds.remove(bed);
        return portions==null?List.of():portions;
    }
}
