package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.settlement.HomeClearance.Box;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeClearanceTest {
    @Test void rejectsNestFloorAcrossTheEntrance() {
        var entrance = new Box(.1, 0, .1, .9, .6, .9);
        var bedding = new Box(0, 0, 0, 1, .03125, 1);

        assertTrue(HomeClearance.blocks(List.of(bedding), entrance));
    }

    @Test void rejectsShelterRoofAboveTheEntranceCellWhenTheResidentNeedsTheHeight() {
        var tallResident = new Box(.1, 0, .1, .9, 1.2, .9);
        var roof = new Box(0, 1, 0, 1, 1.375, 1);

        assertTrue(HomeClearance.blocks(List.of(roof), tallResident));
    }

    @Test void acceptsAClearSiteBesideAndAboveTheStand() {
        var entrance = new Box(.1, 0, .1, 1, .6, .9);
        var bedding = new Box(1, 0, 0, 2, .03125, 1);
        var roof = new Box(0, 1, 0, 1, 1.375, 1);

        assertFalse(HomeClearance.blocks(List.of(bedding, roof), entrance));
    }
}
