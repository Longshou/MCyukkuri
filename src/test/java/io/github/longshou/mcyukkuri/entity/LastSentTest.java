package io.github.longshou.mcyukkuri.entity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LastSentTest {
    private record Placement(int hat, long beat) {}

    @Test void skipsEqualValuesAndPublishesMovementAndClear() {
        var sent = new LastSent<Placement>();
        var empty = new Placement(-1, -1);
        assertTrue(sent.changed(empty));
        assertFalse(sent.changed(new Placement(-1, -1)));
        assertTrue(sent.changed(new Placement(1, 20)));
        assertTrue(sent.changed(new Placement(1, -1)));
        assertTrue(sent.changed(new Placement(2, -1)));
        assertTrue(sent.changed(empty));
        assertFalse(sent.changed(empty));
    }

    @Test void invalidationPublishesEvenAnEqualValue() {
        var sent = new LastSent<Placement>();
        var held = new Placement(1, 20);
        assertTrue(sent.changed(held));
        sent.invalidate();
        assertTrue(sent.changed(held));
        assertFalse(sent.changed(held));
    }
}
