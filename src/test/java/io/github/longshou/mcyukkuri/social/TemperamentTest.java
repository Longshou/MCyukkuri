package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.function.ToDoubleFunction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemperamentTest {
    @Test
    void wildScoresAndGatesHaveTheExpectedSpread() {
        var seeds = new Random(171119);
        var wild = new Temperament[10_000];
        for (int n = 0; n < wild.length; n++) {
            long seed = seeds.nextLong();
            wild[n] = new Temperament(TraitDraw.wild(seed, 0, 50), TraitDraw.wild(seed, 1, 50),
                    TraitDraw.wild(seed, 2, 50), TraitDraw.wild(seed, 3, 50), TraitDraw.wild(seed, 4, 50));
        }
        List<ToDoubleFunction<Temperament>> scores = List.of(Temperament::timid, Temperament::patient,
                Temperament::caring, Temperament::selfish, Temperament::curious);
        for (var score : scores) {
            double[] ordered = Arrays.stream(wild).mapToDouble(score).sorted().toArray();
            assertEquals(.25, ordered[1600], .03);
            assertEquals(.40, ordered[3500], .03);
            assertEquals(.50, ordered[5000], .03);
            assertEquals(.60, ordered[6500], .03);
            assertEquals(.75, ordered[8400], .03);
        }

        double[] shares = {16.6, 35.7, 35.8, 16.7, 15.7, 5.9, 6.0, 18.0, 34.1, 15.7, 7.9,
                12.2, 65.3, 34.4, 15.9, 17.7, 13.3, 22.9, 18.0, 35.5, 16.5, 35.6, 16.6, 8.5, 72.4,
                15.3, 35.8, 35.9, 16.8, 8.5, 35.9, 16.8, 5.1};
        var rare = EnumSet.of(Gate.SHY_REFUSAL, Gate.OBJECTS_TO_MOVE, Gate.GREEDY,
                Gate.COMPOSED, Gate.DEVOTED_KIN, Gate.SEES_PAST_HAT);
        for (Gate gate : Gate.values()) {
            double share = 100.0 * Arrays.stream(wild).filter(t -> t.is(gate)).count() / wild.length;
            assertEquals(shares[gate.ordinal()], share, 3, gate.name());
            assertTrue(rare.contains(gate) ? share >= 2 && share <= 12 : share >= 5 && share <= 95, gate.name());
        }
        double giving = Arrays.stream(wild).filter(t -> t.willingToGive() > 0).count() / (double) wild.length;
        assertTrue(giving >= .50 && giving <= .65);
        double mean = Arrays.stream(wild).mapToDouble(Temperament::willingToGive).average().orElseThrow();
        assertTrue(mean >= .25 && mean <= .35);
    }

    @Test
    void sharedCutsFallOnTheExpectedIntegerBoundaries() {
        boundary(Gate.TIMID,                  new Temperament(50, 50, 35, 50, 50), new Temperament(50, 50, 36, 50, 50));
        boundary(Gate.BOLD,                   new Temperament(50, 50, 65, 50, 50), new Temperament(50, 50, 64, 50, 50));
        boundary(Gate.WARY,                   new Temperament(50, 50, 44, 50, 50), new Temperament(50, 50, 45, 50, 50));
        boundary(Gate.BOLDISH,                new Temperament(50, 50, 56, 50, 50), new Temperament(50, 50, 55, 50, 50));
        boundary(Gate.PATIENT,                new Temperament(50, 50, 50, 50, 56), new Temperament(50, 50, 50, 50, 55));
        boundary(Gate.STOIC,                  new Temperament(50, 50, 50, 50, 65), new Temperament(50, 50, 50, 50, 64));
        boundary(Gate.COMPOSED,               new Temperament(50, 50, 50, 50, 71), new Temperament(50, 50, 50, 50, 70));
        boundary(Gate.ALOOF,                  new Temperament(50, 50, 50, 34, 50), new Temperament(50, 50, 50, 35, 50));
        boundary(Gate.ATTACHED,               new Temperament(50, 50, 50, 56, 50), new Temperament(50, 50, 50, 55, 50));
        boundary(Gate.SEES_PAST_HAT,          new Temperament(75, 50, 50, 50, 50), new Temperament(74, 50, 50, 50, 50));
        boundary(Gate.SWADDLER,               new Temperament(50, 80, 50, 50, 50), new Temperament(49, 80, 50, 50, 50));
    }

    private static void boundary(Gate gate, Temperament inside, Temperament outside) {
        assertTrue(inside.is(gate), gate.name());
        assertFalse(outside.is(gate), gate.name());
    }

    @Test
    void weightedScoresIncludeActorsExactlyAtTheCut() {
        boundary(Gate.KINDLY,   new Temperament(50, 34, 50, 72, 50), new Temperament(50, 34, 50, 71, 50));
        boundary(Gate.WARM,     new Temperament(50, 48, 50, 70, 50), new Temperament(50, 48, 50, 69, 50));
        boundary(Gate.DEVOTED,  new Temperament(50, 63, 50, 58, 50), new Temperament(50, 63, 50, 57, 50));
        boundary(Gate.HARSH,    new Temperament(50, 52, 50, 50, 30), new Temperament(50, 52, 50, 50, 31));
        boundary(Gate.SELFISH,  new Temperament(50, 37, 50, 50, 42), new Temperament(50, 37, 50, 50, 43));
        boundary(Gate.GREEDY,   new Temperament(50, 36, 50, 50, 29), new Temperament(50, 36, 50, 50, 30));
        boundary(Gate.EXPLORER, new Temperament( 7, 50, 97, 50, 50), new Temperament( 6, 50, 97, 50, 50));
    }

    @Test
    void cockyPreservesTheTraitPartOfInsistence() {
        for (int friendliness = 0; friendliness <= 100; friendliness += 5) {
            for (int control = 0; control <= 100; control += 5) {
                for (int boldness = 0; boldness <= 100; boldness += 5) {
                    double restraint = Math.min(1 - oldRamp(friendliness, 35, 62), 1 - oldRamp(control, 35, 62));
                    double oldInsistence = Math.min(restraint, oldRamp(boldness, 35, 72));
                    assertEquals(oldInsistence, new Temperament(50, friendliness, boldness, 50, control).cocky());
                }
            }
        }
    }

    private static double oldRamp(double value, double low, double high) {
        return Math.clamp((value - low) / (high - low), 0, 1);
    }

    @Test
    void museumDefaultHasTheExpectedGates() {
        var defaultActor = new Temperament(55, 70, 55, 60, 70);
        var actual = EnumSet.noneOf(Gate.class);
        for (Gate gate : Gate.values()) if (defaultActor.is(gate)) actual.add(gate);
        assertEquals(EnumSet.of(Gate.KINDLY, Gate.WARM, Gate.DEVOTED, Gate.SWADDLER, Gate.SELF_OFFER,
                Gate.DOCILE, Gate.WAITS_FOR_OWNER, Gate.PATIENT, Gate.STOIC, Gate.KEEPS_RULE, Gate.ATTACHED), actual);
    }
}
