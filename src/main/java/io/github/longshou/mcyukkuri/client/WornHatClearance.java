package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.headwear.HatScale;
import io.github.longshou.mcyukkuri.headwear.Headwear;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Draw the posed headwear inside the free side of nearby block collision faces. */
final class WornHatClearance {
    private record Face(float x, float z, float distance) {}

    private final List<Face> faces;
    private final float originX, originZ;

    private WornHatClearance(List<Face> faces, Matrix4f origin) {
        this.faces = faces;
        originX = origin.m30();
        originZ = origin.m32();
    }

    static WornHatClearance nearby(YukkuriEntity source, YukkuriModel model, float partial, Matrix4f origin) {
        YukkuriView entity = source;
        int headwear = entity.visibleHeadwear();
        var junk = Headwear.visualJunk(headwear);
        if (junk == null && !entity.hasVisibleAccessory() && Headwear.visualLevel(headwear) != 3) return null;
        String prop = junk == null ? entity.subspeciesKind().hatProp() : "junk_" + junk.id();
        var low = new Vector3f();
        var high = new Vector3f();
        if (prop == null) {
            // Skinned accessories, including the house forms, are part of the live model.
            var bounds = model.restAccessoryBounds();
            low.set(bounds[0] / 16, bounds[1] / 16, bounds[2] / 16);
            high.set(bounds[3] / 16, bounds[4] / 16, bounds[5] / 16);
        } else {
            var bounds = PropModels.bounds(prop, 8);
            low.set((float)bounds.minX, (float)bounds.minY, (float)bounds.minZ);
            high.set((float)bounds.maxX, (float)bounds.maxY, (float)bounds.maxZ);
            if (junk != null) PropModels.anchor(prop, entity.assetId()).matrix().transformAab(low, high, low, high);
        }
        double scale = entity.getScale() * entity.growthScale();
        double wornScale = junk == null ? HatScale.factor(entity.subspeciesFlags()) : 1;
        // Rest bounds plus room for the animated hat's swing, lean and bounce.
        double motion = scale / 4;
        double reach = scale * wornScale * Math.hypot(Math.max(-low.x, high.x), Math.max(-low.z, high.z)) + motion;
        var feet = new Vec3(Mth.lerp(partial, source.xOld, source.getX()), Mth.lerp(partial, source.yOld, source.getY()),
                Mth.lerp(partial, source.zOld, source.getZ()));
        var sweep = new AABB(-reach, Math.max(0, scale * low.y - motion), -reach,
                reach, scale * Math.max(high.y, high.y * wornScale) + motion, reach).move(feet);
        var faces = new ArrayList<Face>();
        for (var shape : entity.level().getBlockCollisions(source, sweep)) {
            for (var box : shape.toAabbs()) {
                if (!box.intersects(sweep)) continue;
                double x = Mth.clamp(feet.x, box.minX, box.maxX) - feet.x;
                double z = Mth.clamp(feet.z, box.minZ, box.maxZ) - feet.z;
                double distance = Math.hypot(x, z);
                // A shape over the body axis is a floor or ceiling. Side faces use the nearest point on the shape,
                // which also gives a continuous separating plane around the outside of a corner.
                if (distance > 0 && distance < reach)
                    faces.add(new Face((float)(x / distance), (float)(z / distance), (float)Math.max(0, distance - 1.0 / 1024)));
            }
        }
        return faces.isEmpty() ? null : new WornHatClearance(faces, origin);
    }

    record Tuck(WornHatClearance clearance, VertexConsumer source) implements VertexConsumer {
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            float side = x - clearance.originX;
            float front = z - clearance.originZ;
            float fraction = 1;
            // All vertices use the same planes, so a triangle stays clear between its vertices as well.
            for (var face : clearance.faces) {
                float toward = face.x * side + face.z * front;
                if (toward > face.distance) fraction = Math.min(fraction, face.distance / toward);
            }
            source.addVertex(clearance.originX + side * fraction, y, clearance.originZ + front * fraction);
            return this;
        }

        @Override public VertexConsumer setColor(int r, int g, int b, int a) { source.setColor(r, g, b, a); return this; }
        @Override public VertexConsumer setUv(float u, float v) { source.setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { source.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { source.setUv2(u, v); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { source.setNormal(x, y, z); return this; }
    }
}
