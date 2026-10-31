package io.github.longshou.mcyukkuri.client.aquatic;

import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.longshou.mcyukkuri.aquatic.HullAfloat;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Keeps the world's water surface out of a floating hat's dry hollow: after the entities are drawn and
 * before the translucent water, a depth-only quad over the opening at the waterline of every upright hat afloat (the vanilla
 * boat's water mask). It reads the synced hat pose and the client's own world, nothing of the server.
 */
public final class HullWaterMask {
    /** Just over the water surface the client draws (blocks); farther than this from the camera nothing is masked. */
    private static final float LIFT = .03f, RANGE = 64;

    private HullWaterMask() {}

    public static void renderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
        var minecraft = Minecraft.getInstance();
        var level = minecraft.level;
        if (level == null) return;
        var camera = event.getCamera().getPosition();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        var buffers = minecraft.renderBuffers().bufferSource();
        var pose = event.getPoseStack().last().pose();
        VertexConsumer mask = null;
        for (var entity : level.entitiesForRendering()) {
            if (!(entity instanceof PlacedHatEntity hat) || hat.isRemoved() || hat.upsideDown()
                    || hat.state() == PlacedHatEntity.State.RESCUED || hat.distanceToSqr(camera) > RANGE * RANGE) continue;
            float[] o = hat.profile().opening();
            float[][] ring = {{o[1], o[2]}, {o[1], o[3]}, {o[0], o[3]}, {o[0], o[2]}};
            var at = hat.getPosition(partial);
            double water = HullAfloat.surface(level, at.x, at.y - .5, at.z);
            if (!Double.isFinite(water) || water <= at.y) continue;  // its hollow is not below the waterline
            if (mask == null) { buffers.endBatch(); mask = buffers.getBuffer(RenderType.waterMask()); }
            var frame = new Matrix4f().rotationY((180 - Mth.rotLerp(partial, hat.previousYaw(), hat.getYRot())) * Mth.DEG_TO_RAD)
                    .rotateZ(hat.roll(partial) * Mth.DEG_TO_RAD).rotateX(hat.pitch(partial) * Mth.DEG_TO_RAD);
            float unit = hat.hullScale() / 16, depth = (float) (water - at.y), y = (float) (water - camera.y) + LIFT;
            var corners = new Vector3f[ring.length];
            for (int i = 0; i < ring.length; i++) {
                // the opening's corner where the hull's own axis meets the waterline, dropped onto the flat water
                var c = frame.transformPosition(new Vector3f(ring[i][0] * unit, depth, ring[i][1] * unit));
                corners[i] = new Vector3f((float) (at.x - camera.x) + c.x, y, (float) (at.z - camera.z) + c.z);
            }
            for (int face = 0; face < 2; face++)
                for (int i = 0; i < corners.length; i++) {
                    var c = corners[face == 0 ? i : corners.length - 1 - i];
                    mask.addVertex(pose, c.x, c.y, c.z);
                }
        }
        if (mask != null) buffers.endBatch(RenderType.waterMask());
    }
}
