package io.github.longshou.mcyukkuri.entity.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.entity.presentation.FaceTier.Tier;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FaceTierTest {
    @Test void aLiteralKeepsTheTierItAlreadySatIn() {
        assertEquals(Tier.SHORT, Tier.ofTicks(14));
        assertEquals(Tier.SHORT, Tier.ofTicks(60));
        assertEquals(Tier.MEDIUM, Tier.ofTicks(61));
        assertEquals(Tier.MEDIUM, Tier.ofTicks(130));
        assertEquals(Tier.LONG, Tier.ofTicks(131));
    }

    @Test void holdStaysInItsRangeAndGrowsWithTheLean() {
        for (var tier : Tier.values()) for (int seed : new int[] {0, 1, 12345, 0xFFFF, -77}) {
            int last = 0;
            for (int i = 0; i <= 10; i++) {
                int hold = FaceTier.hold(tier, i / 10f, seed);
                assertTrue(hold >= tier.lo && hold <= tier.hi, tier + " " + hold);
                assertTrue(hold >= last, "monotone in the lean: " + tier + " seed " + seed);
                last = hold;
            }
        }
    }

    @Test void theTiersDoNotOverlap() {
        assertTrue(Tier.SHORT.hi < Tier.MEDIUM.lo && Tier.MEDIUM.hi < Tier.LONG.lo);
    }

    @Test void theLeanFollowsTheTraitsOfTheFacesMood() {
        assertTrue(FaceTier.lean(FaceMood.SAD, 90, 20, 50, 50) > FaceTier.lean(FaceMood.SAD, 10, 90, 50, 50));
        assertTrue(FaceTier.lean(FaceMood.ANGRY, 50, 10, 50, 50) > FaceTier.lean(FaceMood.ANGRY, 50, 90, 50, 50));
        assertTrue(FaceTier.lean(FaceMood.FEAR, 50, 50, 10, 50) > FaceTier.lean(FaceMood.FEAR, 50, 50, 90, 50));
        assertTrue(FaceTier.lean(FaceMood.JOY, 50, 50, 50, 90) > FaceTier.lean(FaceMood.JOY, 50, 50, 50, 10));
        assertEquals(.5f, FaceTier.lean(FaceMood.NEUTRAL, 0, 0, 0, 0));
    }

    @Test void theFaceHoldsToTheEndOfItsLineAndTheTail() {
        for (var tier : Tier.values()) {
            assertEquals(1000 + 300 + tier.tail(), FaceTier.holdEnd(1000, 50, 1300, tier), "a long line extends the hold");
            assertEquals(1000 + 200, FaceTier.holdEnd(1000, 200, 1010, tier), "a short line leaves the tier hold");
            assertTrue(FaceTier.holdEnd(5, 40, 90, tier) >= 90 + tier.tail());
        }
        assertEquals(20, Tier.MEDIUM.tail());
        assertEquals(30, Tier.LONG.tail());
    }

    @Test void aNewcomerReplacesOnlyATierNotLower() {
        assertTrue(FaceTier.replaces(Tier.SHORT, Tier.SHORT));
        assertTrue(FaceTier.replaces(Tier.SHORT, Tier.LONG));
        assertFalse(FaceTier.replaces(Tier.MEDIUM, Tier.SHORT));
        assertFalse(FaceTier.replaces(Tier.LONG, Tier.MEDIUM));
        assertTrue(FaceTier.replaces(Tier.LONG, Tier.LONG));
    }

    @Test void theCalmingWindowIsClamped() {
        assertEquals(20, FaceTier.fade(30));
        assertEquals(40, FaceTier.fade(100));
        assertEquals(100, FaceTier.fade(320));
    }

    @Test void calmingNeverCrossesTheMoodAndEveryFaceExistsOnEveryModel() throws Exception {
        List<String> faces = new ArrayList<>();
        try (var in = FaceTierTest.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/face_library.json")) {
            var root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var face : root.getAsJsonArray("faces")) faces.add(face.getAsString());
            var models = root.getAsJsonObject("models");
            for (var tier : Tier.values()) for (var face : faces) for (var calmer : FaceMood.calming(face, tier)) {
                assertNotEquals(FaceMood.NEUTRAL, FaceMood.of(calmer), calmer);
                assertEquals(FaceMood.of(face), FaceMood.of(calmer), face + " calms into " + calmer);
                for (var model : models.entrySet()) {
                    boolean has = false;
                    for (var id : model.getValue().getAsJsonObject().getAsJsonArray("selectable")) has |= id.getAsString().equals(calmer);
                    assertTrue(has, model.getKey() + " lacks " + calmer);
                }
            }
        }
    }

    @Test void angerSulksOnlyAfterALongHold() {
        assertEquals(List.of("down_angry"), FaceMood.calming("angry", Tier.MEDIUM));
        assertEquals(List.of("down_angry", "hmph"), FaceMood.calming("angry", Tier.LONG));
        assertTrue(FaceMood.calming("hmph", Tier.LONG).isEmpty());
        assertTrue(FaceMood.calming("teary_smile", Tier.LONG).isEmpty(), "the mixed face has no calming path");
        assertEquals(FaceMood.BITTERSWEET, FaceMood.of("teary_smile"));
    }
}
