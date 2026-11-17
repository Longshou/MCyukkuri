package io.github.longshou.mcyukkuri.interaction;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Contact checks run in order, before inspecting the tool's action-specific requirements. */
final class ToolContactRules {
    static String refusal(Supplier<String> reach, BooleanSupplier exam, Supplier<String> harm,
                          BooleanSupplier ready, BooleanSupplier held, Supplier<String> conditions) {
        String reason = reach.get();
        if (reason != null) return reason;
        if (exam.getAsBoolean()) return "busy";
        reason = harm.get();
        if (reason != null) return reason;
        if (!ready.getAsBoolean()) return "busy";
        if (!held.getAsBoolean()) return "tool";
        return conditions.get();
    }

    private ToolContactRules() {}
}
