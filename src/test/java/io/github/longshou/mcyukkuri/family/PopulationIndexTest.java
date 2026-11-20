package io.github.longshou.mcyukkuri.family;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PopulationIndexTest {
    record Resident(String id, int bellX, int bellZ) {}

    @Test void reloadedVillageIncludesUnloadedFamiliesAcrossObserverBells() {
        var saved = List.of(new Resident("parent", -40, 0), new Resident("child", -40, 0),
                new Resident("neighbor", 48, 0), new Resident("distant", 220, 0));
        var roots = new PopulationIndex<String, Resident>();
        for (var person : saved) roots.locate(person, "overworld", person.bellX, person.bellZ);
        // The only loaded body re-registers at its saved address. The other two still occupy places.
        roots.locate(saved.get(2), "overworld", 48, 0);
        for (int bell : new int[]{-40, 48}) assertEquals(3, residents(roots, bell));
        roots.remove(saved.get(0));
        assertEquals(2, residents(roots, 48));
        assertEquals(1, residents(roots, 220));
        assertEquals(0, roots.count("nether", 48, 0, 128, p -> true));
    }

    private int residents(PopulationIndex<String, Resident> roots, int bell) {
        return roots.count("overworld", bell, 0, 128,
                p -> PopulationIndex.within(p.bellX - bell, p.bellZ, 128));
    }

    @Test void movedEggsLeaveTheirOldAreaAndDistantLedgerEntriesAreNeverVisited() {
        var ledger = new PopulationIndex<String, Integer>();
        for (int i = 1; i <= 5000; i++) ledger.locate(i, "overworld", i + 1000, 0);
        ledger.locate(0, "overworld", -33, -32);
        ledger.locate(-1, "overworld", -32, -32);
        var visited = new AtomicInteger();
        assertEquals(1, ledger.count("overworld", -32, -32, 32, id -> {
            visited.incrementAndGet();
            return id == 0; // the captured neighbor is excluded
        }));
        assertEquals(2, visited.get());
        ledger.locate(0, "nether", 96, 64);
        assertEquals(1, ledger.count("overworld", -32, -32, 32, id -> true));
        assertEquals(1, ledger.count("nether", 96, 64, 32, id -> true));
        ledger.remove(0);
        assertEquals(0, ledger.count("nether", 96, 64, 32, id -> true));
    }
}
