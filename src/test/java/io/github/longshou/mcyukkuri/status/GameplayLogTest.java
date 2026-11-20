package io.github.longshou.mcyukkuri.status;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class GameplayLogTest {
    @Test void suppressedCallSkipsItsSupplier() {
        Map<String, GameplayLog.Window> windows = new HashMap<>();
        Object[] values = {"probe"};
        int first = GameplayLog.Window.enter(windows, 100, "move", "test", 20);
        assertEquals(0, first);
        assertSame(values, GameplayLog.Window.arguments(first, () -> values));

        int suppressed = GameplayLog.Window.enter(windows, 119, "move", "test", 20);
        assertNull(GameplayLog.Window.arguments(suppressed, () -> {
            throw new AssertionError("suppressed diagnostic evaluated");
        }));

        int reopened = GameplayLog.Window.enter(windows, 120, "move", "test", 20);
        assertEquals(1, reopened);
        assertSame(values, GameplayLog.Window.arguments(reopened, () -> values));
    }
}
