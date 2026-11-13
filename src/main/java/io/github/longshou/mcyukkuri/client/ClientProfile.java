package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import java.time.Instant;
import java.util.Map;
import java.util.WeakHashMap;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Only registered by the profiling launcher. Custom events share the JVM clock with the samples. */
final class ClientProfile {
    private static final long SECOND = 1_000_000_000L;
    private static final Map<YukkuriView, Boolean> WOUNDED = new WeakHashMap<>();
    private static long frameAt, windowAt, worstAt, worst;
    private static int frames;

    @Name("mcyukkuri.ClientWound")
    @Label("First visible wound")
    @StackTrace(false)
    static final class Wound extends Event {
        @Label("Game tick") long gameTick;
        @Label("Entity ID") int entityId;
        @Label("Dimension") String dimension;
        @Label("Model") String model;
        @Label("Wound kind") String kind;
        @Label("Severity") int severity;
    }

    @Name("mcyukkuri.ClientFrames")
    @Label("Client frame intervals")
    @StackTrace(false)
    static final class Frames extends Event {
        @Label("Game tick") long gameTick;
        @Label("Frame count") int count;
        @Timespan(Timespan.NANOSECONDS) long mean;
        @Timespan(Timespan.NANOSECONDS) long max;
        @Timespan(Timespan.NANOSECONDS) long worstEndedAgo;
    }

    static void tick(ClientTickEvent.Post event) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            WOUNDED.clear();
            return;
        }
        for (var entity : level.entitiesForRendering()) {
            if (!(entity instanceof YukkuriView body)) continue;
            var wounds = body.visibleWounds();
            boolean wounded = !wounds.isEmpty();
            Boolean previous = WOUNDED.put(body, wounded);
            // The first observation can be an already-wounded body loaded into view.
            if (!wounded || !Boolean.FALSE.equals(previous)) continue;
            var first = wounds.getFirst();
            var marker = new Wound();
            marker.gameTick = level.getGameTime();
            marker.entityId = entity.getId();
            marker.dimension = level.dimension().location().toString();
            marker.model = body.assetId();
            marker.kind = first.kind().name();
            marker.severity = first.severity();
            marker.commit();
            MCYukkuri.LOGGER.info("[client-profile] first_wound utc={} gameTick={} dimension={} entity={} model={} kind={} severity={}",
                    Instant.now(), marker.gameTick, marker.dimension, marker.entityId, marker.model, marker.kind, marker.severity);
        }
    }

    static void frame(RenderFrameEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()) {
            frameAt = windowAt = worstAt = worst = 0;
            frames = 0;
            return;
        }
        long now = System.nanoTime();
        if (frameAt == 0) {
            frameAt = windowAt = now;
            return;
        }
        long interval = now - frameAt;
        frameAt = now;
        frames++;
        if (interval > worst) {
            worst = interval;
            worstAt = now;
        }
        long elapsed = now - windowAt;
        if (elapsed < SECOND) return;
        var summary = new Frames();
        summary.gameTick = mc.level.getGameTime();
        summary.count = frames;
        summary.mean = elapsed / frames;
        summary.max = worst;
        summary.worstEndedAgo = now - worstAt;
        summary.commit();
        windowAt = now;
        frames = 0;
        worst = 0;
    }

    private ClientProfile() {}
}
