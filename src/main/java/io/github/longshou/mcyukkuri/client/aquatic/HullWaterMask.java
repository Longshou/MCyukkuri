package io.github.longshou.mcyukkuri.client.aquatic;

import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.longshou.mcyukkuri.aquatic.HullAfloat;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.headwear.PlacedHatView;
import io.github.longshou.mcyukkuri.motor.geom.HullHydro;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Keeps the world's water surface out of a floating hat's dry hollow: after the entities are drawn and
 * before the translucent water, a depth-only polygon over the shell's waterline of every dry upright hat afloat (the vanilla
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
            PlacedHatView view = hat;
            var at = view.getPosition(partial);
            double water = HullAfloat.surface(level, at.x, at.y - .5, at.z);
            if (!Double.isFinite(water)) continue;
            var ring = HullHydro.of(view.profile()).waterline(view.hullScale() / 16, view.roll(partial), view.pitch(partial), water - at.y);
            if (ring.size() < 3) continue;
            if (mask == null) { buffers.endBatch(); mask = buffers.getBuffer(RenderType.waterMask()); }
            var frame = new Matrix4f().rotationY((180 - Mth.rotLerp(partial, view.previousYaw(), view.getYRot())) * Mth.DEG_TO_RAD);
            float y = (float) (water - camera.y) + LIFT;
            var corners = new Vector3f[ring.size()];
            for (int i = 0; i < ring.size(); i++) {
                var point = ring.get(i);
                var c = frame.transformPosition(new Vector3f((float) point.x(), 0, (float) point.z()));
                corners[i] = new Vector3f((float) (at.x - camera.x) + c.x, y, (float) (at.z - camera.z) + c.z);
            }
            for (int face = 0; face < 2; face++)
                for (int i = 1; i + 1 < corners.length; i++) {
                    for (int index : new int[]{0, face == 0 ? i : i + 1, face == 0 ? i + 1 : i, 0}) {
                        var c = corners[index];
                        mask.addVertex(pose, c.x, c.y, c.z);
                    }
                }
        }
        if (mask != null) buffers.endBatch(RenderType.waterMask());
    }
}
