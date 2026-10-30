package io.github.longshou.mcyukkuri.intent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** One beat of a {@link Choreography}: it names its successors (null ends the ceremony) and the roles it engages, the first of a {@link Perform} being its lead, who ends it. */
public sealed interface Beat permits Beat.Perform, Beat.Present, Beat.Await, Beat.Branch {
    default Map<String, Supplier<Intent>> cast() { return Map.of(); }

    /** The lead runs its intent; its report goes to {@code next} (a Branch decides the story), {@code ticks} later to {@code onTimeout}. */
    record Perform(Map<String, Supplier<Intent>> cast, IntSupplier ticks, String next, String onTimeout) implements Beat {
        public Perform { if (cast.isEmpty()) throw new IllegalArgumentException("A Perform needs a lead role"); }
    }

    /** The lead plays {@code clip} (on its own body, through its own goal) for {@code ticks}; the rest of the cast runs its intents. */
    record Present(Map<String, Supplier<Intent>> cast, String clip, int ticks, String next) implements Beat {
        public Present { if (ticks <= 0) throw new IllegalArgumentException("Present needs a positive ticks"); }
    }

    /** Waits until {@code until} holds, never longer than {@code ticks}; the cast is held or runs its side jobs meanwhile. */
    record Await(Map<String, Supplier<Intent>> cast, BooleanSupplier until, IntSupplier ticks, String next, String onTimeout)
            implements Beat {
        public Await { Objects.requireNonNull(until, "until"); Objects.requireNonNull(onTimeout, "onTimeout"); }
    }

    /** Chooses the next beat from the report that led here, in the same tick. */
    record Branch(Function<Choreography.Report, String> choose) implements Beat {}

    /** A cast of one role; a null intent holds the role still. */
    static Map<String, Supplier<Intent>> roles(String role, Supplier<Intent> intent) {
        var out = new LinkedHashMap<String, Supplier<Intent>>();
        out.put(role, intent);
        return out;
    }

    /** A cast of two roles, the first the lead. */
    static Map<String, Supplier<Intent>> roles(String lead, Supplier<Intent> leadIntent, String other, Supplier<Intent> otherIntent) {
        var out = roles(lead, leadIntent);
        out.put(other, otherIntent);
        return out;
    }
}
