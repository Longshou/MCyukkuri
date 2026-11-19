package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.BirthProfile;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraitDrawTest {
    private static final int SAMPLES = 10_000;
    private static final Map<YukkuriVariant, Map<Trait, Integer>> OFFSETS = Map.of(
            YukkuriVariant.REIMU, Map.of(ATTACHMENT, 7),
            YukkuriVariant.MARISA, Map.of(BOLDNESS, 7, SELF_CONTROL, -7),
            YukkuriVariant.HOUSE_REIMU, Map.of(BOLDNESS, -7),
            YukkuriVariant.HOUSE_MARISA, Map.of(BOLDNESS, -7),
            YukkuriVariant.CHEN, Map.of(BOLDNESS, -7),
            YukkuriVariant.ALICE, Map.of(FRIENDLINESS, 7, SELF_CONTROL, 7),
            YukkuriVariant.PATCHOULI, Map.of(INTELLIGENCE, 7, BOLDNESS, -7),
            YukkuriVariant.MYON, Map.of(INTELLIGENCE, -7, BOLDNESS, 7),
            YukkuriVariant.REMILIA, Map.of(FRIENDLINESS, -7, BOLDNESS, 7),
            YukkuriVariant.FLANDRE, Map.of(FRIENDLINESS, -7, BOLDNESS, 7));

    @Test
    void pairedDrawsShiftOnlyTheListedAxesAndKeepPopulationsOverlapping() {
        YukkuriVariant[] variants = YukkuriVariant.values();
        for (Trait trait : Trait.values()) {
            int[][] counts = new int[variants.length][91];
            for (YukkuriVariant variant : variants) {
                String label = variant + " " + trait;
                int offset = OFFSETS.get(variant).getOrDefault(trait, 0);
                Random seeds = new Random(171119);
                int total = 0;
                int[] histogram = counts[variant.ordinal()];
                for (int n = 0; n < SAMPLES; n++) {
                    long seed = seeds.nextLong();
                    int value = TraitDraw.wild(seed, trait, variant);
                    assertEquals(wildReference(seed, trait, 50 + offset), value, label);
                    assertTrue(value >= 10 && value <= 90, label);
                    histogram[value]++;
                    total += value;
                }
                assertEquals(50 + offset, (double) total / SAMPLES, .7, label);
            }
            for (int first = 0; first < variants.length; first++) {
                for (int second = first + 1; second < variants.length; second++) {
                    int shared = 0;
                    for (int value = 10; value <= 90; value++) {
                        shared += Math.min(counts[first][value], counts[second][value]);
                    }
                    assertTrue(shared >= .60 * SAMPLES, variants[first] + " / " + variants[second] + " " + trait);
                }
            }
        }
    }

    private static int wildReference(long seed, Trait trait, int centre) {
        long axisSalt = 0x9E3779B97F4A7C15L * (trait.ordinal() + 1L);
        double noise = 15 * new Random(seed ^ axisSalt ^ 0xD1B54A32D192ED03L).nextGaussian();
        return Math.clamp((int) Math.round(centre + noise), 10, 90);
    }

    @Test
    void eachWildAxisHasTheExpectedSpread() {
        for (Trait trait : Trait.values()) {
            YukkuriVariant variant = trait == ATTACHMENT ? YukkuriVariant.CHEN : YukkuriVariant.REIMU;
            Random seeds = new Random(171119);
            double total = 0;
            double squares = 0;
            int low = 0;
            for (int n = 0; n < SAMPLES; n++) {
                long seed = seeds.nextLong();
                int value = TraitDraw.wild(seed, trait, variant);
                assertEquals(value, TraitDraw.wild(seed, trait, variant));
                assertTrue(value >= 10 && value <= 90);
                total += value;
                squares += value * value;
                if (value <= 35) low++;
            }
            double mean = total / SAMPLES;
            double sd = Math.sqrt(squares / SAMPLES - mean * mean);
            assertEquals(50, mean, .6);
            assertTrue(sd >= 14.5 && sd <= 15.5, "sd=" + sd);
            assertTrue(low >= 1400 && low <= 1900, "low=" + low);
        }
    }

    @Test
    void missingParentDrawUsesTheChildVariantAndItsOwnSalt() {
        long seed = 171119;
        for (YukkuriVariant variant : YukkuriVariant.values()) {
            for (Trait trait : Trait.values()) {
                int centre = 50 + OFFSETS.get(variant).getOrDefault(trait, 0);
                assertEquals(wildReference(seed ^ 0xBF58476D1CE4E5B9L, trait, centre),
                        TraitDraw.unknownParent(seed, trait, variant));
            }
        }
        assertNotEquals(TraitDraw.wild(seed, BOLDNESS, YukkuriVariant.MARISA),
                TraitDraw.unknownParent(seed, BOLDNESS, YukkuriVariant.MARISA));
    }

    @Test
    void inheritanceKeepsTheS1DrawForTwoKnownParents() {
        Random seeds = new Random(171119);
        double knownTotal = 0;
        double unknownTotal = 0;
        for (int n = 0; n < SAMPLES; n++) {
            long seed = seeds.nextLong();
            for (Trait trait : Trait.values()) {
                int axis = trait.ordinal();
                long axisSalt = 0x9E3779B97F4A7C15L * (axis + 1L);
                int noise = new Random(seed ^ axisSalt ^ 0x94D049BB133111EBL).nextInt(31) - 15;
                assertEquals(50 + noise, TraitDraw.inherit(seed, axis, 30, 70, 0, 100));
                assertEquals(Math.round((50 + noise) * .25), TraitDraw.inherit(seed, axis, 30, 70, 0, 25));
            }
            knownTotal += TraitDraw.inherit(seed, BOLDNESS.ordinal(), 30, 70, 0, 100);
            int unknown = TraitDraw.unknownParent(seed, BOLDNESS, YukkuriVariant.MARISA);
            unknownTotal += TraitDraw.inherit(seed, BOLDNESS.ordinal(), 80, unknown, 0, 100);
        }
        assertEquals(50, knownTotal / SAMPLES, 1);
        assertEquals(68.5, unknownTotal / SAMPLES, 1);
    }

    @Test
    void directAndNaturalCreationUseTheSameSeedBeforeFormMapping() {
        Random seeds = new Random(171119);
        for (int n = 0; n < 100; n++) {
            UUID identity = new UUID(seeds.nextLong(), seeds.nextLong());
            long seed = identity.getMostSignificantBits() ^ identity.getLeastSignificantBits();
            for (YukkuriVariant variant : YukkuriVariant.values()) {
                BirthProfile direct = BirthProfile.wild(identity, variant, false, Kind.NORMAL);
                for (Kind kind : Kind.values()) {
                    if (kind.fits(variant) && kind != Kind.WASA_REIMU)
                        assertEquals(direct, BirthProfile.wild(identity, variant, false, kind));
                }
                for (Trait trait : Trait.values()) {
                    assertEquals(TraitDraw.wild(seed, trait, variant), direct.innate(trait));
                }
            }
            BirthProfile direct = BirthProfile.wild(identity, YukkuriVariant.REIMU, false, Kind.NORMAL);
            BirthProfile deficient = BirthProfile.wild(identity, YukkuriVariant.REIMU, true, Kind.NORMAL);
            BirthProfile wasa = BirthProfile.wild(identity, YukkuriVariant.REIMU, false, Kind.WASA_REIMU);
            assertEquals(new BirthProfile((int) Math.round(direct.intelligence() * .25),
                    75 + (int) Math.round(direct.friendliness() * .25), direct.boldness(), direct.attachment(),
                    direct.selfControl()), deficient);
            assertEquals(new BirthProfile(direct.intelligence(), (int) Math.round(direct.friendliness() * .25),
                    direct.boldness(), 75 + (int) Math.round(direct.attachment() * .25),
                    (int) Math.round(direct.selfControl() * .25)), wasa);
        }
    }

    @Test
    void halfPointsRoundUpInOrdinaryAndNarrowForms() {
        assertEquals(15, TraitDraw.inForm(58, 0, 25));
        assertEquals(90, TraitDraw.inForm(58, 75, 100));
        for (long seed = 0; seed < 100; seed++) {
            int noise = TraitDraw.inherit(seed, 0, 50, 50, 0, 100) - 50;
            assertEquals(25 + noise, TraitDraw.inherit(seed, 0, 19, 30, 0, 100));
        }
    }
}
