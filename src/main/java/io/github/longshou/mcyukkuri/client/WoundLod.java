package io.github.longshou.mcyukkuri.client;

import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Optional {@code [render]} readout of the wound pass (gated by {@code render.woundPerfLog}). State is bounded and never saved. */
final class WoundLod {
    private static final long REPORT_NANOS = 5_000_000_000L;
    private static final Logger LOGGER = LoggerFactory.getLogger("MCYukkuri/render");

    private static boolean measuring;
    private static int frameDrawn, frameTriangles;
    private static long frameNanos, windowStart;
    private static int frames, drawn, builds;
    private static long triangles, nanos, maxNanos, buildNanos;
    private static int maxTriangles;

    static void stage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) frame();
    }

    /** Called once at the start of each level render. */
    static void frame() {
        measuring = ClientConfig.woundPerfLog();
        if (!measuring) { windowStart = 0; return; }
        long now = System.nanoTime();
        if (windowStart == 0) windowStart = now;
        if (frameDrawn > 0) {
            frames++; drawn += frameDrawn;
            triangles += frameTriangles; maxTriangles = Math.max(maxTriangles, frameTriangles);
            nanos += frameNanos; maxNanos = Math.max(maxNanos, frameNanos);
        }
        frameDrawn = frameTriangles = 0; frameNanos = 0;
        if (now - windowStart >= REPORT_NANOS) {
            if (frames > 0) LOGGER.info("[render] wound frames={} drawn/frame={} tris/frame avg={} max={} ms/frame avg={} max={} builds={} buildMs={}",
                    frames, fmt(drawn / (double) frames), triangles / frames, maxTriangles,
                    fmt(nanos / 1e6 / frames), fmt(maxNanos / 1e6), builds, fmt(buildNanos / 1e6));
            frames = drawn = builds = 0; triangles = nanos = maxNanos = buildNanos = 0;
            maxTriangles = 0; windowStart = now;
        }
    }
    private static String fmt(double value) { return String.format("%.2f", value); }

    static boolean measuring() { return measuring; }
    static long start() { return measuring ? System.nanoTime() : 0; }
    static void drawn(long startNanos) {
        if (!measuring) return;
        frameDrawn++; frameNanos += System.nanoTime() - startNanos;
    }
    static void triangle() { frameTriangles++; }
    static void built(long startNanos) {
        if (!measuring) return;
        builds++; buildNanos += System.nanoTime() - startNanos;
    }
    private WoundLod() {}
}
