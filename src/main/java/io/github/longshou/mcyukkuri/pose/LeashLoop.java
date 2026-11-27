package io.github.longshou.mcyukkuri.pose;

import java.util.Arrays;
import org.joml.Vector3f;

/**
 * Procedural vanilla-style rope loop wrapped around a leashed yukkuri's posed body and close-hugging hair,
 * with a tied knot at the back (+Z in model space) where the vanilla lead line attaches.
 * Shared by the game client and the offline preview renderer (no Minecraft imports).
 */
public final class LeashLoop {
    /** Emitted triangle mesh: 8 floats per vertex (x, y, z, u, v, nx, ny, nz) and the back knot anchor. */
    public record Mesh(float[] vertices, float[] knotAnchor) {}

    public static final float FRONT_HEIGHT = .15f;
    public static final float BACK_HEIGHT = .24f;
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
     * Build the closed rope loop and back knot in caller units (blocks in client, model units in preview).
     *
     * @param bodyVerts      posed body-surface vertices (x, y, z triples; first {@code bodyLen} floats valid)
     * @param hairVerts      posed close-hugging hair/sidelock/braid vertices (x, y, z triples; first {@code hairLen} floats valid)
     * @param extraClearance extra radial clearance in caller units (e.g. when wearing a cloth or leaf swaddle)
     */
    public static Mesh build(float[] bodyVerts, int bodyLen, float[] hairVerts, int hairLen, float extraClearance) {
        if (bodyLen < 9) return null;
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
        if (bodyH < 1e-4f) return null;
        float cx = (minX + maxX) * .5f, cz = (minZ + maxZ) * .5f;

        float[] cellR = new float[CELLS * BINS];
        float[] cellF = new float[CELLS * BINS];
        float[] cellA = new float[CELLS * BINS];
        for (int i = 0; i < bodyLen; i += 3) {
            float dx = bodyVerts[i] - cx, dz = bodyVerts[i + 2] - cz;
            float f = (bodyVerts[i + 1] - minY) / bodyH;
            if (f < 0 || f > 1) continue;
            float r = (float) Math.hypot(dx, dz);
            int c = Math.min(CELLS - 1, Math.max(0, (int) (f * CELLS)));
            int b = bin(dx, dz);
            int at = c * BINS + b;
            if (r > cellR[at]) {
                cellR[at] = r;
                cellF[at] = f;
                cellA[at] = (float) Math.atan2(dz, dx);
            }
        }

        float[] cos = new float[BINS], sin = new float[BINS], ang = new float[BINS];
        float[] bodyR = new float[BINS], sampleAng = new float[BINS], loopF = new float[BINS];
        for (int i = 0; i < BINS; i++) {
            float a = binAngle(i);
            ang[i] = a;
            cos[i] = (float) Math.cos(a);
            sin[i] = (float) Math.sin(a);
            loopF[i] = heightAt(a);
            float[] sample = radiusNear(cellR, cellF, cellA, loopF[i], i);
            bodyR[i] = sample[0];
            sampleAng[i] = sample[1];
        }
        if (!hull(bodyR, sampleAng, cos, sin)) return null;

        float[] ringR = Arrays.copyOf(bodyR, BINS);
        if (hairVerts != null && hairLen >= 3) {
            float cap = HAIR_CAP * bodyH;
            for (int i = 0; i < hairLen; i += 3) {
                float dx = hairVerts[i] - cx, dz = hairVerts[i + 2] - cz;
                float f = (hairVerts[i + 1] - minY) / bodyH;
                int b = bin(dx, dz);
                if (Math.abs(f - loopF[b]) > HAIR_WINDOW) continue;
                float r = (float) Math.hypot(dx, dz);
                if (r > bodyR[b] + cap) continue;
                if (r > ringR[b]) ringR[b] = r;
            }
            // Convexify body+hair contour so the rope bridges tautly without inward kinks or hair clipping.
            hull(ringR, ang, cos, sin);
        }

        float meanR = 0f;
        for (int i = 0; i < BINS; i++) meanR += ringR[i];
        meanR /= BINS;
        // Scale rope thickness from both height and girth so vertical squash does not thin the rope.
        float ropeRef = (bodyH + meanR * 1.35f) * .5f;
        float ropeR = ROPE_RADIUS * ropeRef;
        float offset = (CLEARANCE + Math.max(0f, extraClearance)) * ropeRef + ropeR;
        Vector3f[] center = new Vector3f[BINS];
        for (int i = 0; i < BINS; i++) {
            float r = ringR[i] + offset;
            center[i] = new Vector3f(cx + cos[i] * r, minY + loopF[i] * bodyH, cz + sin[i] * r);
        }

        // Main loop: BINS * SIDES quads; knot collar (8*6) + hitch stub (6*6) + 2 hanging ends (2*4*6) = 132 quads -> plenty of room.
        FloatBuilder out = new FloatBuilder((BINS * SIDES + 160) * 6 * 8);
        Vector3f[] tangents = new Vector3f[BINS];
        Vector3f[] outNormals = new Vector3f[BINS];
        Vector3f[] binormals = new Vector3f[BINS];
        for (int i = 0; i < BINS; i++) {
            Vector3f prev = center[(i + BINS - 1) % BINS], next = center[(i + 1) % BINS];
            Vector3f t = new Vector3f(next).sub(prev).normalize();
            Vector3f u = new Vector3f(cos[i], 0, sin[i]);
            u.fma(-u.dot(t), t);
            if (u.lengthSquared() < 1e-8f) u.set(0, 1, 0);
            else u.normalize();
            Vector3f v = new Vector3f(t).cross(u).normalize();
            tangents[i] = t;
            outNormals[i] = u;
            binormals[i] = v;
        }

        float[] arc = new float[BINS + 1];
        for (int i = 0; i < BINS; i++) arc[i + 1] = arc[i] + center[i].distance(center[(i + 1) % BINS]);
        float tiles = Math.max(4f, Math.round(arc[BINS] / (ropeR * 6f)));
        float uScale = tiles / Math.max(1e-5f, arc[BINS]);

        float[] phiCos = new float[SIDES + 1], phiSin = new float[SIDES + 1];
        for (int s = 0; s <= SIDES; s++) {
            double phi = 2 * Math.PI * s / SIDES;
            phiCos[s] = (float) Math.cos(phi);
            phiSin[s] = (float) Math.sin(phi);
        }

        Vector3f p00 = new Vector3f(), p01 = new Vector3f(), p11 = new Vector3f(), p10 = new Vector3f();
        Vector3f n00 = new Vector3f(), n01 = new Vector3f(), n11 = new Vector3f(), n10 = new Vector3f();
        for (int i = 0; i < BINS; i++) {
            int j = (i + 1) % BINS;
            float u0 = arc[i] * uScale, u1 = arc[i + 1] * uScale;
            for (int s = 0; s < SIDES; s++) {
                float v0 = (float) s / SIDES, v1 = (float) (s + 1) / SIDES;
                ringPoint(center[i], outNormals[i], binormals[i], ropeR, phiCos[s], phiSin[s], p00, n00);
                ringPoint(center[i], outNormals[i], binormals[i], ropeR, phiCos[s + 1], phiSin[s + 1], p01, n01);
                ringPoint(center[j], outNormals[j], binormals[j], ropeR, phiCos[s + 1], phiSin[s + 1], p11, n11);
                ringPoint(center[j], outNormals[j], binormals[j], ropeR, phiCos[s], phiSin[s], p10, n10);
                // Outward CCW winding: (p01 - p00) x (p10 - p00) aligns with outward radial normal.
                emitQuad(out, p00, n00, u0, v0, p01, n01, u0, v1, p11, n11, u1, v1, p10, n10, u1, v0);
            }
        }

        // Back knot at +Z (angle = +pi/2): find the exact interpolated back point on the loop.
        Vector3f backCenter = sampleBack(center, ang);
        Vector3f backOut = new Vector3f(0, 0, 1);
        Vector3f knotCenter = new Vector3f(backCenter).add(0, 0, ropeR * .35f);
        // Chunky tied barrel knot wrapping the loop at the back.
        emitBarrel(out, knotCenter, new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1),
                ropeR * 1.55f, ropeR * 1.45f, ropeR * 1.55f, 8, 6);
        // Raised hitch eyelet extending +Z where the vanilla lead line joins.
        Vector3f hitchEnd = new Vector3f(knotCenter).add(0, ropeR * .2f, ropeR * 1.95f);
        emitSegment(out, knotCenter, hitchEnd, ropeR * .85f, ropeR * .65f, 6);
        // Two short drooping knot tails on left and right of the knot.
        for (int side = -1; side <= 1; side += 2) {
            Vector3f tailStart = new Vector3f(knotCenter).add(side * ropeR * .9f, -ropeR * .35f, ropeR * .45f);
            Vector3f tailMid = new Vector3f(tailStart).add(side * ropeR * .95f, -ropeR * 1.35f, ropeR * .35f);
            Vector3f tailTip = new Vector3f(tailMid).add(side * ropeR * .45f, -ropeR * 1.35f, -ropeR * .15f);
            emitSegment(out, tailStart, tailMid, ropeR * .68f, ropeR * .58f, 6);
            emitSegment(out, tailMid, tailTip, ropeR * .58f, ropeR * .40f, 6);
        }

        float[] anchor = {hitchEnd.x, hitchEnd.y, hitchEnd.z};
        return new Mesh(out.toArray(), anchor);
    }

    private static Vector3f sampleBack(Vector3f[] center, float[] ang) {
        int best = 0;
        float bestDiff = Float.MAX_VALUE;
        for (int i = 0; i < ang.length; i++) {
            float diff = Math.abs(wrapAngle(ang[i] - (float) (Math.PI / 2)));
            if (diff < bestDiff) { bestDiff = diff; best = i; }
        }
        int other = wrapAngle(ang[best] - (float) (Math.PI / 2)) > 0 ? (best + ang.length - 1) % ang.length : (best + 1) % ang.length;
        float a0 = wrapAngle(ang[best] - (float) (Math.PI / 2));
        float a1 = wrapAngle(ang[other] - (float) (Math.PI / 2));
        float t = Math.abs(a1 - a0) < 1e-5f ? 0f : Math.clamp(-a0 / (a1 - a0), 0f, 1f);
        return new Vector3f(center[best]).lerp(center[other], t);
    }

    private static void ringPoint(Vector3f c, Vector3f u, Vector3f v, float r, float cp, float sp, Vector3f pOut, Vector3f nOut) {
        nOut.set(u.x * cp + v.x * sp, u.y * cp + v.y * sp, u.z * cp + v.z * sp).normalize();
        pOut.set(c.x + nOut.x * r, c.y + nOut.y * r, c.z + nOut.z * r);
    }

    /** Closed rounded barrel / knot bulge along axis {@code axis} with orthogonal basis {@code b1, b2}. */
    private static void emitBarrel(FloatBuilder out, Vector3f center, Vector3f axis, Vector3f b1, Vector3f b2,
                                   float halfLen, float rad1, float rad2, int rings, int sides) {
        Vector3f p00 = new Vector3f(), p01 = new Vector3f(), p11 = new Vector3f(), p10 = new Vector3f();
        Vector3f n00 = new Vector3f(), n01 = new Vector3f(), n11 = new Vector3f(), n10 = new Vector3f();
        for (int r = 0; r < rings; r++) {
            float t0 = (float) r / rings, t1 = (float) (r + 1) / rings;
            float a0 = (t0 - .5f) * (float) Math.PI, a1 = (t1 - .5f) * (float) Math.PI;
            float x0 = (float) Math.sin(a0) * halfLen, x1 = (float) Math.sin(a1) * halfLen;
            float k0 = Math.max(.08f, (float) Math.cos(a0)), k1 = Math.max(.08f, (float) Math.cos(a1));
            for (int s = 0; s < sides; s++) {
                double ph0 = 2 * Math.PI * s / sides, ph1 = 2 * Math.PI * (s + 1) / sides;
                float c0 = (float) Math.cos(ph0), s0 = (float) Math.sin(ph0);
                float c1 = (float) Math.cos(ph1), s1 = (float) Math.sin(ph1);
                barrelVert(center, axis, b1, b2, x0, rad1 * k0, rad2 * k0, c0, s0, (float) Math.sin(a0), p00, n00);
                barrelVert(center, axis, b1, b2, x0, rad1 * k0, rad2 * k0, c1, s1, (float) Math.sin(a0), p01, n01);
                barrelVert(center, axis, b1, b2, x1, rad1 * k1, rad2 * k1, c1, s1, (float) Math.sin(a1), p11, n11);
                barrelVert(center, axis, b1, b2, x1, rad1 * k1, rad2 * k1, c0, s0, (float) Math.sin(a1), p10, n10);
                emitQuad(out, p00, n00, t0 * 2f, (float) s / sides, p01, n01, t0 * 2f, (float) (s + 1) / sides,
                        p11, n11, t1 * 2f, (float) (s + 1) / sides, p10, n10, t1 * 2f, (float) s / sides);
            }
        }
    }

    private static void barrelVert(Vector3f center, Vector3f axis, Vector3f b1, Vector3f b2,
                                   float along, float r1, float r2, float cp, float sp, float axN,
                                   Vector3f pOut, Vector3f nOut) {
        pOut.set(center).fma(along, axis).fma(r1 * cp, b1).fma(r2 * sp, b2);
        nOut.set(0, 0, 0).fma(axN * .6f, axis).fma(cp, b1).fma(sp, b2).normalize();
    }

    /** Tapered capped rope segment from {@code from} to {@code to}. */
    private static void emitSegment(FloatBuilder out, Vector3f from, Vector3f to, float r0, float r1, int sides) {
        Vector3f t = new Vector3f(to).sub(from);
        float len = t.length();
        if (len < 1e-6f) return;
        t.div(len);
        Vector3f u = Math.abs(t.y) < .9f ? new Vector3f(0, 1, 0).cross(t).normalize() : new Vector3f(1, 0, 0).cross(t).normalize();
        Vector3f v = new Vector3f(t).cross(u).normalize();
        Vector3f p00 = new Vector3f(), p01 = new Vector3f(), p11 = new Vector3f(), p10 = new Vector3f();
        Vector3f n0 = new Vector3f(), n1 = new Vector3f();
        for (int s = 0; s < sides; s++) {
            double ph0 = 2 * Math.PI * s / sides, ph1 = 2 * Math.PI * (s + 1) / sides;
            float c0 = (float) Math.cos(ph0), s0 = (float) Math.sin(ph0);
            float c1 = (float) Math.cos(ph1), s1 = (float) Math.sin(ph1);
            ringPoint(from, u, v, r0, c0, s0, p00, n0);
            ringPoint(from, u, v, r0, c1, s1, p01, n1);
            ringPoint(to, u, v, r1, c1, s1, p11, n1);
            ringPoint(to, u, v, r1, c0, s0, p10, n0);
            emitQuad(out, p00, n0, 0f, (float) s / sides, p01, n1, 0f, (float) (s + 1) / sides,
                    p11, n1, 1f, (float) (s + 1) / sides, p10, n0, 1f, (float) s / sides);
            // End cap at `to`
            emitTri(out, to, t, .5f, .5f, p10, t, 1f, (float) s / sides, p11, t, 1f, (float) (s + 1) / sides);
        }
    }

    private static void emitQuad(FloatBuilder out,
                                 Vector3f a, Vector3f na, float ua, float va,
                                 Vector3f b, Vector3f nb, float ub, float vb,
                                 Vector3f c, Vector3f nc, float uc, float vc,
                                 Vector3f d, Vector3f nd, float ud, float vd) {
        emitTri(out, a, na, ua, va, b, nb, ub, vb, c, nc, uc, vc);
        emitTri(out, a, na, ua, va, c, nc, uc, vc, d, nd, ud, vd);
    }

    private static void emitTri(FloatBuilder out,
                                Vector3f a, Vector3f na, float ua, float va,
                                Vector3f b, Vector3f nb, float ub, float vb,
                                Vector3f c, Vector3f nc, float uc, float vc) {
        out.add(a, ua, va, na);
        out.add(b, ub, vb, nb);
        out.add(c, uc, vc, nc);
    }

    private static int bin(float dx, float dz) {
        double a = Math.atan2(dz, dx) + Math.PI;
        return Math.min(BINS - 1, Math.max(0, (int) (a / (2 * Math.PI) * BINS)));
    }

    private static float binAngle(int bin) {
        return (float) (-Math.PI + 2 * Math.PI * (bin + .5) / BINS);
    }

    private static float wrapAngle(float a) {
        return (float) Math.atan2(Math.sin(a), Math.cos(a));
    }

    private static float[] radiusAt(float[] cellR, float[] cellF, float[] cellA, float f, int bin) {
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
        if (loF < 0) return hiF > 1 ? new float[]{0, centre} : new float[]{hiR, centre + hiA};
        if (hiF > 1) return new float[]{loR, centre + loA};
        float t = (f - loF) / (hiF - loF);
        return new float[]{loR + (hiR - loR) * t, centre + loA + (hiA - loA) * t};
    }

    private static float[] radiusNear(float[] cellR, float[] cellF, float[] cellA, float f, int bin) {
        float[] best = radiusAt(cellR, cellF, cellA, f, bin);
        for (int d = -1; d <= 1; d += 2) {
            float[] other = radiusAt(cellR, cellF, cellA, f, (bin + d + BINS) % BINS);
            if (other[0] > best[0]) best = other;
        }
        int lo = Math.max(0, (int) ((f - NEAR_WINDOW) * CELLS));
        int hi = Math.min(CELLS - 1, (int) ((f + NEAR_WINDOW) * CELLS));
        for (int c = lo; c <= hi; c++) {
            for (int d = -1; d <= 1; d++) {
                int at = c * BINS + (bin + d + BINS) % BINS;
                if (cellR[at] > best[0]) { best[0] = cellR[at]; best[1] = cellA[at]; }
            }
        }
        return best;
    }

    private static boolean hull(float[] radius, float[] ang, float[] cos, float[] sin) {
        float[] hx = new float[BINS], hz = new float[BINS];
        int n = 0;
        for (int i = 0; i < BINS; i++) {
            if (radius[i] > 0) {
                hx[n] = (float) Math.cos(ang[i]) * radius[i];
                hz[n] = (float) Math.sin(ang[i]) * radius[i];
                n++;
            }
        }
        if (n < 3) return false;
        for (int i = 1; i < n; i++) {
            for (int k = i; k > 0 && (hx[k] < hx[k - 1] || hx[k] == hx[k - 1] && hz[k] < hz[k - 1]); k--) {
                float tx = hx[k], tz = hz[k];
                hx[k] = hx[k - 1]; hz[k] = hz[k - 1];
                hx[k - 1] = tx; hz[k - 1] = tz;
            }
        }
        float[] ox = new float[2 * BINS + 1], oz = new float[2 * BINS + 1];
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

    private static final class FloatBuilder {
        private float[] data;
        private int size;
        FloatBuilder(int capacity) { data = new float[capacity]; }
        void add(Vector3f p, float u, float v, Vector3f n) {
            if (size + 8 > data.length) data = Arrays.copyOf(data, data.length * 2);
            data[size++] = p.x; data[size++] = p.y; data[size++] = p.z;
            data[size++] = u; data[size++] = v;
            data[size++] = n.x; data[size++] = n.y; data[size++] = n.z;
        }
        float[] toArray() { return Arrays.copyOf(data, size); }
    }
}
