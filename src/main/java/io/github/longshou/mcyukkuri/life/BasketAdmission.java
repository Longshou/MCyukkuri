package io.github.longshou.mcyukkuri.life;

import java.util.function.BooleanSupplier;

/** Home admission needs the body in the level. Its token remains authoritative until the claim succeeds. */
final class BasketAdmission {
    static boolean finish(BooleanSupplier admit, Runnable withdrawBody, Runnable consumeToken) {
        if (!admit.getAsBoolean()) {
            withdrawBody.run();
            return false;
        }
        consumeToken.run();
        return true;
    }
}
