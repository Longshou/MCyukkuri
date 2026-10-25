package io.github.longshou.mcyukkuri.subspecies.boat;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;

/**
 * What a rider of an afloat hull does, one registered {@link AfloatActivity} at a time. The activities are tried in priority order, the first that takes the turn runs, a running one
 * that a more urgent one displaces is stopped with {@code preempted}, and nothing resumes by itself.
 *
 * <p>The agenda keeps no goal teardown: if it is not ticked for a few ticks (another goal took over, the rider left the hull) the
 * running activity is stopped with {@code lapsed} the next time it is. Every activity is bounded by its own {@code maxTicks} and a
 * progress watchdog ({@link #STALL_TICKS} without progress ends it with {@code stalled} and a cooldown).
 */
public final class AfloatAgenda {
    public static final int STALL_TICKS = 200, STALL_COOLDOWN = 400, TIMEOUT_COOLDOWN = 200, LAPSE_TICKS = 3;
    /** Priorities up to this are run by the priority-3 goal (incidents, threats, labor); the rest by the routine goal. */
    public static final double URGENT = 3.99;

    /** What an activity sees on each call. */
    public record Context(YukkuriEntity entity, HatBoatEntity boat, boolean owner, long now, long tick, long age, AfloatAgenda agenda) {
        /** Name the behaviour the rider shows this tick (what the presentation reads). */
        public void show(String behavior) { agenda.shown = behavior; }
        public ServerLevel level() { return (ServerLevel) entity.level(); }
    }

    private final YukkuriEntity entity;
    private final List<AfloatActivity> activities = new ArrayList<>();
    private final Map<String, Long> cooling = new HashMap<>();
    private AfloatActivity current;
    private long startedAt, progressAt, lastProgress = Long.MIN_VALUE, lastTick = Long.MIN_VALUE, ticks;
    private String shown = Behaviors.IDLE;

    public AfloatAgenda(YukkuriEntity entity) { this.entity = entity; }

    public void register(AfloatActivity activity) {
        activities.add(activity);
        activities.sort(Comparator.comparingDouble(AfloatActivity::priority));
    }

    /** Id of the running activity, or "none". */
    public String currentId() { return current == null ? "none" : current.id(); }
    public AfloatActivity current() { return current; }
    /** The behaviour string the running activity last showed, IDLE when nobody ticked recently. */
    public String shown(long now) { return now - lastTick > LAPSE_TICKS ? Behaviors.IDLE : shown; }
    public long age(long now) { return current == null ? 0 : now - startedAt; }

    /** Whether any activity of at most this priority would take the turn (a side-effect free peek for the goals' canUse). */
    public boolean wantsAtMost(HatBoatEntity boat, boolean owner, double cap) {
        if (!(entity.level() instanceof ServerLevel level)) return false;
        long now = level.getGameTime();
        var context = new Context(entity, boat, owner, now, ticks, 0, this);
        for (var activity : activities) {
            if (activity.priority() > cap) break;
            if (!cooling(activity, now) && activity.wants(context)) return true;
        }
        return false;
    }

    /** Stop whatever runs, for a rider who left the agenda's territory (a phase that is not AFLOAT, the hull gone). */
    public void halt(String reason) {
        if (current != null && entity.level() instanceof ServerLevel level) {
            long now = level.getGameTime();
            end(new Context(entity, entity.getVehicle() instanceof HatBoatEntity b ? b : null, false, now, ticks, now - startedAt, this), reason, 0);
        }
        shown = Behaviors.IDLE;
    }

    private boolean cooling(AfloatActivity activity, long now) {
        var until = cooling.get(activity.id());
        return until != null && until > now;
    }

    private void end(Context c, String reason, int cooldown) {
        var ended = current;
        if (ended == null) return;
        current = null;
        lastProgress = Long.MIN_VALUE;
        try { ended.stop(c, reason); }
        finally {
            if (cooldown > 0) cooling.put(ended.id(), c.now() + cooldown);
            if (!reason.equals("done") && !reason.equals("preempted"))
                GameplayLog.limited(entity, "aquatic", "activity.end." + ended.id() + "." + reason, 200,
                        "activity {} ended reason={} age={} cooldown={}", ended.id(), reason, c.now() - startedAt, cooldown);
        }
    }

    /**
     * One tick of the chain, restricted to activities of at most {@code cap} priority. Returns true when an activity took the turn.
     */
    public boolean tick(HatBoatEntity boat, boolean owner, double cap) {
        if (!(entity.level() instanceof ServerLevel level)) return false;
        long now = level.getGameTime();
        if (now == lastTick) return current != null;   // two goals asked in one tick: the first answer stands
        if (current != null && now - lastTick > LAPSE_TICKS) {
            end(new Context(entity, boat, owner, now, ticks, now - startedAt, this), "lapsed", 0);
        }
        lastTick = now;
        shown = Behaviors.IDLE;
        ticks++;
        cooling.values().removeIf(until -> until <= now);
        if (current != null) {
            var context = new Context(entity, boat, owner, now, ticks, now - startedAt, this);
            if (now - startedAt > current.maxTicks()) end(context, "timeout", current.cooldownAfterTimeout());
        }
        for (var activity : activities) {
            if (activity.priority() > cap) break;
            if (cooling(activity, now)) continue;
            boolean running = activity == current;
            var context = new Context(entity, boat, owner, now, ticks, running ? now - startedAt : 0, this);
            if (!activity.wants(context)) {
                if (running) end(context, "done", 0);
                continue;
            }
            if (!running) activity.start(context);
            if (activity.tick(context)) {
                if (!running) {
                    if (current != null) end(new Context(entity, boat, owner, now, ticks, now - startedAt, this), "preempted", 0);
                    current = activity;
                    startedAt = now;
                    progressAt = now;
                    lastProgress = Long.MIN_VALUE;
                    GameplayLog.limited(entity, "aquatic", "activity.start." + activity.id(), 100, "activity {} started", activity.id());
                    activity.adopted(context);
                }
                long progress = activity.progress(context);
                if (progress != Long.MIN_VALUE) {
                    if (progress != lastProgress) { lastProgress = progress; progressAt = now; }
                    else if (now - progressAt > STALL_TICKS) {
                        GameplayLog.limited(entity, "aquatic", "stall." + activity.id(), 400,
                                "stall activity={} metric={} for={}", activity.id(), progress, now - progressAt);
                        end(context, "stalled", STALL_COOLDOWN);
                    }
                }
                return true;
            }
            if (running) end(context, "done", 0);
        }
        return false;
    }
}
