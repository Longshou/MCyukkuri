package io.github.longshou.mcyukkuri.pose;

import com.google.gson.JsonArray;
import java.util.Arrays;

/** Which clip channels may ease without moving a fitted contact surface through its partner. */
public final class ClipBlend {
    private static final byte FREE = 9, RIGID = 6, PINNED = 0;
    private final byte[] channels;
    private final boolean hasLooseParts;
    // Model-owned scratch. A draw may test its fitted bounds, then take the incoming common frame.
    private final float[] incoming;
    private final byte[] eased;
    private boolean independentMotion;

    public ClipBlend(Rig.Bone[] bones, Skin.Binding[] bindings, JsonArray parts) {
        channels = new byte[bones.length];
        incoming = new float[bones.length * 9];
        eased = new byte[bones.length];
        Arrays.fill(channels, FREE);
        boolean loose = false;
        for (var raw : parts) {
            if (WallYield.loose(raw.getAsJsonObject().get("name").getAsString())) { loose = true; break; }
        }
        hasLooseParts = loose;
        int common = bones.length;
        for (var raw : parts) {
            var part = raw.getAsJsonObject();
            String name = part.get("name").getAsString();
            boolean body = name.endsWith("_body_surface") || name.startsWith("soft_head_slice_");
            if (!body && !WallYield.loose(name)) continue;
            int start = part.get("start").getAsInt(), end = start + part.get("count").getAsInt();
            for (int i = start; i < end; i++) {
                var binding = bindings[i];
                common = attach(bones, binding.first(), common);
                if (binding.weight() > 0) common = attach(bones, binding.second(), common);
            }
            if (part.has("feature_bone")) common = attach(bones, part.get("feature_bone").getAsInt(), common);
        }
        // Translation and rotation of the whole assembly leave its internal clearance intact.
        // The outermost moving frame can rotate even when its incoming scale is anisotropic.
        if (common != bones.length)
            for (int i = common; i >= 0; i = bones[i].parent) channels[i] = RIGID;
    }

    private int attach(Rig.Bone[] bones, int joint, int common) {
        for (int i = joint; i >= 0 && channels[i] != PINNED; i = bones[i].parent) channels[i] = PINNED;
        if (common == bones.length) return joint;
        while (common >= 0 && !Rig.descends(bones, joint, common)) common = bones[common].parent;
        return common;
    }

    /** Returns whether any channel differs from the incoming sample after fitting. */
    public boolean apply(float[][] pose, float[] from, float weight, boolean external) {
        boolean uniform = true, changed = false;
        independentMotion = false;
        for (int i = 0; i < pose.length; i++) {
            int n = !hasLooseParts && !external ? FREE : channels[i];
            if (n == RIGID) {
                // Below a nonuniform parent a local rotation would shear the fitted shape.
                if (external) n = PINNED;
                else if (!uniform) n = 3;
                uniform &= pose[i][6] == pose[i][7] && pose[i][6] == pose[i][8];
            }
            eased[i] = channels[i] == FREE ? 0 : (byte)n;
            if (eased[i] > 0) System.arraycopy(pose[i], 0, incoming, i * 9, n);
            for (int k = 0; k < n; k++) {
                float start = from[i * 9 + k], delta = pose[i][k] - start;
                if (k >= 3 && k < 6) delta -= 360 * Math.floor((delta + 180) / 360);
                if (delta == 0) continue;
                float mixed = start + weight * delta;
                changed |= mixed != pose[i][k];
                if (channels[i] == FREE) independentMotion |= mixed != pose[i][k];
                pose[i][k] = mixed;
            }
        }
        return changed;
    }

    public boolean takeContactFrame(float[][] pose, float[] last) {
        for (int i = 0; i < pose.length; i++) {
            int n = eased[i];
            if (n == 0) continue;
            System.arraycopy(incoming, i * 9, pose[i], 0, n);
            System.arraycopy(incoming, i * 9, last, i * 9, n);
        }
        return independentMotion;
    }

    public boolean keepPuff(boolean external) { return external || hasLooseParts; }
}
