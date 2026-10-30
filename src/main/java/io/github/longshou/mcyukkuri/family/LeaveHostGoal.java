package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.WorldSignature;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import java.util.EnumSet;
import java.util.Set;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * A young on a host that asks to be unloaded leaves it on its own. The trigger is the host's
 * {@code unloadRequested()}, which is derived from the taker's running intent; nothing is stored.
 *
 * <p>The whole body of the goal is one {@link GetTo} run, and the goal only releases the body once that intent has ended:
 * a young that is grabbed mid-step therefore ends its own intent DONE instead of being reported as interrupted.
 */
public final class LeaveHostGoal extends Goal {
    private final YukkuriEntity child;
    private IntentRunner runner;
    private SurfaceHost host;
    /** The world a run ended under with any result but DONE; the same world may not start the same intent again. */
    private WorldSignature ended;

    public LeaveHostGoal(YukkuriEntity child) {
        this.child = child;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    /** The host this young rides that wants its young off, or null; a newborn first wakes (first_wake) where it landed. */
    private SurfaceHost unloadHost() {
        if (child.life().isSleeping() || child.body().condition().dying() || child.firstWaking()) return null;
        if (!(Attach.of(child) instanceof AttachState.OnHost on)) return null;
        return on.host().unloadRequested() ? on.host() : null;
    }

    /**
     * A run that ended without finishing keeps its world: the same signature is not started again. The
     * signature's own 200 tick bucket expires the memory, so no timer is added here.
     */
    @Override public boolean canUse() {
        SurfaceHost host = unloadHost();
        return host != null && !signature(host).equals(ended);
    }

    private WorldSignature signature(SurfaceHost host) {
        return WorldSignature.of(child.position(), host.entity().position(), Set.of(), child.level().getGameTime());
    }

    /**
     * Runs exactly as long as its intent: the host only has to ask once. The ask is derived from the taker's running intent,
     * and the taker is between two body actions for the tick in which it hands the work to its own {@code Fetch}, so a
     * single unanswered tick must not read as a withdrawal. The young's own intent is bounded by its deadline and its one wait.
     */
    @Override public boolean canContinueToUse() {
        return runner != null && runner.result() == IntentRunner.Result.RUNNING;
    }

    @Override public boolean requiresUpdateEveryTick() { return true; }

    @Override public void start() {
        var host = unloadHost();
        if (host == null) return;
        this.host = host;
        runner = new IntentRunner(child, new GetTo(child, host), "leave_host")
                .target(() -> new IntentRunner.Target(child.position(), host.entity().position(), Set.of()));
        runner.start();
    }

    @Override public void tick() {
        if (runner != null) runner.tick();
    }

    @Override public void stop() {
        if (runner == null) return;
        runner.stop("leave_host_stop");
        ended = runner.result() == IntentRunner.Result.DONE || host == null ? null : signature(host);
        runner = null;
        host = null;
    }
}
