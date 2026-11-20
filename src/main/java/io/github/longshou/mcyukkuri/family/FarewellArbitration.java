package io.github.longshou.mcyukkuri.family;

import java.util.function.BooleanSupplier;

/** The parent's reply and the child's departure compete for movement separately. */
public final class FarewellArbitration {
    public static final int ANSWER_PRIORITY = 3;
    public static final int DEPARTURE_PRIORITY = 4;

    private FarewellArbitration() {}

    static boolean canAnswer(boolean safeAndFreeOfNeeds, BooleanSupplier onDuty) {
        return safeAndFreeOfNeeds && !onDuty.getAsBoolean();
    }
}
