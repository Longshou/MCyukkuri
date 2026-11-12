package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.coordination.LeaseTable;
import java.util.*;
import java.util.function.IntPredicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Delayed shop scenes (farewell, sale, locate beam). Tasks are transient: a restart mid-scene drops them. The stock's own answers to
 *  customers live on its goal ({@link ShopWindow}). */
public final class ShopPerformance {
    private record Task(ServerLevel level, Set<UUID> participants, int[] age, IntPredicate run, Runnable cancel) {}
    private static final List<Task> TASKS = new ArrayList<>();
    private record Pen(ServerLevel level, long pos) {}
    /** A phase's handle to one reservation token; handoff changes the holder, not the token. */
    public static final class Reservation {
        private final Pen pen;
        private final Object token;
        private Reservation(Pen pen, Object token) { this.pen = pen; this.token = token; }
    }
    private static final int PEN_HOLD = 600;
    /** Pens promised to an animal that has not arrived yet; equal coordinates in other dimensions are independent. */
    private static final LeaseTable<Pen, Reservation> RESERVED = new LeaseTable<>();
    public static Reservation reserve(ServerLevel level, BlockPos pos) {
        var lease = new Reservation(new Pen(level, pos.asLong()), new Object());
        long now = level.getGameTime();
        return RESERVED.acquireAll(List.of(lease.pen), lease, now + PEN_HOLD, now) ? lease : null;
    }
    public static boolean owns(ServerLevel level, BlockPos pos, Reservation lease) {
        return lease != null && lease.pen.equals(new Pen(level, pos.asLong())) && RESERVED.owns(lease.pen, lease, level.getGameTime());
    }
    public static boolean renew(ServerLevel level, Reservation lease) {
        long now = level.getGameTime();
        return lease != null && lease.pen.level == level && RESERVED.renew(lease.pen, lease, now + PEN_HOLD, now);
    }
    /** Only the returned holder may renew/release after a successful sale-to-courier transfer. */
    public static Reservation handoff(ServerLevel level, Reservation lease) {
        if (lease == null || lease.pen.level != level) return null;
        long now = level.getGameTime();
        var next = new Reservation(lease.pen, lease.token);
        return RESERVED.transfer(lease.pen, lease, next, now + PEN_HOLD, now) ? next : null;
    }
    public static void release(Reservation lease) { if (lease != null) RESERVED.release(lease.pen, lease); }
    public static boolean reserved(ServerLevel level, BlockPos pos) { return !RESERVED.free(List.of(new Pen(level, pos.asLong())), level.getGameTime()); }

    /** The predicate gets the age in ticks (1, 2, ...) and returns true when the scene is over. */
    public static void schedule(ServerLevel level, UUID owner, IntPredicate run) { schedule(level, Set.of(owner), run); }
    public static void schedule(ServerLevel level, Collection<UUID> participants, IntPredicate run) {
        schedule(level, participants, run, () -> {});
    }
    public static void schedule(ServerLevel level, Collection<UUID> participants, IntPredicate run, Runnable cancel) {
        TASKS.add(new Task(level, Set.copyOf(participants), new int[1], run, cancel));
    }
    public static void forget(ServerLevel level, AABB box, Set<UUID> ids) {
        for (var task : List.copyOf(TASKS)) if (task.participants.stream().anyMatch(ids::contains)) {
            task.cancel.run(); TASKS.remove(task);
        }
        RESERVED.removeKeys(pen -> pen.level == level && box.contains(Vec3.atCenterOf(BlockPos.of(pen.pos))));
    }

    /** Transient scenes and pen reservations never outlive their server (singleplayer world switches). */
    public static void started(ServerStartingEvent event) { TASKS.clear(); RESERVED.clear(); }
    public static void stopped(ServerStoppedEvent event) { TASKS.clear(); RESERVED.clear(); }

    public static void tick(ServerTickEvent.Post event) {
        if (TASKS.isEmpty()) return;
        for (var t : List.copyOf(TASKS)) {
            if (t.level.getServer() != event.getServer()) { TASKS.remove(t); continue; }
            if (t.run.test(++t.age[0])) TASKS.remove(t);
        }
    }

    private ShopPerformance() {}
}
