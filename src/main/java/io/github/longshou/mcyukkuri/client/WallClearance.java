package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.longshou.mcyukkuri.body.PaperCeiling;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.headwear.HatScale;
import io.github.longshou.mcyukkuri.headwear.Headwear;
import io.github.longshou.mcyukkuri.human.GarbageBinBlock;
import io.github.longshou.mcyukkuri.pose.WallYield;
import io.github.longshou.mcyukkuri.pose.SnugglePose;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.WeakHashMap;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** The block collision faces beside a body, measured each frame: its headwear and loose parts are drawn on their free side
 *  ({@link WallYield}), while the body keeps its own collision box. */
final class WallClearance {
    // Render-thread scratch so crowded indoor scenes do not allocate box lists per colliding block.
    private static double[] boxes = new double[48];
    private static int boxCount;
    private static final WeakHashMap<YukkuriView, Integer> lastPartners = new WeakHashMap<>();

    private final List<WallYield.Face> faces;
    private final double paperCeiling;
    private final float originX, originY, originZ;
    private VertexConsumer lastSource;
    private Tuck lastTuck;

    private WallClearance(List<WallYield.Face> faces, Matrix4f origin, double paperCeiling) {
        this.faces = faces;
        this.paperCeiling = paperCeiling;
        originX = origin.m30();
        originY = origin.m31();
        originZ = origin.m32();
    }

    private static WallYield.Bounds bounds(YukkuriView entity, YukkuriModel model) {
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
        return new WallYield.Bounds(reach + motion, -motion, top + motion);
    }

    static WallClearance nearby(YukkuriEntity source, YukkuriModel model, String action, float partial, YukkuriView mover, Vec3 rubOffset,
                                Matrix4f origin, boolean paper) {
        YukkuriView entity = source;
        var bounds = bounds(entity, model);
        double reach = bounds.reach();
        var feet = new Vec3(Mth.lerp(partial, source.xOld, source.getX()), Mth.lerp(partial, source.yOld, source.getY()),
                Mth.lerp(partial, source.zOld, source.getZ())).add(rubOffset);
        double paperCeiling = paper ? PaperCeiling.height(source, feet) : Double.POSITIVE_INFINITY;
        var sweep = new AABB(-reach, 0, -reach, reach, bounds.high(), reach).move(feet);
        double bodyTop = feet.y + source.getBbHeight();
        boxCount = 0;
        // The bin's solid collision box spans its open mouth. A reaching tuft keeps the authored pose there.
        var bin = Behaviors.BRAID_EAT_BIN.equals(action) ? entity.braidMealBin() : null;
        if (bin != null) {
            var state = entity.level().getBlockState(bin);
            if (!(state.getBlock() instanceof GarbageBinBlock) || !state.getValue(GarbageBinBlock.TIPPED)) bin = null;
        }
        var reachedBin = bin;
        var collisions = new BlockCollisions<>(entity.level(), source, sweep, false,
                (pos, shape) -> pos.equals(reachedBin) ? Shapes.empty() : shape);
        while (collisions.hasNext()) {
            var shape = collisions.next();
            shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
                if (maxX <= sweep.minX || minX >= sweep.maxX || maxY <= sweep.minY || minY >= sweep.maxY
                        || maxZ <= sweep.minZ || minZ >= sweep.maxZ) return;
                if (boxCount + 6 > boxes.length) boxes = Arrays.copyOf(boxes, boxes.length * 2);
                boxes[boxCount++] = minX; boxes[boxCount++] = minY; boxes[boxCount++] = minZ;
                boxes[boxCount++] = maxX; boxes[boxCount++] = maxY; boxes[boxCount++] = maxZ;
            });
        }
        var partner = partnerFace(entity, mover, partial, bounds);
        if (boxCount == 0 && partner == null && !Double.isFinite(paperCeiling)) return null;
        var faces = new ArrayList<WallYield.Face>(boxCount / 6 + 1);
        for (int i = 0; i < boxCount; i += 6) {
            double minX = boxes[i], minY = boxes[i + 1], minZ = boxes[i + 2];
            double maxX = boxes[i + 3], maxY = boxes[i + 4], maxZ = boxes[i + 5];
            double px = Mth.clamp(feet.x, minX, maxX), pz = Mth.clamp(feet.z, minZ, maxZ);
            double x = px - feet.x, z = pz - feet.z;
            double distance = Math.hypot(x, z);
            // A shape over the body axis is a floor or ceiling, and an overhead box (carton roof, burrow ceiling,
            // tabletop) only forms a side face where a wall below it actually blocks the body. Side faces use the nearest
            // point on the shape, giving a continuous separating plane around the outside of a corner.
            if (distance <= 0 || distance >= reach || minY >= bodyTop && !wallBelow(px, pz, bodyTop)) continue;
            faces.add(new WallYield.Face((float)(x / distance), (float)(z / distance), (float)Math.max(0, distance - 1.0 / 1024),
                    (float)(minY - feet.y), (float)(maxY - feet.y)));
        }
        if (partner != null) faces.add(partner);
        return faces.isEmpty() && !Double.isFinite(paperCeiling) ? null : new WallClearance(faces, origin, paperCeiling);
    }

    /** Both renders observe the same admission even if the anchor's animation packet is still in flight. */
    static YukkuriView rubMover(YukkuriView entity) {
        int id = entity.nuzzlePartnerId();
        if (id < 0) return null;
        var peer = entity.yukkuriView(id);
        if (peer == null || peer.nuzzlePartnerId() != entity.getId()) return null;
        if (entity.peerRubTick() >= 0 && entity.getAnimationId().equals("snuggle_right")) return entity;
        return peer.peerRubTick() >= 0 && peer.getAnimationId().equals("snuggle_right") ? peer : null;
    }

    /** Draw the resolved stance at its pose spacing while the two network positions interpolate independently. */
    static Vec3 rubOffset(YukkuriView mover, float partial) {
        double angle = Math.toRadians(mover.bodyYaw()), spacing = mover.peerRubSpacing();
        var delta = mover.yukkuriView(mover.nuzzlePartnerId()).getPosition(partial).subtract(mover.getPosition(partial));
        return new Vec3(delta.x + Math.cos(angle) * spacing, 0, delta.z + Math.sin(angle) * spacing);
    }

    /** Retain a former partner only while the two loose-part sweeps can still meet, even off camera. */
    static void observePartner(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof YukkuriView entity) || !entity.level().isClientSide) return;
        int id = entity.nuzzlePartnerId();
        if (id >= 0 && entity.yukkuriView(id) != null) {
            lastPartners.put(entity, id);
            return;
        }
        var last = lastPartners.get(entity);
        if (last == null) return;
        var peer = entity.yukkuriView(last);
        if (peer != null) {
            var own = bounds(entity, YukkuriModel.get(entity.assetId()));
            var theirs = bounds(peer, YukkuriModel.get(peer.assetId()));
            var before = peer.getPosition(0).subtract(entity.getPosition(0));
            var now = peer.position().subtract(entity.position());
            if (own.overlaps(theirs, before.x, before.y, before.z) || own.overlaps(theirs, now.x, now.y, now.z)) return;
        }
        lastPartners.remove(entity);
    }

    private static WallYield.Face partnerFace(YukkuriView entity, YukkuriView mover, float partial, WallYield.Bounds ownBounds) {
        int id = entity.nuzzlePartnerId();
        if (id < 0) id = lastPartners.getOrDefault(entity, -1);
        if (id < 0) return null;
        var peer = entity.yukkuriView(id);
        if (peer == null) return null;
        var peerModel = YukkuriModel.get(peer.assetId());
        var peerBounds = bounds(peer, peerModel);
        var delta = peer.getPosition(partial).subtract(entity.getPosition(partial));
        double scale = entity.growthScale() * entity.getScale(), peerScale = peer.growthScale() * peer.getScale();
        if (mover == null) {
            if (!ownBounds.overlaps(peerBounds, delta.x, delta.y, delta.z)) return null;
            var toward = delta.multiply(1, 0, 1).normalize();
            double now = entity.level().getGameTime() + (double)partial;
            double own = YukkuriModel.get(entity.assetId()).contactReach(entity, now, partial, null, toward, true, false).body() * scale;
            double theirs = peerModel.contactReach(peer, now, partial, null, toward.reverse(), true, false).body() * peerScale;
            return WallYield.passingFace(delta.x, delta.y, delta.z, own, theirs, ownBounds, peerBounds);
        }
        boolean right = entity == mover;
        int tick = mover.peerRubTick();
        double angle = Math.toRadians(mover.bodyYaw());
        float x = (float)Math.cos(angle) * (right ? -1 : 1), z = (float)Math.sin(angle) * (right ? -1 : 1);
        double lift = delta.y;
        double own = SnugglePose.at(entity.assetId(), right, tick).reach(right) * scale / 16;
        double theirs = SnugglePose.at(peer.assetId(), !right, tick).reach(!right) * peerScale / 16;
        return WallYield.partnerFace(x, z, mover.peerRubSpacing(), own, theirs,
                (float)Math.min(ownBounds.low(), lift + peerBounds.low()), (float)Math.max(ownBounds.high(), lift + peerBounds.high()));
    }

    private static boolean wallBelow(double x, double z, double bodyTop) {
        for (int i = 0; i < boxCount; i += 6)
            if (boxes[i + 1] < bodyTop && boxes[i + 4] >= bodyTop && x >= boxes[i] - 1e-4 && x <= boxes[i + 3] + 1e-4
                    && z >= boxes[i + 2] - 1e-4 && z <= boxes[i + 5] + 1e-4) return true;
        return false;
    }

    List<WallYield.Face> faces() { return faces; }
    double paperCeiling() { return paperCeiling; }
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
