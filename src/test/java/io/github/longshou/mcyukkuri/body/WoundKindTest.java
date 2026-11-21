package io.github.longshou.mcyukkuri.body;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class WoundKindTest {
    @ParameterizedTest
    @EnumSource(value = WoundKind.class, names = {"SCRATCH", "TEAR", "DENT", "SCORCH"})
    void skinInjuriesAreWhatOrangeJuiceTreats(WoundKind kind) {
        assertTrue(kind.injury());
    }

    @ParameterizedTest
    @EnumSource(value = WoundKind.class, names = {"SCRATCH", "TEAR", "DENT", "SCORCH"}, mode = EnumSource.Mode.EXCLUDE)
    void marksAndCoreDamageAreNotInjuries(WoundKind kind) {
        assertFalse(kind.injury());
    }
}
