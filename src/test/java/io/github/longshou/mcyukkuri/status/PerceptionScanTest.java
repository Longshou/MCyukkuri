package io.github.longshou.mcyukkuri.status;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PerceptionScanTest {
    @Test void consecutiveActorsAreSpreadAcrossEveryFiveTickWindow() {
        for (long start = -5; start < 10; start++) {
            int[] scans = new int[5];
            for (int id = -7; id < 3; id++) {
                int visits = 0;
                for (int offset = 0; offset < 5; offset++) {
                    if (PerceptionScan.due(start + offset, id)) {
                        visits++;
                        scans[offset]++;
                    }
                }
                assertEquals(1, visits);
            }
            for (int count : scans) assertEquals(2, count);
        }
    }

    @Test void sectionChangesReachEveryoneUsingTheSameCandidateView() {
        var first = new PerceptionScan.Section<String>();
        var second = new PerceptionScan.Section<String>();
        var shared = first.candidates();
        first.enter("predator");
        first.enter("resident");
        assertEquals(List.of("predator", "resident"), shared);

        first.leave("predator");
        second.enter("predator");
        assertEquals(List.of("resident"), shared);
        assertEquals(List.of("predator"), second.candidates());

        second.leave("predator");
        first.enter("predator");
        assertEquals(List.of("resident", "predator"), shared);
        assertEquals(List.of(), second.candidates());
    }
}
