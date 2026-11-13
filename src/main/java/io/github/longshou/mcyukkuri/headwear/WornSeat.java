package io.github.longshou.mcyukkuri.headwear;

import com.google.gson.JsonObject;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * A named placement authored with a prop (its {@code anchors}): position in blocks in the body's model frame, rotation degrees
 * composed Rz*Ry*Rx, uniform scale. The client draws worn junk through {@link #matrix()} (PropModels anchors) and shelter
 * admission measures the same placed prop with it (HeadTop), so both read one calculation.
 */
public record WornSeat(Vector3f position, float rx, float ry, float rz, float scale) {
    public static WornSeat read(JsonObject anchor) {
        var p = anchor.getAsJsonArray("position");
        var r = anchor.getAsJsonArray("rotation");
        return new WornSeat(new Vector3f(p.get(0).getAsFloat() / 16, p.get(1).getAsFloat() / 16, p.get(2).getAsFloat() / 16),
                r.get(0).getAsFloat(), r.get(1).getAsFloat(), r.get(2).getAsFloat(), anchor.has("scale") ? anchor.get("scale").getAsFloat() : 1);
    }

    public Matrix4f matrix() {
        return new Matrix4f().translate(position).rotateZ((float)Math.toRadians(rz)).rotateY((float)Math.toRadians(ry))
                .rotateX((float)Math.toRadians(rx)).scale(scale);
    }
}
