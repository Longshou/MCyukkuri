package io.github.longshou.mcyukkuri.pose;

import java.util.Arrays;
import org.joml.Vector3f;

/**
 * Procedural vanilla-style rope loop wrapped around a leashed yukkuri's posed body and close-hugging hair,
 * with a tied knot at the back (+Z in model space) where the vanilla lead line attaches.
 * Shared by the game client and the offline preview renderer (no Minecraft imports).
 */
public final class LeashLoop {
    public static final float FRONT_HEIGHT = .15f;
    public static final float BACK_HEIGHT = .24f;
    /**
     * Rest-pose body-height fractions that close-hugging hair can widen the loop from: the loop's own band is
     * {@link #FRONT_HEIGHT} to {@link #BACK_HEIGHT} plus a window, so anything outside this cannot reach it in any pose.
     * Callers with a fixed rest mesh (the game model) prefilter their per-frame hair sample with {@link #inHairBand}.
     * Measured on every body model across the idle, waddle and brace clips: contributing rest fractions fall in .06-.33.
     */
    public static final float HAIR_BAND_LOW = .04f, HAIR_BAND_HIGH = .36f;
    private static final int BINS = 32;
    private static final int CELLS = 24;
    private static final int SIDES = 6;
    private static final float SAG = .012f;
    private static final float ROPE_RADIUS = .035f;
    private static final float CLEARANCE = .004f;
    private static final float HAIR_WINDOW = .045f;
    private static final float HAIR_CAP = .22f;
    private static final float NEAR_WINDOW = .045f;

    private LeashLoop() {}

    /**
     * Loop centerline height fraction at azimuth {@code angle} (atan2(z, x); -pi/2 is front -Z, +pi/2 is back +Z).
     * Stays low under the mouth at the front with a gentle gravity sag and rises smoothly to the widest lower-back girth.
     */
    public static float heightAt(float angle) {
        float away = Math.abs((float) Math.atan2(Math.sin(angle + Math.PI / 2), Math.cos(angle + Math.PI / 2))) / (float) Math.PI;
        float smooth = .5f - .5f * (float) Math.cos(away * Math.PI);
        float sag = SAG * (float) Math.sin(Math.PI * (1 - away)) * (1 - .4f * away);
        return FRONT_HEIGHT + (BACK_HEIGHT - FRONT_HEIGHT) * smooth - sag;
    }

    /**
     * Reusable scratch and output for one caller (a game model, the preview renderer or a test): {@link #build} writes into
     * arrays held here, so {@link #vertices()} stays valid only until the next build. One builder is not thread safe; each
     * caller owns one. A per-frame rebuild then allocates nothing.
     */
    public static final class Builder {
        private final float[] cellR = new float[CELLS * BINS];
        private final float[] cellF = new float[CELLS * BINS];
        private final float[] cellA = new float[CELLS * BINS];
        private final float[] ang = new float[BINS];
        private final float[] cos = new float[BINS];
        private final float[] sin = new float[BINS];
        private final float[] loopF = new float[BINS];
        private final float[] bodyR = new float[BINS];
        private final float[] sampleAng = new float[BINS];
        private final float[] ringR = new float[BINS];
        private final float[] ringR2 = new float[BINS];
        private final float[] hullX = new float[BINS], hullZ = new float[BINS];
        private final float[] hullOX = new float[2 * BINS + 1], hullOZ = new float[2 * BINS + 1];
        private final Vector3f[] center = new Vector3f[BINS];
        private final Vector3f[] tangents = new Vector3f[BINS];
        private final Vector3f[] outNormals = new Vector3f[BINS];
        private final Vector3f[] binormals = new Vector3f[BINS];
        private final Vector3f p0 = new Vector3f(), p1 = new Vector3f(), p2 = new Vector3f(), p3 = new Vector3f();
        private final Vector3f n0 = new Vector3f(), n1 = new Vector3f(), n2 = new Vector3f(), n3 = new Vector3f();
        private final Vector3f back = new Vector3f(), knotCenter = new Vector3f(), hitchEnd = new Vector3f();
        private final Vector3f tailStart = new Vector3f(), tailMid = new Vector3f(), tailTip = new Vector3f();
        private final Vector3f segT = new Vector3f(), segU = new Vector3f(), segV = new Vector3f();
        private final float[] knot = new float[3];
        private float[] vertices = new float[(BINS * SIDES + 160) * 6 * 8];
        private int vertexCount;
        private float nearR, nearA;

        public Builder() {
            for (int i = 0; i < BINS; i++) {
                center[i] = new Vector3f();
                tangents[i] = new Vector3f();
                outNormals[i] = new Vector3f();
                binormals[i] = new Vector3f();
                float a = binAngle(i);
                ang[i] = a;
                cos[i] = (float) Math.cos(a);
                sin[i] = (float) Math.sin(a);
                loopF[i] = heightAt(a);
            }
        }

        /**
         * Build the closed rope loop and back knot in caller units (blocks in client, model units in preview).
         *
         * @param bodyVerts      posed body-surface vertices (x, y, z triples; first {@code bodyLen} floats valid)
         * @param hairVerts      posed close-hugging hair/sidelock/braid vertices in the same frame, optionally
         *                       prefiltered to {@link #inHairBand} (x, y, z triples; first {@code hairLen} floats valid)
         * @param extraClearance extra radial clearance in caller units (e.g. when wearing a cloth or leaf swaddle)
         * @return whether a mesh was emitted; {@link #vertices()} and {@link #knotAnchor()} then hold this frame's geometry
         */
        public boolean build(float[] bodyVerts, int bodyLen, float[] hairVerts, int hairLen, float extraClearance) {
            vertexCount = 0;
            if (bodyLen < 9) return false;
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (int i = 0; i < bodyLen; i += 3) {
                float x = bodyVerts[i], y = bodyVerts[i + 1], z = bodyVerts[i + 2];
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                if (z < minZ) minZ = z;
                if (z > maxZ) maxZ = z;
            }
            float bodyH = maxY - minY;
            if (bodyH < 1e-4f) return false;
            float cx = (minX + maxX) * .5f, cz = (minZ + maxZ) * .5f;

            Arrays.fill(cellR, 0f);
            for (int i = 0; i < bodyLen; i += 3) {
                float dx = bodyVerts[i] - cx, dz = bodyVerts[i + 2] - cz;
                float f = (bodyVerts[i + 1] - minY) / bodyH;
                if (f < 0 || f > 1) continue;
                float r = (float) Math.hypot(dx, dz);
                int c = Math.min(CELLS - 1, Math.max(0, (int) (f * CELLS)));
                int bin = bin(dx, dz);
                int at = c * BINS + bin;
                if (r > cellR[at]) {
                    cellR[at] = r;
                    cellF[at] = f;
                    cellA[at] = (float) Math.atan2(dz, dx);
                }
            }
            for (int i = 0; i < BINS; i++) {
                radiusNear(loopF[i], i);
                bodyR[i] = nearR;
                sampleAng[i] = nearA;
            }
            if (!hull(bodyR, sampleAng)) return false;

            System.arraycopy(bodyR, 0, ringR, 0, BINS);
            for (int i = 0; i < BINS; i++) ringR2[i] = bodyR[i] * bodyR[i];
            if (hairVerts != null && hairLen >= 3) {
                float cap = HAIR_CAP * bodyH;
                // Reject whole heights cheaply; only hair near some bin's loop height can widen the ring.
                float low = Float.MAX_VALUE, high = -Float.MAX_VALUE;
                for (int i = 0; i < BINS; i++) {
                    low = Math.min(low, loopF[i] - HAIR_WINDOW);
                    high = Math.max(high, loopF[i] + HAIR_WINDOW);
                }
                // A multiply by the reciprocal: the per-vertex divide dominated the frame's build on long-haired bodies.
                float invH = 1f / bodyH;
                for (int i = 0; i < hairLen; i += 3) {
                    float f = (hairVerts[i + 1] - minY) * invH;
                    if (f < low || f > high) continue;
                    float dx = hairVerts[i] - cx, dz = hairVerts[i + 2] - cz;
                    int bin = bin(dx, dz);
                    if (Math.abs(f - loopF[bin]) > HAIR_WINDOW) continue;
                    float r2 = dx * dx + dz * dz;
                    float reach = bodyR[bin] + cap;
                    if (r2 > reach * reach) continue;
                    if (r2 > ringR2[bin]) {
                        ringR2[bin] = r2;
                        ringR[bin] = (float) Math.sqrt(r2);
                    }
                }
                // Convexify body+hair contour so the rope bridges tautly without inward kinks or hair clipping.
                if (!hull(ringR, ang)) return false;
            }

            float meanR = 0f;
            for (int i = 0; i < BINS; i++) meanR += ringR[i];
            meanR /= BINS;
            // Scale rope thickness from both height and girth so vertical squash does not thin the rope.
            float ropeRef = (bodyH + meanR * 1.35f) * .5f;
            float ropeR = ROPE_RADIUS * ropeRef;
            float offset = (CLEARANCE + Math.max(0f, extraClearance)) * ropeRef + ropeR;
            for (int i = 0; i < BINS; i++) {
                float r = ringR[i] + offset;
                center[i].set(cx + cos[i] * r, minY + loopF[i] * bodyH, cz + sin[i] * r);
            }
            for (int i = 0; i < BINS; i++) {
                Vector3f prev = center[(i + BINS - 1) % BINS], next = center[(i + 1) % BINS];
                tangents[i].set(next).sub(prev).normalize();
                outNormals[i].set(cos[i], 0, sin[i]);
                outNormals[i].fma(-outNormals[i].dot(tangents[i]), tangents[i]);
                if (outNormals[i].lengthSquared() < 1e-8f) outNormals[i].set(0, 1, 0);
                else outNormals[i].normalize();
                binormals[i].set(tangents[i]).cross(outNormals[i]).normalize();
            }

            float arcLength = 0f;
            for (int i = 0; i < BINS; i++) arcLength += center[i].distance(center[(i + 1) % BINS]);
            float tiles = Math.max(4f, Math.round(arcLength / (ropeR * 6f)));
            float uScale = tiles / Math.max(1e-5f, arcLength);

            float u = 0f;
            for (int i = 0; i < BINS; i++) {
                int j = (i + 1) % BINS;
                float u0 = u, u1 = u + center[i].distance(center[j]) * uScale;
                u = u1;
                for (int s = 0; s < SIDES; s++) {
                    float v0 = (float) s / SIDES, v1 = (float) (s + 1) / SIDES;
                    ringPoint(center[i], outNormals[i], binormals[i], ropeR, PHI_COS[s], PHI_SIN[s], p0, n0);
                    ringPoint(center[i], outNormals[i], binormals[i], ropeR, PHI_COS[s + 1], PHI_SIN[s + 1], p1, n1);
                    ringPoint(center[j], outNormals[j], binormals[j], ropeR, PHI_COS[s + 1], PHI_SIN[s + 1], p2, n2);
                    ringPoint(center[j], outNormals[j], binormals[j], ropeR, PHI_COS[s], PHI_SIN[s], p3, n3);
                    // Outward CCW winding: (p1 - p0) x (p3 - p0) aligns with outward radial normal.
                    emitQuad(p0, n0, u0, v0, p1, n1, u0, v1, p2, n2, u1, v1, p3, n3, u1, v0);
                }
            }

            // Back knot at +Z (angle = +pi/2): find the exact interpolated back point on the loop.
            sampleBack();
            knotCenter.set(back).add(0, 0, ropeR * .35f);
            // Chunky tied barrel knot wrapping the loop at the back.
            emitBarrel(knotCenter, new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1),
                    ropeR * 1.55f, ropeR * 1.45f, ropeR * 1.55f, 8, 6);
            // Raised hitch eyelet extending +Z where the vanilla lead line joins.
            hitchEnd.set(knotCenter).add(0, ropeR * .2f, ropeR * 1.95f);
            emitSegment(knotCenter, hitchEnd, ropeR * .85f, ropeR * .65f, 6);
            // Two short drooping knot tails on left and right of the knot.
            for (int side = -1; side <= 1; side += 2) {
                tailStart.set(knotCenter).add(side * ropeR * .9f, -ropeR * .35f, ropeR * .45f);
                tailMid.set(tailStart).add(side * ropeR * .95f, -ropeR * 1.35f, ropeR * .35f);
                tailTip.set(tailMid).add(side * ropeR * .45f, -ropeR * 1.35f, -ropeR * .15f);
                emitSegment(tailStart, tailMid, ropeR * .68f, ropeR * .58f, 6);
                emitSegment(tailMid, tailTip, ropeR * .58f, ropeR * .40f, 6);
            }

            knot[0] = hitchEnd.x; knot[1] = hitchEnd.y; knot[2] = hitchEnd.z;
            return true;
        }

        /** Emitted triangles: 8 floats per vertex (x, y, z, u, v, nx, ny, nz), first {@link #vertexCount()} floats valid. */
        public float[] vertices() { return vertices; }
        public int vertexCount() { return vertexCount; }
        /** The lead line's attach point behind the knot, valid after a successful build. */
        public float[] knotAnchor() { return knot; }

        private void sampleBack() {
            int best = 0;
            float bestDiff = Float.MAX_VALUE;
            for (int i = 0; i < BINS; i++) {
                float diff = Math.abs(wrapAngle(ang[i] - (float) (Math.PI / 2)));
                if (diff < bestDiff) { bestDiff = diff; best = i; }
            }
            int other = wrapAngle(ang[best] - (float) (Math.PI / 2)) > 0 ? (best + BINS - 1) % BINS : (best + 1) % BINS;
            float a0 = wrapAngle(ang[best] - (float) (Math.PI / 2));
            float a1 = wrapAngle(ang[other] - (float) (Math.PI / 2));
            float t = Math.abs(a1 - a0) < 1e-5f ? 0f : Math.clamp(-a0 / (a1 - a0), 0f, 1f);
            back.set(center[best]).lerp(center[other], t);
        }

        private void ringPoint(Vector3f c, Vector3f u, Vector3f v, float r, float cp, float sp, Vector3f pOut, Vector3f nOut) {
            nOut.set(u.x * cp + v.x * sp, u.y * cp + v.y * sp, u.z * cp + v.z * sp).normalize();
            pOut.set(c.x + nOut.x * r, c.y + nOut.y * r, c.z + nOut.z * r);
        }

        /** Closed rounded barrel / knot bulge along axis {@code axis} with orthogonal basis {@code b1, b2}. */
        private void emitBarrel(Vector3f center, Vector3f axis, Vector3f b1, Vector3f b2,
                                float halfLen, float rad1, float rad2, int rings, int sides) {
            for (int r = 0; r < rings; r++) {
                float t0 = (float) r / rings, t1 = (float) (r + 1) / rings;
                float alpha0 = (t0 - .5f) * (float) Math.PI, alpha1 = (t1 - .5f) * (float) Math.PI;
                float x0 = (float) Math.sin(alpha0) * halfLen, x1 = (float) Math.sin(alpha1) * halfLen;
                float k0 = Math.max(.08f, (float) Math.cos(alpha0)), k1 = Math.max(.08f, (float) Math.cos(alpha1));
                for (int s = 0; s < sides; s++) {
                    double ph0 = 2 * Math.PI * s / sides, ph1 = 2 * Math.PI * (s + 1) / sides;
                    float c0 = (float) Math.cos(ph0), s0 = (float) Math.sin(ph0);
                    float c1 = (float) Math.cos(ph1), s1 = (float) Math.sin(ph1);
                    barrelVert(center, axis, b1, b2, x0, rad1 * k0, rad2 * k0, c0, s0, (float) Math.sin(alpha0), p0, n0);
                    barrelVert(center, axis, b1, b2, x0, rad1 * k0, rad2 * k0, c1, s1, (float) Math.sin(alpha0), p1, n1);
                    barrelVert(center, axis, b1, b2, x1, rad1 * k1, rad2 * k1, c1, s1, (float) Math.sin(alpha1), p2, n2);
                    barrelVert(center, axis, b1, b2, x1, rad1 * k1, rad2 * k1, c0, s0, (float) Math.sin(alpha1), p3, n3);
                    emitQuad(p0, n0, t0 * 2f, (float) s / sides, p1, n1, t0 * 2f, (float) (s + 1) / sides,
                            p2, n2, t1 * 2f, (float) (s + 1) / sides, p3, n3, t1 * 2f, (float) s / sides);
                }
            }
        }

        private void barrelVert(Vector3f center, Vector3f axis, Vector3f b1, Vector3f b2,
                                float along, float r1, float r2, float cp, float sp, float axN,
                                Vector3f pOut, Vector3f nOut) {
            pOut.set(center).fma(along, axis).fma(r1 * cp, b1).fma(r2 * sp, b2);
            nOut.set(0, 0, 0).fma(axN * .6f, axis).fma(cp, b1).fma(sp, b2).normalize();
        }

        /** Tapered capped rope segment from {@code from} to {@code to}. */
        private void emitSegment(Vector3f from, Vector3f to, float r0, float r1, int sides) {
            segT.set(to).sub(from);
            float len = segT.length();
            if (len < 1e-6f) return;
            segT.div(len);
            if (Math.abs(segT.y) < .9f) segU.set(0, 1, 0).cross(segT).normalize();
            else segU.set(1, 0, 0).cross(segT).normalize();
            segV.set(segT).cross(segU).normalize();
            for (int s = 0; s < sides; s++) {
                double ph0 = 2 * Math.PI * s / sides, ph1 = 2 * Math.PI * (s + 1) / sides;
                float c0 = (float) Math.cos(ph0), s0 = (float) Math.sin(ph0);
                float c1 = (float) Math.cos(ph1), s1 = (float) Math.sin(ph1);
                ringPoint(from, segU, segV, r0, c0, s0, p0, n0);
                ringPoint(from, segU, segV, r0, c1, s1, p1, n1);
                ringPoint(to, segU, segV, r1, c1, s1, p2, n2);
                ringPoint(to, segU, segV, r1, c0, s0, p3, n3);
                emitQuad(p0, n0, 0f, (float) s / sides, p1, n1, 0f, (float) (s + 1) / sides,
                        p2, n2, 1f, (float) (s + 1) / sides, p3, n3, 1f, (float) s / sides);
                // End cap at `to`
                emitTri(to, segT, .5f, .5f, p3, segT, 1f, (float) s / sides, p2, segT, 1f, (float) (s + 1) / sides);
            }
        }

        private void emitQuad(Vector3f pa, Vector3f normA, float ua, float va,
                              Vector3f pb, Vector3f normB, float ub, float vb,
                              Vector3f pc, Vector3f normC, float uc, float vc,
                              Vector3f pd, Vector3f normD, float ud, float vd) {
            emitTri(pa, normA, ua, va, pb, normB, ub, vb, pc, normC, uc, vc);
            emitTri(pa, normA, ua, va, pc, normC, uc, vc, pd, normD, ud, vd);
        }

        private void emitTri(Vector3f pa, Vector3f normA, float ua, float va,
                             Vector3f pb, Vector3f normB, float ub, float vb,
                             Vector3f pc, Vector3f normC, float uc, float vc) {
            add(pa, ua, va, normA);
            add(pb, ub, vb, normB);
            add(pc, uc, vc, normC);
        }

        private void add(Vector3f p, float u, float v, Vector3f n) {
            if (vertexCount + 8 > vertices.length) vertices = Arrays.copyOf(vertices, vertices.length * 2);
            int i = vertexCount;
            vertices[i] = p.x; vertices[i + 1] = p.y; vertices[i + 2] = p.z;
            vertices[i + 3] = u; vertices[i + 4] = v;
            vertices[i + 5] = n.x; vertices[i + 6] = n.y; vertices[i + 7] = n.z;
            vertexCount = i + 8;
        }

        private void radiusNear(float f, int bin) {
            radiusAt(f, bin);
            float bestR = nearR, bestA = nearA;
            for (int d = -1; d <= 1; d += 2) {
                radiusAt(f, (bin + d + BINS) % BINS);
                if (nearR > bestR) { bestR = nearR; bestA = nearA; }
            }
            int lo = Math.max(0, (int) ((f - NEAR_WINDOW) * CELLS));
            int hi = Math.min(CELLS - 1, (int) ((f + NEAR_WINDOW) * CELLS));
            for (int cell = lo; cell <= hi; cell++) {
                for (int d = -1; d <= 1; d++) {
                    int at = cell * BINS + (bin + d + BINS) % BINS;
                    if (cellR[at] > bestR) { bestR = cellR[at]; bestA = cellA[at]; }
                }
            }
            nearR = bestR; nearA = bestA;
        }

        private void radiusAt(float f, int bin) {
            float loF = -1, loR = 0, hiF = 2, hiR = 0, loA = 0, hiA = 0, centre = binAngle(bin);
            for (int c = 0; c < CELLS; c++) {
                int at = c * BINS + bin;
                float r = cellR[at];
                if (r <= 0) continue;
                float cf = cellF[at], off = wrapAngle(cellA[at] - centre);
                if (cf <= f) {
                    if (cf > loF) { loF = cf; loR = r; loA = off; }
                } else if (cf < hiF) {
                    hiF = cf; hiR = r; hiA = off;
                }
            }
            if (loF < 0) { nearR = hiF > 1 ? 0 : hiR; nearA = hiF > 1 ? centre : centre + hiA; }
            else if (hiF > 1) { nearR = loR; nearA = centre + loA; }
            else { float t = (f - loF) / (hiF - loF); nearR = loR + (hiR - loR) * t; nearA = centre + loA + (hiA - loA) * t; }
        }

        private boolean hull(float[] radius, float[] angles) {
            float[] hx = hullX, hz = hullZ;
            int n = 0;
            for (int i = 0; i < BINS; i++) {
                if (radius[i] > 0) {
                    hx[n] = (float) Math.cos(angles[i]) * radius[i];
                    hz[n] = (float) Math.sin(angles[i]) * radius[i];
                    n++;
                }
            }
            if (n < 3) return false;
            // Sort by x, then z (insertion sort over at most 32 points).
            for (int i = 1; i < n; i++) {
                float px = hx[i], pz = hz[i];
                int k = i;
                for (; k > 0 && (hx[k - 1] > px || hx[k - 1] == px && hz[k - 1] > pz); k--) {
                    hx[k] = hx[k - 1]; hz[k] = hz[k - 1];
                }
                hx[k] = px; hz[k] = pz;
            }
            float[] ox = hullOX, oz = hullOZ;
            int m = 0;
            for (int pass = 0; pass < 2; pass++) {
                int start = m;
                for (int s = 0; s < n; s++) {
                    int i = pass == 0 ? s : n - 1 - s;
                    while (m >= start + 2 && (ox[m - 1] - ox[m - 2]) * (hz[i] - oz[m - 2]) - (oz[m - 1] - oz[m - 2]) * (hx[i] - ox[m - 2]) <= 0) m--;
                    ox[m] = hx[i]; oz[m] = hz[i]; m++;
                }
                m--;
            }
            for (int i = 0; i < BINS; i++) {
                float dx = cos[i], dz = sin[i], best = 0;
                for (int e = 0; e < m; e++) {
                    float px = ox[e], pz = oz[e], ex = ox[(e + 1) % m] - px, ez = oz[(e + 1) % m] - pz;
                    float den = dx * ez - dz * ex;
                    if (Math.abs(den) < 1e-9f) continue;
                    float t = (px * ez - pz * ex) / den, s = (px * dz - pz * dx) / den;
                    if (s >= -1e-4f && s <= 1 + 1e-4f && t > best) best = t;
                }
                if (best > 0) radius[i] = best;
            }
            return true;
        }
    }

    private static final float[] PHI_COS = new float[SIDES + 1], PHI_SIN = new float[SIDES + 1];
    static {
        for (int s = 0; s <= SIDES; s++) {
            double phi = 2 * Math.PI * s / SIDES;
            PHI_COS[s] = (float) Math.cos(phi);
            PHI_SIN[s] = (float) Math.sin(phi);
        }
    }

    private static final float TAN1 = (float) Math.tan(Math.PI / 16), TAN2 = (float) Math.tan(2 * Math.PI / 16), TAN3 = (float) Math.tan(3 * Math.PI / 16);
    /** Bin of the first sector in a quadrant, and how the sector count runs, for the eight half-quadrants (x-major then z-major). */
    private static final int[] BIN_BASE = {16, 15, 31, 0, 23, 8, 24, 7}, BIN_STEP = {1, -1, -1, 1, -1, 1, 1, -1};

    /** Whether hair at this rest body-height fraction can reach the loop ({@link #HAIR_BAND_LOW}, {@link #HAIR_BAND_HIGH}). */
    public static boolean inHairBand(float restFraction) {
        return restFraction >= HAIR_BAND_LOW && restFraction <= HAIR_BAND_HIGH;
    }

    /**
     * Azimuth bin (0..31) of a direction from the loop center, the sector of atan2(dz, dx) that starts at -pi.
     * Compares the folded slope against the sector tangents instead of taking the transcendental, and picks the
     * quadrant branchlessly: the hair pass asks this of every close vertex of every leashed body, every frame.
     */
    public static int bin(float dx, float dz) {
        float ax = Math.abs(dx), az = Math.abs(dz);
        float r = Math.min(ax, az) / Math.max(ax, az);
        int sectors = (r >= TAN1 ? 1 : 0) + (r >= TAN2 ? 1 : 0) + (r >= TAN3 ? 1 : 0);
        int at = (ax < az ? 4 : 0) + ((Float.floatToRawIntBits(dx) >>> 30) & 2) + (Float.floatToRawIntBits(dz) >>> 31);
        return BIN_BASE[at] + BIN_STEP[at] * sectors;
    }

    private static float binAngle(int bin) {
        return (float) (-Math.PI + 2 * Math.PI * (bin + .5) / BINS);
    }

    private static float wrapAngle(float a) {
        return (float) Math.atan2(Math.sin(a), Math.cos(a));
    }
}
