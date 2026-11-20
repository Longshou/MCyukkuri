package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.coordination.LeaseTable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlantSitesTest {
    private record Tuft(double x, double z, double distance) {}

    @Test void activeSightingExcludesTheCloserPlantForEitherHarvest() {
        var near = new Tuft(8, 6, 1);
        var safe = new Tuft(11, 0, 4);
        var candidates = List.of(safe, near);
        assertEquals(List.of(safe), PlantSites.nearest(candidates, Tuft::distance,
                tuft -> !PlantSites.threatened(1, tuft.x, tuft.z)).toList());
        assertEquals(List.of(near, safe), PlantSites.nearest(candidates, Tuft::distance,
                tuft -> !PlantSites.threatened(0, tuft.x, tuft.z)).toList());
    }

    @Test void threatBoundaryIncludesTenBlocksOnlyWhileRemembered() {
        assertTrue(PlantSites.threatened(1, -6, 8));
        assertFalse(PlantSites.threatened(1, -6, 9));
        assertFalse(PlantSites.threatened(0, 0, 0));
    }

    @Test void claimSnapshotKeepsLiveForeignOwnersAndRefreshesForTheNextSearch() {
        var leases = new LeaseTable<Tuft, String>(600);
        var own = new Tuft(12, 0, 1);
        var occupied = new Tuft(13, 0, 2);
        var expired = new Tuft(14, 0, 2);
        var rollback = new Tuft(15, 0, 2);
        leases.restore(own, "self", 700);
        leases.restore(occupied, "other", 700);
        leases.restore(expired, "other", 100);
        leases.restore(rollback, "other", 701);
        var candidates = List.of(expired, own, rollback, occupied);
        var taken = new HashSet<Tuft>();
        leases.forEachLive(100, (site, owner) -> { if (!owner.equals("self")) taken.add(site); });
        assertEquals(Set.of(occupied), taken);
        var decisions = PlantSites.nearest(candidates, Tuft::distance, site -> !taken.contains(site));
        leases.release(occupied, "other");
        assertEquals(List.of(own, expired, rollback), decisions.toList());
        taken.clear();
        leases.forEachLive(101, (site, owner) -> { if (!owner.equals("self")) taken.add(site); });
        assertEquals(Set.of(rollback), taken);
    }

    @Test void admissionVisitsOnlyTheRequiredPrefixInStableDistanceOrder() {
        var refused = new Tuft(8, 6, 1);
        var first = new Tuft(11, 0, 2);
        var second = new Tuft(12, 0, 2);
        var unread = new Tuft(13, 0, 3);
        var visited = new ArrayList<Tuft>();
        var ordered = PlantSites.nearest(List.of(unread, first, refused, second), Tuft::distance, site -> {
            visited.add(site);
            return !PlantSites.threatened(1, site.x, site.z);
        });
        assertEquals(List.of(first, second), ordered.limit(2).toList());
        assertEquals(List.of(refused, first, second), visited);
    }
}
