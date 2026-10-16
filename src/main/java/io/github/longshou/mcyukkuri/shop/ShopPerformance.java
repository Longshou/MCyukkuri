package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.*;
import java.util.function.IntPredicate;
import net.minecraft.server.level.ServerLevel;
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
    /** Pens promised to a sold animal that has not arrived yet (BlockPos.asLong). */
    public static final Set<Long> RESERVED = new HashSet<>();

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
        if (!io.github.longshou.mcyukkuri.human.HumanCommerce.data(y).getString("Use").equals("stock") || y.pet().busy() || y.isPassenger() || y.isPanicking()
                || y.life().isSleeping() || y.life().personality().trait(Trait.BOLDNESS) < 40 || y.getRandom().nextInt(40) != 0) return;
        long now = level.getGameTime(); Long last = APPEAL.get(y.getUUID());
        if (last != null && now - last < 600) return;
        if (APPEAL.size() > 256) APPEAL.values().removeIf(t -> now - t > 600);
        if (level.getEntitiesOfClass(Player.class, y.getBoundingBox().inflate(5), p -> p.isAlive() && !p.isSpectator()).isEmpty()) return;
        APPEAL.put(y.getUUID(), now);
        y.playAction("beg"); y.speak("shop_appeal");
    }
    private ShopPerformance() {}
}
