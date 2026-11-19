package io.github.longshou.mcyukkuri.mind;

import io.github.longshou.mcyukkuri.mind.GriefVow.Lost;
import io.github.longshou.mcyukkuri.social.Temperament;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GriefVowTest {
    @Test
    void relationshipAndResponsibilityDetermineTheVow() {
        // Columns: neither, dependants only, selfish only, both.
        String[][] events = {
                {null, "vow_partner_young", null, "vow_partner_young"},
                {"vow_to_mother", "vow_to_mother", "vow_blame_parent", "vow_blame_parent"},
                {"vow_to_father", "vow_to_father", "vow_blame_parent", "vow_blame_parent"},
                {null, "vow_for_child", null, "vow_for_child"},
                {null, null, null, null}
        };
        for (Lost lost : Lost.values()) {
            for (int n = 0; n < 4; n++) {
                assertEquals(events[lost.ordinal()][n], GriefVow.event(lost, (n & 2) != 0, (n & 1) != 0));
            }
        }
    }

    @Test
    void styleComposesAttachmentStoicismAndBoldness() {
        String[] styles = {"plain", "proud", "proud", "proud", "timid", "timid", "proud", "proud"};
        for (int n = 0; n < styles.length; n++) {
            assertEquals(styles[n], GriefVow.style((n & 4) != 0, (n & 2) != 0, (n & 1) != 0));
        }
    }

    @Test
    void widowedMotherAndChildrenHaveDistinctVows() {
        var mother = new Temperament(55, 70, 55, 60, 70);
        var child = new Temperament(55, 70, 55, 60, 70);
        var selfishChild = new Temperament(55, 25, 55, 60, 35);
        assertVow(mother, Lost.PARTNER, true, "vow_partner_young", "proud");
        assertVow(child, Lost.FATHER, false, "vow_to_father", "proud");
        assertVow(selfishChild, Lost.MOTHER, false, "vow_blame_parent", "timid");
    }

    private static void assertVow(Temperament t, Lost lost, boolean dependants, String event, String style) {
        assertEquals(event, GriefVow.event(lost, t.is(Gate.SELFISH), dependants));
        assertEquals(style, GriefVow.style(t.is(Gate.ATTACHED), t.is(Gate.STOIC), t.is(Gate.BOLD)));
    }
}
