package io.github.longshou.mcyukkuri.intent;

import io.github.longshou.mcyukkuri.motor.Action;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** One step of a {@link Method} (spec 11.1). {@code AskFor} joins this list in WP3; nothing here waits forever. */
public sealed interface Step permits Step.Do, Step.Wait, Step.Sub {
    /** One body action, run through the {@code BodyDriver} by the owning {@link IntentRunner}. */
    record Do(Action action) implements Step {
        public Do {
            Objects.requireNonNull(action, "action");
        }
    }

    /** Stops until {@code until} holds; {@code maxTicks} must be positive so no wait is unbounded. */
    record Wait(BooleanSupplier until, int maxTicks, String why) implements Step {
        public Wait {
            Objects.requireNonNull(until, "until");
            if (maxTicks <= 0) throw new IllegalArgumentException("Step.Wait needs a positive maxTicks");
            why = why == null ? "" : why;
        }
    }

    /** Runs a child intent; its result maps onto this intent's next step (spec 11.2). */
    record Sub(Intent intent) implements Step {
        public Sub {
            Objects.requireNonNull(intent, "intent");
        }
    }
}
