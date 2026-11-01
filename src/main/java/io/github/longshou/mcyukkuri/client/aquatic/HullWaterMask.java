package io.github.longshou.mcyukkuri.client.aquatic;

import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.longshou.mcyukkuri.aquatic.HullAfloat;
import io.github.longshou.mcyukkuri.aquatic.HullPresentation;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.headwear.PlacedHatView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Keeps the world's water surface out of a floating hat's dry hollow: after the entities are drawn and
 * before the translucent water, a depth-only disc over the basin mouth of every upright hat afloat (the vanilla
 * boat's water mask). It reads the synced hat pose and the client's own world, nothing of the server.
 */
public final class HullWaterMask {
    /** Just over the water surface the client draws (blocks); farther than this from the camera nothing is masked. */
    private static final float LIFT = .03f, RANGE = 64;
    private static final int SEGMENTS = 32;

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
            if (!(entity instanceof PlacedHatEntity hat) || hat.isRemoved() || hat.distanceToSqr(camera) > RANGE * RANGE) continue;
            PlacedHatView view = hat;
            if (view.upsideDown() || view.state() == PlacedHatEntity.State.RESCUED) continue;
            var at = view.getPosition(partial);
            double water = HullAfloat.surface(level, at.x, at.y - 2, at.z);
            if (!Double.isFinite(water)) continue;
            double radius = HullPresentation.MOUTH * view.hullScale() / HullPresentation.SCALE;
            if (mask == null) { buffers.endBatch(); mask = buffers.getBuffer(RenderType.waterMask()); }
            float y = (float) (water - camera.y) + LIFT;
            for (int face = 0; face < 2; face++)
                for (int i = 0; i < SEGMENTS; i++) {
                    for (int corner = 0; corner < 4; corner++) {
                        int index = i + (face == 0 ? corner - 1 : 2 - corner);
                        double a = index * 2 * Math.PI / SEGMENTS;
                        double r = corner == 0 || corner == 3 ? 0 : radius;
                        mask.addVertex(pose, (float) (at.x - camera.x + r * Math.cos(a)), y,
                                (float) (at.z - camera.z + r * Math.sin(a)));
                    }
                }
        }
        if (mask != null) buffers.endBatch(RenderType.waterMask());
    }
}
