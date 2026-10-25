package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Server-side event context and bounded throttling; never owns or changes gameplay state. */
public final class GameplayLog {
    private static final int MAX_KEYS = 64;
    private static final Map<Entity, Map<String, Window>> WINDOWS = new WeakHashMap<>();
    private static final Map<ServerLevel, Map<String, Window>> WORLD_WINDOWS = new WeakHashMap<>();

    private GameplayLog() {}

    /** Detail switch for per-phase aquatic sampling ("[aqdiag]"); off by default: {@code /yukkuri aqdiag on|off} or -Dmcyukkuri.aqdiag=true. */
    private static volatile boolean detail = Boolean.getBoolean("mcyukkuri.aqdiag");
    private static final Map<String, Long> DETAIL_LAST = new HashMap<>();

    public static boolean detailEnabled() { return detail; }

    public static LiteralArgumentBuilder<CommandSourceStack> detailCommand() {
        return Commands.literal("aqdiag").requires(source -> source.hasPermission(2))
                .then(Commands.literal("on").executes(context -> { detail = true; context.getSource().sendSuccess(() -> Component.literal("aqdiag logging on"), false); return 1; }))
                .then(Commands.literal("off").executes(context -> { detail = false; context.getSource().sendSuccess(() -> Component.literal("aqdiag logging off"), false); return 1; }));
    }

    /** Detail line for {@code key} at most once per {@code minTicks} game ticks (0: every call); only while the detail switch is on. */
    public static void detail(Entity at, String key, int minTicks, String message) {
        if (!detail || at == null || at.level().isClientSide) return;
        long now = at.level().getGameTime();
        if (minTicks > 0) {
            Long before = DETAIL_LAST.get(key);
            if (before != null && now - before < minTicks) return;
            DETAIL_LAST.put(key, now);
            if (DETAIL_LAST.size() > 2048) DETAIL_LAST.clear();
        }
        MCYukkuri.LOGGER.info("[aqdiag] t={} {}: {}", now, key, message);
    }

    public static String at(Vec3 p) {
        return p == null ? "null" : String.format(Locale.ROOT, "(%.2f,%.2f,%.2f)", p.x, p.y, p.z);
    }

    /** Finite transactions and actual transitions should call this only after the result is known. */
    public static void event(Entity source, String category, String message, Object... values) {
        if (source == null || source.level().isClientSide) return;
        Object[] args = new Object[7 + values.length];
        args[0] = category;
        args[1] = source instanceof YukkuriEntity y ? y.getVariant().assetId() : source.getType().getDescriptionId();
        args[2] = source.getId();
        args[3] = source.getUUID();
        args[4] = source.level().getGameTime();
        args[5] = source.level().dimension().location();
        args[6] = source.blockPosition().toShortString();
        System.arraycopy(values, 0, args, 7, values.length);
        MCYukkuri.LOGGER.info("[{}] {} #{} uuid={} t={} dim={} pos={} " + message, args);
    }

    /** Repeated automatic refusals: stable per-entity keys, with suppressed count on the next emitted line. */
    public static void limited(Entity source, String category, String key, int minTicks, String message, Object... values) {
        if (source == null || source.level().isClientSide) return;
        Map<String, Window> windows = WINDOWS.computeIfAbsent(source, ignored -> new HashMap<>());
        int suppressed = enter(windows, source.level().getGameTime(), category, key, minTicks);
        if (suppressed < 0) return;
        event(source, category, message + suffix(suppressed), values);
    }

    /** Transactions without a live entity must include their stable token, group or block identity. */
    public static void world(ServerLevel level, String category, String message, Object... values) {
        Object[] args = new Object[3 + values.length];
        args[0] = category;
        args[1] = level.getGameTime();
        args[2] = level.dimension().location();
        System.arraycopy(values, 0, args, 3, values.length);
        MCYukkuri.LOGGER.info("[{}] t={} dim={} " + message, args);
    }

    public static void worldLimited(ServerLevel level, String category, String key, int minTicks, String message, Object... values) {
        Map<String, Window> windows = WORLD_WINDOWS.computeIfAbsent(level, ignored -> new HashMap<>());
        int suppressed = enter(windows, level.getGameTime(), category, key, minTicks);
        if (suppressed < 0) return;
        world(level, category, message + suffix(suppressed), values);
    }

    private static int enter(Map<String, Window> windows, long now, String category, String key, int minTicks) {
        String slot = category + ":" + key;
        Window previous = windows.get(slot);
        if (previous != null && now >= previous.tick && now - previous.tick < Math.max(1, minTicks)) {
            if (previous.suppressed < Integer.MAX_VALUE) previous.suppressed++;
            return -1;
        }
        int suppressed = previous == null ? 0 : previous.suppressed;
        if (previous == null && windows.size() >= MAX_KEYS) windows.clear();
        windows.put(slot, new Window(now));
        return suppressed;
    }

    private static String suffix(int suppressed) { return suppressed == 0 ? "" : " (suppressed=" + suppressed + ")"; }

    private static final class Window {
        private final long tick;
        private int suppressed;
        private Window(long tick) { this.tick = tick; }
    }
}
