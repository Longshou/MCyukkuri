package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.coordination.LeaseTable;
import io.github.longshou.mcyukkuri.social.Temperament;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.*;
import java.util.function.IntPredicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Delayed shop scenes (farewell, sale, locate beam) and the display "beg" appeal. Tasks are transient: a restart mid-scene drops them. */
@EventBusSubscriber(modid = MCYukkuri.MOD_ID)
public final class ShopPerformance {
    private record Task(ServerLevel level, int[] age, IntPredicate run) {}
    private static final List<Task> TASKS = new ArrayList<>();
    private static final Map<UUID, Long> APPEAL = new HashMap<>();
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
    public static void schedule(ServerLevel level, IntPredicate run) { TASKS.add(new Task(level, new int[1], run)); }

    /** Transient scenes and pen reservations never outlive their server (singleplayer world switches). */
    @SubscribeEvent public static void started(ServerStartingEvent event) { TASKS.clear(); RESERVED.clear(); APPEAL.clear(); }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { TASKS.clear(); RESERVED.clear(); APPEAL.clear(); }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (TASKS.isEmpty()) return;
        for (var t : List.copyOf(TASKS)) {
            if (t.level.getServer() != event.getServer()) { TASKS.remove(t); continue; }
            if (t.run.test(++t.age[0])) TASKS.remove(t);
        }
    }

    /** Called once per second from HumanCommerce.tickAnimal for every yukkuri. */
    public static void appeal(YukkuriEntity y, ServerLevel level) {
        if (Custody.view(y).role() != Custody.Role.STOCK || y.pet().busy() || y.isPassenger() || y.isPanicking()
                || y.life().isSleeping() || y.life().personality().trait(Trait.BOLDNESS) < Temperament.BOLDNESS_AT_40 || y.getRandom().nextInt(40) != 0) return;
        long now = level.getGameTime(); Long last = APPEAL.get(y.getUUID());
        if (last != null && now - last < 600) return;
        if (APPEAL.size() > 256) APPEAL.values().removeIf(t -> now - t > 600);
        if (level.getEntitiesOfClass(Player.class, y.getBoundingBox().inflate(5), p -> p.isAlive() && !p.isSpectator()).isEmpty()) return;
        APPEAL.put(y.getUUID(), now);
        y.playAction("beg"); y.speak("shop_appeal");
    }
    private ShopPerformance() {}
}
