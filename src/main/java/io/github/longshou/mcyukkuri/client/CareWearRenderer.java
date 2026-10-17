package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.longshou.mcyukkuri.MCYukkuri;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Pet harness and swaddle as flat-coloured shells derived procedurally from the posed body surface (no per-species authored attachments,
 * docs/PET_SHOP.md section 12). The body's skinned vertices give its height, centre and a radius for every angle at a few heights, so both
 * follow squash, stretch and growth of whatever model and clip is being drawn. Presentation only; the wear state comes from the server.
 * The model's front is -Z, so the back ring sits at +Z. Client only.
 */
final class CareWearRenderer {
    private static final ResourceLocation TEXTURE = MCYukkuri.id("textures/misc/care_wear.png");
    private static final int BINS = 24, HARNESS = 1, SWADDLE = 2;
    /** Harness band height and the heights of the swaddle rings, as fractions of the body height. */
    private static final float BAND_AT = .46f;
    private static final float[] RINGS = {.05f, .14f, .24f, .34f, .44f, .54f};
    private static final float SLAB = .065f;
    // colours (flat), the shared care-prop ramps of art/props/palette.json: strap = mint 2, brass = gold 2, cloth = ceramic 2, cloth in the shade of a fold = ceramic 1, hem = berry 3
    private static final int[] STRAP = {98, 137, 127}, BRASS = {243, 193, 105}, CLOTH = {234, 223, 195}, FOLD = {211, 197, 163}, HEM = {232, 180, 189};

    private CareWearRenderer() {}

    static void render(YukkuriModel model, int wear, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (wear == 0) return;
        float[] pts = model.posedBody();
        if (pts.length < 9) return;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < pts.length; i += 3) {
            minX = Math.min(minX, pts[i]); maxX = Math.max(maxX, pts[i]);
            minY = Math.min(minY, pts[i + 1]); maxY = Math.max(maxY, pts[i + 1]);
            minZ = Math.min(minZ, pts[i + 2]); maxZ = Math.max(maxZ, pts[i + 2]);
        }
        float h = maxY - minY;
        if (h < .02f) return;
        float cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;
        boolean harness = (wear & HARNESS) != 0, swaddle = (wear & SWADDLE) != 0;
        float[] band = new float[BINS];
        float[][] rings = new float[RINGS.length][BINS];
        for (int i = 0; i < pts.length; i += 3) {
            float dx = pts[i] - cx, dz = pts[i + 2] - cz, f = (pts[i + 1] - minY) / h;
            float r = (float) Math.sqrt(dx * dx + dz * dz);
            int bin = bin(dx, dz);
            if (harness && Math.abs(f - BAND_AT) <= SLAB) band[bin] = Math.max(band[bin], r);
            if (swaddle) for (int k = 0; k < RINGS.length; k++) if (Math.abs(f - RINGS[k]) <= SLAB) rings[k][bin] = Math.max(rings[k][bin], r);
        }
        var buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        var last = pose.last();
        float out = .02f * h;
        boolean ok = true;
        if (swaddle) {
            for (var ring : rings) ok &= fill(ring);
            if (ok) swaddle(buffer, last, rings, cx, cz, minY, h, out, light, overlay);
        }
        if (harness && fill(band)) harness(buffer, last, band, cx, cz, minY, h, swaddle ? out + .012f * h : .012f * h, light, overlay);
    }

    private static int bin(float dx, float dz) {
        double angle = Math.atan2(dz, dx) + Math.PI;
        return Math.min(BINS - 1, Math.max(0, (int) (angle / (2 * Math.PI) * BINS)));
    }
    /** Angle of the centre of a bin; the front of the model (-Z) is at -pi/2. */
    private static float angle(int bin) { return (float) (-Math.PI + 2 * Math.PI * (bin + .5) / BINS); }

    /** Fills bins without a vertex from their nearest neighbours and smooths once; false when the slab held no vertex at all. */
    private static boolean fill(float[] radius) {
        boolean any = false;
        for (float r : radius) if (r > 0) { any = true; break; }
        if (!any) return false;
        float[] copy = radius.clone();
        for (int i = 0; i < BINS; i++) {
            if (copy[i] > 0) continue;
            for (int d = 1; d < BINS; d++) {
                float a = copy[(i + d) % BINS], b = copy[(i - d + BINS) % BINS];
                if (a > 0 || b > 0) { radius[i] = a > 0 && b > 0 ? (a + b) / 2 : Math.max(a, b); break; }
            }
        }
        copy = radius.clone();
        for (int i = 0; i < BINS; i++) radius[i] = Math.max(copy[i], (copy[(i + 1) % BINS] + copy[(i + BINS - 1) % BINS]) / 2);
        return true;
    }

    private static void harness(VertexConsumer b, com.mojang.blaze3d.vertex.PoseStack.Pose pose, float[] radius, float cx, float cz, float minY, float h, float out,
                                int light, int overlay) {
        float y0 = minY + (BAND_AT - .045f) * h, y1 = minY + (BAND_AT + .045f) * h;
        for (int i = 0; i < BINS; i++) {
            int j = (i + 1) % BINS;
            float a0 = angle(i), a1 = angle(j), r0 = radius[i] + out, r1 = radius[j] + out;
            float x0 = cx + (float) Math.cos(a0) * r0, z0 = cz + (float) Math.sin(a0) * r0, x1 = cx + (float) Math.cos(a1) * r1, z1 = cz + (float) Math.sin(a1) * r1;
            float am = (a0 + a1) / 2, nx = (float) Math.cos(am), nz = (float) Math.sin(am);
            quad(b, pose, STRAP, light, overlay, nx, 0, nz, x0, y0, z0, x1, y0, z1, x1, y1, z1, x0, y1, z0);
        }
        // the ring for the lead sits on the back (+Z): a flat square frame lying on the band
        int back = bin(0, 1);
        float zr = cz + radius[back] + out + .006f * h, yc = minY + (BAND_AT + .04f) * h, o = .05f * h, in = .028f * h;
        quad(b, pose, BRASS, light, overlay, 0, 0, 1, cx - o, yc + o, zr, cx + o, yc + o, zr, cx + o, yc + in, zr, cx - o, yc + in, zr);
        quad(b, pose, BRASS, light, overlay, 0, 0, 1, cx - o, yc - in, zr, cx + o, yc - in, zr, cx + o, yc - o, zr, cx - o, yc - o, zr);
        quad(b, pose, BRASS, light, overlay, 0, 0, 1, cx - o, yc + in, zr, cx - in, yc + in, zr, cx - in, yc - in, zr, cx - o, yc - in, zr);
        quad(b, pose, BRASS, light, overlay, 0, 0, 1, cx + in, yc + in, zr, cx + o, yc + in, zr, cx + o, yc - in, zr, cx + in, yc - in, zr);
    }

    /** A soft shell over the lower body and the back: closed up to a fifth of the height, then opening at the front so face and mouth stay free. */
    private static void swaddle(VertexConsumer b, com.mojang.blaze3d.vertex.PoseStack.Pose pose, float[][] rings, float cx, float cz, float minY, float h, float out,
                                int light, int overlay) {
        for (int k = 0; k + 1 < RINGS.length; k++) {
            float fa = RINGS[k], fb = RINGS[k + 1];
            float ya = minY + fa * h, yb = minY + fb * h;
            float gap = (float) Math.toRadians(85 * Math.clamp((fa + fb) / 2 - .16f, 0f, .30f) / .30f);
            for (int i = 0; i < BINS; i++) {
                int j = (i + 1) % BINS;
                float a0 = angle(i), a1 = angle(j), am = (a0 + a1) / 2;
                float fromFront = Math.abs((float) Math.atan2(Math.sin(am + Math.PI / 2), Math.cos(am + Math.PI / 2)));
                if (fromFront < gap) continue;
                float ra0 = rings[k][i] + out, ra1 = rings[k][j] + out, rb0 = rings[k + 1][i] + out, rb1 = rings[k + 1][j] + out;
                var color = (i & 1) == 0 ? CLOTH : FOLD;
                quad(b, pose, color, light, overlay, (float) Math.cos(am), 0, (float) Math.sin(am),
                        cx + (float) Math.cos(a0) * ra0, ya, cz + (float) Math.sin(a0) * ra0, cx + (float) Math.cos(a1) * ra1, ya, cz + (float) Math.sin(a1) * ra1,
                        cx + (float) Math.cos(a1) * rb1, yb, cz + (float) Math.sin(a1) * rb1, cx + (float) Math.cos(a0) * rb0, yb, cz + (float) Math.sin(a0) * rb0);
                if (k + 2 == RINGS.length) { // hem along the upper edge
                    float t = out + .004f * h, yt0 = yb - .018f * h, yt1 = yb + .006f * h;
                    quad(b, pose, HEM, light, overlay, (float) Math.cos(am), 0, (float) Math.sin(am),
                            cx + (float) Math.cos(a0) * (rb0 + t - out), yt0, cz + (float) Math.sin(a0) * (rb0 + t - out), cx + (float) Math.cos(a1) * (rb1 + t - out), yt0, cz + (float) Math.sin(a1) * (rb1 + t - out),
                            cx + (float) Math.cos(a1) * (rb1 + t - out), yt1, cz + (float) Math.sin(a1) * (rb1 + t - out), cx + (float) Math.cos(a0) * (rb0 + t - out), yt1, cz + (float) Math.sin(a0) * (rb0 + t - out));
                }
            }
        }
    }

    private static void quad(VertexConsumer b, com.mojang.blaze3d.vertex.PoseStack.Pose pose, int[] c, int light, int overlay, float nx, float ny, float nz,
                             float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3) {
        vertex(b, pose, c, light, overlay, nx, ny, nz, x0, y0, z0);
        vertex(b, pose, c, light, overlay, nx, ny, nz, x1, y1, z1);
        vertex(b, pose, c, light, overlay, nx, ny, nz, x2, y2, z2);
        vertex(b, pose, c, light, overlay, nx, ny, nz, x3, y3, z3);
    }
    private static void vertex(VertexConsumer b, com.mojang.blaze3d.vertex.PoseStack.Pose pose, int[] c, int light, int overlay, float nx, float ny, float nz, float x, float y, float z) {
        b.addVertex(pose.pose(), x, y, z).setColor(c[0], c[1], c[2], 255).setUv(.25f, .25f).setOverlay(overlay).setLight(light).setNormal(pose, nx, ny, nz);
    }
}
