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
 * all follow squash, stretch and growth of whatever model and clip is being drawn.
 *
 * <p>The harness is a low belt just above the ground contact with the lead ring on the back. The cloth swaddle is a soft bag over the lower
 * body, outside the hanging back hair, whose rim is high at the back and dips under the mouth at the front; each of the eight styles of
 * {@link io.github.longshou.mcyukkuri.item.SwaddleStyle} picks its fabric tile, colours, rim (rolled cuff, lace frill or plain hem), a
 * crossover front flap with a bow, and fixtures (back initial, tiger mark, rubber sole with strap and pouch, pompoms). The leaf swaddle is
 * one leaf worn like a diaper: petiole and blade base at the anus on the back, down and under the body, the tip up over the front outlet
 * below the mouth; while a parent has opened it for care it lies flat on the ground under the child. Presentation only; the wear state
 * comes from the server. The model's front is -Z, so the back is +Z. Client only.</p>
 */
final class CareWearRenderer {
    private static final ResourceLocation TEXTURE = MCYukkuri.id("textures/misc/care_wear.png");
    /** One 32x32 leaf (petiole at u 0, tip at u 1, midrib across the middle), clean in the upper half, stool-stained in the lower. */
    private static final ResourceLocation LEAF = MCYukkuri.id("textures/misc/swaddle_leaf_wear.png");
    /** 64x64 cutout sheet of the cloth's non-repeating parts (art/props/pet-goods, drafts/care-props/wear.py TRIM). */
    private static final ResourceLocation TRIM = MCYukkuri.id("textures/misc/swaddle_trim.png");
    private static final int BINS = 24, CELLS = 32, HARNESS = 1, SWADDLE = 2, LEAF_WORN = 4, LEAF_OPEN = 8, RINGS = 5;
    /** Harness belt centre and half height as fractions of the body height: low, under the mouth line, never across the face. */
    private static final float BELT_AT = .14f, BELT_HALF = .045f;
    /** Lowest edge of every shell, as a fraction of the body height. */
    private static final float CUP_BASE = .015f;
    /** Nothing worn rises above this height fraction at the front, so the mouth stays free; a front bow is centred no higher than BOW_AT. */
    private static final float MOUTH_GUARD = .22f, BOW_AT = .11f;
    /** Height window (fraction of the body height) of radiusNear's fallback, and the height below which radiusLow does without it. */
    private static final float NEAR_WINDOW = .05f, LOW = .1f;
    /** Fabric texels per model unit (16 units per block), the same density as the placed care props; every fabric tile is 16 texels. */
    private static final float FABRIC_DENSITY = 1;

    // ---- cloth swaddle styles (order of item.SwaddleStyle) ----------------------------------------------------------------
    private static final int ROLL = 0, FRILL = 1, HEM = 2;
    private static final int PLAIN = 0, LETTER = 1, MARK = 2, OUTING = 3, POMPOMS = 4;
    /**
     * One style: body fabric tile and colour, rim fabric and colour, accent (piping under the cuff, crossover edge, lace frill), trim colour
     * (bow, pompoms, letter), rim kind, crossover front flap, fixture, rim height at the back and the front, extra bulge of the bag and
     * the cuff's thickness (fractions of the body height). Colours multiply the near-white tiles; patterned tiles take white.
     */
    private record Style(ResourceLocation fabric, int[] body, ResourceLocation rollFabric, int[] roll, int[] accent, int[] trim, int rim,
                         boolean crossover, int fixture, float back, float front, float puff, float rollSize) {}
    private static final int[] WHITE = {255, 255, 255};
    private static final Style[] STYLES = {
            // knit: cream knit, thick cuff piped in pink, a crossover flap tied with a pink bow (project design)
            new Style(fabric("knit"), rgb(255, 250, 240), fabric("knit"), rgb(255, 250, 240), rgb(242, 176, 198), rgb(240, 136, 168), ROLL, true, PLAIN, .55f, .20f, .04f, .13f),
            // frill: pink bag, white lace frill, red bow at the front (EH30's look only)
            new Style(fabric("cotton"), rgb(246, 176, 196), fabric("cotton"), rgb(246, 176, 196), WHITE, rgb(222, 64, 58), FRILL, false, PLAIN, .52f, .20f, .05f, .07f),
            // letter: red bag with a frilled collar and the wearer's white initial on the back (EH34 p.7-8)
            new Style(fabric("cotton"), rgb(206, 64, 60), fabric("cotton"), rgb(206, 64, 60), WHITE, WHITE, FRILL, false, LETTER, .55f, .20f, .05f, .07f),
            // tiger: striped lower piece with a fluffy cuff and the tiger mark front and back (EH34 p.12-14; the eared hood is not made)
            new Style(fabric("tiger"), WHITE, fabric("fleece"), rgb(255, 244, 226), null, null, ROLL, false, MARK, .55f, .20f, .04f, .13f),
            // outing: light cloth with a navy piped edge, a rubber sole, a strap and a pouch (EH31 p.2)
            new Style(fabric("cotton"), rgb(168, 204, 232), fabric("cotton"), rgb(72, 94, 138), null, null, HEM, false, OUTING, .50f, .20f, .03f, .07f),
            // fleece: thick, higher winter wrap, crossover flap and pompom ties at the sides (N91's cold-season wear; the shape is project design)
            new Style(fabric("fleece"), rgb(226, 206, 176), fabric("fleece"), rgb(242, 228, 206), rgb(242, 228, 206), rgb(198, 96, 78), ROLL, true, POMPOMS, .60f, .19f, .07f, .15f),
            // gingham: blue checks, white cuff piped in navy, crossover flap and navy bow (project design)
            new Style(fabric("gingham"), WHITE, fabric("cotton"), WHITE, rgb(70, 96, 170), rgb(70, 96, 170), ROLL, true, PLAIN, .55f, .20f, .04f, .12f),
            // floral: cream with small flowers, pale green cuff piped in green, crossover flap and green bow (project design)
            new Style(fabric("floral"), WHITE, fabric("cotton"), rgb(214, 236, 200), rgb(120, 180, 110), rgb(120, 180, 110), ROLL, true, PLAIN, .55f, .20f, .04f, .12f)};
    /**
     * Every bag stands SHELL_OUT of the body height clear of it (less at the ground, so the bottom rounds in) plus the style's bulge, which is
     * fullest in the lower half: a padded wrap, not a sleeve.
     */
    private static final float SHELL_OUT = .12f;
    /**
     * The crossover flap: one panel wrapped round from the back left (SEAM_AT) over the left flank and across the front; its leading edge
     * runs diagonally from low at the front right up to the rim beside the left cheek (SEAM_SLANT of the sweep back at the top), where
     * the bow ties it, and stands FLAP_LIFT proud of the bag.
     */
    private static final float SEAM_AT = -4.0f, SEAM_SWEEP = 3.03f, SEAM_SLANT = .53f, FLAP_LIFT = .08f;
    /** Cross-section of the cuff tube (radians from outwards, upwards positive), and how many of its first segments take the accent. */
    private static final float[] TUBE = {-1.75f, -1.05f, -.45f, .15f, .75f, 1.35f, 2.4f};
    private static final int PIPING = 2;
    /** Which way round from its leading edge the flap lies (+1: towards larger angles). */
    private static final float FLAP_SIDE = Math.signum(SEAM_SWEEP) * -1;
    /** Trim sheet rectangles in texels of the 64x64 sheet: {x, y, w, h}. */
    private static final float[] FRILL_UV = {0, 0, 16, 8}, BOW_UV = {16, 0, 16, 12}, MARK_UV = {32, 0, 16, 16}, POUCH_UV = {48, 0, 16, 16},
            POMPOM_UV = {0, 8, 8, 8}, SOLE_UV = {8, 8, 8, 8}, STRAP_UV = {0, 16, 8, 8};
    /** Letter glyphs: 8x8 cells, eight per row, A..Z from this corner. */
    private static final int LETTERS_X = 0, LETTERS_Y = 32;

    // ---- leaf swaddle --------------------------------------------------------------------------------------------------
    /**
     * The leaf laid as a diaper, as fractions of the body height: the blade base sits at the anus on the back (LEAF_BACK), the petiole
     * rises STALK above it and stands STALK_OUT off the body; the blade runs down the back, under the body and up the front to LEAF_FRONT,
     * over the front outlet and under the mouth. LEAF_HALF is the half width of the leaf texture across the body; the margins curl out by
     * CURL and rise by RISE towards the sides, so the leaf cups the bottom and shows on the flanks. U_* split the texture along the leaf:
     * petiole, blade on the back, under the body, blade on the front (its broadest part low on the front).
     */
    private static final float LEAF_BACK = .32f, LEAF_FRONT = .22f, STALK = .14f, STALK_OUT = .04f, LEAF_HALF = 1.05f, CURL = .01f, RISE = .22f;
    private static final float U_STALK = 5 / 32f, U_UNDER = .40f, U_FRONT = .50f;
    /** Rows of the leaf grid per part (petiole, back, under, front) and columns across; the widest angle a margin wraps round the body. */
    private static final int STALK_ROWS = 2, BACK_ROWS = 5, UNDER_ROWS = 3, FRONT_ROWS = 5, ACROSS = 8;
    private static final int LEAF_ROWS = STALK_ROWS + BACK_ROWS + UNDER_ROWS + FRONT_ROWS;
    private static final float MAX_WRAP = 1.6f;
    /** Texture v height of the leaf cell, and the offset from the clean leaf to its stool-stained copy. */
    private static final float CELL_V = .5f, STAINED_V = .5f;
    /** Stool loads the wear bits can show; at this many the base gets its brown blotch. */
    private static final int MAX_SOIL_SHOWN = 3;

    // colours: harness flat (palette mint 2, gold 2); leaves keep their own texture colour
    private static final int[] STRAP = {98, 137, 127}, BRASS = {243, 193, 105};
    // client render thread scratch, reused every frame: radius profile (largest radius and its height fraction per height cell and angle bin) and shell rings
    private static final float[] CELL_R = new float[CELLS * BINS], CELL_F = new float[CELLS * BINS], CELL_A = new float[CELLS * BINS];
    /** True angle of the sample radiusAt / radiusNear / radiusLow last returned, and the convex-hull scratch (render thread). */
    private static float sampleAngle;
    private static final float[] ANG = new float[BINS], HX = new float[BINS], HZ = new float[BINS], HULL_X = new float[2 * BINS + 1], HULL_Z = new float[2 * BINS + 1];
    private static final float[][] RING = new float[RINGS][BINS];
    private static final float[] BELT_LO = new float[BINS], BELT_HI = new float[BINS], TOP = new float[BINS], COPY = new float[BINS], ARC = new float[BINS + 1];
    private static final float[] GRID = new float[(LEAF_ROWS + 1) * (ACROSS + 1) * 4];
    /** Hull radius per height cell (lower half of the body) and bin, rebuilt for each leaf wrap. */
    private static final float[][] NEAR = new float[CELLS / 2][BINS];
    /** Cloth surface of the current swaddle: inset, shell offset and bulge (model units), the angle leafPoint last used, point scratch. */
    private static float clothInset, clothShell, clothPuff, leafAngle;
    private static final float[] PT = new float[3], PT2 = new float[3];

    /** Direction of each bin centre. */
    private static final float[] COS = new float[BINS], SIN = new float[BINS];
    static { for (int i = 0; i < BINS; i++) { COS[i] = (float) Math.cos(angle(i)); SIN[i] = (float) Math.sin(angle(i)); } }

    private CareWearRenderer() {}

    private static ResourceLocation fabric(String name) { return MCYukkuri.id("textures/misc/swaddle/" + name + ".png"); }
    private static int[] rgb(int r, int g, int b) { return new int[]{r, g, b}; }

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
        // Heights come from the body alone. The cloth hugs the body and the hanging hair falls over it; the leaf's radii also take the hair
        // hanging at the back, so the leaf closes outside it. One buffer at a time: a batched source may flush the previous one when another
        // texture is requested.
        if (cloth) profile(pts, body, cx, cz, minY, h);
        if (leaf) profile(pts, all, cx, cz, minY, h);
        if (cloth) swaddle(buffers, last, STYLES[wear >> 6 & 7], initial(model.id()), cx, cz, minY, h, out, light, overlay);
        if (leaf && (wear & LEAF_OPEN) != 0) groundLeaf(buffers.getBuffer(RenderType.entityCutoutNoCull(LEAF)), last, cx, cz, h, light, overlay);
        else if (leaf) leafWrap(buffers.getBuffer(RenderType.entityCutoutNoCull(LEAF)), last, cx, cz, minY, h, out, wear >> 4 & 3, light, overlay);
        boolean wrapped = cloth || leaf && (wear & LEAF_OPEN) == 0;
        // The harness follows the body itself, so its back ring stays where the lead attaches (YukkuriInteractions.getLeashOffset).
        if ((wear & HARNESS) != 0) profile(pts, body, cx, cz, minY, h);
        if ((wear & HARNESS) != 0) harness(buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE)), last, cx, cz, minY, h,
                wrapped ? out + .03f * h : .03f * h, light, overlay);
    }

    /** Letter index (0 = A) of the model's initial for the letter style; a house form takes its species' letter. */
    private static int initial(String id) {
        String name = id == null ? "" : id.startsWith("house_") ? id.substring(6) : id;
        int c = name.isEmpty() ? 'Y' : Character.toUpperCase(name.charAt(0));
        return c < 'A' || c > 'Z' ? 'Y' - 'A' : c - 'A';
    }

    private static int bin(float dx, float dz) {
        double angle = Math.atan2(dz, dx) + Math.PI;
        return Math.min(BINS - 1, Math.max(0, (int) (angle / (2 * Math.PI) * BINS)));
    }
    /** Angle of the centre of a bin; the front of the model (-Z) is at -pi/2. */
    private static float angle(int bin) { return (float) (-Math.PI + 2 * Math.PI * (bin + .5) / BINS); }

    /**
     * Largest horizontal radius per angle bin in each of CELLS height cells (first `length` floats of pts), remembering the height fraction
     * and the true angle at which it occurred.
     */
    private static void profile(float[] pts, int length, float cx, float cz, float minY, float h) {
        Arrays.fill(CELL_R, 0);
        for (int i = 0; i < length; i += 3) {
            float dx = pts[i] - cx, dz = pts[i + 2] - cz, f = (pts[i + 1] - minY) / h;
            if (f < 0) continue; // hair tips hanging below the body's lowest point would otherwise widen the base cell
            float r = (float) Math.sqrt(dx * dx + dz * dz);
            int at = Math.min(CELLS - 1, Math.max(0, (int) (f * CELLS))) * BINS + bin(dx, dz);
            if (r > CELL_R[at]) { CELL_R[at] = r; CELL_F[at] = f; CELL_A[at] = (float) Math.atan2(dz, dx); }
        }
    }

    /**
     * Body radius at height fraction f in a bin: linear between the nearest samples below and above, the single side at the ends, 0 when
     * the bin has no vertex. The samples' true angles are interpolated the same way into sampleAngle.
     */
    private static float radiusAt(float f, int bin) {
        float loF = -1, loR = 0, hiF = 2, hiR = 0, loA = 0, hiA = 0, centre = angle(bin);
        for (int c = 0; c < CELLS; c++) {
            int at = c * BINS + bin;
            float r = CELL_R[at];
            if (r <= 0) continue;
            float cf = CELL_F[at], off = offset(CELL_A[at], centre);
            if (cf <= f) { if (cf > loF) { loF = cf; loR = r; loA = off; } }
            else if (cf < hiF) { hiF = cf; hiR = r; hiA = off; }
        }
        if (loF < 0) { sampleAngle = centre + hiA; return hiF > 1 ? 0 : hiR; }
        if (hiF > 1) { sampleAngle = centre + loA; return loR; }
        float t = (f - loF) / (hiF - loF);
        sampleAngle = centre + loA + (hiA - loA) * t;
        return loR + (hiR - loR) * t;
    }

    /** Signed angle from `centre` to `a`, wrapped into -pi..pi. */
    private static float offset(float a, float centre) { return (float) Math.atan2(Math.sin(a - centre), Math.cos(a - centre)); }

    /** radiusAt in this bin and its two neighbours, the largest kept (with its sampleAngle). */
    private static float radiusAround3(float f, int bin) {
        float best = radiusAt(f, bin), angle = sampleAngle;
        for (int d = -1; d <= 1; d += 2) {
            float r = radiusAt(f, (bin + d + BINS) % BINS);
            if (r > best) { best = r; angle = sampleAngle; }
        }
        sampleAngle = angle;
        return best;
    }

    /**
     * The largest of radiusAt in this bin and its two neighbours, and of any sample near this height in them. A bin whose vertex rings
     * fall far apart in height interpolates a chord that cuts into the round body (dents at the back and the low front of the rounded
     * forms); the neighbours' and nearby samples close it.
     */
    private static float radiusNear(float f, int bin) {
        float r = radiusAround3(f, bin), angle = sampleAngle;
        // Sparse vertex rings leave whole bins without a sample near this height (the rounded bodies' low front); the largest radius
        // within a twentieth of the body height above or below, in the same three bins, keeps the shell outside the real surface there.
        int lo = Math.max(0, (int) ((f - NEAR_WINDOW) * CELLS)), hi = Math.min(CELLS - 1, (int) ((f + NEAR_WINDOW) * CELLS));
        for (int c = lo; c <= hi; c++) for (int d = -1; d <= 1; d++) {
            int at = c * BINS + (bin + d + BINS) % BINS;
            if (CELL_R[at] > r) { r = CELL_R[at]; angle = CELL_A[at]; }
        }
        sampleAngle = angle;
        return r;
    }

    /**
     * radiusNear, except near the ground: there the body rounds in under itself, and the height window would borrow the wider ring above
     * and push a shell out past the bottom edge, so below LOW the radius is interpolated in the bin and its neighbours only.
     */
    private static float radiusLow(float f, int bin) {
        return f >= LOW ? radiusNear(f, bin) : radiusAround3(f, bin);
    }

    /**
     * Sets radius[i] to the distance, along bin i's centre direction, to the convex hull of the samples at their true angles (ANG), so a
     * flat face that has vertices only at its edges is spanned exactly, neither cut into nor bulged past. Falls back to fill and
     * convexify with fewer than three samples; false when there is none.
     */
    private static boolean hull(float[] radius, float[] ang) {
        int n = 0;
        for (int i = 0; i < BINS; i++) if (radius[i] > 0) { HX[n] = (float) Math.cos(ang[i]) * radius[i]; HZ[n] = (float) Math.sin(ang[i]) * radius[i]; n++; }
        if (n < 3) { if (!fill(radius)) return false; convexify(radius); return true; }
        for (int i = 1; i < n; i++) for (int k = i; k > 0 && (HX[k] < HX[k - 1] || HX[k] == HX[k - 1] && HZ[k] < HZ[k - 1]); k--) {
            float x = HX[k], z = HZ[k]; HX[k] = HX[k - 1]; HZ[k] = HZ[k - 1]; HX[k - 1] = x; HZ[k - 1] = z;
        }
        int m = 0;
        for (int pass = 0; pass < 2; pass++) {
            int start = m;
            for (int s = 0; s < n; s++) {
                int i = pass == 0 ? s : n - 1 - s;
                while (m >= start + 2 && (HULL_X[m - 1] - HULL_X[m - 2]) * (HZ[i] - HULL_Z[m - 2]) - (HULL_Z[m - 1] - HULL_Z[m - 2]) * (HX[i] - HULL_X[m - 2]) <= 0) m--;
                HULL_X[m] = HX[i]; HULL_Z[m] = HZ[i]; m++;
            }
            m--;
        }
        for (int i = 0; i < BINS; i++) {
            float dx = COS[i], dz = SIN[i], best = 0;
            for (int e = 0; e < m; e++) {
                float px = HULL_X[e], pz = HULL_Z[e], ex = HULL_X[(e + 1) % m] - px, ez = HULL_Z[(e + 1) % m] - pz, den = dx * ez - dz * ex;
                if (Math.abs(den) < 1e-9f) continue;
                float t = (px * ez - pz * ex) / den, s = (px * dz - pz * dx) / den;
                if (s >= -1e-4f && s <= 1 + 1e-4f && t > best) best = t;
            }
            if (best > 0) radius[i] = best;
        }
        return true;
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

    /** Top edge of a cup at an angle: high at the back and sides, low at the front (-pi/2), rising to the cheeks beside the face. */
    private static float rim(float angle, float front, float back) {
        float away = Math.abs((float) Math.atan2(Math.sin(angle + Math.PI / 2), Math.cos(angle + Math.PI / 2)));
        float s = Math.clamp((away - .3f) / 1.25f, 0f, 1f);
        return front + (back - front) * s * s * (3 - 2 * s);
    }

    /** RING[k] = body radius per bin at the k-th of RINGS heights between the base and TOP[bin]; false when the profile is empty. */
    private static boolean rings(float front, float back) {
        for (int i = 0; i < BINS; i++) TOP[i] = rim(angle(i), front, back);
        for (int k = 0; k < RINGS; k++) {
            float u = k / (float) (RINGS - 1);
            for (int i = 0; i < BINS; i++) { RING[k][i] = radiusLow(CUP_BASE + (TOP[i] - CUP_BASE) * u, i); ANG[i] = sampleAngle; }
            if (!hull(RING[k], ANG)) return false;
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

    // ---- cloth swaddle ---------------------------------------------------------------------------------------------------

    /** How far the bag stands off the body at ring fraction u (0 base .. 1 rim): drawn in at the ground, full above, bulging in the lower half. */
    private static float puffAt(float u) {
        float t = Math.min(1, u / .35f);
        return clothShell * (.45f + .55f * t * t * (3 - 2 * t)) + clothPuff * (float) Math.sin(Math.PI * Math.min(1, u / .7f));
    }

    /** Rim height fraction at a continuous angle, between the neighbouring bins. */
    private static float topAt(float angle) {
        double w = Math.atan2(Math.sin(angle), Math.cos(angle));
        float fb = (float) ((w + Math.PI) / (2 * Math.PI) * BINS - .5);
        int lo = Math.floorMod((int) Math.floor(fb), BINS), hi = (lo + 1) % BINS;
        float t = fb - (float) Math.floor(fb);
        return TOP[lo] + (TOP[hi] - TOP[lo]) * t;
    }

    /**
     * A point on the cloth surface at height fraction f (clamped between the base and the rim at that angle) and a continuous angle,
     * pushed out by `lift`: rings and bins interpolated, plus the cloth's inset and puff. Written into `out`.
     */
    private static float[] clothPoint(float f, float angle, float lift, float cx, float cz, float minY, float h, float[] out) {
        double w = Math.atan2(Math.sin(angle), Math.cos(angle));
        float fb = (float) ((w + Math.PI) / (2 * Math.PI) * BINS - .5);
        int lo = Math.floorMod((int) Math.floor(fb), BINS), hi = (lo + 1) % BINS;
        float t = fb - (float) Math.floor(fb), top = TOP[lo] + (TOP[hi] - TOP[lo]) * t;
        float u = Math.clamp((f - CUP_BASE) / Math.max(1e-4f, top - CUP_BASE), 0f, 1f), kf = u * (RINGS - 1);
        int k = Math.min(RINGS - 2, (int) kf);
        float q = kf - k;
        float r0 = RING[k][lo] + (RING[k][hi] - RING[k][lo]) * t, r1 = RING[k + 1][lo] + (RING[k + 1][hi] - RING[k + 1][lo]) * t;
        float r = r0 + (r1 - r0) * q + clothInset + puffAt(u) + lift;
        out[0] = cx + (float) Math.cos(w) * r; out[1] = minY + (CUP_BASE + (top - CUP_BASE) * u) * h; out[2] = cz + (float) Math.sin(w) * r;
        return out;
    }

    /** Height fraction of ring k at a continuous angle. */
    private static float ringF(float angle, int k) { return CUP_BASE + (topAt(angle) - CUP_BASE) * k / (RINGS - 1); }

    /** Angle of the crossover flap's leading edge on ring k: furthest towards the front at the bottom, slanting back towards the top. */
    private static float seamLead(int k) { return SEAM_AT + SEAM_SWEEP * (1 - SEAM_SLANT * k / (RINGS - 1)); }

    /**
     * The cloth swaddle in three passes (one texture each): the padded bag, the shadow under the cuff and the crossover flap with its edge
     * and shadow in the style's fabric; the round cuff tube (piped with the accent), and the flap's hemmed edge, in the rim fabric; then the
     * trim sheet's lace frill, bow and fixtures. Every ring radius comes from the profile at that ring's height plus the bag's offset, so
     * the bag follows every form and growth stage; the fabric runs around the body by arc length (rounded to whole tiles so the seam
     * closes) and up by height at FABRIC_DENSITY texels per model unit.
     */
    private static void swaddle(MultiBufferSource buffers, PoseStack.Pose pose, Style style, int letter, float cx, float cz, float minY, float h,
                                float out, int light, int overlay) {
        if (!rings(style.front, style.back)) return;
        clothInset = out * .6f; clothShell = SHELL_OUT * h; clothPuff = style.puff * h;
        // arc length around the middle ring sets u, so every ring shares the same columns of the pattern
        ARC[0] = 0;
        for (int i = 0; i < BINS; i++) {
            float[] a = clothPoint(ringF(angle(i), 2), angle(i), 0, cx, cz, minY, h, PT), p0x = {a[0], a[2]};
            float[] c = clothPoint(ringF(angle((i + 1) % BINS), 2), angle((i + 1) % BINS), 0, cx, cz, minY, h, PT2);
            ARC[i + 1] = ARC[i] + (float) Math.hypot(c[0] - p0x[0], c[2] - p0x[1]);
        }
        float tiles = Math.max(1, Math.round(ARC[BINS] * 16 * FABRIC_DENSITY / 16)), uScale = tiles / Math.max(1e-4f, ARC[BINS]), vScale = 16 * FABRIC_DENSITY / 16;
        float roll = style.rollSize * h, under = style.rollSize * .7f;
        int[] shade = {style.body[0] * 7 / 10, style.body[1] * 7 / 10, style.body[2] * 7 / 10};
        var b = buffers.getBuffer(RenderType.entityCutoutNoCull(style.fabric));
        for (int i = 0; i < BINS; i++) {
            int j = (i + 1) % BINS;
            float ai = angle(i), aj = angle(j), am = (ai + aj) / 2 + (j == 0 ? (float) Math.PI : 0), nx = (float) Math.cos(am), nz = (float) Math.sin(am);
            float u0 = ARC[i] * uScale, u1 = ARC[i + 1] * uScale;
            for (int k = 0; k + 1 < RINGS; k++)
                clothQuad(b, pose, style.body, light, overlay, nx, 0, nz, cx, cz, minY, h, vScale,
                        ai, ringF(ai, k), u0, aj, ringF(aj, k), u1, aj, ringF(aj, k + 1), u1, ai, ringF(ai, k + 1), u0, 0, 0);
            // the cuff's shadow on the bag just under it
            clothQuad(b, pose, shade, light, overlay, nx, 0, nz, cx, cz, minY, h, vScale, ai, TOP[i] - under - .04f, u0, aj, TOP[j] - under - .04f, u1,
                    aj, TOP[j] - under, u1, ai, TOP[i] - under, u0, .002f * h, .002f * h);
        }
        // the crossover flap: lapped across the front from the left side, standing proud of the bag and thinning to it at its far end
        float lift = FLAP_LIFT * h, edge = .16f, meanR = ARC[BINS] / (float) (2 * Math.PI);
        if (style.crossover) {
            int steps = 8;
            for (int k = 0; k + 1 < RINGS; k++) {
                float leadA = seamLead(k), leadB = seamLead(k + 1);
                for (int s = 0; s < steps; s++) {
                    float a0 = SEAM_AT + (leadA - SEAM_AT) * s / steps, a1 = SEAM_AT + (leadA - SEAM_AT) * (s + 1) / steps;
                    float c0 = SEAM_AT + (leadB - SEAM_AT) * s / steps, c1 = SEAM_AT + (leadB - SEAM_AT) * (s + 1) / steps;
                    float am = (a0 + a1 + c0 + c1) / 4, la = lift * Math.min(1, s / 3f), lb = lift * Math.min(1, (s + 1) / 3f);
                    clothQuad(b, pose, style.body, light, overlay, (float) Math.cos(am), 0, (float) Math.sin(am), cx, cz, minY, h, vScale,
                            a0, ringF(a0, k), .37f + a0 * meanR * uScale, a1, ringF(a1, k), .37f + a1 * meanR * uScale,
                            c1, ringF(c1, k + 1), .37f + c1 * meanR * uScale, c0, ringF(c0, k + 1), .37f + c0 * meanR * uScale, la, lb);
                }
                // the flap's cut edge down to the bag, and the shadow it casts past the edge
                int[] side = {style.body[0] * 3 / 4, style.body[1] * 3 / 4, style.body[2] * 3 / 4};
                float am = (leadA + leadB) / 2 - FLAP_SIDE * .2f;
                clothQuad(b, pose, side, light, overlay, (float) Math.cos(am), 0, (float) Math.sin(am), cx, cz, minY, h, vScale,
                        leadA, ringF(leadA, k), 0, leadA, ringF(leadA, k), .1f, leadB, ringF(leadB, k + 1), .1f, leadB, ringF(leadB, k + 1), 0, lift, .001f * h);
                clothQuad(b, pose, shade, light, overlay, (float) Math.cos(am), 0, (float) Math.sin(am), cx, cz, minY, h, vScale,
                        leadA, ringF(leadA, k), 0, leadA - FLAP_SIDE * .18f, ringF(leadA - FLAP_SIDE * .18f, k), .1f,
                        leadB - FLAP_SIDE * .18f, ringF(leadB - FLAP_SIDE * .18f, k + 1), .1f,
                        leadB, ringF(leadB, k + 1), 0, .002f * h, .002f * h);
            }
        }
        // the cuff: a round tube along the rim, its lower segments piped with the accent; then the flap's hemmed edge
        b = buffers.getBuffer(RenderType.entityCutoutNoCull(style.rollFabric));
        float tube = roll * .5f;
        for (int i = 0; i < BINS; i++) {
            int j = (i + 1) % BINS;
            float ai = angle(i), aj = angle(j), am = (ai + aj) / 2 + (j == 0 ? (float) Math.PI : 0), nx = (float) Math.cos(am), nz = (float) Math.sin(am);
            float u0 = ARC[i] * uScale, u1 = ARC[i + 1] * uScale;
            float[] p = clothPoint(TOP[i], ai, 0, cx, cz, minY, h, PT), q = clothPoint(TOP[j], aj, 0, cx, cz, minY, h, PT2);
            float ri = (float) Math.hypot(p[0] - cx, p[2] - cz) + tube, rj = (float) Math.hypot(q[0] - cx, q[2] - cz) + tube;
            float yi = p[1] - .2f * roll, yj = q[1] - .2f * roll;
            for (int t = 0; t + 1 < TUBE.length; t++) {
                float c0 = (float) Math.cos(TUBE[t]), s0 = (float) Math.sin(TUBE[t]), c1 = (float) Math.cos(TUBE[t + 1]), s1 = (float) Math.sin(TUBE[t + 1]);
                float mid = (TUBE[t] + TUBE[t + 1]) / 2, mc = (float) Math.cos(mid);
                int[] tint = t < PIPING && style.rim == ROLL && style.accent != null ? style.accent : style.roll;
                ring(b, pose, tint, light, overlay, nx * mc, (float) Math.sin(mid), nz * mc, u0, u1, t / 8f, (t + 1) / 8f, cx, cz, ai, aj,
                        ri + tube * c0, yi + tube * s0, rj + tube * c0, yj + tube * s0, ri + tube * c1, yi + tube * s1, rj + tube * c1, yj + tube * s1);
            }
        }
        if (style.crossover && style.accent != null) for (int k = 0; k + 1 < RINGS; k++) {
            float leadA = seamLead(k), leadB = seamLead(k + 1), hemLift = lift + .003f * h, nx = (float) Math.cos(leadA), nz = (float) Math.sin(leadA);
            float ea = leadA + FLAP_SIDE * edge, eb = leadB + FLAP_SIDE * edge;
            float[] a0 = clothPoint(ringF(ea, k), ea, hemLift, cx, cz, minY, h, new float[3]), a1 = clothPoint(ringF(leadA, k), leadA, hemLift, cx, cz, minY, h, new float[3]);
            float[] b1 = clothPoint(ringF(leadB, k + 1), leadB, hemLift, cx, cz, minY, h, new float[3]), b0 = clothPoint(ringF(eb, k + 1), eb, hemLift, cx, cz, minY, h, new float[3]);
            quad(b, pose, style.accent, light, overlay, nx, 0, nz, 0, .5f, .2f, .5f, .2f, .6f, 0, .6f, a0, a1, b1, b0);
        }
        // trim: lace frill, bow and fixtures
        b = buffers.getBuffer(RenderType.entityCutoutNoCull(TRIM));
        if (style.rim == FRILL) frill(b, pose, style, cx, cz, minY, h, roll, light, overlay);
        if (style.fixture == PLAIN && style.trim != null) {
            float a = style.crossover ? seamLead(RINGS - 1) + FLAP_SIDE * .15f : (float) (-Math.PI / 2);
            float f = style.crossover ? topAt(a) - under - .08f : BOW_AT, bowLift = style.crossover ? FLAP_LIFT + .02f : .11f;
            bow(b, pose, style.trim, light, overlay, clothPoint(f, a, bowLift * h, cx, cz, minY, h, PT), a, .30f * h, .22f * h);
        }
        switch (style.fixture) {
            case LETTER -> decal(b, pose, style.trim, light, overlay, LETTERS_X + letter % 8 * 8, LETTERS_Y + letter / 8 * 8, 8, 8,
                    (float) Math.PI / 2, .26f, .15f * h, .15f, .008f * h, cx, cz, minY, h);
            case MARK -> {
                decal(b, pose, WHITE, light, overlay, MARK_UV[0], MARK_UV[1], MARK_UV[2], MARK_UV[3], (float) Math.PI / 2, .26f, .15f * h, .15f, .008f * h, cx, cz, minY, h);
                decal(b, pose, WHITE, light, overlay, MARK_UV[0], MARK_UV[1], MARK_UV[2], MARK_UV[3], (float) (-Math.PI / 2), .065f, .055f * h, .055f, .008f * h, cx, cz, minY, h);
            }
            case OUTING -> outing(b, pose, cx, cz, minY, h, light, overlay);
            case POMPOMS -> {
                for (float side : new float[]{-1.15f, 1.15f}) {
                    float a = (float) (-Math.PI / 2) + side;
                    cube(b, pose, style.trim, light, overlay, clothPoint(topAt(a) - under - .06f, a, .06f * h, cx, cz, minY, h, PT), .055f * h, POMPOM_UV);
                }
            }
            default -> { }
        }
    }

    /**
     * One fabric quad between four (angle, height fraction) points on the cloth, its texture u given and v from the height; the first and
     * last corner stand liftA off the bag, the middle two liftB.
     */
    private static void clothQuad(VertexConsumer b, PoseStack.Pose pose, int[] c, int light, int overlay, float nx, float ny, float nz,
                                  float cx, float cz, float minY, float h, float vScale, float a0, float f0, float u0, float a1, float f1, float u1,
                                  float a2, float f2, float u2, float a3, float f3, float u3, float liftA, float liftB) {
        float[] p = PT;
        clothPoint(f0, a0, liftA, cx, cz, minY, h, p); vertex(b, pose, c, u0, -(p[1] - minY) * vScale, light, overlay, nx, ny, nz, p[0], p[1], p[2]);
        clothPoint(f1, a1, liftB, cx, cz, minY, h, p); vertex(b, pose, c, u1, -(p[1] - minY) * vScale, light, overlay, nx, ny, nz, p[0], p[1], p[2]);
        clothPoint(f2, a2, liftB, cx, cz, minY, h, p); vertex(b, pose, c, u2, -(p[1] - minY) * vScale, light, overlay, nx, ny, nz, p[0], p[1], p[2]);
        clothPoint(f3, a3, liftA, cx, cz, minY, h, p); vertex(b, pose, c, u3, -(p[1] - minY) * vScale, light, overlay, nx, ny, nz, p[0], p[1], p[2]);
    }

    /** A rim quad between angles ai and aj: the bottom edge at radii/heights (r0, y0) and the top edge at (r2, y2) of each side. */
    private static void ring(VertexConsumer b, PoseStack.Pose pose, int[] c, int light, int overlay, float nx, float ny, float nz,
                             float u0, float u1, float v0, float v1, float cx, float cz, float ai, float aj,
                             float ri0, float yi0, float rj0, float yj0, float ri1, float yi1, float rj1, float yj1) {
        float ci = (float) Math.cos(ai), si = (float) Math.sin(ai), cj = (float) Math.cos(aj), sj = (float) Math.sin(aj);
        vertex(b, pose, c, u0, v0, light, overlay, nx, ny, nz, cx + ci * ri0, yi0, cz + si * ri0);
        vertex(b, pose, c, u1, v0, light, overlay, nx, ny, nz, cx + cj * rj0, yj0, cz + sj * rj0);
        vertex(b, pose, c, u1, v1, light, overlay, nx, ny, nz, cx + cj * rj1, yj1, cz + sj * rj1);
        vertex(b, pose, c, u0, v1, light, overlay, nx, ny, nz, cx + ci * ri1, yi1, cz + si * ri1);
    }

    /**
     * The lace frill under the cuff tube: one scallop of the trim sheet per angle bin, from under the tube out and down; every other bin
     * stands a little further out so the lace ruffles.
     */
    private static void frill(VertexConsumer b, PoseStack.Pose pose, Style style, float cx, float cz, float minY, float h, float roll, int light, int overlay) {
        float drop = .085f * h, reach = .055f * h, u0 = FRILL_UV[0] / 64, u1 = (FRILL_UV[0] + FRILL_UV[2]) / 64, v0 = FRILL_UV[1] / 64, v1 = (FRILL_UV[1] + FRILL_UV[3]) / 64;
        for (int i = 0; i < BINS; i++) {
            int j = (i + 1) % BINS;
            float ai = angle(i), aj = angle(j), am = (ai + aj) / 2 + (j == 0 ? (float) Math.PI : 0);
            float[] p = clothPoint(TOP[i], ai, 0, cx, cz, minY, h, PT), q = clothPoint(TOP[j], aj, 0, cx, cz, minY, h, PT2);
            float ri = (float) Math.hypot(p[0] - cx, p[2] - cz) + roll * .4f, rj = (float) Math.hypot(q[0] - cx, q[2] - cz) + roll * .4f;
            float yi = p[1] - roll * .7f, yj = q[1] - roll * .7f, oi = reach + (i % 2 == 0 ? .014f : -.004f) * h, oj = reach + (j % 2 == 0 ? .014f : -.004f) * h;
            ring(b, pose, style.accent, light, overlay, (float) Math.cos(am) * .7f, -.7f, (float) Math.sin(am) * .7f, u1, u0, v0, v1, cx, cz, ai, aj,
                    ri, yi, rj, yj, ri + oi, yi - drop, rj + oj, yj - drop);
        }
    }

    /** A ribbon bow standing on the cloth at angle a: two halves turned slightly outwards from the knot, the trim sheet's bow sprite. */
    private static void bow(VertexConsumer b, PoseStack.Pose pose, int[] c, int light, int overlay, float[] at, float a, float w, float hgt) {
        float px = at[0], py = at[1], pz = at[2], nx = (float) Math.cos(a), nz = (float) Math.sin(a), tx = -nz, tz = nx, turn = .3f;
        float cos = (float) Math.cos(turn) * w / 2, sin = (float) Math.sin(turn) * w / 2;
        float u0 = BOW_UV[0] / 64, um = (BOW_UV[0] + BOW_UV[2] / 2) / 64, u1 = (BOW_UV[0] + BOW_UV[2]) / 64, v0 = BOW_UV[1] / 64, v1 = (BOW_UV[1] + BOW_UV[3]) / 64;
        for (int side = -1; side <= 1; side += 2) {
            float ex = px + side * tx * cos + nx * sin, ez = pz + side * tz * cos + nz * sin, ue = side > 0 ? u0 : u1;
            float[] top = {px, py + hgt / 2, pz}, bottom = {px, py - hgt / 2, pz}, etop = {ex, py + hgt / 2, ez}, ebottom = {ex, py - hgt / 2, ez};
            quad(b, pose, c, light, overlay, nx, 0, nz, um, v1, ue, v1, ue, v0, um, v0, bottom, ebottom, etop, top);
        }
    }

    /**
     * A sprite of the trim sheet laid on the cloth: a 4x4 patch centred at angle a0 and height fraction f0, halfW model units wide and
     * halfH of the body height tall to either side, standing `lift` off the cloth. u runs against the angle so the sprite reads unmirrored
     * from outside.
     */
    private static void decal(VertexConsumer b, PoseStack.Pose pose, int[] c, int light, int overlay, float tx, float ty, float tw, float th,
                              float a0, float f0, float halfW, float halfH, float lift, float cx, float cz, float minY, float h) {
        float[] centre = clothPoint(f0, a0, lift, cx, cz, minY, h, PT2);
        float half = halfW / Math.max(1e-4f, (float) Math.hypot(centre[0] - cx, centre[2] - cz));
        int n = 4;
        for (int gi = 0; gi < n; gi++) for (int gj = 0; gj < n; gj++) {
            float aA = a0 - half + 2 * half * gi / n, aB = a0 - half + 2 * half * (gi + 1) / n;
            float fA = f0 + halfH - 2 * halfH * gj / n, fB = f0 + halfH - 2 * halfH * (gj + 1) / n;
            float uA = (tx + tw * (1 - gi / (float) n)) / 64, uB = (tx + tw * (1 - (gi + 1) / (float) n)) / 64;
            float vA = (ty + th * gj / (float) n) / 64, vB = (ty + th * (gj + 1) / (float) n) / 64, am = (aA + aB) / 2;
            float[] p0 = clothPoint(fB, aA, lift, cx, cz, minY, h, new float[3]), p1 = clothPoint(fB, aB, lift, cx, cz, minY, h, new float[3]);
            float[] p2 = clothPoint(fA, aB, lift, cx, cz, minY, h, new float[3]), p3 = clothPoint(fA, aA, lift, cx, cz, minY, h, new float[3]);
            quad(b, pose, c, light, overlay, (float) Math.cos(am), 0, (float) Math.sin(am), uA, vB, uB, vB, uB, vA, uA, vA, p0, p1, p2, p3);
        }
    }

    /** EH31's outing wear: a rubber sole band round the bottom, a strap across the back and a pouch on the right side. */
    private static void outing(VertexConsumer b, PoseStack.Pose pose, float cx, float cz, float minY, float h, int light, int overlay) {
        float soleTop = .10f, out = .016f * h, su0 = SOLE_UV[0] / 64, su1 = (SOLE_UV[0] + SOLE_UV[2]) / 64;
        for (int i = 0; i < BINS; i++) {
            int j = (i + 1) % BINS;
            float ai = angle(i), aj = angle(j), am = (ai + aj) / 2 + (j == 0 ? (float) Math.PI : 0), nx = (float) Math.cos(am), nz = (float) Math.sin(am);
            float[] lo0 = clothPoint(CUP_BASE, ai, out, cx, cz, minY, h, new float[3]), lo1 = clothPoint(CUP_BASE, aj, out, cx, cz, minY, h, new float[3]);
            float[] hi0 = clothPoint(soleTop, ai, out, cx, cz, minY, h, new float[3]), hi1 = clothPoint(soleTop, aj, out, cx, cz, minY, h, new float[3]);
            float[] in0 = clothPoint(soleTop, ai, 0, cx, cz, minY, h, new float[3]), in1 = clothPoint(soleTop, aj, 0, cx, cz, minY, h, new float[3]);
            quad(b, pose, WHITE, light, overlay, nx, 0, nz, su1, (SOLE_UV[1] + 8) / 64, su0, (SOLE_UV[1] + 8) / 64, su0, (SOLE_UV[1] + 1) / 64, su1, (SOLE_UV[1] + 1) / 64, lo0, lo1, hi1, hi0);
            quad(b, pose, WHITE, light, overlay, 0, 1, 0, su1, (SOLE_UV[1] + 1) / 64, su0, (SOLE_UV[1] + 1) / 64, su0, SOLE_UV[1] / 64, su1, SOLE_UV[1] / 64, hi0, hi1, in1, in0);
        }
        // the strap: from the pouch's top on the right, round the back, up to the rim at the front left
        float start = -.25f, end = start + 4.25f, steps = 16, half = .035f;
        for (int s = 0; s < steps; s++) {
            float a0 = start + (end - start) * s / steps, a1 = start + (end - start) * (s + 1) / steps;
            float f0 = strapF(a0, start, end), f1 = strapF(a1, start, end), am = (a0 + a1) / 2;
            float[] p0 = clothPoint(f0 - half, a0, .012f * h, cx, cz, minY, h, new float[3]), p1 = clothPoint(f1 - half, a1, .012f * h, cx, cz, minY, h, new float[3]);
            float[] p2 = clothPoint(f1 + half, a1, .012f * h, cx, cz, minY, h, new float[3]), p3 = clothPoint(f0 + half, a0, .012f * h, cx, cz, minY, h, new float[3]);
            float u0 = STRAP_UV[0] / 64, u1 = (STRAP_UV[0] + STRAP_UV[2]) / 64, v0 = STRAP_UV[1] / 64, v1 = (STRAP_UV[1] + STRAP_UV[3]) / 64;
            quad(b, pose, WHITE, light, overlay, (float) Math.cos(am), 0, (float) Math.sin(am), u0, v0, u0, v1, u1, v1, u1, v0, p0, p1, p2, p3);
        }
        decal(b, pose, WHITE, light, overlay, POUCH_UV[0], POUCH_UV[1], POUCH_UV[2], POUCH_UV[3], start, .22f, .14f * h, .14f, .03f * h, cx, cz, minY, h);
    }

    /** Height fraction of the outing strap's middle at angle a: from the pouch top, sagging across the back, up to the rim at the end. */
    private static float strapF(float a, float start, float end) {
        float t = (a - start) / (end - start), rim = topAt(end) - .03f;
        return .34f + (rim - .34f) * t - .06f * (float) Math.sin(Math.PI * t);
    }

    /** A small axis-aligned cube centred at `at` with the same sprite on every face (the fleece's pompoms). */
    private static void cube(VertexConsumer b, PoseStack.Pose pose, int[] c, int light, int overlay, float[] at, float s, float[] uv) {
        float x = at[0], y = at[1], z = at[2], u0 = uv[0] / 64, u1 = (uv[0] + uv[2]) / 64, v0 = uv[1] / 64, v1 = (uv[1] + uv[3]) / 64;
        float[][] corner = new float[8][];
        for (int n = 0; n < 8; n++) corner[n] = new float[]{x + ((n & 1) == 0 ? -s : s), y + ((n & 2) == 0 ? -s : s), z + ((n & 4) == 0 ? -s : s)};
        int[][] faces = {{0, 4, 6, 2}, {5, 1, 3, 7}, {0, 1, 5, 4}, {6, 7, 3, 2}, {1, 0, 2, 3}, {4, 5, 7, 6}};
        float[][] normals = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
        for (int f = 0; f < 6; f++) {
            int[] q = faces[f];
            quad(b, pose, c, light, overlay, normals[f][0], normals[f][1], normals[f][2], u0, v1, u1, v1, u1, v0, u0, v0, corner[q[0]], corner[q[1]], corner[q[2]], corner[q[3]]);
        }
    }

    // ---- leaf swaddle ----------------------------------------------------------------------------------------------------

    /**
     * The leaf swaddle: one leaf laid like a diaper on a grid LEAF_ROWS long and ACROSS wide. The petiole stands off the back above the
     * anus; the blade runs down the back from LEAF_BACK, under the body and up the front to LEAF_FRONT, its pointed tip lying on the
     * body. Across the leaf every grid column keeps its lateral distance from the middle line, wrapping round the posed profile (which
     * includes the hair hanging at the back, so the leaf closes outside it) up to MAX_WRAP radians; the margins rise and curl out so the
     * leaf cups the bottom like a diaper and shows on the flanks. Grid cells
     * below the soiled height use the stool-stained copy of the leaf, higher with every load.
     */
    private static void leafWrap(VertexConsumer b, PoseStack.Pose pose, float cx, float cz, float minY, float h, float out, int soil,
                                 int light, int overlay) {
        for (int c = 0; c < NEAR.length; c++) {
            for (int i = 0; i < BINS; i++) { NEAR[c][i] = radiusLow((c + .5f) / CELLS, i); ANG[i] = sampleAngle; }
            if (!hull(NEAR[c], ANG)) return;
        }
        float base = out * .6f, half = LEAF_HALF * h;
        float[] along = new float[LEAF_ROWS + 1];
        for (int row = 0; row <= LEAF_ROWS; row++) {
            int part = row <= STALK_ROWS ? 0 : row <= STALK_ROWS + BACK_ROWS ? 1 : row < STALK_ROWS + BACK_ROWS + UNDER_ROWS ? 2 : 3;
            float s = switch (part) {
                case 0 -> row / (float) STALK_ROWS;
                case 1 -> (row - STALK_ROWS) / (float) BACK_ROWS;
                case 2 -> (row - STALK_ROWS - BACK_ROWS) / (float) UNDER_ROWS;
                default -> (row - STALK_ROWS - BACK_ROWS - UNDER_ROWS) / (float) FRONT_ROWS;
            };
            along[row] = switch (part) {
                case 0 -> U_STALK * s;
                case 1 -> U_STALK + (U_UNDER - U_STALK) * s;
                case 2 -> U_UNDER + (U_FRONT - U_UNDER) * s;
                default -> U_FRONT + (1 - U_FRONT) * s;
            };
            for (int j = 0; j <= ACROSS; j++) {
                float edge = (j / (float) ACROSS - .5f) * 2, lateral = edge * half;
                int at = (row * (ACROSS + 1) + j) * 4;
                float cup = RISE * edge * edge;
                if (part == 2) {
                    // under the body: the middle runs straight across between the back and front of the blade at the base; towards the
                    // margins it follows the flank round the side at the cupped height, where the back and front halves of the margin meet
                    float fs = CUP_BASE + cup, w = edge * edge;
                    leafPoint(true, fs, lateral, base, cx, cz, minY, h, PT);
                    float aBack = leafAngle;
                    leafPoint(false, fs, lateral, base, cx, cz, minY, h, PT2);
                    float d = aBack - (float) (Math.PI / 2), arc = aBack + (float) ((edge >= 0 ? Math.PI : -Math.PI) - 2 * d) * s;
                    float rr = radiusAround(fs, arc) + base;
                    float chordX = PT[0] + (PT2[0] - PT[0]) * s, chordZ = PT[2] + (PT2[2] - PT[2]) * s, chordY = minY + (.006f + (fs - .006f) * w) * h;
                    GRID[at] = chordX + (cx + (float) Math.cos(arc) * rr - chordX) * w; GRID[at + 1] = chordY;
                    GRID[at + 2] = chordZ + (cz + (float) Math.sin(arc) * rr - chordZ) * w; GRID[at + 3] = 0;
                    continue;
                }
                boolean back = part < 2;
                float f = part == 0 ? LEAF_BACK + STALK * (1 - s) : Math.min(.48f, cup + (part == 1 ? LEAF_BACK + (CUP_BASE - LEAF_BACK) * s
                        : CUP_BASE + (LEAF_FRONT - CUP_BASE) * s));
                float lift = base + CURL * h * edge * edge + (part == 0 ? STALK_OUT * h * (1 - s) * (1 - s) : 0);
                leafPoint(back, f, lateral, lift, cx, cz, minY, h, PT);
                GRID[at] = PT[0]; GRID[at + 1] = PT[1]; GRID[at + 2] = PT[2]; GRID[at + 3] = f;
            }
        }
        for (int i = 0; i < LEAF_ROWS; i++) {
            boolean under = i >= STALK_ROWS + BACK_ROWS && i < STALK_ROWS + BACK_ROWS + UNDER_ROWS;
            for (int j = 0; j < ACROSS; j++) {
                int c00 = (i * (ACROSS + 1) + j) * 4, c10 = ((i + 1) * (ACROSS + 1) + j) * 4, c11 = c10 + 4, c01 = c00 + 4;
                float mx = (GRID[c00] + GRID[c11]) / 2 - cx, mz = (GRID[c00 + 2] + GRID[c11 + 2]) / 2 - cz, ml = (float) Math.sqrt(mx * mx + mz * mz) + 1e-6f;
                float side = under ? Math.abs((j + .5f) / ACROSS - .5f) * 2 : 1;
                float nx = mx / ml * side, ny = -(1 - side), nz = mz / ml * side, u0 = along[i], u1 = along[i + 1];
                float midF = (GRID[c00 + 3] + GRID[c11 + 3]) / 2, cell = midF < soilHeight(soil) ? STAINED_V : 0;
                float v0 = cell + CELL_V * j / ACROSS, v1 = cell + CELL_V * (j + 1) / ACROSS;
                gridVertex(b, pose, c00, u0, v0, light, overlay, nx, ny, nz, soil);
                gridVertex(b, pose, c10, u1, v0, light, overlay, nx, ny, nz, soil);
                gridVertex(b, pose, c11, u1, v1, light, overlay, nx, ny, nz, soil);
                gridVertex(b, pose, c01, u0, v1, light, overlay, nx, ny, nz, soil);
            }
        }
    }

    /**
     * A point of the leaf on the back (+Z) or front (-Z) of the body at height fraction f, `lateral` model units to the side of the middle
     * line measured round the profile (both faces turn the same lateral side to the same model side), pushed out by `lift`; the angle used
     * is left in leafAngle.
     */
    private static void leafPoint(boolean back, float f, float lateral, float lift, float cx, float cz, float minY, float h, float[] out) {
        float meridian = (float) (back ? Math.PI / 2 : -Math.PI / 2);
        float d = Math.clamp(lateral / Math.max(1e-4f, radiusAround(f, meridian)), -MAX_WRAP, MAX_WRAP), a = back ? meridian + d : meridian - d;
        float r = radiusAround(f, a) + lift;
        out[0] = cx + (float) Math.cos(a) * r; out[1] = minY + f * h; out[2] = cz + (float) Math.sin(a) * r;
        leafAngle = a;
    }

    /** A full wrap (three loads) also darkens its very base towards brown, so soiling never reads as autumn leaves. */
    private static void gridVertex(VertexConsumer b, PoseStack.Pose pose, int at, float u, float v, int light, int overlay, float nx, float ny, float nz, int soil) {
        float blotch = soil >= MAX_SOIL_SHOWN ? Math.clamp(1 - (GRID[at + 3] - CUP_BASE) / .07f, 0f, 1f) : 0;
        b.addVertex(pose.pose(), GRID[at], GRID[at + 1], GRID[at + 2])
                .setColor(Math.round(255 - 70 * blotch), Math.round(255 - 95 * blotch), Math.round(255 - 125 * blotch), 255)
                .setUv(u, v).setOverlay(overlay).setLight(light).setNormal(pose, nx, ny, nz);
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

    /** Height fraction up to which a soiled wrap shows the stained leaf: none when clean, then .13, .22, .31 of the body height. */
    private static float soilHeight(int soil) { return soil <= 0 ? -1 : .04f + .09f * Math.min(3, soil); }

    /**
     * The opened leaf laid flat on the ground (model y 0, so it stays down while the body squashes or lifts): one leaf under the child, its
     * petiole out behind and its tip out in front, reaching a little past the body on every side, the support leaf a parent works on.
     */
    private static void groundLeaf(VertexConsumer b, PoseStack.Pose pose, float cx, float cz, float h, int light, int overlay) {
        float reach = 0;
        for (int i = 0; i < BINS; i++) reach = Math.max(reach, radiusNear(.2f, i));
        if (reach <= 0) return;
        float length = reach * 1.45f, width = reach * 1.1f, y = .004f * h, back = cz + length * 1.05f, front = cz - length * .95f;
        float[] stemL = {cx - width, y, back}, stemR = {cx + width, y, back}, tipR = {cx + width, y, front}, tipL = {cx - width, y, front};
        quad(b, pose, WHITE, light, overlay, 0, 1, 0, 0, 0, 0, CELL_V, 1, CELL_V, 1, 0, stemL, stemR, tipR, tipL);
    }

    /** Untextured flat colour on the white care_wear texel. */
    private static void flat(VertexConsumer b, PoseStack.Pose pose, int[] c, int light, int overlay, float nx, float ny, float nz,
                             float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3) {
        vertex(b, pose, c, .25f, .25f, light, overlay, nx, ny, nz, x0, y0, z0);
        vertex(b, pose, c, .25f, .25f, light, overlay, nx, ny, nz, x1, y1, z1);
        vertex(b, pose, c, .25f, .25f, light, overlay, nx, ny, nz, x2, y2, z2);
        vertex(b, pose, c, .25f, .25f, light, overlay, nx, ny, nz, x3, y3, z3);
    }

    /** A quad through four points with their own (u, v). */
    private static void quad(VertexConsumer b, PoseStack.Pose pose, int[] c, int light, int overlay, float nx, float ny, float nz,
                             float u0, float v0, float u1, float v1, float u2, float v2, float u3, float v3, float[] p0, float[] p1, float[] p2, float[] p3) {
        vertex(b, pose, c, u0, v0, light, overlay, nx, ny, nz, p0[0], p0[1], p0[2]);
        vertex(b, pose, c, u1, v1, light, overlay, nx, ny, nz, p1[0], p1[1], p1[2]);
        vertex(b, pose, c, u2, v2, light, overlay, nx, ny, nz, p2[0], p2[1], p2[2]);
        vertex(b, pose, c, u3, v3, light, overlay, nx, ny, nz, p3[0], p3[1], p3[2]);
    }

    private static void vertex(VertexConsumer b, PoseStack.Pose pose, int[] c, float u, float v, int light, int overlay, float nx, float ny, float nz,
                               float x, float y, float z) {
        b.addVertex(pose.pose(), x, y, z).setColor(c[0], c[1], c[2], 255).setUv(u, v).setOverlay(overlay).setLight(light).setNormal(pose, nx, ny, nz);
    }
}
