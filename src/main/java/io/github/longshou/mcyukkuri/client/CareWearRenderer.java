package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.longshou.mcyukkuri.MCYukkuri;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.Arrays;

/**
 * Pet harness and swaddle as flat-coloured shells derived procedurally from the posed body surface (no per-species authored attachments,
 * docs/PET_SHOP.md section 12). The body's skinned vertices are reduced to a radius profile (largest horizontal radius per angle bin and
 * height cell); a shell at any height is read from that profile by interpolating between the nearest samples below and above, so sparse
 * meshes (the rounded bodies have only eight vertex rings) and dense ones alike give a closed shell, and both follow squash, stretch and
 * growth of whatever model and clip is being drawn. The harness is a low belt just above the ground contact, under the face, with the lead
 * ring and a small buckle on the back; the swaddle is a soft cup over the lower body whose front edge dips below the mouth.
 * Presentation only; the wear state comes from the server. The model's front is -Z, so the back is +Z. Client only.
 */
final class CareWearRenderer {
    private static final ResourceLocation TEXTURE = MCYukkuri.id("textures/misc/care_wear.png");
    private static final int BINS = 24, CELLS = 32, HARNESS = 1, SWADDLE = 2, RINGS = 5;
    /** Harness belt centre and half height as fractions of the body height: low, under the mouth line, never across the face. */
    private static final float BELT_AT = .14f, BELT_HALF = .045f;
    /** Swaddle cup: lowest edge, and the top edge at the back and sides versus at the front (dips under the mouth). */
    private static final float CUP_BASE = .015f, CUP_BACK = .40f, CUP_FRONT = .09f;
    // colours (flat), the shared care-prop ramps of art/props/palette.json: strap = mint 2, brass = gold 2, cloth = ceramic 3, cloth in the shade of a fold = ceramic 1, hem = berry 3
    private static final int[] STRAP = {98, 137, 127}, BRASS = {243, 193, 105}, CLOTH = {250, 245, 228}, FOLD = {211, 197, 163}, HEM = {232, 180, 189};
    // client render thread scratch, reused every frame: radius profile (largest radius and its height fraction per height cell and angle bin) and shell rings
    private static final float[] CELL_R = new float[CELLS * BINS], CELL_F = new float[CELLS * BINS];
    private static final float[][] RING = new float[RINGS][BINS];
    private static final float[] BELT_LO = new float[BINS], BELT_HI = new float[BINS], TOP = new float[BINS], COPY = new float[BINS];

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
        profile(pts, cx, cz, minY, h);
        var buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        var last = pose.last();
        float out = .035f * h;
        boolean swaddle = (wear & SWADDLE) != 0;
        if (swaddle) swaddle(buffer, last, cx, cz, minY, h, out, light, overlay);
        if ((wear & HARNESS) != 0) harness(buffer, last, cx, cz, minY, h, swaddle ? out + .03f * h : .03f * h, light, overlay);
    }

    private static int bin(float dx, float dz) {
        double angle = Math.atan2(dz, dx) + Math.PI;
        return Math.min(BINS - 1, Math.max(0, (int) (angle / (2 * Math.PI) * BINS)));
    }
    /** Angle of the centre of a bin; the front of the model (-Z) is at -pi/2. */
    private static float angle(int bin) { return (float) (-Math.PI + 2 * Math.PI * (bin + .5) / BINS); }

    /** Largest horizontal radius per angle bin in each of CELLS height cells, remembering the height fraction at which it occurred. */
    private static void profile(float[] pts, float cx, float cz, float minY, float h) {
        Arrays.fill(CELL_R, 0);
        for (int i = 0; i < pts.length; i += 3) {
            float dx = pts[i] - cx, dz = pts[i + 2] - cz, f = (pts[i + 1] - minY) / h;
            float r = (float) Math.sqrt(dx * dx + dz * dz);
            int at = Math.min(CELLS - 1, Math.max(0, (int) (f * CELLS))) * BINS + bin(dx, dz);
            if (r > CELL_R[at]) { CELL_R[at] = r; CELL_F[at] = f; }
        }
    }

    /** Body radius at height fraction f in a bin: linear between the nearest samples below and above, the single side at the ends, 0 when the bin has no vertex. */
    private static float radiusAt(float f, int bin) {
        float loF = -1, loR = 0, hiF = 2, hiR = 0;
        for (int c = 0; c < CELLS; c++) {
            int at = c * BINS + bin;
            float r = CELL_R[at];
            if (r <= 0) continue;
            float cf = CELL_F[at];
            if (cf <= f) { if (cf > loF) { loF = cf; loR = r; } }
            else if (cf < hiF) { hiF = cf; hiR = r; }
        }
        if (loF < 0) return hiF > 1 ? 0 : hiR;
        if (hiF > 1) return loR;
        return loR + (hiR - loR) * (f - loF) / (hiF - loF);
    }

    /** Fills bins without a radius from their nearest neighbours and smooths once; false when no bin had one at all. */
    private static boolean fill(float[] radius) {
        boolean any = false;
        for (float r : radius) if (r > 0) { any = true; break; }
        if (!any) return false;
        float[] copy = COPY;
        System.arraycopy(radius, 0, copy, 0, BINS);
        for (int i = 0; i < BINS; i++) {
            if (copy[i] > 0) continue;
            for (int d = 1; d < BINS; d++) {
                float a = copy[(i + d) % BINS], b = copy[(i - d + BINS) % BINS];
                if (a > 0 || b > 0) { radius[i] = a > 0 && b > 0 ? (a + b) / 2 : Math.max(a, b); break; }
            }
        }
        System.arraycopy(radius, 0, copy, 0, BINS);
        for (int i = 0; i < BINS; i++) radius[i] = Math.max(copy[i], (copy[(i + 1) % BINS] + copy[(i + BINS - 1) % BINS]) / 2);
        return true;
    }

    /** A low belt around the body just above the ground contact (below the mouth), with the lead ring and a small buckle on the back (+Z). */
    private static void harness(VertexConsumer b, com.mojang.blaze3d.vertex.PoseStack.Pose pose, float cx, float cz, float minY, float h, float out,
                                int light, int overlay) {
        for (int i = 0; i < BINS; i++) { BELT_LO[i] = radiusAt(BELT_AT - BELT_HALF, i); BELT_HI[i] = radiusAt(BELT_AT + BELT_HALF, i); }
        if (!fill(BELT_LO) || !fill(BELT_HI)) return;
        float y0 = minY + (BELT_AT - BELT_HALF) * h, y1 = minY + (BELT_AT + BELT_HALF) * h;
        for (int i = 0; i < BINS; i++) {
            int j = (i + 1) % BINS;
            float a0 = angle(i), a1 = angle(j), am = (a0 + a1) / 2;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            float l0 = BELT_LO[i] + out, l1 = BELT_LO[j] + out, u0 = BELT_HI[i] + out, u1 = BELT_HI[j] + out;
            quad(b, pose, STRAP, light, overlay, (float) Math.cos(am), 0, (float) Math.sin(am),
                    cx + c0 * l0, y0, cz + s0 * l0, cx + c1 * l1, y0, cz + s1 * l1, cx + c1 * u1, y1, cz + s1 * u1, cx + c0 * u0, y1, cz + s0 * u0);
        }
        // the lead ring stands on the back (+Z) of the belt, a small buckle sits beside it
        float half = (float) Math.PI / 2, lift = .006f * h;
        float back = Math.max(Math.max(BELT_LO[17], BELT_LO[18]), Math.max(BELT_HI[17], BELT_HI[18])) + out + lift;
        frame(b, pose, BRASS, light, overlay, cx, cz, half, back, minY + (BELT_AT + .02f) * h, .05f * h, .05f * h, .028f * h, .028f * h);
        float side = Math.max(BELT_LO[20], BELT_HI[20]) + out + lift;
        frame(b, pose, BRASS, light, overlay, cx, cz, half + .62f, side, minY + BELT_AT * h, .045f * h, .052f * h, .022f * h, .028f * h);
    }

    /** A flat rectangular frame standing on the body at angle `a` and radius `r`, facing outwards; outer and inner half sizes in width and height. */
    private static void frame(VertexConsumer b, com.mojang.blaze3d.vertex.PoseStack.Pose pose, int[] color, int light, int overlay, float cx, float cz,
                              float a, float r, float yc, float ow, float oh, float iw, float ih) {
        float nx = (float) Math.cos(a), nz = (float) Math.sin(a), tx = -nz, tz = nx, px = cx + nx * r, pz = cz + nz * r;
        quad(b, pose, color, light, overlay, nx, 0, nz, px - tx * ow, yc + oh, pz - tz * ow, px + tx * ow, yc + oh, pz + tz * ow, px + tx * ow, yc + ih, pz + tz * ow, px - tx * ow, yc + ih, pz - tz * ow);
        quad(b, pose, color, light, overlay, nx, 0, nz, px - tx * ow, yc - ih, pz - tz * ow, px + tx * ow, yc - ih, pz + tz * ow, px + tx * ow, yc - oh, pz + tz * ow, px - tx * ow, yc - oh, pz - tz * ow);
        quad(b, pose, color, light, overlay, nx, 0, nz, px - tx * ow, yc + ih, pz - tz * ow, px - tx * iw, yc + ih, pz - tz * iw, px - tx * iw, yc - ih, pz - tz * iw, px - tx * ow, yc - ih, pz - tz * ow);
        quad(b, pose, color, light, overlay, nx, 0, nz, px + tx * iw, yc + ih, pz + tz * iw, px + tx * ow, yc + ih, pz + tz * ow, px + tx * ow, yc - ih, pz + tz * ow, px + tx * iw, yc - ih, pz + tz * iw);
    }

    /**
     * A soft cup over the lower body: five rings from just above the ground to a top edge that is high at the back and sides and dips below
     * the mouth at the front, so the face stays free; the top edge is folded over into a lip with a pink hem. Every ring radius comes from the
     * body profile at that ring's own height, so it closes on every form.
     */
    private static void swaddle(VertexConsumer b, com.mojang.blaze3d.vertex.PoseStack.Pose pose, float cx, float cz, float minY, float h, float out,
                                int light, int overlay) {
        for (int i = 0; i < BINS; i++) {
            float away = Math.abs((float) Math.atan2(Math.sin(angle(i) + Math.PI / 2), Math.cos(angle(i) + Math.PI / 2)));
            float s = Math.clamp((away - .45f) / 1.65f, 0f, 1f);
            TOP[i] = CUP_FRONT + (CUP_BACK - CUP_FRONT) * s * s * (3 - 2 * s);
        }
        for (int k = 0; k < RINGS; k++) {
            float u = k / (float) (RINGS - 1);
            for (int i = 0; i < BINS; i++) RING[k][i] = radiusAt(CUP_BASE + (TOP[i] - CUP_BASE) * u, i);
            if (!fill(RING[k])) return;
        }
        float lip = .016f * h, hem = .022f * h, drop = .045f * h, rise = .008f * h;
        for (int i = 0; i < BINS; i++) {
            int j = (i + 1) % BINS;
            float a0 = angle(i), a1 = angle(j), am = (a0 + a1) / 2;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1), nx = (float) Math.cos(am), nz = (float) Math.sin(am);
            var color = i % 3 == 0 ? FOLD : CLOTH;
            for (int k = 0; k + 1 < RINGS; k++) {
                float ua = k / (float) (RINGS - 1), ub = (k + 1) / (float) (RINGS - 1);
                float yai = minY + (CUP_BASE + (TOP[i] - CUP_BASE) * ua) * h, yaj = minY + (CUP_BASE + (TOP[j] - CUP_BASE) * ua) * h;
                float ybi = minY + (CUP_BASE + (TOP[i] - CUP_BASE) * ub) * h, ybj = minY + (CUP_BASE + (TOP[j] - CUP_BASE) * ub) * h;
                float ra0 = RING[k][i] + out, ra1 = RING[k][j] + out, rb0 = RING[k + 1][i] + out, rb1 = RING[k + 1][j] + out;
                quad(b, pose, color, light, overlay, nx, 0, nz, cx + c0 * ra0, yai, cz + s0 * ra0, cx + c1 * ra1, yaj, cz + s1 * ra1, cx + c1 * rb1, ybj, cz + s1 * rb1, cx + c0 * rb0, ybi, cz + s0 * rb0);
            }
            // folded-over lip: a band thicker than the cloth, a flat top and a thin pink hem on its lower edge
            float top0 = RING[RINGS - 1][i] + out, top1 = RING[RINGS - 1][j] + out;
            float yt0 = minY + TOP[i] * h, yt1 = minY + TOP[j] * h;
            quad(b, pose, FOLD, light, overlay, nx, 0, nz, cx + c0 * (top0 + lip), yt0 - drop, cz + s0 * (top0 + lip), cx + c1 * (top1 + lip), yt1 - drop, cz + s1 * (top1 + lip),
                    cx + c1 * (top1 + lip), yt1 + rise, cz + s1 * (top1 + lip), cx + c0 * (top0 + lip), yt0 + rise, cz + s0 * (top0 + lip));
            quad(b, pose, HEM, light, overlay, nx, 0, nz, cx + c0 * (top0 + hem), yt0 - drop, cz + s0 * (top0 + hem), cx + c1 * (top1 + hem), yt1 - drop, cz + s1 * (top1 + hem),
                    cx + c1 * (top1 + hem), yt1 - drop + .012f * h, cz + s1 * (top1 + hem), cx + c0 * (top0 + hem), yt0 - drop + .012f * h, cz + s0 * (top0 + hem));
            quad(b, pose, CLOTH, light, overlay, 0, 1, 0, cx + c0 * (top0 + lip), yt0 + rise, cz + s0 * (top0 + lip), cx + c1 * (top1 + lip), yt1 + rise, cz + s1 * (top1 + lip),
                    cx + c1 * (top1 - .004f * h), yt1 + rise, cz + s1 * (top1 - .004f * h), cx + c0 * (top0 - .004f * h), yt0 + rise, cz + s0 * (top0 - .004f * h));
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
