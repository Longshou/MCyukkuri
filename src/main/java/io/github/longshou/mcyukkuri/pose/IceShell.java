package io.github.longshou.mcyukkuri.pose;

/**
 * Bevelled translucent ice block fitted directly to a live posed Yukkuri mesh (body, hair, braids, tails, wings and worn headwear),
 * for the R5 cold-stiff state. Pure geometry with no Minecraft imports; shared by the client renderer and offline previews/tests.
 *
 * <p>Topology: flat base resting at ground level (no bottom face, avoiding z-fighting with floor blocks), four main vertical walls,
 * four 45-degree vertical corner chamfers, four 45-degree top-edge bevels, four planar top-corner bevels and a flat octagonal top
 * (40 triangles, outward CCW winding, planar quads). Containment is verified against all 17 outer planes so no hair or headwear
 * vertex ever pierces the shell on any form, size or pose.</p>
 */
public final class IceShell {
    /** Floats per triangle in {@link #vertices()}: 3 corners of (x, y, z, u, v, nx, ny, nz). */
    public static final int TRIANGLE = 24;
    /** 8 vertical wall quads + 8 sloped top-bevel quads + 8 top-cap fan triangles = 40 triangles. */
    public static final int TRIANGLES = 40;
    /** Ticks over which the ice shell grows from the feet to full height on stiffening (design section 1). */
    public static final float GROW_TICKS = 40f;
    /** Number of chisel crack stages (0 clean .. 3 heavy shatter web). */
    public static final int MAX_CRACK = 3;

    private static final float SQRT2 = (float) Math.sqrt(2.0);
    private static final float SQRT6 = (float) Math.sqrt(6.0);
    private static final float INV_SQRT2 = 1f / SQRT2;
    private static final float INV_SQRT6 = 1f / SQRT6;
    /** Half-texel inset on the 64x64 2x2 crack atlas so adjacent crack tiles never bleed across seams. */
    private static final float UV_INSET = 0.5f / 64f;

    /** Fitted full-grown shell bounds in caller units (blocks on the client, model units in tests). */
    public record Fit(float x0, float x1, float y0, float y1, float z0, float z1, float bv, float bt, float pad) {
        /** Signed distance of a point from the nearest of the 17 outer planes (negative = strictly inside, positive = outside). */
        public float signedDistance(float x, float y, float z) {
            float d = y0 - y;
            d = Math.max(d, x - x1);
            d = Math.max(d, x0 - x);
            d = Math.max(d, z - z1);
            d = Math.max(d, z0 - z);
            d = Math.max(d, y - y1);
            // 4 vertical 45-degree corner chamfers
            d = Math.max(d, (x + z - (x1 + z1 - bv)) * INV_SQRT2);
            d = Math.max(d, (x - z - (x1 - z0 - bv)) * INV_SQRT2);
            d = Math.max(d, (-x + z - (-x0 + z1 - bv)) * INV_SQRT2);
            d = Math.max(d, (-x - z - (-x0 - z0 - bv)) * INV_SQRT2);
            // 4 top 45-degree edge bevels
            d = Math.max(d, (x + y - (x1 + y1 - bt)) * INV_SQRT2);
            d = Math.max(d, (-x + y - (-x0 + y1 - bt)) * INV_SQRT2);
            d = Math.max(d, (z + y - (z1 + y1 - bt)) * INV_SQRT2);
            d = Math.max(d, (-z + y - (-z0 + y1 - bt)) * INV_SQRT2);
            // 4 planar top-corner bevels with outward normal (+-1, +2, +-1) / sqrt(6)
            d = Math.max(d, (x + 2f * y + z - (x1 + z1 + 2f * y1 - bv - 2f * bt)) * INV_SQRT6);
            d = Math.max(d, (x + 2f * y - z - (x1 - z0 + 2f * y1 - bv - 2f * bt)) * INV_SQRT6);
            d = Math.max(d, (-x + 2f * y + z - (-x0 + z1 + 2f * y1 - bv - 2f * bt)) * INV_SQRT6);
            d = Math.max(d, (-x + 2f * y - z - (-x0 - z0 + 2f * y1 - bv - 2f * bt)) * INV_SQRT6);
            return d;
        }
    }

    private final float[] mesh = new float[TRIANGLES * TRIANGLE];
    private final float[] r0 = new float[24], r1 = new float[24], r2 = new float[24];
    private int length;

    /** Smooth ease-out shell growth fraction (0..1) from elapsed ticks since freeze began. */
    public static float growth(float elapsedTicks) {
        float t = Math.clamp(elapsedTicks / GROW_TICKS, 0f, 1f);
        return 1f - (1f - t) * (1f - t);
    }

    /**
     * Fits the bevelled ice block tightly around all visible posed vertices ({@code x, y, z} triples; first {@code len} floats valid).
     * {@code unit} is the size of one model unit in the caller's coordinates ({@code 1/16f} for blocks, {@code 1f} for model units).
     */
    public static Fit fit(float[] pts, int len, float bodyHeight, float unit) {
        if (len < 9) return null;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < len; i += 3) {
            float x = pts[i], y = pts[i + 1], z = pts[i + 2];
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }
        float refH = Math.max(bodyHeight, maxY - minY);
        if (refH < 1e-4f) return null;
        // Hugging clearance: ~0.43 model units on a standard adult, proportional on young and tiny house forms.
        float pad = Math.max(0.32f * unit, 0.045f * refH);
        float x0 = minX - pad, x1 = maxX + pad;
        float z0 = minZ - pad, z1 = maxZ + pad;
        float y0 = Math.min(0f, minY);
        float y1 = maxY + pad;

        float w = x1 - x0, d = z1 - z0, h = y1 - y0;
        float minWD = Math.min(w, d);
        float minBv = 0.10f * minWD;
        float bv = 0.16f * minWD;
        float minBt = Math.min(0.10f * minWD, 0.12f * h);
        float bt = Math.min(0.22f * minWD, 0.28f * h);

        // 1. Ensure vertical 45-degree corner chamfers clear every vertex by at least pad.
        float maxCorner = -Float.MAX_VALUE;
        for (int i = 0; i < len; i += 3) {
            float x = pts[i], z = pts[i + 2];
            maxCorner = Math.max(maxCorner, (x + z) - (x1 + z1));
            maxCorner = Math.max(maxCorner, (x - z) - (x1 - z0));
            maxCorner = Math.max(maxCorner, (-x + z) - (-x0 + z1));
            maxCorner = Math.max(maxCorner, (-x - z) - (-x0 - z0));
        }
        float allowedBv = -maxCorner - pad * SQRT2;
        if (allowedBv < bv) {
            if (allowedBv >= minBv) {
                bv = allowedBv;
            } else {
                float expand = (minBv - allowedBv) * 0.5f;
                x0 -= expand; x1 += expand;
                z0 -= expand; z1 += expand;
                bv = minBv;
            }
        }

        // Keep top cap strictly non-degenerate: 2 * (bv + bt) <= 0.80 * min(w, d).
        minWD = Math.min(x1 - x0, z1 - z0);
        bt = Math.min(bt, Math.max(minBt, 0.40f * minWD - bv));

        // 2. Ensure top-edge 45-degree bevels clear every vertex by at least pad.
        float maxTopEdge = -Float.MAX_VALUE;
        for (int i = 0; i < len; i += 3) {
            float x = pts[i], y = pts[i + 1], z = pts[i + 2];
            maxTopEdge = Math.max(maxTopEdge, (x + y) - (x1 + y1));
            maxTopEdge = Math.max(maxTopEdge, (-x + y) - (-x0 + y1));
            maxTopEdge = Math.max(maxTopEdge, (z + y) - (z1 + y1));
            maxTopEdge = Math.max(maxTopEdge, (-z + y) - (-z0 + y1));
        }
        float allowedBt = -maxTopEdge - pad * SQRT2;
        if (allowedBt < bt) {
            if (allowedBt >= minBt) {
                bt = allowedBt;
            } else {
                y1 += (minBt - allowedBt);
                bt = minBt;
            }
        }

        // 3. Ensure top-corner planar bevels (+-1, +2, +-1) clear every vertex by at least pad.
        float maxTopCorner = -Float.MAX_VALUE;
        for (int i = 0; i < len; i += 3) {
            float x = pts[i], y = pts[i + 1], z = pts[i + 2];
            maxTopCorner = Math.max(maxTopCorner, (x + 2f * y + z) - (x1 + z1 + 2f * y1 - bv - 2f * bt));
            maxTopCorner = Math.max(maxTopCorner, (x + 2f * y - z) - (x1 - z0 + 2f * y1 - bv - 2f * bt));
            maxTopCorner = Math.max(maxTopCorner, (-x + 2f * y + z) - (-x0 + z1 + 2f * y1 - bv - 2f * bt));
            maxTopCorner = Math.max(maxTopCorner, (-x + 2f * y - z) - (-x0 - z0 + 2f * y1 - bv - 2f * bt));
        }
        float cornerNeed = maxTopCorner + pad * SQRT6;
        if (cornerNeed > 0f) {
            y1 += cornerNeed * 0.5f;
        }

        return new Fit(x0, x1, y0, y1, z0, z1, bv, bt, pad);
    }

    /**
     * Builds the 40-triangle bevelled ice shell into this instance's reusable buffer for the given growth (0..1) and crack stage (0..3).
     */
    public void build(Fit fit, float growth, int crack) {
        int stage = Math.clamp(crack, 0, MAX_CRACK);
        float g = Math.clamp(growth, 0f, 1f);
        float fullH = fit.y1 - fit.y0;
        float curY1 = fit.y0 + fullH * (0.14f + 0.86f * g);
        float curH = curY1 - fit.y0;
        float bt = Math.min(fit.bt, curH * 0.42f);
        float bv = fit.bv;
        float yShoulder = curY1 - bt;

        float x0 = fit.x0, x1 = fit.x1, z0 = fit.z0, z1 = fit.z1;
        float ix0 = x0 + bv, ix1 = x1 - bv, iz0 = z0 + bv, iz1 = z1 - bv;
        float tx0 = x0 + bt, tx1 = x1 - bt, tz0 = z0 + bt, tz1 = z1 - bt;
        float tix0 = tx0 + bv, tix1 = tx1 - bv, tiz0 = tz0 + bv, tiz1 = tz1 - bv;

        // Counter-clockwise perimeter when viewed from above (+Y):
        // 0:(-X,-Z) -> 1:(+X,-Z) [front -Z], 1->2 [front-right bevel], 2->3 [right +X], 3->4 [back-right bevel],
        // 4->5 [back +Z], 5->6 [back-left bevel], 6->7 [left -X], 7->0 [front-left bevel].
        writeRing(r0, fit.y0, ix0, ix1, x0, x1, iz0, iz1, z0, z1);
        writeRing(r1, yShoulder, ix0, ix1, x0, x1, iz0, iz1, z0, z1);
        writeRing(r2, curY1, tix0, tix1, tx0, tx1, tiz0, tiz1, tz0, tz1);

        // 2x2 crack atlas tile bounds (insets avoid texel bleeding).
        float uMin = (stage & 1) * 0.5f + UV_INSET;
        float uMax = ((stage & 1) + 1) * 0.5f - UV_INSET;
        float vMin = (stage >> 1) * 0.5f + UV_INSET;
        float vMax = ((stage >> 1) + 1) * 0.5f - UV_INSET;
        float uSpan = uMax - uMin, vSpan = vMax - vMin;
        // Narrow frosted rim band for bevel strips (outer 4 texels of the 32x32 tile).
        float rimU0 = uMin, rimU1 = uMin + uSpan * 0.14f;
        float rimV0 = vMin, rimV1 = vMin + vSpan * 0.16f;
        // Vertical walls take the lower portion of the tile as the shell grows upward.
        float wallVTop = vMax - vSpan * (0.16f + 0.68f * g);

        length = 0;
        // Outward normals for the 8 vertical faces (i = 0..7) and 8 sloped top-bevel faces.
        float[] vnx = {0, INV_SQRT2, 1, INV_SQRT2, 0, -INV_SQRT2, -1, -INV_SQRT2};
        float[] vnz = {-1, -INV_SQRT2, 0, INV_SQRT2, 1, INV_SQRT2, 0, -INV_SQRT2};
        float[] bnx = {0, INV_SQRT6, INV_SQRT2, INV_SQRT6, 0, -INV_SQRT6, -INV_SQRT2, -INV_SQRT6};
        float[] bny = {INV_SQRT2, 2f * INV_SQRT6, INV_SQRT2, 2f * INV_SQRT6, INV_SQRT2, 2f * INV_SQRT6, INV_SQRT2, 2f * INV_SQRT6};
        float[] bnz = {-INV_SQRT2, -INV_SQRT6, 0, INV_SQRT6, INV_SQRT2, INV_SQRT6, 0, -INV_SQRT6};

        for (int i = 0; i < 8; i++) {
            int j = (i + 1) & 7;
            boolean bevel = (i & 1) == 1;
            float qaU0 = bevel ? rimU0 : uMin, qaU1 = bevel ? rimU1 : uMax;
            // Vertical quad: (r0[i], r0[j], r1[j], r1[i]) — check winding:
            // On front (-Z, i=0): r0[0]=(ix0,y0,z0), r0[1]=(ix1,y0,z0), r1[1]=(ix1,yS,z0).
            // (r0[1]-r0[0]) x (r1[1]-r0[0]) = (+X) x (+Y) = +Z (inward!). So outward CCW is (r0[j], r0[i], r1[i], r1[j])!
            quad(r0, j, r0, i, r1, i, r1, j, qaU1, vMax, qaU0, vMax, qaU0, wallVTop, qaU1, wallVTop, vnx[i], 0f, vnz[i]);
            // Top-bevel quad: outward CCW is (r1[j], r1[i], r2[i], r2[j]).
            quad(r1, j, r1, i, r2, i, r2, j, qaU1, rimV1, qaU0, rimV1, qaU0, rimV0, qaU1, rimV0, bnx[i], bny[i], bnz[i]);
        }

        // Flat octagonal top cap at y = curY1, outward normal +Y: fan from center (cx, curY1, cz).
        // Outward +Y CCW: (center, r2[j], r2[i]) -> on front edge (r2[1]-c) x (r2[0]-c) points +Y!
        float cx = (tx0 + tx1) * 0.5f, cz = (tz0 + tz1) * 0.5f;
        float topSpanX = Math.max(1e-5f, tx1 - tx0), topSpanZ = Math.max(1e-5f, tz1 - tz0);
        float cu = uMin + 0.5f * uSpan, cv = vMin + 0.5f * vSpan;
        for (int i = 0; i < 8; i++) {
            int j = (i + 1) & 7;
            int pi = i * 3, pj = j * 3;
            float ui = uMin + (r2[pi] - tx0) / topSpanX * uSpan;
            float vi = vMin + (r2[pi + 2] - tz0) / topSpanZ * vSpan;
            float uj = uMin + (r2[pj] - tx0) / topSpanX * uSpan;
            float vj = vMin + (r2[pj + 2] - tz0) / topSpanZ * vSpan;
            vertex(cx, curY1, cz, cu, cv, 0f, 1f, 0f);
            vertex(r2[pj], r2[pj + 1], r2[pj + 2], uj, vj, 0f, 1f, 0f);
            vertex(r2[pi], r2[pi + 1], r2[pi + 2], ui, vi, 0f, 1f, 0f);
        }
    }

    private static void writeRing(float[] out, float y, float ix0, float ix1, float x0, float x1,
                                  float iz0, float iz1, float z0, float z1) {
        setPt(out, 0, ix0, y, z0);
        setPt(out, 1, ix1, y, z0);
        setPt(out, 2, x1, y, iz0);
        setPt(out, 3, x1, y, iz1);
        setPt(out, 4, ix1, y, z1);
        setPt(out, 5, ix0, y, z1);
        setPt(out, 6, x0, y, iz1);
        setPt(out, 7, x0, y, iz0);
    }

    private static void setPt(float[] out, int index, float x, float y, float z) {
        int o = index * 3;
        out[o] = x; out[o + 1] = y; out[o + 2] = z;
    }

    private void quad(float[] a, int ia, float[] b, int ib, float[] c, int ic, float[] d, int id,
                      float ua, float va, float ub, float vb, float uc, float vc, float ud, float vd,
                      float nx, float ny, float nz) {
        int pa = ia * 3, pb = ib * 3, pc = ic * 3, pd = id * 3;
        vertex(a[pa], a[pa + 1], a[pa + 2], ua, va, nx, ny, nz);
        vertex(b[pb], b[pb + 1], b[pb + 2], ub, vb, nx, ny, nz);
        vertex(c[pc], c[pc + 1], c[pc + 2], uc, vc, nx, ny, nz);
        vertex(a[pa], a[pa + 1], a[pa + 2], ua, va, nx, ny, nz);
        vertex(c[pc], c[pc + 1], c[pc + 2], uc, vc, nx, ny, nz);
        vertex(d[pd], d[pd + 1], d[pd + 2], ud, vd, nx, ny, nz);
    }

    private void vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz) {
        mesh[length] = x; mesh[length + 1] = y; mesh[length + 2] = z;
        mesh[length + 3] = u; mesh[length + 4] = v;
        mesh[length + 5] = nx; mesh[length + 6] = ny; mesh[length + 7] = nz;
        length += 8;
    }

    /** Triangles written by the last {@link #build}: 8 floats per vertex (x, y, z, u, v, nx, ny, nz), valid up to {@link #length()}. */
    public float[] vertices() { return mesh; }

    /** Floats written by the last {@link #build}; always {@code TRIANGLES * TRIANGLE}. */
    public int length() { return length; }
}
