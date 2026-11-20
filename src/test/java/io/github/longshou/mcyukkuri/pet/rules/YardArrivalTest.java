package io.github.longshou.mcyukkuri.pet.rules;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class YardArrivalTest {
    private record Stand(double squaredDistance, boolean inside) {}

    @Test void occupiedHomeStillOffersEveryFreeStandInTheYardWithinLeadReach() {
        var free = Map.of("near", new Stand(1, true), "edge", new Stand(100, true),
                "over reach", new Stand(101, true), "outside", new Stand(1, false));
        var stands = YardArrival.stands(List.of("home", "near", "edge", "over reach", "outside"), free::get,
                stand -> stand.inside() && YardArrival.withinReach(stand.squaredDistance(), 10));
        assertEquals(Set.of("near", "edge"), stands);
    }

    @Test void theExactHomeCellIsOnlyOneOfTheRouteTargets() {
        var stands = YardArrival.stands(List.of("home", "occupied by a led pet", "reachable"),
                Map.of("home", 0, "reachable", 4)::get, distance -> YardArrival.withinReach(distance, 10));
        assertEquals(Set.of("home", "reachable"), stands);
    }
}
