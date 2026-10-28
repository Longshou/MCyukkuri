package io.github.longshou.mcyukkuri.motor;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.component.SyncSlot;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Locale;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.world.phys.Vec3;

/**
 * The MOTOR component (spec 9.3): exactly one action drives a body at a time, and every request to move goes
 * through it. Server only, never saved, never driven by a passive clock.
 *
 * <p>Per tick: the owner check, the action's own tick, the progress watchdog, and on an {@link Outcome} the
 * {@link Attach#settle}, the {@code last} record and the {@code [action] end} line.
 */
public final class BodyDriver {
    /** Current action id, empty when idle. */
    public static final SyncSlot<String> MOTOR_ACTION = new SyncSlot<>(EntityDataSerializers.STRING, () -> "");
    /** The tick the current action started on (clip phase). */
    public static final SyncSlot<Long> MOTOR_START = new SyncSlot<>(EntityDataSerializers.LONG, () -> 0L);
    /** 0 Free, 1 Airborne, 2 OnHost, 3 Held. */
    public static final SyncSlot<Byte> ATTACH_KIND = new SyncSlot<>(EntityDataSerializers.BYTE, () -> (byte) 0);

    /** Ticks between two {@code [action] refused} lines for the same entity and reason. */
    private static final int REFUSED_WINDOW = 200;

    private static final ThreadLocal<Boolean> DECIDING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final YukkuriEntity body;
    private Action current;
    private MotorOwner owner;
    private Outcome last;
    private long lastTick;
    private long startTick;
    private double bestMetric = Double.POSITIVE_INFINITY;
    private long windowStart;
    private boolean interrupted;
    private String interruptedWhy = "";
    private String settled = "";
    private byte attachKind = -1;

    public BodyDriver(YukkuriEntity body) {
        this.body = body;
    }

    /**
     * True while {@code YukkuriEntity.aiStep} runs {@code super.aiStep()}, i.e. inside the goal decisions and the
     * travel pass. {@link #run} refuses outside it, so a passive clock or a callback cannot start an action.
     */
    public static boolean deciding() { return DECIDING.get(); }

    public static void deciding(boolean on) { DECIDING.set(on); }

    /**
     * Asks for {@code action} to become the body's action. True when accepted and started.
     * Acceptance follows spec 9.3: nothing running, or the same owner while the current action is interruptible,
     * or another owner while the current action is interruptible and its owner is no longer active.
     */
    public boolean run(MotorOwner candidate, Action action) {
        if (action == null || candidate == null || body.level().isClientSide) return false;
        if (!deciding()) {
            Motor.warn(body, "action refused id={} owner={} reason=not_deciding", action.id(), candidate.ownerId());
            return false;
        }
        Feasibility feasibility = action.check(body);
        if (feasibility != null && !feasibility.ok()) {
            last = Outcome.of(feasibility.reason(), feasibility.shortfall(), feasibility.detail());
            lastTick = now();
            refused(action, "infeasible:" + feasibility.reason());
            return false;
        }
        if (current != null) {
            boolean sameOwner = candidate.ownerId() != null && candidate.ownerId().equals(owner.ownerId());
            if (!current.interruptible()) {
                refused(action, "not_interruptible");
                return false;
            }
            if (!sameOwner && owner.active()) {
                refused(action, "owner_active");
                return false;
            }
            abortNow("replaced");
        }
        start(action, candidate);
        return true;
    }

    /** Ends the current action at the owner's request, keeping the physical state. */
    public void cancel(MotorOwner candidate, String why) {
        if (current == null || owner == null || candidate == null) return;
        if (candidate.ownerId() == null || !candidate.ownerId().equals(owner.ownerId())) return;
        abortNow(why == null ? "cancelled" : why);
    }

    public Action current() { return current; }

    public Outcome last() { return last; }

    public long lastTick() { return lastTick; }

    public MotorOwner owner() { return owner; }

    /**
     * Spec 2 step 4: the action's tick, run from {@code YukkuriEntity.travel} before vanilla integrates anything.
     * With no current action this returns {@link TravelMode#PASS} and touches nothing.
     */
    public TravelMode tickTravel(Vec3 input) {
        if (current == null) return TravelMode.PASS;
        if (body.level().isClientSide) return TravelMode.PASS;
        Action action = current;
        if ((owner == null || !owner.active()) && action.interruptible()) {
            abortNow("owner_inactive");
            return TravelMode.PASS;
        }
        Progress progress = action.tick(body);
        if (progress instanceof Outcome outcome) {
            finish(outcome);
            return TravelMode.PASS;
        }
        if (!(progress instanceof Running running)) {
            finish(new Outcome.Incapable(Reason.NO_PROGRESS, "tick returned no progress"));
            return TravelMode.PASS;
        }
        long tick = now();
        if (running.metric() < bestMetric - action.progressEpsilon()) {
            bestMetric = running.metric();
            windowStart = tick;
        } else if (tick - windowStart >= action.progressWindow()) {
            double moved = Double.isInfinite(bestMetric) ? 0 : Math.max(0, bestMetric - running.metric());
            finish(Outcome.of(Reason.NO_PROGRESS, moved,
                    String.format(Locale.ROOT, "window=%d metric=%.2f", action.progressWindow(), running.metric())));
            return TravelMode.PASS;
        }
        return running.travel();
    }

    /**
     * The interrupt bridge (FRIGHT, HURT, COMMAND, PET_LOCK, BURNING, DEATH). An interruptible action stops now; an
     * uninterruptible one finishes its flight and the outcome becomes {@link Outcome.Interrupted}.
     */
    public void interrupt(String why) {
        if (current == null) return;
        if (current.interruptible()) {
            abortNow(why == null ? "interrupted" : why);
            return;
        }
        interrupted = true;
        interruptedWhy = why == null ? "interrupted" : why;
    }

    /** {@code body.action} status: the current id and the owner, or idle. */
    public String detail() {
        return current == null ? "idle" : current.id() + " owner=" + owner.ownerId();
    }

    /** {@code body.last} status: the last outcome, its reason and its shortfall. */
    public String lastDetail() {
        return last == null ? "none" : last.label() + " " + fields(last);
    }

    /** The attachment description of the last settle; {@link Attach} owns the transition. */
    String settled() { return settled; }

    void settled(String text) { settled = text == null ? "" : text; }

    /** Writes {@code ATTACH_KIND} only when the kind changed. */
    void syncAttach(byte kind) {
        if (kind == attachKind) return;
        attachKind = kind;
        body.syncComponentData(ATTACH_KIND, kind);
    }

    private void start(Action action, MotorOwner candidate) {
        long tick = now();
        current = action;
        owner = candidate;
        startTick = tick;
        lastTick = tick;
        bestMetric = Double.POSITIVE_INFINITY;
        windowStart = tick;
        interrupted = false;
        interruptedWhy = "";
        if (!action.id().equals(body.componentData(MOTOR_ACTION))) body.syncComponentData(MOTOR_ACTION, action.id());
        if (body.componentData(MOTOR_START) != startTick) body.syncComponentData(MOTOR_START, startTick);
        GameplayLog.event(body, "action", "start id={} owner={}", action.id(), candidate.ownerId());
        action.start(body);
    }

    private void finish(Outcome raw) {
        Action action = current;
        if (action == null) return;
        Outcome outcome = raw == null ? new Outcome.Incapable(Reason.NO_PROGRESS, "no outcome") : raw;
        if (interrupted && !(outcome instanceof Outcome.Interrupted)) outcome = new Outcome.Interrupted(interruptedWhy);
        current = null;
        owner = null;
        interrupted = false;
        interruptedWhy = "";
        last = outcome;
        lastTick = now();
        Attach.settle(body, "action_end:" + outcome.label());
        GameplayLog.event(body, "action", "end id={} outcome={} {}", action.id(), outcome.label(), fields(outcome));
        if (!body.componentData(MOTOR_ACTION).isEmpty()) body.syncComponentData(MOTOR_ACTION, "");
    }

    private void abortNow(String why) {
        Action action = current;
        if (action == null) return;
        action.abort(body, why);
        finish(new Outcome.Interrupted(why));
    }

    private void refused(Action action, String reason) {
        GameplayLog.limited(body, "action", "refused:" + reason, REFUSED_WINDOW,
                "refused id={} owner={} current={} reason={}",
                action.id(), owner == null ? "none" : owner.ownerId(),
                current == null ? "idle" : current.id(), reason);
    }

    private long now() { return body.level().getGameTime(); }

    private static String fields(Outcome outcome) {
        if (outcome instanceof Outcome.Done done) return "detail=" + done.detail();
        if (outcome instanceof Outcome.Interrupted interrupted) return "why=" + interrupted.why();
        return String.format(Locale.ROOT, "reason=%s shortfall=%.2f detail=%s",
                outcome.reason(), outcome.shortfall(), detailOf(outcome));
    }

    private static String detailOf(Outcome outcome) {
        if (outcome instanceof Outcome.Blocked blocked) return blocked.detail();
        if (outcome instanceof Outcome.Incapable incapable) return incapable.detail();
        if (outcome instanceof Outcome.Lost lost) return lost.detail();
        return "";
    }
}
