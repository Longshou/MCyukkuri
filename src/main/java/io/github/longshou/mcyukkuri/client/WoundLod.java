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
    private static final float KEEP_BIAS = .6f;
    static final int FULL_BUILDS_PER_FRAME = 1;
    private static final long REPORT_NANOS = 5_000_000_000L;
    private static final Logger LOGGER = LoggerFactory.getLogger("MCYukkuri/render");

    private record Seen(Object owner, float distanceSquared) {}
    private static final List<Seen> SEEN = new ArrayList<>();
    private static final Object[] FULL = new Object[FULL_COUNT];
    private static int fullBuilds;

    // Readout state
    private static boolean measuring;
    static final int PREPARE = 0, BODY = 1, SURFACE = 2, DISPLAY = 3;
    private static int frameDrawn, frameFull, framePatch, frameLayer, frameCavity;
    private static final long[] FRAME_STAGE = new long[4], STAGE = new long[4];
    private static long windowStart;
    private static int frames, drawn, fullDrawn, simpleDrawn;
    private static long patchTriangles, layerTriangles, cavityTriangles, maxNanos, buildNanos;
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
            patchTriangles += framePatch; layerTriangles += frameLayer; cavityTriangles += frameCavity;
            maxTriangles = Math.max(maxTriangles, framePatch + frameLayer + frameCavity);
            long total = 0;
            for (int i = 0; i < FRAME_STAGE.length; i++) { STAGE[i] += FRAME_STAGE[i]; total += FRAME_STAGE[i]; }
            maxNanos = Math.max(maxNanos, total);
        }
        frameDrawn = frameFull = framePatch = frameLayer = frameCavity = 0; java.util.Arrays.fill(FRAME_STAGE, 0);
        if (now - windowStart >= REPORT_NANOS) {
            if (frames > 0) LOGGER.info("[render] wound frames={} drawn/frame={} full/frame={} simple/frame={} tris/frame patch={} layer={} cavity={} max={} "
                            + "ms/frame prepare={} body={} surface={} display={} total={} max={} builds full={} simple={} buildMs={}",
                    frames, fmt(drawn / (double) frames), fmt(fullDrawn / (double) frames), fmt(simpleDrawn / (double) frames),
                    patchTriangles / frames, layerTriangles / frames, cavityTriangles / frames, maxTriangles,
                    fmt(STAGE[PREPARE] / 1e6 / frames), fmt(STAGE[BODY] / 1e6 / frames), fmt(STAGE[SURFACE] / 1e6 / frames), fmt(STAGE[DISPLAY] / 1e6 / frames),
                    fmt((STAGE[PREPARE] + STAGE[BODY] + STAGE[SURFACE] + STAGE[DISPLAY]) / 1e6 / frames), fmt(maxNanos / 1e6),
                    buildsFull, buildsSimple, fmt(buildNanos / 1e6));
            frames = drawn = fullDrawn = simpleDrawn = 0; patchTriangles = layerTriangles = cavityTriangles = maxNanos = buildNanos = 0;
            java.util.Arrays.fill(STAGE, 0);
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
        boolean chosen = false;
        for (var full : FULL) if (full == owner) { chosen = true; break; }
        if (SEEN.size() < 64) {
            boolean known = false;
            for (var seen : SEEN) if (seen.owner == owner) { known = true; break; }
            // Hysteresis: a body that already has full detail looks nearer, so it does not flip at the range edge.
            if (!known) SEEN.add(new Seen(owner, chosen ? distanceSquared * KEEP_BIAS : distanceSquared));
        }
        return chosen;
    }
    /** At most one full-detail build per frame; later ones stay simple until a following frame. */
    static boolean takeFullBuild() {
        if (fullBuilds >= FULL_BUILDS_PER_FRAME) return false;
        fullBuilds++; return true;
    }

    static boolean measuring() { return measuring; }
    static long start() { return measuring ? System.nanoTime() : 0; }
    /** Counts one wounded body drawn this frame. */
    static void drawn(boolean full) {
        if (!measuring) return;
        frameDrawn++; if (full) frameFull++;
    }
    /** Adds the time since {@code startNanos} (from {@link #start()}) to one stage of this frame's wound cost. */
    static void time(int stage, long startNanos) {
        if (measuring) FRAME_STAGE[stage] += System.nanoTime() - startNanos;
    }
    static void triangle(boolean layer) { if (layer) frameLayer++; else framePatch++; }
    static void cavity(int faces) { frameCavity += faces; }
    static void built(boolean full, long startNanos) {
        if (!measuring) return;
        if (full) buildsFull++; else buildsSimple++;
        buildNanos += System.nanoTime() - startNanos;
    }
    private WoundLod() {}
}
