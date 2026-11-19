package io.github.longshou.mcyukkuri.pet.rules;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HouseRulesTest {
    @Test void aRevisionBelongsToTheOwnerWhoTaughtIt() {
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
        var heard = new HouseRule.Instruction(owner, 3);
        assertTrue(heard.known(owner, 3));
        assertFalse(heard.known(other, 3));
        assertFalse(heard.known(owner, 4));
        var untold = new HouseRule.Instruction(null, 0);
        assertFalse(untold.known(owner, 3));
        assertTrue(untold.known(owner, 0));
        assertFalse(untold.known(null, 0));
    }
}
