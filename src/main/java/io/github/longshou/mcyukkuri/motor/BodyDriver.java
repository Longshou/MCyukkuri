package io.github.longshou.mcyukkuri.motor;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.component.SyncSlot;
import io.github.longshou.mcyukkuri.life.YukkuriLife;
import io.github.longshou.mcyukkuri.motor.action.Release;
import io.github.longshou.mcyukkuri.motor.geom.Buoyancy;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;

/** The MOTOR component: exactly one action drives a body at a time and every request to move goes through it. Server only, never saved, never driven by a passive clock. */
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
    private Outcome last, refusal;
    private long lastTick;
    private long startTick;
    private double bestMetric = Double.POSITIVE_INFINITY;
    private long windowStart;
    private boolean interrupted, refusedBusy;
    private String interruptedWhy = "";
    private String settled = "";
    private byte attachKind = -1;

    public BodyDriver(YukkuriEntity body) {
        this.body = body;
    }

    /** True while {@code YukkuriEntity.aiStep} runs the goal decisions and the travel pass; {@link #run} refuses outside it, so a passive clock or a callback cannot start an action. */
    public static boolean deciding() { return DECIDING.get(); }

    public static void deciding(boolean on) { DECIDING.set(on); }

    /** Asks for {@code action} to become the body's action: true when accepted and started, refused while an uninterruptible action or an active owner's action holds it. */
    public boolean run(MotorOwner candidate, Action action) {
        refusal = null;
        refusedBusy = false;
        if (action == null || candidate == null || body.level().isClientSide) return false;
        if (!deciding()) {
            Motor.warn(body, "action refused id={} owner={} reason=not_deciding", action.id(), candidate.ownerId());
            return false;
        }
        Feasibility feasibility = action.check(body);
        if (feasibility != null && !feasibility.ok()) {
            last = refusal = Outcome.of(feasibility.reason(), feasibility.shortfall(), feasibility.detail());
            lastTick = now();
            refused(action, "infeasible:" + feasibility.reason());
            return false;
        }
        if (current != null) {
            boolean sameOwner = candidate.ownerId() != null && candidate.ownerId().equals(owner.ownerId());
            if (!current.interruptible()) {
                refusedBusy = true;
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

    /** Ends the current action at the owner's request, keeping the physical state; an uninterruptible stretch (a flight) finishes first, then ends {@link Outcome.Interrupted}. */
    public void cancel(MotorOwner candidate, String why) {
        if (current == null || owner == null || candidate == null) return;
        if (candidate.ownerId() == null || !candidate.ownerId().equals(owner.ownerId())) return;
        stop(why == null ? "cancelled" : why);
    }

    /** The latest {@link #run} was refused only because the current action is in an uninterruptible stretch: try again after it. */
    public boolean refusedBusy() { return refusedBusy; }

    public Action current() { return current; }

    public Outcome last() { return last; }

    /** Why the latest {@link #run} call refused its action by the action's own check, or null (accepted, or refused by the acceptance rules, which name no reason of the action's). */
    public Outcome refusal() { return refusal; }

    public long lastTick() { return lastTick; }

    public MotorOwner owner() { return owner; }

    /** The action's tick, run from {@code YukkuriEntity.travel} before vanilla integrates anything; the water's lift is added here afterwards, for every body in water. */
    public TravelMode tickTravel(Vec3 input) {
        if (body.level().isClientSide) return TravelMode.PASS;
        TravelMode mode = current == null ? fall() : drive();
        if (mode == TravelMode.PASS && body.isAlive() && body.isInWaterOrBubble() && !body.predatorFlying()) buoy();
        return mode;
    }

    /** How near (blocks) a host must be before a free fall is tested against it. */
    private static final double FALL_HOST = 3;

    /** No action and falling free (put down over a hollow, stepped off a bank): it lands on a host the way a flight does, only when one is near, never while riding. */
    private TravelMode fall() {
        if (body.isAlive() && !body.isPassenger() && !body.onGround() && !body.isInWaterOrBubble() && body.getDeltaMovement().y < 0
                && !body.level().getEntities(body, body.getBoundingBox().inflate(FALL_HOST), e -> e instanceof SurfaceHost).isEmpty())
            ContactResolver.resolve(body);
        return TravelMode.PASS;
    }

    /** The water's lift ({@link Buoyancy#bodyLift}) for the share of the body under water over five columns of its box: it floats about half under, lift capped at 0.085. */
    private void buoy() {
        var box = body.getBoundingBox();
        double d = body.getBbWidth() * .35, under = 0;
        for (double[] c : new double[][]{{0, 0}, {-d, -d}, {d, -d}, {-d, d}, {d, d}})
            for (int y = Mth.floor(box.minY); y <= Mth.floor(box.maxY); y++) {
                var at = BlockPos.containing(body.getX() + c[0], y, body.getZ() + c[1]);
                var fluid = body.level().hasChunkAt(at) ? body.level().getFluidState(at) : null;
                if (fluid != null && fluid.is(FluidTags.WATER)) under += Math.max(0, Math.min(box.maxY, y + fluid.getHeight(body.level(), at)) - Math.max(box.minY, y));
            }
        double fraction = box.getYsize() <= 0 ? 0 : Math.clamp(under / (5 * box.getYsize()), 0, 1);
        if (fraction <= 0) return;
        Vec3 v = body.getDeltaMovement();
        double lift = Buoyancy.bodyLift(fraction, v.y);
        Motor.velocity(body, v.add(0, lift > 0 ? Math.max(0, Math.min(lift, .085 - v.y)) : lift, 0), "buoyancy");
    }

    private TravelMode drive() {
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
            finish(Outcome.of(Reason.NO_PROGRESS, 0, "tick returned no progress"));
            return TravelMode.PASS;
        }
        long tick = now();
        if (running.metric() < bestMetric - action.progressEpsilon()) {
            bestMetric = running.metric();
            windowStart = tick;
        } else if (running.replanned()) {
            windowStart = tick;
        } else if (tick - windowStart >= action.progressWindow()) {
            double moved = Double.isInfinite(bestMetric) ? 0 : Math.max(0, bestMetric - running.metric());
            finish(Outcome.of(Reason.NO_PROGRESS, moved,
                    String.format(Locale.ROOT, "window=%d metric=%.2f", action.progressWindow(), running.metric())));
            return TravelMode.PASS;
        }
        return running.travel();
    }

    /** The interrupt bridge (fright, hurt, death, water, panic, a lost hat): an interruptible action stops now, an uninterruptible one finishes and ends {@link Outcome.Interrupted}. */
    public void interrupt(String why) {
        stop(why);
        Release.interrupted(body, why == null ? "interrupted" : why);
    }

    private void stop(String why) {
        if (current == null) return;
        if (current.interruptible()) {
            abortNow(why == null ? "interrupted" : why);
            return;
        }
        interrupted = true;
        interruptedWhy = why == null ? "interrupted" : why;
    }

    /** The HURT bridge: water damage (drowning, soaking) only takes health and stops nothing; a real hit interrupts. */
    public void hurt(DamageSource source) {
        if (source != null && YukkuriLife.isWaterDamage(source)) {
            if (current != null) GameplayLog.limited(body, "action", "kept_through_water", REFUSED_WINDOW,
                    "kept id={} owner={} through damage={}", current.id(), owner.ownerId(), source.getMsgId());
            return;
        }
        interrupt("hurt");
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
        action.start(body);
        String detail = action.startDetail();
        GameplayLog.event(body, "action", detail.isEmpty() ? "start id={} owner={}" : "start id={} owner={} {}",
                action.id(), candidate.ownerId(), detail);
    }

    private void finish(Outcome raw) {
        Action action = current;
        if (action == null) return;
        Outcome outcome = raw == null ? Outcome.of(Reason.NO_PROGRESS, 0, "no outcome") : raw;
        if (interrupted && !(outcome instanceof Outcome.Interrupted)) outcome = new Outcome.Interrupted(interruptedWhy);
        current = null;
        owner = null;
        interrupted = false;
        interruptedWhy = "";
        last = outcome;
        lastTick = now();
        Attach.settle(body, "action_end:" + outcome.label());
        String detail = action.endDetail(outcome);
        GameplayLog.event(body, "action", detail.isEmpty() ? "end id={} outcome={} {}" : "end id={} outcome={} {} {}",
                action.id(), outcome.label(), fields(outcome), detail);
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
