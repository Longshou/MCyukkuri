package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.headwear.HatScale;
import io.github.longshou.mcyukkuri.headwear.Headwear;
import io.github.longshou.mcyukkuri.pose.WallYield;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** The block collision faces beside a body, measured each frame: its headwear and loose parts are drawn on their free side
 *  ({@link WallYield}), while the body keeps its own collision box. */
final class WallClearance {
    // Render-thread scratch so crowded indoor scenes do not allocate box lists per colliding block.
    private static double[] boxes = new double[48];
    private static int boxCount;

    private final List<WallYield.Face> faces;
    private final float originX, originY, originZ;
    private VertexConsumer lastSource;
    private Tuck lastTuck;

    private WallClearance(List<WallYield.Face> faces, Matrix4f origin) {
        this.faces = faces;
        originX = origin.m30();
        originY = origin.m31();
        originZ = origin.m32();
    }

    static WallClearance nearby(YukkuriEntity source, YukkuriModel model, float partial, Matrix4f origin) {
        YukkuriView entity = source;
        double scale = entity.getScale() * entity.growthScale();
        // Rest bounds plus room for the animated swing, lean and bounce.
        double motion = scale / 4;
        int headwear = entity.visibleHeadwear();
        var junk = Headwear.visualJunk(headwear);
        double wornScale = junk == null ? HatScale.factor(entity.subspeciesFlags()) : 1;
        // Skinned headwear, including the house forms, is among the live model's loose parts.
        var loose = model.restLooseBounds();
        double reach = scale * Math.max(1, wornScale) * loose[0] / 16, top = scale * Math.max(1, wornScale) * loose[1] / 16;
        String prop = junk == null ? entity.subspeciesKind().hatProp() : "junk_" + junk.id();
        if (prop != null && (junk != null || entity.hasVisibleAccessory() || Headwear.visualLevel(headwear) == 3)) {
            // A hat drawn as a prop is not part of the live model.
            var bounds = PropModels.bounds(prop, 8);
            var low = new Vector3f((float)bounds.minX, (float)bounds.minY, (float)bounds.minZ);
            var high = new Vector3f((float)bounds.maxX, (float)bounds.maxY, (float)bounds.maxZ);
            if (junk != null) PropModels.anchor(prop, entity.assetId()).matrix().transformAab(low, high, low, high);
            reach = Math.max(reach, scale * wornScale * Math.hypot(Math.max(-low.x, high.x), Math.max(-low.z, high.z)));
            top = Math.max(top, scale * Math.max(high.y, high.y * wornScale));
        }
        reach += motion;
        var feet = new Vec3(Mth.lerp(partial, source.xOld, source.getX()), Mth.lerp(partial, source.yOld, source.getY()),
                Mth.lerp(partial, source.zOld, source.getZ()));
        var sweep = new AABB(-reach, 0, -reach, reach, top + motion, reach).move(feet);
        double bodyTop = feet.y + source.getBbHeight();
        boxCount = 0;
        for (var shape : entity.level().getBlockCollisions(source, sweep)) {
            shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
                if (maxX <= sweep.minX || minX >= sweep.maxX || maxY <= sweep.minY || minY >= sweep.maxY
                        || maxZ <= sweep.minZ || minZ >= sweep.maxZ) return;
                if (boxCount + 6 > boxes.length) boxes = Arrays.copyOf(boxes, boxes.length * 2);
                boxes[boxCount++] = minX; boxes[boxCount++] = minY; boxes[boxCount++] = minZ;
                boxes[boxCount++] = maxX; boxes[boxCount++] = maxY; boxes[boxCount++] = maxZ;
            });
        }
        if (boxCount == 0) return null;
        var faces = new ArrayList<WallYield.Face>(boxCount / 6);
        for (int i = 0; i < boxCount; i += 6) {
            double minX = boxes[i], minY = boxes[i + 1], minZ = boxes[i + 2];
            double maxX = boxes[i + 3], maxY = boxes[i + 4], maxZ = boxes[i + 5];
            double px = Mth.clamp(feet.x, minX, maxX), pz = Mth.clamp(feet.z, minZ, maxZ);
            double x = px - feet.x, z = pz - feet.z;
            double distance = Math.hypot(x, z);
            // A shape over the body axis is a floor or ceiling, and an overhead box (crate/carton roof, burrow ceiling,
            // tabletop) only forms a side face where a wall below it actually blocks the body. Side faces use the nearest
            // point on the shape, giving a continuous separating plane around the outside of a corner.
            if (distance <= 0 || distance >= reach || minY >= bodyTop && !wallBelow(px, pz, bodyTop)) continue;
            faces.add(new WallYield.Face((float)(x / distance), (float)(z / distance), (float)Math.max(0, distance - 1.0 / 1024),
                    (float)(minY - feet.y), (float)(maxY - feet.y)));
        }
        return faces.isEmpty() ? null : new WallClearance(faces, origin);
    }

    private static boolean wallBelow(double x, double z, double bodyTop) {
        for (int i = 0; i < boxCount; i += 6)
            if (boxes[i + 1] < bodyTop && boxes[i + 4] >= bodyTop && x >= boxes[i] - 1e-4 && x <= boxes[i + 3] + 1e-4
                    && z >= boxes[i + 2] - 1e-4 && z <= boxes[i + 5] + 1e-4) return true;
        return false;
    }

    List<WallYield.Face> faces() { return faces; }
    /** Posed model units under {@code pose} to the faces' frame: blocks from the body axis, above the feet. */
    Matrix4f frame(Matrix4f pose, Matrix4f target) { return target.translation(-originX, -originY, -originZ).mul(pose).scale(1 / 16f); }

    VertexConsumer tuck(VertexConsumer source) {
        if (source != lastSource) { lastSource = source; lastTuck = new Tuck(this, source); }
        return lastTuck;
    }

    record Tuck(WallClearance clearance, VertexConsumer source) implements VertexConsumer {
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            float side = x - clearance.originX;
            float front = z - clearance.originZ;
            float fraction = WallYield.press(clearance.faces, side, y - clearance.originY, front);
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
