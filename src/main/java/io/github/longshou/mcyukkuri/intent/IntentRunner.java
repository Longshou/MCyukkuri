package io.github.longshou.mcyukkuri.intent;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.BodyDriver;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.action.Await;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.world.phys.Vec3;

/**
 * Runs one {@link Intent} for one body (spec 11.2). The owning Goal builds it in {@code start()}, calls {@link #tick()}
 * once per decision tick (inside {@link BodyDriver#deciding()}) and {@link #stop(String)} in {@code stop()}. Every step
 * goes through the {@link BodyDriver}, so this class never writes a position, velocity or ride. Server only, never saved.
 */
public final class IntentRunner implements MotorOwner {
    public enum Result { RUNNING, DONE, EXHAUSTED, LOST, INTERRUPTED }

    /** The world facts a signature is keyed on (spec 11.2); a runner without one only follows the tick bucket. */
    public record Target(Vec3 at, Vec3 host, Set<Integer> blockers) {
        public Target {
            blockers = blockers == null ? Set.of() : Set.copyOf(blockers);
        }

        /** A target with no host and no blockers. */
        public static Target of(Vec3 at) {
            return new Target(at, null, Set.of());
        }
    }

    private final YukkuriEntity body;
    private final Intent intent;
    private final String ownerId;
    private final Attempts attempts;
    private Supplier<Target> target;
    private Result result = Result.RUNNING;
    private boolean active;
    private Method method;
    private Step step;
    private Action currentAction;
    private IntentRunner child;
    private long stepStartTick;

    public IntentRunner(YukkuriEntity body, Intent intent, String ownerId) {
        this(body, intent, ownerId, Attempts.DEFAULT_MAX_SWITCHES);
    }

    public IntentRunner(YukkuriEntity body, Intent intent, String ownerId, int maxSwitches) {
        if (body == null || intent == null || ownerId == null) {
            throw new IllegalArgumentException("An intent runner needs a body, an intent and an owner id");
        }
        if (intent.methods() == null) throw new IllegalArgumentException("Intent " + intent.id() + " has no methods");
        if (intent.deadline() == Long.MAX_VALUE) {
            throw new IllegalArgumentException("Intent " + intent.id() + " needs a finite deadline");
        }
        this.body = body;
        this.intent = intent;
        this.ownerId = ownerId;
        this.attempts = new Attempts(maxSwitches);
    }

    /** Sets the world facts the signature is keyed on; without it the signature only follows the 200 tick bucket. */
    public IntentRunner target(Supplier<Target> source) {
        this.target = source;
        return this;
    }

    /** Called from the Goal's {@code start()}; logs {@code [intent] start}. */
    public void start() {
        if (active) return;
        active = true;
        result = Result.RUNNING;
        GameplayLog.event(body, "intent", "start id={} deadline=+{}", intent.id(), intent.deadline() - now());
    }

    /** Called by the owning Goal once per decision tick, inside {@link BodyDriver#deciding()}. */
    public void tick() {
        if (!active) return;
        long now = now();
        if (intent.satisfied()) {
            end(Result.DONE);
            return;
        }
        if (now > intent.deadline() || attempts.exhausted()) {
            end(Result.EXHAUSTED);
            return;
        }
        if (step == null) choose(now);
        else readStep(now);
    }

    /** The Goal's {@code stop()}: cancels the action this runner started and ends INTERRUPTED, with no resume. */
    public void stop(String why) {
        if (!active) return;
        IntentRunner c = child;
        if (c != null) c.stop(why);
        driver().cancel(this, why);
        end(Result.INTERRUPTED);
    }

    @Override public String ownerId() { return ownerId; }

    @Override public boolean active() { return active; }

    public Intent intent() { return intent; }

    /** The current method, or null before the first pick. */
    public Method method() { return method; }

    public Attempts attempts() { return attempts; }

    public Result result() { return result; }

    /** Every counted reason with its shortfall (spec 11.2: the end line records all of them). */
    public List<Attempts.Failure> reasons() { return attempts.reasons(); }

    /** The innermost running child ({@code Sub}) runner; status reads this one. */
    public IntentRunner innermost() {
        IntentRunner r = this;
        while (r.child != null && r.child.active()) r = r.child;
        return r;
    }

    // ---- one decision ------------------------------------------------------------------------------------------

    private void choose(long now) {
        WorldSignature signature = signature(now);
        Method picked = pick(null, signature);
        if (picked == null) {
            end(Result.EXHAUSTED);
            return;
        }
        begin(picked, now);
    }

    /** Starts {@code chosen}: logs {@code [method] pick} and runs its first step. */
    private void begin(Method chosen, long now) {
        method = chosen;
        GameplayLog.event(body, "method", "pick intent={} method={} attempts={}/{}",
                intent.id(), chosen.id(), attempts.used(), attempts.max());
        Step first = chosen.next();
        if (first == null) {
            failed(Reason.NO_PROGRESS, 0, now);
            return;
        }
        start(first, now);
    }

    /** The first applicable method that is not excluded under {@code signature}, preferring one that reduces. */
    private Method pick(Reason prefer, WorldSignature signature) {
        if (attempts.exhausted()) return null;
        Method fallback = null;
        for (Method candidate : intent.methods()) {
            if (candidate == null) continue;
            Feasibility feasibility = candidate.applicable();
            if (feasibility == null || !feasibility.ok()) continue;
            if (attempts.excluded(candidate, signature)) continue;
            Set<Reason> reduces = candidate.reduces();
            if (prefer != null && reduces != null && reduces.contains(prefer)) return candidate;
            if (fallback == null) fallback = candidate;
        }
        return fallback;
    }

    private void start(Step next, long now) {
        step = next;
        currentAction = null;
        child = null;
        stepStartTick = now;
        if (next instanceof Step.Do doStep) {
            currentAction = doStep.action();
        } else if (next instanceof Step.Wait wait) {
            currentAction = new Await(wait.until(), wait.maxTicks(), wait.why());
        } else if (next instanceof Step.Sub sub) {
            child = new IntentRunner(body, sub.intent(), ownerId).target(target);
            child.start();
            return;
        }
        if (currentAction == null) {
            failed(Reason.NO_PROGRESS, 0, now);
            return;
        }
        if (!driver().run(this, currentAction)) refused(now);
    }

    private void readStep(long now) {
        if (step instanceof Step.Sub) {
            readSub(now);
            return;
        }
        BodyDriver driver = driver();
        if (currentAction != null && driver.current() == currentAction) return;
        Outcome outcome = driver.last();
        if (outcome == null || driver.lastTick() < stepStartTick) {
            failed(Reason.NO_PROGRESS, 0, now);
            return;
        }
        read(outcome, now);
    }

    private void readSub(long now) {
        IntentRunner c = child;
        if (c == null) {
            failed(Reason.NO_PROGRESS, 0, now);
            return;
        }
        c.tick();
        switch (c.result()) {
            case RUNNING -> { }
            case DONE -> advance(now);
            case EXHAUSTED -> failAsChild(c, now);
            case LOST -> end(Result.LOST);
            case INTERRUPTED -> end(Result.INTERRUPTED);
        }
    }

    /**
     * A child that ran out of attempts fails the parent with the child's own last counted reason and shortfall: the one
     * physical failure is counted once in the parent, not recorded again as a fresh {@code NO_PROGRESS:0}.
     */
    private void failAsChild(IntentRunner c, long now) {
        var reasons = c.reasons();
        Attempts.Failure last = reasons.isEmpty() ? null : reasons.get(reasons.size() - 1);
        if (last == null) failed(Reason.NO_PROGRESS, 0, now);
        else failed(last.reason(), last.shortfall(), now);
    }

    private void read(Outcome outcome, long now) {
        switch (outcome) {
            case Outcome.Done ignored -> advance(now);
            case Outcome.Blocked blocked -> failed(blocked.reason(), blocked.shortfall(), now);
            case Outcome.Incapable incapable -> incapable(incapable.reason(), now);
            case Outcome.Lost ignored -> end(Result.LOST);
            case Outcome.Interrupted ignored -> end(Result.INTERRUPTED);
        }
    }

    /** The current step finished: ask the same method for its next step, or count NO_PROGRESS when it has none. */
    private void advance(long now) {
        clearStep();
        Step next = method.next();
        if (next == null) {
            failed(Reason.NO_PROGRESS, 0, now);
            return;
        }
        start(next, now);
    }

    /** One blocked end: counted once, then a method that reduces the reason is preferred. */
    private void failed(Reason reason, double shortfall, long now) {
        Method from = method;
        WorldSignature signature = signature(now);
        clearStep();
        attempts.fail(from, reason, shortfall, signature);
        Method to = attempts.exhausted() ? null : pick(reason, signature);
        switchLog(from, reason, shortfall, to);
        if (to == null) {
            end(Result.EXHAUSTED);
            return;
        }
        begin(to, now);
    }

    /** One incapable end: the method stays excluded for this intent, and the switch is counted once. */
    private void incapable(Reason reason, long now) {
        Method from = method;
        WorldSignature signature = signature(now);
        clearStep();
        attempts.incapable(from, reason);
        Method to = attempts.exhausted() ? null : pick(null, signature);
        switchLog(from, reason, 0, to);
        if (to == null) {
            end(Result.EXHAUSTED);
            return;
        }
        begin(to, now);
    }

    /** The driver refused the action: the refusal that very run() call stored, in its own class, else NO_PROGRESS. */
    private void refused(long now) {
        Outcome refusal = driver().refusal();
        if (refusal != null) read(refusal, now);
        else failed(Reason.NO_PROGRESS, 0, now);
    }

    private void clearStep() {
        step = null;
        currentAction = null;
        child = null;
    }

    private void end(Result outcome) {
        if (!active) return;
        result = outcome;
        active = false;
        IntentRunner c = child;
        if (c != null && c.active()) c.stop("parent_" + outcome.name().toLowerCase(Locale.ROOT));
        clearStep();
        GameplayLog.event(body, "intent", "end id={} result={} reasons={}", intent.id(), outcome, attempts.describe());
    }

    private void switchLog(Method from, Reason reason, double shortfall, Method to) {
        GameplayLog.event(body, "method", "switch intent={} from={} reason={} shortfall={} to={}",
                intent.id(), from == null ? "none" : from.id(), reason, Attempts.amount(shortfall),
                to == null ? "none" : to.id());
    }

    private WorldSignature signature(long now) {
        Supplier<Target> source = target;
        Target facts = source == null ? null : source.get();
        if (facts == null) return WorldSignature.of(now);
        return WorldSignature.of(facts.at(), facts.host(), facts.blockers(), now);
    }

    private BodyDriver driver() { return body.motor(); }

    private long now() { return body.level().getGameTime(); }

    // ---- status (spec 13.2: read the owner, never keep a second copy) --------------------------------------------

    /** The innermost running runner owning this body, or null. */
    public static IntentRunner running(YukkuriEntity body) {
        if (body == null) return null;
        MotorOwner owner = body.motor().owner();
        if (!(owner instanceof IntentRunner runner)) return null;
        IntentRunner inner = runner.innermost();
        return inner.active() ? inner : null;
    }

    public static String intentStatus(YukkuriEntity body) {
        IntentRunner runner = running(body);
        return runner == null ? "none" : runner.intentDescription();
    }

    public static String methodStatus(YukkuriEntity body) {
        IntentRunner runner = running(body);
        if (runner == null) return "none";
        return runner.method == null ? "none" : runner.method.id();
    }

    public static String attemptsStatus(YukkuriEntity body) {
        IntentRunner runner = running(body);
        if (runner == null) return "none";
        Attempts a = runner.attempts;
        return "used=" + a.used() + " left=" + a.remaining() + " " + a.describe();
    }

    /** {@code fetch result=RUNNING}. */
    public String intentDescription() {
        return intent.id() + " result=" + result;
    }
}
