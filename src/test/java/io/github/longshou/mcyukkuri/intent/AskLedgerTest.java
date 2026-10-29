package io.github.longshou.mcyukkuri.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Spec 11.3: one draw per (incident, helper), the draw kept, only settled incidents evicted. No Minecraft type is touched. */
class AskLedgerTest {
    private static final UUID FALLER = new UUID(1, 1), MOTHER = new UUID(2, 2), FATHER = new UUID(3, 3);

    private static Ask ask(UUID helper, String incident) { return new Ask(FALLER, helper, body -> null, incident); }

    @Test
    void oneDrawPerIncidentAndHelper() {
        var ledger = new AskLedger();
        ledger.open("water#1", FALLER, "water", 0);
        var first = ledger.decide(ask(MOTHER, "water#1"), .5, () -> .3);
        var again = ledger.decide(ask(MOTHER, "water#1"), .99, () -> .9);
        assertTrue(first.accepted());
        assertEquals(first, again);
        assertEquals(.3, again.roll(), 1E-9);
        var refused = ledger.decide(ask(FATHER, "water#1"), .5, () -> .7);
        assertFalse(refused.accepted());
        assertFalse(ledger.decide(ask(FATHER, "water#1"), 1, () -> 0).accepted());
    }

    @Test
    void aSettledIncidentDrawsNoMore() {
        var ledger = new AskLedger();
        ledger.open("water#2", FALLER, "water", 0);
        var kept = ledger.decide(ask(MOTHER, "water#2"), 1, () -> .1);
        ledger.settle("water#2", "aboard");
        assertNull(ledger.decide(ask(FATHER, "water#2"), 1, () -> .1));
        assertEquals(kept, ledger.decide(ask(MOTHER, "water#2"), 0, () -> .9));
        assertNull(ledger.openOf(FALLER, "water"));
    }

    @Test
    void helpingEndsOnceTheRunIsOver() {
        var ledger = new AskLedger();
        ledger.open("share#1", MOTHER, "share_young", 0);
        ledger.offer(new Ask(MOTHER, FATHER, body -> null, "share#1"));
        assertEquals(1, ledger.offered(FATHER).size());
        assertNotNull(ledger.decide(ledger.offered(FATHER).get(0), 1, () -> .2));
        assertTrue(ledger.helping("share#1", FATHER));
        ledger.ended("share#1", FATHER, "DONE");
        assertFalse(ledger.helping("share#1", FATHER));
        assertTrue(ledger.offered(FATHER).isEmpty());
    }

    @Test
    void onlySettledIncidentsAreEvicted() {
        var ledger = new AskLedger();
        ledger.open("open#0", FALLER, "water", 0);
        ledger.decide(ask(MOTHER, "open#0"), 1, () -> .5);
        for (int n = 1; n <= AskLedger.KEEP + 20; n++) {
            ledger.open("done#" + n, FALLER, "water", n);
            ledger.settle("done#" + n, "aboard");
        }
        assertEquals(AskLedger.KEEP + 1, ledger.size());
        assertTrue(ledger.decision("open#0", MOTHER).accepted());
        assertEquals("open#0", ledger.openOf(FALLER, "water"));
    }
}
