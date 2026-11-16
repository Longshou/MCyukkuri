package io.github.longshou.mcyukkuri.pose;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Predicate;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * How the parts that hang off the body give way to nearby walls. A limb (wing, tail, side lock) turns about its own root
 * by the least angle that clears the faces, up to its limit; whatever still reaches past a face, and every other loose
 * part, is pressed onto the free side of that face. Only the drawn mesh changes. Shared by the client and the offline preview.
 */
public final class WallYield {
    /** One collision face beside the body: unit horizontal direction from the body axis to its nearest point, the distance
     *  to it, and the height span of the shape it belongs to (above the feet). */
    public record Face(float x, float z, float distance, float low, float high) {}
    /** The posed, unyielded position of one model vertex (model units). */
    @FunctionalInterface public interface Skinner { void skin(int index, Vector3f target); }

    /**
     * One kind of limb: its hinge bones, the axis the right-hand one turns about in its parent bone's frame (y and z mirror
     * for a left-hand one), the largest turn of its tip in degrees, and its stiffness: 1 turns the whole limb at the root,
     * 0 spreads the turn evenly along its length so it curls. Project design numbers:
     * a wing droops beside the back, stopping before its tip reaches the floor; a tail curls up over itself as a cat's
     * does against a wall; a side lock or braid swings in until it lies on the cheek.
     */
    private record Kind(String id, List<String> bones, float x, float y, float z, float limit, float stiffness) {}
    private static final List<Kind> KINDS = List.of(
            new Kind("wing", List.of("predator_wing_left", "predator_wing_right"), 0, -.34f, -.94f, 95, 1),
            new Kind("tail", List.of("chen_tail_left", "chen_tail_right"), -1, 0, 0, 175, .2f),
            new Kind("lock", List.of("hand_left", "hand_right", "sideburn_left", "sideburn_right"), 0, 0, -1, 16, 1));
    /** Candidate turns tried before refining; the residual a turn may leave for the press (blocks). */
    private static final int STEPS = 12;
    private static final float SLACK = 1 / 256f;
    /** Most turn spent per block of clearance gained (16 degrees per quarter model unit), so a grazing or rear-quarter wall
     *  unwinds smoothly instead of snapping across a non-monotonic hump when {@code rest - best} crosses {@link #SLACK}. */
    private static final float GAIN = 1024;

    private static final class Limb {
        Kind kind;
        int bone;
        float side;
        /** Extreme vertices of each segment, root to tip, and each segment's share of the whole turn. */
        int[][] samples;
        float[] share;
        Vector3f[][] samplePosed;
        Vector3f[] posed, joints;
        Matrix4f[] turns;
        boolean turned;
    }

    private final Rig.Bone[] bones;
    private final List<Limb> limbs = new ArrayList<>();
    /** Limb and segment of each vertex ({@code limb << 8 | segment}), or -1. */
    private final int[] owner;
    private boolean any;
    private final Vector3f axis = new Vector3f(), point = new Vector3f(), carried = new Vector3f();
    private final float[] reached = new float[STEPS + 1];

    private WallYield(Rig.Bone[] bones, int vertices) { this.bones = bones; owner = new int[vertices]; Arrays.fill(owner, -1); }

    /** Parts drawn apart from the body surface: hair, locks, braids, wings, tails and headwear. The body, its face, its
     *  marks and its anatomy keep the body's own collision. */
    public static boolean loose(String part) {
        return !(part.isEmpty() || part.endsWith("_body_surface") || part.startsWith("soft_head_slice_") || part.startsWith("face_")
                || part.startsWith("puff_") || part.startsWith("mamu_") || part.startsWith("peni_") || part.contains("rump")
                || part.endsWith("_mark"));
    }

    /** The limbs of one model, or null when it has none. Parts are given as parallel name, start and count arrays. */
    public static WallYield of(Rig.Bone[] bones, Skin.Binding[] bindings, float[] rest, String[] names, int[] starts, int[] counts) {
        var result = new WallYield(bones, bindings.length);
        for (var kind : KINDS) for (int bone = 0; bone < bones.length; bone++) {
            if (!kind.bones.contains(bones[bone].name)) continue;
            var root = new Vector3f(bones[bone].x, bones[bone].y, bones[bone].z);
            var parts = new ArrayList<Integer>();
            for (int p = 0; p < names.length; p++)
                if (counts[p] > 0 && loose(names[p]) && Rig.descends(bones, bindings[starts[p]].first(), bone)) parts.add(p);
            if (parts.isEmpty()) continue;
            var limb = new Limb();
            limb.kind = kind; limb.bone = bone; limb.side = root.x < 0 ? -1 : 1;
            // A curling limb is a chain of its parts ordered from the root; a stiff one is a single piece.
            List<List<Integer>> segments = new ArrayList<>();
            if (kind.stiffness >= 1) segments.add(parts);
            else {
                parts.sort(Comparator.comparingDouble(p -> centre(rest, starts[p], counts[p]).distance(root)));
                for (int p : parts) segments.add(List.of(p));
            }
            int n = segments.size();
            limb.samples = new int[n][]; limb.share = new float[n]; limb.turns = new Matrix4f[n]; limb.joints = new Vector3f[n];
            limb.posed = new Vector3f[n]; limb.samplePosed = new Vector3f[n][];
            for (int k = 0; k < n; k++) {
                var extremes = new LinkedHashSet<Integer>();
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    int best = -1; float reach = -Float.MAX_VALUE;
                    for (int p : segments.get(k)) for (int v = starts[p]; v < starts[p] + counts[p]; v++) {
                        float along = rest[v * 8] * dx + rest[v * 8 + 1] * dy + rest[v * 8 + 2] * dz;
                        if (along > reach) { reach = along; best = v; }
                    }
                    extremes.add(best);
                }
                limb.samples[k] = extremes.stream().mapToInt(Integer::intValue).toArray();
                limb.samplePosed[k] = new Vector3f[limb.samples[k].length];
                for (int i = 0; i < limb.samplePosed[k].length; i++) limb.samplePosed[k][i] = new Vector3f();
                limb.share[k] = kind.stiffness + (1 - kind.stiffness) * (k + 1) / n;
                limb.turns[k] = new Matrix4f(); limb.joints[k] = new Vector3f(); limb.posed[k] = new Vector3f();
                for (int p : segments.get(k)) Arrays.fill(result.owner, starts[p], starts[p] + counts[p], result.limbs.size() << 8 | k);
            }
            result.limbs.add(limb);
        }
        return result.limbs.isEmpty() ? null : result;
    }
    private static Vector3f centre(float[] rest, int start, int count) {
        var sum = new Vector3f();
        for (int v = start; v < start + count; v++) sum.add(rest[v * 8], rest[v * 8 + 1], rest[v * 8 + 2]);
        return sum.div(count);
    }

    /** Leave every limb as posed. */
    public void clear() { any = false; for (var limb : limbs) limb.turned = false; }

    /**
     * Turn each limb for this frame. {@code world} holds the final bone transforms, {@code toWall} takes posed model units to
     * the faces' frame (body axis at the origin, y above the feet), and {@code busy} names the kinds that are doing something
     * of their own this frame and so are only pressed.
     */
    public void solve(Skinner skin, Matrix4f[] world, Matrix4f toWall, List<Face> faces, Predicate<String> busy) {
        clear();
        for (var limb : limbs) {
            if (busy.test(limb.kind.id)) continue;
            int n = limb.samples.length;
            var posed = limb.samplePosed;
            for (int k = 0; k < n; k++) {
                limb.posed[k].zero();
                for (int i = 0; i < posed[k].length; i++) {
                    skin.skin(limb.samples[k][i], posed[k][i]);
                    limb.posed[k].add(posed[k][i]);
                }
                limb.posed[k].div(posed[k].length);
            }
            float rest = reach(limb, posed, 0, toWall, faces);
            if (rest <= 0) continue;
            var hinge = bones[limb.bone];
            world[limb.bone].transformPosition(limb.joints[0].set(hinge.x, hinge.y, hinge.z));
            for (int k = 1; k < n; k++) limb.joints[k].set(limb.posed[k - 1]).add(limb.posed[k]).mul(.5f);
            axis.set(limb.kind.x, limb.kind.y * limb.side, limb.kind.z * limb.side);
            if (hinge.parent >= 0) world[hinge.parent].transformDirection(axis);
            axis.normalize();
            // The least turn that does as well as any turn can: a limb does not fold for a wall its folding cannot clear.
            reached[0] = rest;
            float best = rest;
            for (int step = 1; step <= STEPS; step++) {
                turn(limb, limb.kind.limit * step / STEPS);
                best = Math.min(best, reached[step] = reach(limb, posed, 1, toWall, faces));
            }
            float allowed = Math.max(0, best) + SLACK;
            if (rest <= allowed) continue;
            int first = 1;
            while (reached[first] > allowed) first++;
            float low = limb.kind.limit * (first - 1) / STEPS, high = limb.kind.limit * first / STEPS;
            for (int i = 0; i < 6; i++) {
                float middle = (low + high) / 2;
                turn(limb, middle);
                if (reach(limb, posed, 1, toWall, faces) > allowed) low = middle; else high = middle;
            }
            turn(limb, Math.min(high, (rest - Math.max(0, best) - SLACK) * GAIN));
            limb.turned = any = true;
        }
    }
    /** Segment transforms in posed model space for one turn of the tip (degrees). */
    private void turn(Limb limb, float degrees) {
        carried.set(limb.joints[0]);
        for (int k = 0; k < limb.turns.length; k++) {
            var joint = limb.joints[k];
            limb.turns[k].translation(carried).rotate((float)Math.toRadians(degrees * limb.share[k]), axis).translate(-joint.x, -joint.y, -joint.z);
            if (k + 1 < limb.turns.length) limb.turns[k].transformPosition(carried.set(limb.joints[k + 1]));
        }
    }
    /** How far the limb reaches past the faces (blocks, at most 0 when clear), turned by {@code turned} of its current turn. */
    private float reach(Limb limb, Vector3f[][] posed, int turned, Matrix4f toWall, List<Face> faces) {
        float past = -Float.MAX_VALUE;
        for (int k = 0; k < posed.length; k++) for (var sample : posed[k]) {
            point.set(sample);
            if (turned != 0) limb.turns[k].transformPosition(point);
            toWall.transformPosition(point);
            for (var face : faces)
                if (point.y >= face.low && point.y <= face.high) past = Math.max(past, face.x * point.x + face.z * point.z - face.distance);
        }
        return past;
    }

    /** Whether any limb turned this frame. */
    public boolean turned() { return any; }
    /** Move one posed vertex (model units) with its limb's turn. */
    public void apply(int index, Vector3f target) {
        int code = owner[index];
        if (code < 0) return;
        var limb = limbs.get(code >> 8);
        if (limb.turned) limb.turns[code & 255].transformPosition(target);
    }

    /** The share of its horizontal offset from the body axis a point keeps so that it lies on the free side of every face
     *  at its height. Triangles whose corners share the same height band stay clear between their corners as well. */
    public static float press(List<Face> faces, float side, float height, float front) {
        float fraction = 1;
        for (var face : faces) {
            if (height < face.low || height > face.high) continue;
            float toward = face.x * side + face.z * front;
            if (toward > face.distance) fraction = Math.min(fraction, face.distance / toward);
        }
        return fraction;
    }
}
