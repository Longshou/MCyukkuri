package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Reason;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One way to an aquatic intent whose steps are read off the world (refactor WP6a): when its queue is empty it asks
 * {@code derive} for the next short run of steps (walk to the stand, face, grab ...), each built only when it is due so it
 * aims at where things are then; a step builder that answers null is not needed now and is skipped. {@code derive} answering
 * null or nothing is "no way on" ({@code NO_PROGRESS}); a step that ends blocked fails the way, and the runner counts it.
 * Nothing is a stored phase: the next steps always follow from the facts.
 */
final class Plan implements Method {
    /** Builders tried in one {@link #next} call before it gives up (every one skipped means no way on). */
    private static final int SKIPS = 8;

    private final String id;
    private final Supplier<Feasibility> applicable;
    private final Set<Reason> reduces;
    private final Supplier<List<Supplier<Step>>> derive;
    private final ArrayDeque<Supplier<Step>> queue = new ArrayDeque<>();

    Plan(String id, Supplier<Feasibility> applicable, Set<Reason> reduces, Supplier<List<Supplier<Step>>> derive) {
        this.id = id;
        this.applicable = applicable;
        this.reduces = reduces;
        this.derive = derive;
    }

    static Plan of(String id, Supplier<List<Supplier<Step>>> derive) {
        return new Plan(id, Feasibility::pass, Set.of(Reason.NO_PROGRESS, Reason.NO_CONTACT, Reason.TOO_FAR, Reason.TARGET_MOVED), derive);
    }

    @Override public String id() { return id; }
    @Override public Feasibility applicable() { return applicable.get(); }
    @Override public Set<Reason> reduces() { return reduces; }

    @Override public Step next() {
        for (int i = 0; i < SKIPS; i++) {
            if (queue.isEmpty()) {
                var more = derive.get();
                if (more == null || more.isEmpty()) return null;
                queue.addAll(more);
            }
            var step = queue.poll().get();
            if (step != null) return step;
        }
        return null;
    }
}
