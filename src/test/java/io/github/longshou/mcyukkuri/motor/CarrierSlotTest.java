package io.github.longshou.mcyukkuri.motor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CarrierSlotTest {
    @Test
    void occupiedSlotRefusesAnotherPayload() {
        var slot = new CarrierSlot<String>();

        assertTrue(slot.acquire("stick"));
        assertFalse(slot.acquire("food"));
        assertEquals("stick", slot.contents().orElseThrow());
    }

    @Test
    void transferRefusalKeepsBothSlots() {
        var source = new CarrierSlot<String>();
        var destination = new CarrierSlot<String>();
        source.acquire("stick");
        destination.acquire("another stick");

        assertFalse(source.transferTo(destination));
        assertEquals("stick", source.contents().orElseThrow());
        assertEquals("another stick", destination.contents().orElseThrow());
    }

    @Test
    void transferMovesTheSamePayloadOnce() {
        var source = new CarrierSlot<Object>();
        var destination = new CarrierSlot<Object>();
        var payload = new Object();
        source.acquire(payload);

        assertTrue(source.transferTo(destination));
        assertTrue(source.isEmpty());
        assertSame(payload, destination.contents().orElseThrow());
        assertFalse(source.transferTo(destination));
    }

    @Test
    void interruptedReleaseKeepsThePayloadUntilTheDestinationAccepts() {
        var source = new CarrierSlot<String>();
        var placed = new ArrayList<String>();
        source.acquire("portion");

        assertFalse(source.release(payload -> false));
        assertEquals("portion", source.contents().orElseThrow());
        assertTrue(source.release(placed::add));
        assertFalse(source.release(placed::add));
        assertEquals(List.of("portion"), placed);
        assertTrue(source.isEmpty());
    }

    @Test
    void returningToTheSourceUsesTheSameCommit() {
        var holder = new CarrierSlot<String>();
        var originalSource = new CarrierSlot<String>();
        originalSource.acquire("portion");

        assertTrue(originalSource.transferTo(holder));
        assertTrue(holder.transferTo(originalSource));
        assertTrue(holder.isEmpty());
        assertEquals("portion", originalSource.contents().orElseThrow());
    }

    @Test
    void restoredPayloadCanBeConsumedExactlyOnce() {
        var slot = new CarrierSlot<String>();
        slot.restore("bite");
        assertFalse(slot.acquire("stick"));

        slot.consume();
        assertTrue(slot.isEmpty());
        assertFalse(slot.release(payload -> {
            throw new AssertionError("A consumed payload cannot be delivered");
        }));
    }
}
