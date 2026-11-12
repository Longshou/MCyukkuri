package io.github.longshou.mcyukkuri.shop;

import java.util.*;
import java.util.function.IntPredicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Delayed short scenes (a date, a basket climb, a pet's reaction). Tasks are transient: a restart mid-scene drops them. The stock's own
 *  answers to customers live on its goal ({@link ShopWindow}). */
public final class ShopPerformance {
    private record Task(ServerLevel level, Set<UUID> participants, int[] age, IntPredicate run, Runnable cancel) {}
    private static final List<Task> TASKS = new ArrayList<>();

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
    }

    /** Transient scenes never outlive their server (singleplayer world switches). */
    public static void started(ServerStartingEvent event) { TASKS.clear(); }
    public static void stopped(ServerStoppedEvent event) { TASKS.clear(); }

    public static void tick(ServerTickEvent.Post event) {
        if (TASKS.isEmpty()) return;
        for (var t : List.copyOf(TASKS)) {
            if (t.level.getServer() != event.getServer()) { TASKS.remove(t); continue; }
            if (t.run.test(++t.age[0])) TASKS.remove(t);
        }
    }

    private ShopPerformance() {}
}
