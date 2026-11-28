package io.github.longshou.mcyukkuri.dialogue;

import io.github.longshou.mcyukkuri.social.Temperament;
import io.github.longshou.mcyukkuri.status.Identity;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DialogueStyleTest {
    private static NativeDialogue.Line line(String id, String variant) {
        return new NativeDialogue.Line(id, "k." + id, "reimu", "adult", List.of("hello"), 50, "", null, variant, null, null);
    }

    @Test void preferencesOrderFineGrainedStylesAndIdentities() {
        var cocky = new Temperament(50, 20, 80, 30, 20);
        assertEquals(List.of("stray_cocky", "cocky", "stray_proud", "proud", "stray", "plain"),
                DialogueStyle.preferences(cocky, Identity.STRAY, false, false, false, 10f, "marisa"));

        var patchouli = new Temperament(80, 48, 40, 48, 60);
        assertEquals(List.of("wild_polite", "polite", "wild", "plain"),
                DialogueStyle.preferences(patchouli, Identity.WILD_FAMILY, false, false, false, 10f, "patchouli"));

        var warmPatchouli = new Temperament(80, 60, 40, 50, 60);
        assertEquals(List.of("wild_polite", "polite", "warm", "wild", "plain"),
                DialogueStyle.preferences(warmPatchouli, Identity.WILD_FAMILY, false, false, false, 10f, "patchouli"));

        var warmPet = new Temperament(50, 85, 50, 80, 50);
        assertEquals(List.of("pet_warm", "warm", "pet", "plain"),
                DialogueStyle.preferences(warmPet, Identity.PET, false, false, false, 10f, "reimu"));

        var politePet = new Temperament(75, 80, 50, 75, 75);
        assertEquals(List.of("pet_polite", "polite", "warm", "proud", "pet", "plain"),
                DialogueStyle.preferences(politePet, Identity.PET, false, true, false, 10f, "reimu"));

        var timidStray = new Temperament(50, 50, 15, 50, 50);
        assertEquals(List.of("stray_timid", "timid", "stray", "plain"),
                DialogueStyle.preferences(timidStray, Identity.STRAY, false, false, false, 10f, "reimu"));

        var plainWild = new Temperament(50, 50, 50, 50, 50);
        assertEquals(List.of("wild", "plain"),
                DialogueStyle.preferences(plainWild, Identity.WILD_SETTLEMENT, false, false, false, 10f, "reimu"));
    }

    @Test void legacyMatchesOriginalThreeStyles() {
        var bold = new Temperament(50, 50, 80, 50, 50);
        var timid = new Temperament(50, 50, 15, 50, 50);
        var plain = new Temperament(50, 50, 50, 50, 50);
        assertEquals("timid", DialogueStyle.legacy(true, true, bold, 0f));
        assertEquals("proud", DialogueStyle.legacy(false, true, timid, 80f));
        assertEquals("timid", DialogueStyle.legacy(false, false, bold, 55f));
        assertEquals("timid", DialogueStyle.legacy(false, false, timid, 10f));
        assertEquals("proud", DialogueStyle.legacy(false, false, bold, 39f));
        assertEquals("plain", DialogueStyle.legacy(false, false, plain, 10f));
    }

    @Test void selectFollowsPreferenceChainAndAvoidsNearbyAndRecent() {
        var choices = List.of(
                line("plain", null),
                line("proud", "proud"),
                line("cocky", "cocky"),
                line("stray_cocky", "stray_cocky"));
        IntUnaryOperator rng = bound -> 0;

        assertEquals("stray_cocky", NativeDialogue.selectLine(
                choices, List.of("stray_cocky", "cocky", "proud", "plain"), List.of(), Set.of(), rng).id());
        assertEquals("cocky", NativeDialogue.selectLine(
                choices, List.of("stray_cocky", "cocky", "proud", "plain"), List.of("k.stray_cocky"), Set.of(), rng).id());
        assertEquals("proud", NativeDialogue.selectLine(
                choices, List.of("stray_cocky", "cocky", "proud", "plain"), List.of("k.stray_cocky"), Set.of("k.cocky"), rng).id());
        assertEquals("plain", NativeDialogue.selectLine(
                List.of(line("plain", null), line("proud", "proud")),
                List.of("stray_polite", "polite", "plain"), List.of(), Set.of(), rng).id());
    }

    @Test void ticksForPrefersSpeakerVoiceAndFallsBackCleanly() {
        var shared = new NativeDialogue.Line("shared", "k.shared", "*", "*", List.of("greet"), 160, "", null, null,
                Map.of("chen.adult", "v.chen", "reimu.young", "v.reimu_y", "reimu.adult", "v.reimu_a"),
                Map.of("chen.adult", 72, "reimu.young", 85, "reimu.adult", 92));
        assertEquals(72, shared.ticksFor("chen", "adult"));
        assertEquals(85, shared.ticksFor("reimu", "baby"));
        assertEquals(85, shared.ticksFor("reimu", "juvenile"));
        assertEquals(92, shared.ticksFor("reimu", "adult"));
        assertEquals(160, shared.ticksFor("patchouli", "adult"));
        assertEquals(50, line("species", null).ticksFor("reimu", "adult"));
    }
}
