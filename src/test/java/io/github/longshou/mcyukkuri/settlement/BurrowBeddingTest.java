package io.github.longshou.mcyukkuri.settlement;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BurrowBeddingTest {
    @Test void aCrossChunkBreakSurvivesCoreAbsenceAndReloadEvenIfTheFloorWasReplaced(){
        var before=new BurrowFloors();
        long doorway=15, floor=16, intact=14;
        before.watch(doorway,Set.of(floor,intact));
        for(long core:before.owners(floor))before.lose(core,floor);
        var disk=before.homes.get(doorway);
        var after=new BurrowFloors();
        after.watch(doorway,disk.floors());
        for(long lost:disk.lost())after.lose(doorway,lost);
        assertEquals(Set.of(floor),after.pending(doorway));
        assertFalse(after.watch(doorway,Set.of(floor,intact)));
        assertFalse(after.lose(doorway,floor));
        after.watch(doorway,Set.of(intact));
        assertTrue(after.pending(doorway).isEmpty());
        assertTrue(after.owners(floor).isEmpty());
        assertEquals(Set.of(doorway),after.owners(intact));
    }
    @Test void salvageReturnsPrivateAndAddedLiningPortionsExactlyOnce(){
        var stock=new BurrowBedding<Integer,String>();
        stock.build(1,List.of("birch leaves","paper"));
        stock.lining.addAll(List.of("wheat","short grass","oak leaves"));
        stock.enlarge(1);
        assertEquals(List.of("oak leaves"),stock.lining);
        assertEquals(List.of("birch leaves","paper","wheat","short grass"),stock.take(1));
        assertTrue(stock.take(1).isEmpty());
        stock.enlarge(2);
        assertEquals(List.of("oak leaves"),stock.take(2));
    }
}
