package io.github.longshou.mcyukkuri.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

final class ToolContactRulesTest {
    @Test void unreachableContactDoesNotReadTheTarget() {
        for (String reason : new String[]{"target", "reach", "sight"})
            assertEquals(reason, ToolContactRules.refusal(() -> reason, () -> fail("exam"),
                    () -> fail("custody"), () -> fail("operation"), () -> fail("hand"), () -> fail("tool conditions")));
    }

    @Test void anExamIsExclusive() {
        assertEquals("busy", ToolContactRules.refusal(() -> null, () -> true,
                () -> fail("custody"), () -> fail("operation"), () -> fail("hand"), () -> fail("tool conditions")));
    }

    @Test void shopAndCustodyRefusalsPrecedeRestraintAndEquipment() {
        for (String reason : new String[]{"shop", "protected", "owner", "water", "hat", "carried"})
            assertEquals(reason, ToolContactRules.refusal(() -> null, () -> false, () -> reason,
                    () -> fail("operation"), () -> fail("hand"), () -> fail("tool conditions")));
    }

    @Test void existingOperationOrCooldownAndAChangedHandPreventToolContact() {
        assertEquals("busy", ToolContactRules.refusal(() -> null, () -> false, () -> null,
                () -> false, () -> fail("hand"), () -> fail("tool conditions")));
        assertEquals("tool", ToolContactRules.refusal(() -> null, () -> false, () -> null,
                () -> true, () -> false, () -> fail("tool conditions")));
    }

    @Test void validContactChecksTheToolsOwnConditions() {
        for (String reason : new String[]{"space", "heat", "ceiling", "condition"})
            assertEquals(reason, ToolContactRules.refusal(() -> null, () -> false, () -> null,
                    () -> true, () -> true, () -> reason));
        assertNull(ToolContactRules.refusal(() -> null, () -> false, () -> null,
                () -> true, () -> true, () -> null));
    }
}
