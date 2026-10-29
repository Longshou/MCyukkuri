package io.github.longshou.mcyukkuri.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Chen's tails while carrying (ParentCarryPresentation): each rigid tail segment turns about its own joint so the tail
 * rises behind the body, runs flat under the child and curls its tip up around it; a puff tail is lifted instead. The
 * curl blends from the authored rest (0) to the cradle (1); only the drawn mesh changes. Project design numbers.
 */
final class CarryTailCurl {
    /** Outward yaw of each tail, the elevation every segment points at when cradling and the first of the two flat segments
     *  the child sits on (or one lift for a puff, with the seat shift out and back on it). */
    private record Spec(float yaw, float[] pitch, int shelf, float lift, float shiftOut, float shiftBack) {}
    private static final Map<String, Spec> SPECS = Map.of(
            "chen", new Spec(40, new float[]{58, 36, 4, -6, 24, 56, 84}, 2, 0, 0, 0),
            "chen_longtail", new Spec(36, new float[]{62, 6, -6, 40, 95, 140, 175}, 1, 0, 0, 0),
            "chen_roundtail", new Spec(25, null, 0, 35, 1, 1.8f));
    /** The child sits this far above the shelf's top face (model units). */
    private static final float SHELF_LIFT = .15f;
    private record Chain(int bone, int[] parts, Vector3f[] joints, Vector3f[] centres, float[] tops) {}

    private final Spec spec;
    private final Chain[] chains = new Chain[2];
    private final Map<Integer, Matrix4f> parts = new HashMap<>();
    private final Matrix4f[] cradles = new Matrix4f[2];

    private CarryTailCurl(Spec spec) { this.spec = spec; }

    /** The curl of this model's two tails, or null when the form has none. */
    static CarryTailCurl of(YukkuriModel model) {
        var curl = new CarryTailCurl(SPECS.getOrDefault(model.id(), SPECS.get("chen")));
        var bones = model.bones();
        var rest = model.restVertices();
        boolean any = false;
        for (int side = 0; side < 2; side++) {
            String name = "chen_tail_" + (side == 0 ? "left" : "right");
            int bone = -1;
            for (int i = 0; i < bones.length; i++) if (bones[i].name().equals(name)) bone = i;
            if (bone < 0) continue;
            var pattern = Pattern.compile("^" + name + "(\\d+)$");
            var found = new ArrayList<int[]>();
            var list = model.parts();
            for (int i = 0; i < list.size(); i++) {
                var match = pattern.matcher(list.get(i).name());
                if (match.matches()) found.add(new int[]{Integer.parseInt(match.group(1)), i});
            }
            if (found.isEmpty()) continue;
            found.sort((a, b) -> Integer.compare(a[0], b[0]));
            int n = found.size();
            int[] indices = new int[n];
            var centres = new Vector3f[n];
            float[] tops = new float[n];
            for (int k = 0; k < n; k++) {
                var part = list.get(indices[k] = found.get(k)[1]);
                var centre = new Vector3f();
                float top = -Float.MAX_VALUE;
                for (int v = part.start(); v < part.start() + part.count(); v++) {
                    centre.add(rest[v * 8], rest[v * 8 + 1], rest[v * 8 + 2]);
                    top = Math.max(top, rest[v * 8 + 1]);
                }
                centres[k] = centre.div(Math.max(1, part.count()));
                tops[k] = top;
            }
            var joints = new Vector3f[n + 1];
            joints[0] = new Vector3f(bones[bone].x(), bones[bone].y(), bones[bone].z());
            for (int k = 1; k < n; k++) joints[k] = new Vector3f(centres[k - 1]).add(centres[k]).mul(.5f);
            joints[n] = new Vector3f(centres[n - 1]).mul(2).sub(joints[n - 1]);
            curl.chains[side] = new Chain(bone, indices, joints, centres, tops);
            any = true;
        }
        return any ? curl : null;
    }

    /** Pose both tails on the final bone transforms; curl 0 leaves a tail exactly as authored. */
    void pose(Matrix4f[] world, float left, float right) {
        parts.clear();
        for (int side = 0; side < 2; side++) {
            float u = side == 0 ? left : right;
            var chain = chains[side];
            cradles[side] = null;
            if (chain == null || u <= 0) continue;
            float sign = side == 0 ? -1 : 1;
            var w = world[chain.bone()];
            var inverse = new Matrix4f(w).invert();
            int n = chain.parts().length;
            var segments = new Matrix4f[n];
            var joint = new Vector3f(chain.joints()[0]);
            for (int k = 0; k < n; k++) {
                var from = chain.joints()[k];
                var d = new Vector3f(chain.joints()[k + 1]).sub(from);
                float restPitch = (float) Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
                float want = spec.pitch() != null ? spec.pitch()[Math.min(k, spec.pitch().length - 1)] : restPitch + (k == 0 ? spec.lift() : 0);
                // rotateX(-a) raises a backward-pointing segment by a degrees; the yaw swings the tail outward about its root.
                var turn = new Matrix4f().rotateY(sign * spec.yaw() * u * Mth.DEG_TO_RAD).rotateX(-(want - restPitch) * u * Mth.DEG_TO_RAD);
                segments[k] = new Matrix4f().translation(joint).mul(turn).translate(-from.x, -from.y, -from.z);
                joint = segments[k].transformPosition(new Vector3f(chain.joints()[k + 1]));
                parts.put(chain.parts()[k], new Matrix4f(w).mul(segments[k]).mul(inverse));
            }
            Vector3f seat;
            if (spec.pitch() == null) {
                // A puff tail: the child sits on the back of the raised puff, a little outward.
                var c = chain.centres()[n - 1];
                seat = segments[n - 1].transformPosition(new Vector3f(c.x + sign * spec.shiftOut(), chain.tops()[n - 1], c.z + spec.shiftBack()));
            } else {
                // The flat run between the rising root and the up-curled tip.
                seat = new Vector3f();
                int first = Math.min(spec.shelf(), n - 1), last = Math.min(spec.shelf() + 1, n - 1);
                for (int k = first; k <= last; k++) {
                    var c = chain.centres()[k];
                    seat.add(segments[k].transformPosition(new Vector3f(c.x, chain.tops()[k], c.z)));
                }
                seat.div(last - first + 1);
            }
            seat.y += SHELF_LIFT;
            var at = w.transformPosition(seat);
            cradles[side] = new Matrix4f().translation(at).rotate(w.getNormalizedRotation(new Quaternionf()))
                    .rotateY(-sign * spec.yaw() * .5f * u * Mth.DEG_TO_RAD);
        }
    }
    /** The extra transform of one curled tail part in posed model space, or null. */
    Matrix4f part(int index) { return parts.isEmpty() ? null : parts.get(index); }
    /** Seat of the child on one tail (model units, posed), or null when that tail is not curled. */
    Matrix4f cradle(int side) { return cradles[side] == null ? null : new Matrix4f(cradles[side]); }
    void clear() { parts.clear(); cradles[0] = cradles[1] = null; }
}
