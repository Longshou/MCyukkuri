package io.github.longshou.mcyukkuri.client;

import java.util.ArrayList;
import java.util.List;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-frame wound detail budget and the optional {@code [render]} readout. Full detail goes to the nearest few wounded
 * bodies, chosen once per frame from the bodies drawn in the previous frame; everything else draws the simple wound art.
 * All state is bounded and never saved.
 */
final class WoundLod {
    static final int FULL_COUNT = 2;
    static final float FULL_RANGE = 6;
    static final int FULL_BUILDS_PER_FRAME = 1;
    private static final long REPORT_NANOS = 5_000_000_000L;
    private static final Logger LOGGER = LoggerFactory.getLogger("MCYukkuri/render");

    private record Seen(Object owner, float distanceSquared) {}
    private static final List<Seen> SEEN = new ArrayList<>();
    private static final Object[] FULL = new Object[FULL_COUNT];
    private static int fullBuilds;

    // Readout state
    private static boolean measuring;
    private static int frameDrawn, frameFull, frameTriangles;
    private static long frameNanos, windowStart;
    private static int frames, drawn, fullDrawn, simpleDrawn;
    private static long triangles, nanos, maxNanos, buildNanos;
    private static int maxTriangles, buildsFull, buildsSimple;

    static void stage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) frame();
    }

    /** Called once at the start of each level render. */
    static void frame() {
        select();
        fullBuilds = 0;
        measuring = ClientConfig.woundPerfLog();
        if (!measuring) { windowStart = 0; return; }
        long now = System.nanoTime();
        if (windowStart == 0) windowStart = now;
        if (frameDrawn > 0) {
            frames++; drawn += frameDrawn; fullDrawn += frameFull; simpleDrawn += frameDrawn - frameFull;
            triangles += frameTriangles; maxTriangles = Math.max(maxTriangles, frameTriangles);
            nanos += frameNanos; maxNanos = Math.max(maxNanos, frameNanos);
        }
        frameDrawn = frameFull = frameTriangles = 0; frameNanos = 0;
        if (now - windowStart >= REPORT_NANOS) {
            if (frames > 0) LOGGER.info("[render] wound frames={} drawn/frame={} full/frame={} simple/frame={} tris/frame avg={} max={} ms/frame avg={} max={} builds full={} simple={} buildMs={}",
                    frames, fmt(drawn / (double) frames), fmt(fullDrawn / (double) frames), fmt(simpleDrawn / (double) frames),
                    triangles / frames, maxTriangles, fmt(nanos / 1e6 / frames), fmt(maxNanos / 1e6), buildsFull, buildsSimple, fmt(buildNanos / 1e6));
            frames = drawn = fullDrawn = simpleDrawn = 0; triangles = nanos = maxNanos = buildNanos = 0;
            maxTriangles = buildsFull = buildsSimple = 0; windowStart = now;
        }
    }
    private static String fmt(double value) { return String.format("%.2f", value); }

    private static void select() {
        java.util.Arrays.fill(FULL, null);
        for (var seen : SEEN) {
            if (seen.distanceSquared > FULL_RANGE * FULL_RANGE) continue;
            int slot = -1;
            for (int i = 0; i < FULL_COUNT; i++) if (FULL[i] == null) { slot = i; break; }
            if (slot < 0) {
                float worst = -1;
                for (int i = 0; i < FULL_COUNT; i++) {
                    float d = distance(FULL[i]);
                    if (d > seen.distanceSquared && d > worst) { worst = d; slot = i; }
                }
                if (slot < 0) continue;
            }
            FULL[slot] = seen.owner;
        }
        SEEN.clear();
    }
    private static float distance(Object owner) {
        for (var seen : SEEN) if (seen.owner == owner) return seen.distanceSquared;
        return Float.MAX_VALUE;
    }

    /** Registers a wounded body drawn this frame and says whether the previous frame's nearest-few selection includes it. */
    static boolean wantsFull(Object owner, float distanceSquared) {
        if (SEEN.size() < 64) {
            boolean known = false;
            for (var seen : SEEN) if (seen.owner == owner) { known = true; break; }
            if (!known) SEEN.add(new Seen(owner, distanceSquared));
        }
        for (var chosen : FULL) if (chosen == owner) return true;
        return false;
    }
    /** At most one full-detail build per frame; later ones stay simple until a following frame. */
    static boolean takeFullBuild() {
        if (fullBuilds >= FULL_BUILDS_PER_FRAME) return false;
        fullBuilds++; return true;
    }

    static boolean measuring() { return measuring; }
    static long start() { return measuring ? System.nanoTime() : 0; }
    static void drawn(boolean full, long startNanos) {
        if (!measuring) return;
        frameDrawn++; if (full) frameFull++; frameNanos += System.nanoTime() - startNanos;
    }
    static void triangle() { frameTriangles++; }
    static void built(boolean full, long startNanos) {
        if (!measuring) return;
        if (full) buildsFull++; else buildsSimple++;
        buildNanos += System.nanoTime() - startNanos;
    }
    private WoundLod() {}
}
