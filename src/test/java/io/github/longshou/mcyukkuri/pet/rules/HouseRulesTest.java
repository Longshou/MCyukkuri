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

    @Test void syncCopiesRulesIndependentlyAndRespectsPerception() {
        UUID owner = UUID.randomUUID();
        var source = new HouseRule.Policy();
        source.toggle(owner, HouseRule.NO_SINGING, true);
        source.toggle(owner, HouseRule.NO_EATING_UNASKED, true);

        var awakePeer = new HouseRule.Policy();
        var sleepingPeer = new HouseRule.Policy();

        int expectedDiff = HouseRule.NO_SINGING.bit() | HouseRule.NO_EATING_UNASKED.bit();
        assertEquals(expectedDiff, awakePeer.sync(owner, source.mask(), true));
        assertEquals(source.mask(), awakePeer.mask());
        assertTrue(awakePeer.told(owner));
        assertTrue(awakePeer.binds(owner, HouseRule.NO_SINGING, false));

        // Repeating sync when already identical and told is a no-op.
        assertEquals(0, awakePeer.sync(owner, source.mask(), true));

        // Sleeping peer gets the synced mask but stays untold until it meets the owner.
        assertEquals(expectedDiff, sleepingPeer.sync(owner, source.mask(), false));
        assertEquals(source.mask(), sleepingPeer.mask());
        assertFalse(sleepingPeer.told(owner));
        assertFalse(sleepingPeer.binds(owner, HouseRule.NO_SINGING, false));

        // Afterwards each pet remains independent.
        assertFalse(awakePeer.toggle(owner, HouseRule.NO_SINGING, true));
        assertTrue(source.enabled(HouseRule.NO_SINGING));
        assertTrue(sleepingPeer.enabled(HouseRule.NO_SINGING));
    }

    @Test void bystanderLearningAndCautionBoostHoldProbability() {
        var caughtBold = RulesMath.bystanderLearning(false, false, .1);
        var caughtTimid = RulesMath.bystanderLearning(false, false, .8);
        assertEquals(1, caughtBold.obedience());
        assertTrue(caughtTimid.fear() > caughtBold.fear());
        assertEquals(RulesMath.CAUTION_WINDOW / 2, caughtBold.cautionTicks());

        var scold = RulesMath.bystanderLearning(true, false, .5);
        var blow = RulesMath.bystanderLearning(true, true, .5);
        assertTrue(blow.obedience() > scold.obedience());
        assertTrue(blow.fear() > scold.fear());
        assertEquals(RulesMath.CAUTION_WINDOW, blow.cautionTicks());

        double base = RulesMath.hold(.5, 40, .8);
        double cautious = RulesMath.hold(.5, 40, .8, RulesMath.cautionBonus(RulesMath.CAUTION_WINDOW));
        assertTrue(cautious > base + .15);
        assertEquals(base, RulesMath.hold(.5, 40, .8, RulesMath.cautionBonus(0)), 1e-9);
    }
}
