package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.longshou.mcyukkuri.MCYukkuri;
import java.util.Arrays;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Pet harness, cloth swaddle and leaf swaddle as shells derived procedurally from the posed body surface (no per-species authored
 * attachments, docs/PET_SHOP.md, docs/CHILDHOOD_LIFECYCLE.md). The body's skinned vertices are reduced to a radius profile (largest
 * horizontal radius per angle bin and height cell); a shell at any height is read from that profile by interpolating between the nearest
 * samples below and above, so sparse meshes (the rounded bodies have only eight vertex rings) and dense ones alike give a closed shell, and
 * all follow squash, stretch and growth of whatever model and clip is being drawn. The harness is a low belt just above the ground contact,
 * under the face, with the lead ring and a small buckle on the back; the cloth swaddle is a soft knitted cup over the lower body whose front
 * edge dips below the mouth; the leaf swaddle is four overlapping leaves wrapped around the same lower body, their ragged margins and veins
 * drawn by a cutout leaf texture, the front leaf lowest so the mouth stays free. While a parent has opened it for care the leaf wrap lies
 * flat on the ground under the child as a support leaf. Presentation only; the wear state comes from the server. The model's front is -Z,
 * so the back is +Z. Client only.
 */
final class CareWearRenderer {
    private static final ResourceLocation TEXTURE = MCYukkuri.id("textures/misc/care_wear.png");
    /** Seamless 16x16 knit tile, tinted by vertex colour (art/props/pet-goods, drafts/care-props/wear.py). */
    private static final ResourceLocation WOOL = MCYukkuri.id("textures/misc/swaddle_wool_wear.png");
    /** Four 32x16 cells: leaf A, leaf B, then both stool-stained; stem at u=0, tip at u=1, each leaf's upper margin on its top row. */
    private static final ResourceLocation LEAF = MCYukkuri.id("textures/misc/swaddle_leaf_wear.png");
    private static final int BINS = 24, CELLS = 32, HARNESS = 1, SWADDLE = 2, LEAF_WORN = 4, LEAF_OPEN = 8, RINGS = 5, LEAF_STEPS = 10;
    /** Harness belt centre and half height as fractions of the body height: low, under the mouth line, never across the face. */
    private static final float BELT_AT = .14f, BELT_HALF = .045f;
    /** Swaddle cup: lowest edge, and the top edge at the back and sides versus at the front (dips under the mouth). */
    private static final float CUP_BASE = .015f, CUP_BACK = .40f, CUP_FRONT = .16f;
    /** No leaf rises above this height fraction within GUARD_HALF radians of the front, so the mouth stays free. */
    private static final float MOUTH_GUARD = .17f, GUARD_HALF = .35f;
    /**
     * The cloth's one overlapping flap at the front left: start angle and sweep (radians) at the bottom, lapping towards the front; its
     * leading edge slants back by SEAM_SLANT of the sweep at the top, so it shows as a diagonal hemmed edge from the front and 3/4.
     */
    private static final float SEAM_AT = -2.3f, SEAM_SWEEP = .55f, SEAM_SLANT = .6f;
    /** Knit texels per model unit (16 units per block): one 16-texel tile repeats every quarter block, a fine even knit. */
    private static final float WOOL_DENSITY = 4;
    /**
     * Four large leaves laid diagonally on the unrolled lower body, each {stem angle, stem height, tip angle, tip height, width, layer,
     * texture row} with heights and width as fractions of the body height. The back leaf lies innermost; the two side leaves start at the
     * back and rise forwards so their tips stand at the cheeks, making a V; a narrow low front leaf, outermost (four layer steps, clear of the face plate), closes the wrap under the mouth.
     */
    private static final float[][] LEAVES = {{2.52f, .24f, .62f, .28f, .50f, 0, 0}, {2.30f, .17f, 4.08f, .27f, .46f, 1, .25f},
            {.84f, .17f, -.94f, .27f, .46f, 1, .25f}, {-.80f, .075f, -2.34f, .08f, .26f, 4, 0}};
    /** Texture v of one leaf cell, and the offset from a clean leaf to its stool-stained copy. */
    private static final float CELL_V = .25f, STAINED_V = .5f;
    /** Stool loads the wear bits can show; at this many the base gets its brown blotch. */
    private static final int MAX_SOIL_SHOWN = 3;
    /** Unrolling scale: angle (radians) to surface distance in body heights, from the rounded forms' mean radius over their height. */
    private static final float ARC_SCALE = .73f;
    /** Radial step between leaf layers and the extra lift of a leaf tip, as fractions of the body height. */
    private static final float LAYER = .014f, TIP_LIFT = .07f;
    private static final int ACROSS = 6;
    /** Height window (fraction of the body height) of radiusNear's fallback. */
    private static final float NEAR_WINDOW = .05f;
    /** Flat support leaves while the wrap is open: {direction angle, texture row}, crossing under the body. */
    private static final float[][] GROUND = {{.35f, 0}, {-1.95f, .25f}};
    // colours: harness flat (palette mint 2, gold 2); the knit tile (average ~(244,240,232)) is tinted to cloth = ceramic 3, fold = ceramic 1,
    // hem = berry 3; leaves keep their own texture colour (a soiled wrap's low cells switch to the stained leaf copy)
    private static final int[] STRAP = {98, 137, 127}, BRASS = {243, 193, 105}, CLOTH = {255, 252, 244}, FOLD = {226, 218, 198}, HEM = {242, 191, 207},
            WHITE = {255, 255, 255};
    // client render thread scratch, reused every frame: radius profile (largest radius and its height fraction per height cell and angle bin) and shell rings
    private static final float[] CELL_R = new float[CELLS * BINS], CELL_F = new float[CELLS * BINS];
    private static final float[][] RING = new float[RINGS][BINS];
    private static final float[] BELT_LO = new float[BINS], BELT_HI = new float[BINS], TOP = new float[BINS], COPY = new float[BINS], ARC = new float[BINS + 1];
    private static final float[] GRID = new float[(LEAF_STEPS + 1) * (ACROSS + 1) * 4];
    /** Hull radius per height cell (lower half of the body) and bin, rebuilt for each leaf wrap. */
    private static final float[][] NEAR = new float[CELLS / 2][BINS];

    /** Direction of each bin centre. */
    private static final float[] COS = new float[BINS], SIN = new float[BINS];
    static { for (int i = 0; i < BINS; i++) { COS[i] = (float) Math.cos(angle(i)); SIN[i] = (float) Math.sin(angle(i)); } }

    private CareWearRenderer() {}

    static void render(YukkuriModel model, int wear, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (wear == 0) return;
        float[] pts = model.posedWear();
        int body = model.wearBodyLength(), all = model.wearLength();
        if (body < 9) return;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < body; i += 3) {
            minX = Math.min(minX, pts[i]); maxX = Math.max(maxX, pts[i]);
            minY = Math.min(minY, pts[i + 1]); maxY = Math.max(maxY, pts[i + 1]);
            minZ = Math.min(minZ, pts[i + 2]); maxZ = Math.max(maxZ, pts[i + 2]);
        }
        float h = maxY - minY;
        if (h < .02f) return;
        float cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;
        var last = pose.last();
        float out = .035f * h;
        boolean cloth = (wear & SWADDLE) != 0, leaf = !cloth && (wear & LEAF_WORN) != 0;
        // Heights come from the body alone. The cloth hugs the body itself and passes under hanging hair; the leaf wrap's radii also take
        // the hair hanging at the back, so its large leaves close outside it. One buffer at a time: a batched source may flush the previous
        // one when another texture is requested.
        if (cloth) {
            profile(pts, body, cx, cz, minY, h);
            swaddle(buffers.getBuffer(RenderType.entityCutoutNoCull(WOOL)), last, cx, cz, minY, h, out, light, overlay);
        }
        if (leaf) profile(pts, all, cx, cz, minY, h);
        if (leaf && (wear & LEAF_OPEN) != 0) groundLeaves(buffers.getBuffer(RenderType.entityCutoutNoCull(LEAF)), last, cx, cz, h, light, overlay);
        else if (leaf) leafWrap(buffers.getBuffer(RenderType.entityCutoutNoCull(LEAF)), last, cx, cz, minY, h, out, wear >> 4 & 3, light, overlay);
        boolean wrapped = cloth || leaf && (wear & LEAF_OPEN) == 0;
        // The harness follows the body itself, so its back ring stays where the lead attaches (YukkuriInteractions.getLeashOffset).
        if ((wear & HARNESS) != 0 && !cloth) profile(pts, body, cx, cz, minY, h);
        if ((wear & HARNESS) != 0) harness(buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE)), last, cx, cz, minY, h,
                wrapped ? out + .03f * h : .03f * h, light, overlay);
    }

    private static int bin(float dx, float dz) {
        double angle = Math.atan2(dz, dx) + Math.PI;
        return Math.min(BINS - 1, Math.max(0, (int) (angle / (2 * Math.PI) * BINS)));
    }
    /** Angle of the centre of a bin; the front of the model (-Z) is at -pi/2. */
    private static float angle(int bin) { return (float) (-Math.PI + 2 * Math.PI * (bin + .5) / BINS); }

    /** Largest horizontal radius per angle bin in each of CELLS height cells (first `length` floats of pts), remembering the height fraction at which it occurred. */
    private static void profile(float[] pts, int length, float cx, float cz, float minY, float h) {
        Arrays.fill(CELL_R, 0);
        for (int i = 0; i < length; i += 3) {
            float dx = pts[i] - cx, dz = pts[i + 2] - cz, f = (pts[i + 1] - minY) / h;
            if (f < 0) continue; // hair tips hanging below the body's lowest point would otherwise widen the base cell
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

    /**
     * The largest of radiusAt in this bin and its two neighbours, and of any sample near this height in them. A bin whose vertex rings
     * fall far apart in height interpolates a chord that cuts into the round body (dents at the back and the low front of the rounded
     * forms); the neighbours' and nearby samples close it.
     */
    private static float radiusNear(float f, int bin) {
        float r = Math.max(radiusAt(f, bin), Math.max(radiusAt(f, (bin + 1) % BINS), radiusAt(f, (bin + BINS - 1) % BINS)));
        // Sparse vertex rings leave whole bins without a sample near this height (the rounded bodies' low front); the largest radius
        // within a twentieth of the body height above or below, in the same three bins, keeps the shell outside the real surface there.
        int lo = Math.max(0, (int) ((f - NEAR_WINDOW) * CELLS)), hi = Math.min(CELLS - 1, (int) ((f + NEAR_WINDOW) * CELLS));
        for (int c = lo; c <= hi; c++) for (int d = -1; d <= 1; d++) r = Math.max(r, CELL_R[c * BINS + (bin + d + BINS) % BINS]);
        return r;
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

    /**
     * Pushes each bin out to the chord between the bins up to four steps either side, so a flat face plate that has vertices only at its
     * edges (the rounded forms' front) is spanned by the shell instead of being cut into by a chord through the body's centre.
     */
    private static void convexify(float[] radius) {
        for (int k = 1; k <= 4; k++) for (int i = 0; i < BINS; i++) {
            int a = (i - k + BINS) % BINS, c = (i + k) % BINS;
            float ax = COS[a] * radius[a], az = SIN[a] * radius[a];
            float dx = COS[c] * radius[c] - ax, dz = SIN[c] * radius[c] - az, den = COS[i] * dz - SIN[i] * dx;
            if (Math.abs(den) < 1e-6f) continue;
            float lambda = (ax * dz - az * dx) / den;
            if (lambda > radius[i]) radius[i] = lambda;
        }
    }

    /** Top edge of a cup at an angle: high at the back and sides, low at the front (-pi/2), smoothstep between. */
    private static float rim(float angle, float front, float back) {
        float away = Math.abs((float) Math.atan2(Math.sin(angle + Math.PI / 2), Math.cos(angle + Math.PI / 2)));
        float s = Math.clamp((away - .45f) / 1.65f, 0f, 1f);
        return front + (back - front) * s * s * (3 - 2 * s);
    }

    /** RING[k] = body radius per bin at the k-th of RINGS heights between the base and TOP[bin]; false when the profile is empty. */
    private static boolean rings(float front, float back) {
        for (int i = 0; i < BINS; i++) TOP[i] = rim(angle(i), front, back);
        for (int k = 0; k < RINGS; k++) {
            float u = k / (float) (RINGS - 1);
            for (int i = 0; i < BINS; i++) RING[k][i] = radiusNear(CUP_BASE + (TOP[i] - CUP_BASE) * u, i);
            if (!fill(RING[k])) return false;
            convexify(RING[k]);
        }
        return true;
    }

    /** A low belt around the body just above the ground contact (below the mouth), with the lead ring and a small buckle on the back (+Z). */
    private static void harness(VertexConsumer b, PoseStack.Pose pose, float cx, float cz, float minY, float h, float out,
                                int light, int overlay) {
        for (int i = 0; i < BINS; i++) { BELT_LO[i] = radiusNear(BELT_AT - BELT_HALF, i); BELT_HI[i] = radiusNear(BELT_AT + BELT_HALF, i); }
        if (!fill(BELT_LO) || !fill(BELT_HI)) return;
        float y0 = minY + (BELT_AT - BELT_HALF) * h, y1 = minY + (BELT_AT + BELT_HALF) * h;
        for (int i = 0; i < BINS; i++) {
            int j = (i + 1) % BINS;
            float a0 = angle(i), a1 = angle(j), am = (a0 + a1) / 2;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            float l0 = BELT_LO[i] + out, l1 = BELT_LO[j] + out, u0 = BELT_HI[i] + out, u1 = BELT_HI[j] + out;
            flat(b, pose, STRAP, light, overlay, (float) Math.cos(am), 0, (float) Math.sin(am),
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
    private static void frame(VertexConsumer b, PoseStack.Pose pose, int[] color, int light, int overlay, float cx, float cz,
                              float a, float r, float yc, float ow, float oh, float iw, float ih) {
        float nx = (float) Math.cos(a), nz = (float) Math.sin(a), tx = -nz, tz = nx, px = cx + nx * r, pz = cz + nz * r;
        flat(b, pose, color, light, overlay, nx, 0, nz, px - tx * ow, yc + oh, pz - tz * ow, px + tx * ow, yc + oh, pz + tz * ow, px + tx * ow, yc +
                ih, pz + tz * ow, px - tx * ow, yc + ih, pz - tz * ow);
        flat(b, pose, color, light, overlay, nx, 0, nz, px - tx * ow, yc - ih, pz - tz * ow, px + tx * ow, yc - ih, pz + tz * ow, px + tx * ow,
                yc - oh, pz + tz * ow, px - tx * ow, yc - oh, pz - tz * ow);
        flat(b, pose, color, light, overlay, nx, 0, nz, px - tx * ow, yc + ih, pz - tz * ow, px - tx * iw, yc + ih, pz - tz * iw, px - tx * iw,
                yc - ih, pz - tz * iw, px - tx * ow, yc - ih, pz - tz * ow);
        flat(b, pose, color, light, overlay, nx, 0, nz, px + tx * iw, yc + ih, pz + tz * iw, px + tx * ow, yc + ih, pz + tz * ow, px + tx * ow,
                yc - ih, pz + tz * ow, px + tx * iw, yc - ih, pz + tz * iw);
    }

    /**
     * A soft knitted wrap over the lower body: five rings from just above the ground to a top edge that is high at the back and sides and
     * dips below the mouth at the front, so the face stays free. Every ring radius comes from the body's own profile at that ring's height
     * with the leaf wrap's small inset, so it hugs every form (passing under hanging hair) instead of standing off it like a bowl. The top edge rolls over inwards towards the body
     * with a pink hem under the roll; one overlapping flap at the front left shows a diagonal, pink-hemmed leading edge. The knit tile runs
     * around the body by arc length (rounded to whole tiles so the seam closes) and up by height, at WOOL_DENSITY texels per model unit.
     */
    private static void swaddle(VertexConsumer b, PoseStack.Pose pose, float cx, float cz, float minY, float h, float out,
                                int light, int overlay) {
        if (!rings(CUP_FRONT, CUP_BACK)) return;
        float inset = out * .6f;
        // arc length around the middle ring sets u, so every ring shares the same columns of stitches
        ARC[0] = 0;
        for (int i = 0; i < BINS; i++) {
            int j = (i + 1) % BINS;
            float r0 = RING[2][i] + inset, r1 = RING[2][j] + inset;
            float dx = COS[j] * r1 - COS[i] * r0, dz = SIN[j] * r1 - SIN[i] * r0;
            ARC[i + 1] = ARC[i] + (float) Math.sqrt(dx * dx + dz * dz);
        }
        float tiles = Math.max(1, Math.round(ARC[BINS] * 16 * WOOL_DENSITY / 16)), uScale = tiles / Math.max(1e-4f, ARC[BINS]), vScale = WOOL_DENSITY;
        float roll = .016f * h, rise = .012f * h, inward = .03f * h, hem = .03f * h;
        for (int i = 0; i < BINS; i++) {
            int j = (i + 1) % BINS;
            float c0 = COS[i], s0 = SIN[i], c1 = COS[j], s1 = SIN[j], am = (angle(i) + angle(j)) / 2, nx = (float) Math.cos(am), nz = (float) Math.sin(am);
            float u0 = ARC[i] * uScale, u1 = ARC[i + 1] * uScale;
            for (int k = 0; k + 1 < RINGS; k++) {
                float ua = k / (float) (RINGS - 1), ub = (k + 1) / (float) (RINGS - 1);
                float yai = minY + (CUP_BASE + (TOP[i] - CUP_BASE) * ua) * h, yaj = minY + (CUP_BASE + (TOP[j] - CUP_BASE) * ua) * h;
                float ybi = minY + (CUP_BASE + (TOP[i] - CUP_BASE) * ub) * h, ybj = minY + (CUP_BASE + (TOP[j] - CUP_BASE) * ub) * h;
                float ra0 = RING[k][i] + inset, ra1 = RING[k][j] + inset, rb0 = RING[k + 1][i] + inset, rb1 = RING[k + 1][j] + inset;
                vertex(b, pose, CLOTH, u0, -(yai - minY) * vScale, light, overlay, nx, 0, nz, cx + c0 * ra0, yai, cz + s0 * ra0);
                vertex(b, pose, CLOTH, u1, -(yaj - minY) * vScale, light, overlay, nx, 0, nz, cx + c1 * ra1, yaj, cz + s1 * ra1);
                vertex(b, pose, CLOTH, u1, -(ybj - minY) * vScale, light, overlay, nx, 0, nz, cx + c1 * rb1, ybj, cz + s1 * rb1);
                vertex(b, pose, CLOTH, u0, -(ybi - minY) * vScale, light, overlay, nx, 0, nz, cx + c0 * rb0, ybi, cz + s0 * rb0);
            }
            float top0 = RING[RINGS - 1][i] + inset, top1 = RING[RINGS - 1][j] + inset, yt0 = minY + TOP[i] * h, yt1 = minY + TOP[j] * h;
            float b0 = top0 + roll * .5f, b1 = top1 + roll * .5f;
            // the pink hem just under the roll, then the roll's outer face, then its top curling inwards onto the body
            textured(b, pose, HEM, light, overlay, nx, 0, nz, u0, u1, .5f, .6f, cx + c0 * (b0 + .002f * h), yt0 - roll - hem, cz + s0 * (b0 + .002f * h),
                    cx + c1 * (b1 + .002f * h), yt1 - roll - hem, cz + s1 * (b1 + .002f * h), cx + c1 * (b1 + .002f * h), yt1 - roll, cz + s1 * (b1 + .002f * h),
                    cx + c0 * (b0 + .002f * h), yt0 - roll, cz + s0 * (b0 + .002f * h));
            textured(b, pose, CLOTH, light, overlay, nx, 0, nz, u0, u1, 0, .2f, cx + c0 * b0, yt0 - roll, cz + s0 * b0, cx + c1 * b1, yt1 - roll, cz + s1 * b1,
                    cx + c1 * b1, yt1, cz + s1 * b1, cx + c0 * b0, yt0, cz + s0 * b0);
            textured(b, pose, FOLD, light, overlay, nx * .5f, .85f, nz * .5f, u0, u1, .2f, .3f, cx + c0 * b0, yt0, cz + s0 * b0, cx + c1 * b1, yt1, cz + s1 * b1,
                    cx + c1 * (top1 - inward), yt1 + rise, cz + s1 * (top1 - inward), cx + c0 * (top0 - inward), yt0 + rise, cz + s0 * (top0 - inward));
        }
        // the overlapping flap: its free end laps over the cloth towards the front; the leading edge runs diagonally and carries the pink hem
        float lift = inset + .014f * h, edge = .07f;
        for (int k = 0; k + 1 < RINGS; k++) {
            float leadA = seamLead(k), leadB = seamLead(k + 1);
            for (int s = 0; s < 4; s++) {
                float a0 = SEAM_AT + (leadA - edge - SEAM_AT) * s / 4f, a1 = SEAM_AT + (leadA - edge - SEAM_AT) * (s + 1) / 4f;
                float b0 = SEAM_AT + (leadB - edge - SEAM_AT) * s / 4f, b1 = SEAM_AT + (leadB - edge - SEAM_AT) * (s + 1) / 4f;
                float am = (a0 + a1 + b0 + b1) / 4, nx = (float) Math.cos(am), nz = (float) Math.sin(am);
                ringVertex(b, pose, CLOTH[0], CLOTH[1], CLOTH[2], s * .4f, -k * .25f, light, overlay, nx, nz, cx, cz, minY, h, a0, k, lift);
                ringVertex(b, pose, CLOTH[0], CLOTH[1], CLOTH[2], (s + 1) * .4f, -k * .25f, light, overlay, nx, nz, cx, cz, minY, h, a1, k, lift);
                ringVertex(b, pose, CLOTH[0], CLOTH[1], CLOTH[2], (s + 1) * .4f, -(k + 1) * .25f, light, overlay, nx, nz, cx, cz, minY, h, b1, k + 1, lift);
                ringVertex(b, pose, CLOTH[0], CLOTH[1], CLOTH[2], s * .4f, -(k + 1) * .25f, light, overlay, nx, nz, cx, cz, minY, h, b0, k + 1, lift);
            }
            float nx = (float) Math.cos(leadA), nz = (float) Math.sin(leadA), hemLift = lift + .003f * h;
            ringVertex(b, pose, HEM[0], HEM[1], HEM[2], 0, .5f, light, overlay, nx, nz, cx, cz, minY, h, leadA - edge, k, hemLift);
            ringVertex(b, pose, HEM[0], HEM[1], HEM[2], .2f, .5f, light, overlay, nx, nz, cx, cz, minY, h, leadA, k, hemLift);
            ringVertex(b, pose, HEM[0], HEM[1], HEM[2], .2f, .6f, light, overlay, nx, nz, cx, cz, minY, h, leadB, k + 1, hemLift);
            ringVertex(b, pose, HEM[0], HEM[1], HEM[2], 0, .6f, light, overlay, nx, nz, cx, cz, minY, h, leadB - edge, k + 1, hemLift);
        }
    }

    /** Angle of the flap's leading edge on ring k: furthest towards the front at the bottom, slanting back towards the top. */
    private static float seamLead(int k) { return SEAM_AT + SEAM_SWEEP * (1 - SEAM_SLANT * k / (RINGS - 1)); }

    /**
     * The leaf wrap: each leaf of LEAVES is a grid LEAF_STEPS long and ACROSS wide, its centre line running from stem to tip on the
     * unrolled lower body and its width across it. Every grid point takes the posed profile radius at its own height and angle (which
     * includes the hair hanging at the back, so the leaves close outside it), plus the leaf's layer step; the last part lifts off as a
     * pointed tip. The cutout texture draws one whole leaf along the grid: outline, serrated margin, midrib and veins. Stool loads switch
     * the lowest grid cells to the stained copy of the same leaf, higher with every load.
     */
    private static void leafWrap(VertexConsumer b, PoseStack.Pose pose, float cx, float cz, float minY, float h, float out, int soil,
                                 int light, int overlay) {
        for (int c = 0; c < NEAR.length; c++) {
            for (int i = 0; i < BINS; i++) NEAR[c][i] = radiusNear((c + .5f) / CELLS, i);
            if (!fill(NEAR[c])) return;
            convexify(NEAR[c]);
        }
        for (float[] leaf : LEAVES) {
            float s0 = leaf[0] * ARC_SCALE, p0 = leaf[1], ds = leaf[2] * ARC_SCALE - s0, dp = leaf[3] - p0, width = leaf[4];
            float base = out * .6f + leaf[5] * LAYER * h, row = leaf[6], length = (float) Math.sqrt(ds * ds + dp * dp);
            float ns = -dp / length, np = ds / length;
            if (np < 0) { ns = -ns; np = -np; }
            boolean ok = true;
            for (int i = 0; i <= LEAF_STEPS && ok; i++) for (int j = 0; j <= ACROSS; j++) {
                float t = i / (float) LEAF_STEPS, c = j / (float) ACROSS;
                float a = (s0 + ds * t + ns * (c - .5f) * width) / ARC_SCALE, f = p0 + dp * t + np * (c - .5f) * width;
                if (Math.abs((float) Math.atan2(Math.sin(a + Math.PI / 2), Math.cos(a + Math.PI / 2))) < GUARD_HALF) f = Math.min(f, MOUTH_GUARD);
                f = Math.max(CUP_BASE, f);
                float r = radiusAround(f, a);
                if (r <= 0) { ok = false; break; }
                r += base + tipLift(t) * h;
                int at = (i * (ACROSS + 1) + j) * 4;
                GRID[at] = cx + (float) Math.cos(a) * r; GRID[at + 1] = minY + f * h; GRID[at + 2] = cz + (float) Math.sin(a) * r;
                GRID[at + 3] = f;
            }
            if (!ok) continue;
            for (int i = 0; i < LEAF_STEPS; i++) for (int j = 0; j < ACROSS; j++) {
                int c00 = (i * (ACROSS + 1) + j) * 4, c10 = ((i + 1) * (ACROSS + 1) + j) * 4, c11 = c10 + 4, c01 = c00 + 4;
                float mx = (GRID[c00] + GRID[c11]) / 2 - cx, mz = (GRID[c00 + 2] + GRID[c11 + 2]) / 2 - cz, ml = (float) Math.sqrt(mx * mx + mz * mz) + 1e-6f;
                float nx = mx / ml, nz = mz / ml, u0 = i / (float) LEAF_STEPS, u1 = (i + 1) / (float) LEAF_STEPS;
                // grid cells whose middle lies within the soiled height use the stained copy of the same leaf: a hard pixel edge, higher per load
                float midF = (GRID[c00 + 3] + GRID[c11 + 3]) / 2, cell = row + (midF < soilHeight(soil) ? STAINED_V : 0);
                float v0 = cell + CELL_V * (1 - j / (float) ACROSS), v1 = cell + CELL_V * (1 - (j + 1) / (float) ACROSS);
                gridVertex(b, pose, c00, u0, v0, light, overlay, nx, nz, soil);
                gridVertex(b, pose, c10, u1, v0, light, overlay, nx, nz, soil);
                gridVertex(b, pose, c11, u1, v1, light, overlay, nx, nz, soil);
                gridVertex(b, pose, c01, u0, v1, light, overlay, nx, nz, soil);
            }
        }
    }

    /** A full wrap (three loads) also darkens its very base towards brown, so soiling never reads as autumn leaves. */
    private static void gridVertex(VertexConsumer b, PoseStack.Pose pose, int at, float u, float v, int light, int overlay, float nx, float nz, int soil) {
        float blotch = soil >= MAX_SOIL_SHOWN ? Math.clamp(1 - (GRID[at + 3] - CUP_BASE) / .07f, 0f, 1f) : 0;
        b.addVertex(pose.pose(), GRID[at], GRID[at + 1], GRID[at + 2])
                .setColor(Math.round(255 - 70 * blotch), Math.round(255 - 95 * blotch), Math.round(255 - 125 * blotch), 255)
                .setUv(u, v).setOverlay(overlay).setLight(light).setNormal(pose, nx, 0, nz);
    }

    /** Hull radius at height fraction f and a continuous angle: bilinear in the NEAR table between height cells and neighbouring bins. */
    private static float radiusAround(float f, float angle) {
        double wrapped = Math.atan2(Math.sin(angle), Math.cos(angle));
        float fb = (float) ((wrapped + Math.PI) / (2 * Math.PI) * BINS - .5), fc = Math.clamp(f * CELLS - .5f, 0f, NEAR.length - 1f);
        int lo = Math.floorMod((int) Math.floor(fb), BINS), hi = (lo + 1) % BINS, c0 = (int) fc, c1 = Math.min(NEAR.length - 1, c0 + 1);
        float t = fb - (float) Math.floor(fb), q = fc - c0;
        float r0 = NEAR[c0][lo] + (NEAR[c0][hi] - NEAR[c0][lo]) * t, r1 = NEAR[c1][lo] + (NEAR[c1][hi] - NEAR[c1][lo]) * t;
        return r0 + (r1 - r0) * q;
    }

    /** Extra outward lift along a leaf (0 stem .. 1 tip): none for the first 70%, then easing out to TIP_LIFT at the tip. */
    private static float tipLift(float t) {
        float q = Math.clamp((t - .7f) / .3f, 0f, 1f);
        return TIP_LIFT * q * q * (3 - 2 * q);
    }

    /** Height fraction up to which a soiled wrap shows the stained leaf: none when clean, then .08, .14, .20 of the body height. */
    private static float soilHeight(int soil) { return soil <= 0 ? -1 : .02f + .06f * Math.min(3, soil); }

    /** One vertex on ring k at a continuous angle: radius and height interpolated between the two neighbouring bins. */
    private static void ringVertex(VertexConsumer b, PoseStack.Pose pose, int r, int g, int bl, float u, float v, int light, int overlay,
                                   float nx, float nz, float cx, float cz, float minY, float h, float angle, int k, float lift) {
        double wrapped = Math.atan2(Math.sin(angle), Math.cos(angle));
        float f = (float) ((wrapped + Math.PI) / (2 * Math.PI) * BINS - .5);
        int lo = Math.floorMod((int) Math.floor(f), BINS), hi = (lo + 1) % BINS;
        float t = f - (float) Math.floor(f);
        float radius = RING[k][lo] + (RING[k][hi] - RING[k][lo]) * t + lift;
        float top = TOP[lo] + (TOP[hi] - TOP[lo]) * t;
        float y = minY + (CUP_BASE + (top - CUP_BASE) * k / (RINGS - 1)) * h;
        b.addVertex(pose.pose(), cx + (float) Math.cos(wrapped) * radius, y, cz + (float) Math.sin(wrapped) * radius).setColor(r, g, bl, 255)
                .setUv(u, v).setOverlay(overlay).setLight(light).setNormal(pose, nx, 0, nz);
    }

    /**
     * The opened wrap laid flat on the ground (model y 0, so it stays down while the body squashes or lifts): two leaves crossing under
     * the child, each reaching a little past the body on both sides, the support leaf a parent works on.
     */
    private static void groundLeaves(VertexConsumer b, PoseStack.Pose pose, float cx, float cz, float h, int light, int overlay) {
        float reach = 0;
        for (int i = 0; i < BINS; i++) reach = Math.max(reach, radiusNear(.2f, i));
        if (reach <= 0) return;
        float length = reach * 1.35f, width = reach * .75f, y = .004f * h;
        for (int n = 0; n < GROUND.length; n++) {
            float a = GROUND[n][0], row = GROUND[n][1], lay = y + n * .002f * h;
            float dx = (float) Math.cos(a), dz = (float) Math.sin(a), px = -dz, pz = dx;
            textured(b, pose, WHITE, light, overlay, 0, 1, 0, 0, 1, row + CELL_V, row,
                    cx - dx * length - px * width, lay, cz - dz * length - pz * width, cx + dx * length - px * width, lay, cz + dz * length - pz * width,
                    cx + dx * length + px * width, lay, cz + dz * length + pz * width, cx - dx * length + px * width, lay, cz - dz * length + pz * width);
        }
    }

    /** Untextured flat colour on the white care_wear texel. */
    private static void flat(VertexConsumer b, PoseStack.Pose pose, int[] c, int light, int overlay, float nx, float ny, float nz,
                             float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3) {
        textured(b, pose, c, light, overlay, nx, ny, nz, .25f, .25f, .25f, .25f, x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3);
    }

    /** A quad whose first two corners are the bottom edge (u0..u1 at v0) and last two the top edge (u1..u0 at v1). */
    private static void textured(VertexConsumer b, PoseStack.Pose pose, int[] c, int light, int overlay, float nx, float ny, float nz,
                                 float u0, float u1, float v0, float v1,
                                 float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3) {
        vertex(b, pose, c, u0, v0, light, overlay, nx, ny, nz, x0, y0, z0);
        vertex(b, pose, c, u1, v0, light, overlay, nx, ny, nz, x1, y1, z1);
        vertex(b, pose, c, u1, v1, light, overlay, nx, ny, nz, x2, y2, z2);
        vertex(b, pose, c, u0, v1, light, overlay, nx, ny, nz, x3, y3, z3);
    }
    private static void vertex(VertexConsumer b, PoseStack.Pose pose, int[] c, float u, float v, int light, int overlay, float nx, float ny, float nz,
                               float x, float y, float z) {
        b.addVertex(pose.pose(), x, y, z).setColor(c[0], c[1], c[2], 255).setUv(u, v).setOverlay(overlay).setLight(light).setNormal(pose, nx, ny, nz);
    }
}
