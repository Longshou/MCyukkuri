package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import java.util.function.BooleanSupplier;

/** Wait until a condition holds, with a hard deadline: it never moves the body and never waits forever. The pose clip is the waiting animation; the metric is the ticks left. */
public final class Await implements Action {
    public static final String ID = "await";

    private final BooleanSupplier condition;
    private final int maxTicks;
    private final String clip;
    private final String why;
    private long startTick;
    private int ticks;
    private int holdTick = Integer.MAX_VALUE;
    private BooleanSupplier paused = () -> false;

    /** Hold an authored pose frame while the same bounded wait continues. */
    public static Await holding(BooleanSupplier condition, int maxTicks, String clip, int holdTick, BooleanSupplier paused) {
        var wait = new Await(condition, maxTicks, clip);
        wait.holdTick = holdTick;
        wait.paused = paused;
        return wait;
    }

    public Await(BooleanSupplier condition, int maxTicks, String clip) {
        this(condition, maxTicks, clip, clip);
    }

    public Await(BooleanSupplier condition, int maxTicks, String clip, String why) {
        if (condition == null) throw new IllegalArgumentException("Await needs a condition");
        if (maxTicks <= 0) throw new IllegalArgumentException("Await needs a positive maxTicks");
        this.condition = condition;
        this.maxTicks = maxTicks;
        this.clip = clip == null ? "" : clip;
        this.why = why;
    }

    @Override public String id() { return ID; }

    @Override public Feasibility check(YukkuriEntity body) {
        return maxTicks > 0 ? Feasibility.pass() : Feasibility.of(Reason.NO_PROGRESS, 0, "maxTicks must be > 0");
    }

    @Override public void start(YukkuriEntity body) {
        ticks = 0;
        startTick = body.level().getGameTime();
    }

    @Override public Progress tick(YukkuriEntity body) {
        if (paused.getAsBoolean()) { startTick++; return new Running(maxTicks - ticks, TravelMode.PASS); }
        if (condition.getAsBoolean()) return new Outcome.Done("condition");
        ticks++;
        if (ticks >= maxTicks) {
            return Outcome.of(Reason.NO_PROGRESS, 0, "timeout why=" + why + " maxTicks=" + maxTicks);
        }
        return new Running(maxTicks - ticks, TravelMode.PASS);
    }

    @Override public MotionPose pose() { return new MotionPose(clip, startTick + Math.max(0, ticks - holdTick), true); }

    @Override public int progressWindow() { return Integer.MAX_VALUE; }
}
