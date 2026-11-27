package io.github.longshou.mcyukkuri.pose;

import org.joml.Vector3f;

/**
 * The rope a leashed yukkuri wears: one girth line of the body's own skin, with a knot at the back where the lead ends.
 * Shared by the game client and the offline preview (no Minecraft imports).
 *
 * <p>{@link #fit} runs once per model at rest: it casts a horizontal ray from the body axis at each of {@link #BINS} azimuths
 * and keeps the skin triangle it leaves through, with the hit's barycentric weights. {@link #build} then reads the posed
 * positions of just those triangle corners, so every rope point stays on the same skin point through squash, tilt, growth and
 * any pose; there is no separate fit. The tube is centred a little above the skin. Hair the rest ray meets outside the skin
 * (sideburns, braids, back hair) lowers the rope under it, so the hair's own depth hides the rope and the hair is never wrapped.
 * The rope is thin and the hair is drawn before it, which makes the depth test the occluder.
 */
public final class LeashRing {
    public static final int BINS = 40, SIDES = 5;
    /** Girth height as a fraction of the body height: low at the front (under the mouth), rising to the lead's knot at the back. */
    public static final float FRONT_AT = .17f, BACK_AT = .24f;
    /** Rope radius and the rope centre's rest lift above the skin, in model units (1/16 block). */
    public static final float RADIUS = .3f, LIFT = .08f;
    /** Gap kept between the rope's outer side and a hair surface above it, and the deepest the rope sinks under the skin. */
    private static final float HAIR_GAP = .06f, SINK = -1.2f * RADIUS;
    /** A hair surface this far (or more) outside the skin does not affect the rope. */
    private static final float HAIR_REACH = 6 * RADIUS;
    /** Floats per triangle in {@link #vertices()}: three vertices of position, uv and normal. */
    public static final int TRIANGLE = 24;
    private static final int KNOT_TRIANGLES = 12;
    /** The rope's extra lift over a worn wrap, as a fraction of the body height (CareWearRenderer's cloth stands .022 clear of the skin). */
    private static final float WRAP = .03f;
    private static final float U0 = .06f, U1 = .94f;

    /** Posed position of a model vertex, in the space the mesh is drawn in. */
    public interface Skin { void position(int vertex, Vector3f out); }

    /** Model body-surface part names: the soft head slices, rounded, house, predator and deficient bodies. */
    public static boolean bodySurface(String name) {
        return name.startsWith("soft_head_slice_") || name.equals("rounded_body_surface") || name.equals("house_body_surface")
                || name.equals("predator_body_surface") || name.equals("deficient_body_surface");
    }

    /** Hair, sidelocks, braids and headwear: every part that is not the body, the face cards, anatomy, puffs, rump marks, tails or wings. */
    public static boolean hair(String name, boolean anatomy) {
        return !bodySurface(name) && !name.startsWith("face_") && !anatomy && !name.contains("puff") && !name.contains("rump")
                && !name.contains("rear_mark") && !name.contains("tail") && !name.contains("wing");
    }

    private final int[] corner = new int[BINS * 3];
    private final float[] weight = new float[BINS * 3];
    private final float[] lift = new float[BINS];
    private final float[] clearance = new float[BINS];
    private final float bodyHeight;

    private final Vector3f[] skin = new Vector3f[BINS], radial = new Vector3f[BINS];
    private final float[] ring = new float[BINS * SIDES * 3], ringNormal = new float[BINS * SIDES * 3];
    private final Vector3f a = new Vector3f(), b = new Vector3f(), axis = new Vector3f(), centre = new Vector3f(),
            tangent = new Vector3f(), c0 = new Vector3f(), c1 = new Vector3f(), face = new Vector3f();
    private final float[] mesh = new float[(BINS * SIDES * 2 + KNOT_TRIANGLES) * TRIANGLE];
    private int length;

    private LeashRing(float bodyHeight) {
        this.bodyHeight = bodyHeight;
        for (int i = 0; i < BINS; i++) { skin[i] = new Vector3f(); radial[i] = new Vector3f(); }
    }

    /**
     * Fits the rope to a model at rest. {@code rest} holds x, y, z of vertex {@code v} at {@code v * stride}; the ranges are
     * (first vertex, vertex count) pairs of triangle lists: the body skin, and the hair the rope must pass under.
     */
    public static LeashRing fit(float[] rest, int stride, int[] bodyRanges, int[] hairRanges) {
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int r = 0; r < bodyRanges.length; r += 2)
            for (int v = bodyRanges[r]; v < bodyRanges[r] + bodyRanges[r + 1]; v++) {
                minY = Math.min(minY, rest[v * stride + 1]);
                maxY = Math.max(maxY, rest[v * stride + 1]);
            }
        float height = maxY - minY, low = minY + FRONT_AT * height, high = minY + BACK_AT * height;
        // The axis runs through the middle of the girth band, so a body offset from the model origin is still ringed about itself.
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int r = 0; r < bodyRanges.length; r += 2)
            for (int v = bodyRanges[r]; v < bodyRanges[r] + bodyRanges[r + 1]; v++) {
                float y = rest[v * stride + 1];
                if (y < low - .15f * height || y > high + .15f * height) continue;
                minX = Math.min(minX, rest[v * stride]); maxX = Math.max(maxX, rest[v * stride]);
                minZ = Math.min(minZ, rest[v * stride + 2]); maxZ = Math.max(maxZ, rest[v * stride + 2]);
            }
        float cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;
        var ring = new LeashRing(height);
        var hit = new float[4];
        float[] raw = new float[BINS];
        for (int k = 0; k < BINS; k++) {
            double theta = 2 * Math.PI * k / BINS;
            float dx = (float) Math.sin(theta), dz = (float) Math.cos(theta);
            float y = minY + (FRONT_AT + (BACK_AT - FRONT_AT) * (1 + dz) / 2) * height;
            if (!cast(rest, stride, bodyRanges, cx, y, cz, dx, dz, true, hit))
                throw new IllegalStateException("girth ray misses the body at bin " + k);
            ring.corner[k * 3] = (int) hit[0]; ring.corner[k * 3 + 1] = (int) hit[0] + 1; ring.corner[k * 3 + 2] = (int) hit[0] + 2;
            ring.weight[k * 3] = 1 - hit[1] - hit[2]; ring.weight[k * 3 + 1] = hit[1]; ring.weight[k * 3 + 2] = hit[2];
            // Hair outside the skin along the same ray; a hair surface at distance d above the skin leaves d - gap - radius for the rope centre.
            float sx = cx + dx * hit[3], sz = cz + dz * hit[3];
            ring.clearance[k] = cast(rest, stride, hairRanges, sx, y, sz, dx, dz, false, hit) && hit[3] < HAIR_REACH
                    ? Math.max(hit[3], 0) : Float.POSITIVE_INFINITY;
            raw[k] = Math.max(SINK, Math.min(LIFT, ring.clearance[k] - HAIR_GAP - RADIUS));
        }
        // Hair is wider than one bin: a bin also sinks to its neighbours' depth.
        for (int k = 0; k < BINS; k++)
            ring.lift[k] = Math.min(raw[k], Math.min(raw[(k + BINS - 1) % BINS], raw[(k + 1) % BINS]));
        return ring;
    }

    /**
     * Casts a horizontal ray at height {@code y} from (ox, oz) along (dx, dz) against the triangle lists; the outermost hit when
     * {@code far}, otherwise the nearest hit at or beyond the origin (hair already under the skin is ignored). Writes
     * {first vertex of the triangle, weight of corner 1, weight of corner 2, distance} to {@code out}.
     */
    private static boolean cast(float[] v, int stride, int[] ranges, float ox, float y, float oz, float dx, float dz,
            boolean far, float[] out) {
        boolean found = false;
        float best = far ? -Float.MAX_VALUE : Float.MAX_VALUE;
        for (int r = 0; r < ranges.length; r += 2)
            for (int t = ranges[r]; t < ranges[r] + ranges[r + 1]; t += 3) {
                int p = t * stride, q = (t + 1) * stride, s = (t + 2) * stride;
                if (Math.min(v[p + 1], Math.min(v[q + 1], v[s + 1])) > y || Math.max(v[p + 1], Math.max(v[q + 1], v[s + 1])) < y) continue;
                // Moller-Trumbore with the ray direction (dx, 0, dz).
                float e1x = v[q] - v[p], e1y = v[q + 1] - v[p + 1], e1z = v[q + 2] - v[p + 2];
                float e2x = v[s] - v[p], e2y = v[s + 1] - v[p + 1], e2z = v[s + 2] - v[p + 2];
                float px = -dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y;
                float det = e1x * px + e1y * py + e1z * pz;
                if (Math.abs(det) < 1e-9f) continue;
                float tx = ox - v[p], ty = y - v[p + 1], tz = oz - v[p + 2];
                float u = (tx * px + ty * py + tz * pz) / det;
                if (u < -1e-5f || u > 1 + 1e-5f) continue;
                float qx = ty * e1z - tz * e1y, qy = tz * e1x - tx * e1z, qz = tx * e1y - ty * e1x;
                float w = (dx * qx + dz * qz) / det;
                if (w < -1e-5f || u + w > 1 + 1e-5f) continue;
                float d = (e2x * qx + e2y * qy + e2z * qz) / det;
                if (far ? d > best : d >= -.05f && d < best) { best = d; found = true; out[0] = t; out[1] = u; out[2] = w; out[3] = d; }
            }
        return found;
    }

    /** Rest lift of the rope centre above the skin at a bin: negative where hair lies over it and the rope sinks under. */
    public float lift(int bin) { return lift[bin]; }

    /** Distance from the skin to the hair surface over a bin at rest, infinite when no hair covers it. */
    public float clearance(int bin) { return clearance[bin]; }

    /** Height of the body the rope was fitted to, in model units. */
    public float bodyHeight() { return bodyHeight; }

    /** The knot's bin (back of the body, +Z). */
    public static int knotBin() { return 0; }

    /**
     * Builds the rope from the posed skin. {@code unit} is the size of one model unit in the skin's space (1/16 for blocks);
     * {@code wrapped} raises the rope over a cloth or leaf wrap lying on the skin. Reuses its own buffers.
     */
    public void build(Skin posed, float unit, boolean wrapped) {
        float wrapLift = wrapped ? WRAP * bodyHeight : 0;
        centre.zero();
        for (int k = 0; k < BINS; k++) {
            skin[k].zero();
            for (int c = 0; c < 3; c++) {
                posed.position(corner[k * 3 + c], a);
                skin[k].fma(weight[k * 3 + c], a);
            }
            centre.add(skin[k]);
        }
        centre.div(BINS);
        // The girth line's axis, from its own winding (back toward +X is up), then each bin's radial direction around it.
        axis.zero();
        for (int k = 0; k < BINS; k++) {
            a.set(skin[k]).sub(centre);
            b.set(skin[(k + 1) % BINS]).sub(centre);
            axis.add(a.cross(b));
        }
        axis.normalize();
        for (int k = 0; k < BINS; k++) {
            a.set(skin[k]).sub(centre);
            a.fma(-a.dot(axis), axis).normalize();
            radial[k].set(a);
        }
        float r = RADIUS * unit;
        for (int k = 0; k < BINS; k++) {
            float out = (lift[k] + wrapLift) * unit;
            for (int j = 0; j < SIDES; j++) {
                double phi = 2 * Math.PI * j / SIDES;
                float cos = (float) Math.cos(phi), sin = (float) Math.sin(phi);
                int at = (k * SIDES + j) * 3;
                ringNormal[at] = radial[k].x * cos + axis.x * sin;
                ringNormal[at + 1] = radial[k].y * cos + axis.y * sin;
                ringNormal[at + 2] = radial[k].z * cos + axis.z * sin;
                ring[at] = skin[k].x + radial[k].x * out + ringNormal[at] * r;
                ring[at + 1] = skin[k].y + radial[k].y * out + ringNormal[at + 1] * r;
                ring[at + 2] = skin[k].z + radial[k].z * out + ringNormal[at + 2] * r;
            }
        }
        length = 0;
        for (int k = 0; k < BINS; k++)
            for (int j = 0; j < SIDES; j++) {
                int n = (k + 1) % BINS, m = (j + 1) % SIDES;
                int p00 = (k * SIDES + j) * 3, p01 = (k * SIDES + m) * 3, p10 = (n * SIDES + j) * 3, p11 = (n * SIDES + m) * 3;
                vertex(ring, ringNormal, p00, U0, 0);
                vertex(ring, ringNormal, p10, U1, 0);
                vertex(ring, ringNormal, p11, U1, 1);
                vertex(ring, ringNormal, p00, U0, 0);
                vertex(ring, ringNormal, p11, U1, 1);
                vertex(ring, ringNormal, p01, U0, 1);
            }
        knot(r, (lift[0] + wrapLift) * unit);
    }

    /** The lead's knot: a small box at the back, snug on the rope and standing a little proud of it. */
    private void knot(float r, float out) {
        tangent.set(skin[1]).sub(skin[BINS - 1]).normalize();
        c0.set(skin[0]).fma(out + .5f * r, radial[0]);
        box(c0, radial[0], tangent, axis, 1.5f * r, 1.6f * r, 1.3f * r);
    }

    private final Vector3f[] box = {new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f(),
            new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()};
    private static final int[][] BOX_FACES = {{0, 1, 3, 2}, {4, 6, 7, 5}, {0, 4, 5, 1}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 5, 7, 3}};

    private void box(Vector3f middle, Vector3f u, Vector3f t, Vector3f w, float hu, float ht, float hw) {
        for (int i = 0; i < 8; i++)
            box[i].set(middle).fma((i & 4) == 0 ? -hu : hu, u).fma((i & 2) == 0 ? -ht : ht, t).fma((i & 1) == 0 ? -hw : hw, w);
        for (int[] f : BOX_FACES) {
            a.set(box[f[1]]).sub(box[f[0]]);
            b.set(box[f[3]]).sub(box[f[0]]);
            face.set(a).cross(b).normalize();
            // Outward: away from the box centre.
            c1.set(box[f[0]]).sub(middle);
            if (face.dot(c1) < 0) face.negate();
            quad(box[f[0]], box[f[1]], box[f[2]], box[f[3]], face);
        }
    }

    private void quad(Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3, Vector3f n) {
        corner(p0, n, U0, 0); corner(p1, n, U1, 0); corner(p2, n, U1, 1);
        corner(p0, n, U0, 0); corner(p2, n, U1, 1); corner(p3, n, U0, 1);
    }

    private void corner(Vector3f p, Vector3f n, float u, float v) {
        mesh[length] = p.x; mesh[length + 1] = p.y; mesh[length + 2] = p.z;
        mesh[length + 3] = u; mesh[length + 4] = v;
        mesh[length + 5] = n.x; mesh[length + 6] = n.y; mesh[length + 7] = n.z;
        length += 8;
    }

    private void vertex(float[] pos, float[] normal, int at, float u, float v) {
        mesh[length] = pos[at]; mesh[length + 1] = pos[at + 1]; mesh[length + 2] = pos[at + 2];
        mesh[length + 3] = u; mesh[length + 4] = v;
        mesh[length + 5] = normal[at]; mesh[length + 6] = normal[at + 1]; mesh[length + 7] = normal[at + 2];
        length += 8;
    }

    /** The last {@link #build}'s triangles: 8 floats per vertex (position, uv, normal), valid up to {@link #length()}. Not copied. */
    public float[] vertices() { return mesh; }

    /** Floats written by the last {@link #build}; a multiple of {@link #TRIANGLE}. */
    public int length() { return length; }
}
