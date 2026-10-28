package io.github.longshou.mcyukkuri.intent;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One beat of a {@link Choreography} (spec 11.5). The beat table is an ordered map {@code beat id -> Beat}, and every
 * beat says explicitly where it goes next: {@code next} is the ordinary successor, {@code onTimeout} the branch an
 * {@link Await} takes when its finite window runs out, and a {@link Branch} chooses from the report of the beat before.
 * A null beat id ends the choreography.
 *
 * <p>Only the conductor's passive clock (or the role's own report) moves the table; a beat never writes a body. A beat
 * that has a body doing something names the role it is for, and the role's own goal reads the beat through
 * {@link Choreography#roleIntent(java.util.UUID)} / {@link Choreography#present(java.util.UUID)}.
 */
public sealed interface Beat permits Beat.Perform, Beat.Present, Beat.Await, Beat.Branch {
    /** The role this beat is about, or null for a beat that is not about one role. */
    default String role() { return null; }

    /**
     * Ask {@code role} to run {@code intent} (built once when the beat starts). The beat ends when the role reports
     * through {@link Choreography#report(java.util.UUID, Intent, io.github.longshou.mcyukkuri.motor.Outcome)} or
     * {@link Choreography#report(java.util.UUID, Intent, IntentRunner.Result)}, and then goes to {@code next} whatever
     * the result was; a {@link Branch} after it decides the story. It is finite like an {@link Await}: after {@code ticks}
     * the choreography goes to {@code onTimeout} (null ends it) and a late report of the old intent is dropped.
     */
    record Perform(String role, Supplier<Intent> intent, int ticks, String next, String onTimeout) implements Beat {
        /** The window a Perform gets when the table does not name one (one minute). */
        public static final int DEFAULT_TICKS = 1200;

        public Perform {
            role = requireRole(role);
            Objects.requireNonNull(intent, "intent");
            if (ticks <= 0) throw new IllegalArgumentException("Perform needs a positive ticks");
        }

        /** A Perform with the default window whose timeout goes where the ordinary end goes. */
        public Perform(String role, Supplier<Intent> intent, String next) {
            this(role, intent, DEFAULT_TICKS, next, next);
        }

        @Override public String role() { return role; }
    }

    /**
     * Publish a presentation for {@code role}: clip, expression, sound and a finite number of ticks. The role's own
     * goal/presentation polls {@link Choreography#present(java.util.UUID)} and plays it on its own body, so the
     * conductor never touches another entity. The beat advances to {@code next} when the ticks have elapsed.
     */
    record Present(String role, String clip, String expression, String sound, int ticks, String next) implements Beat {
        public Present {
            role = requireRole(role);
            clip = clip == null ? "" : clip;
            expression = expression == null ? "" : expression;
            sound = sound == null ? "" : sound;
            if (ticks <= 0) throw new IllegalArgumentException("Present needs a positive ticks");
        }

        @Override public String role() { return role; }
    }

    /**
     * Wait until {@code condition} holds, but never longer than {@code ticks}: on timeout the choreography goes to
     * {@code onTimeout} instead. Both destinations are beats; there is no unbounded wait (spec 15).
     */
    record Await(BooleanSupplier condition, int ticks, String next, String onTimeout) implements Beat {
        public Await {
            Objects.requireNonNull(condition, "condition");
            if (ticks <= 0) throw new IllegalArgumentException("Await needs a positive ticks");
            Objects.requireNonNull(onTimeout, "onTimeout");
        }
    }

    /**
     * Choose the next beat from the report of the beat that just ended (spec 11.5 {@code Branch(outcome -> beat)}).
     * A null answer ends the choreography. The branch is instant: the conductor enters the chosen beat in the same tick.
     */
    record Branch(Function<Choreography.Report, String> choose) implements Beat {
        public Branch {
            Objects.requireNonNull(choose, "choose");
        }
    }

    private static String requireRole(String role) {
        Objects.requireNonNull(role, "role");
        String trimmed = role.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("A beat role needs a name");
        return trimmed;
    }
}
