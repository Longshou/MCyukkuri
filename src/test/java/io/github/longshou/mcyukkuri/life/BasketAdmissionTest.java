package io.github.longshou.mcyukkuri.life;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BasketAdmissionTest {
    @Test void failedFinalClaimWithdrawsTheBodyAndKeepsTheSameBasket() {
        var events = new ArrayList<String>();
        assertFalse(BasketAdmission.finish(() -> { events.add("claim"); return false; },
                () -> events.add("withdraw"), () -> fail("Consumed a refused basket")));
        assertEquals(List.of("claim", "withdraw"), events);
        assertTrue(BasketAdmission.finish(() -> { events.add("claim again"); return true; },
                () -> fail("Withdrew an admitted sibling"), () -> events.add("consume token")));
        assertEquals(List.of("claim", "withdraw", "claim again", "consume token"), events);
    }
}
