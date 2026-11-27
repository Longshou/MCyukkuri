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
        assertTrue(heard.forbids(owner, 3, true, false));
        assertFalse(heard.forbids(owner, 3, true, true));
        assertFalse(heard.forbids(owner, 3, false, false));
        assertFalse(untold.forbids(owner, 3, true, false));
    }

    @Test void eachPetHasItsOwnIndependentRules() {
        UUID owner = UUID.randomUUID();
        var first = new HouseRule.Policy();
        var second = new HouseRule.Policy();

        assertEquals(HouseRule.NO_SUKKIRI.bit(), first.mask());
        assertTrue(first.binds(owner, HouseRule.NO_SUKKIRI, false));
        assertTrue(second.binds(owner, HouseRule.NO_SUKKIRI, false));

        // Lifting breeding ban and adding no-singing on the first pet does not touch the second pet.
        assertFalse(first.toggle(owner, HouseRule.NO_SUKKIRI, true));
        assertTrue(first.toggle(owner, HouseRule.NO_SINGING, true));

        assertFalse(first.enabled(HouseRule.NO_SUKKIRI));
        assertTrue(first.enabled(HouseRule.NO_SINGING));
        assertTrue(first.binds(owner, HouseRule.NO_SINGING, false));
        assertFalse(first.binds(owner, HouseRule.NO_SUKKIRI, false));

        assertTrue(second.enabled(HouseRule.NO_SUKKIRI));
        assertFalse(second.enabled(HouseRule.NO_SINGING));
        assertTrue(second.binds(owner, HouseRule.NO_SUKKIRI, false));
        assertFalse(second.binds(owner, HouseRule.NO_SINGING, false));
        assertEquals(0, second.version());
    }

    @Test void sleepingPetRemainsUntoldUntilWakingAndOwnerResetClearsPolicy() {
        UUID owner = UUID.randomUUID(), nextOwner = UUID.randomUUID();
        var policy = new HouseRule.Policy();

        assertTrue(policy.toggle(owner, HouseRule.NO_EATING_UNASKED, false));
        assertFalse(policy.told(owner));
        assertFalse(policy.binds(owner, HouseRule.NO_EATING_UNASKED, false));

        assertTrue(policy.markTold(owner));
        assertTrue(policy.told(owner));
        assertTrue(policy.binds(owner, HouseRule.NO_EATING_UNASKED, false));
        assertFalse(policy.binds(nextOwner, HouseRule.NO_EATING_UNASKED, false));

        policy.reset();
        assertEquals(HouseRule.NO_SUKKIRI.bit(), policy.mask());
        assertEquals(0, policy.version());
        assertTrue(policy.told(nextOwner));
        assertTrue(policy.binds(nextOwner, HouseRule.NO_SUKKIRI, false));
    }
}
