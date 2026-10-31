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
import java.util.HashMap;
import java.util.Map;

/**
 * Keeps the world's water surface out of a floating hat's dry hollow: after the entities are drawn and
 * before the translucent water, a depth-only polygon over the shell's waterline of every dry upright hat afloat (the vanilla
 * boat's water mask). It reads the synced hat pose and the client's own world, nothing of the server.
 */
public final class HullWaterMask {
    /** Just over the water surface the client draws (blocks); farther than this from the camera nothing is masked. */
    private static final float LIFT = .03f, RANGE = 64;
    private static final Map<String, HullHydro.Waterline> SECTIONS = new HashMap<>();

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
            var ring = SECTIONS.get(view.formId());
            if (ring == null) { ring = HullHydro.of(view.profile()).waterline(); SECTIONS.put(view.formId(), ring); }
            int count = ring.update(view.hullScale() / 16, view.roll(partial), view.pitch(partial), water - at.y);
            if (count == 0) continue;
            if (mask == null) { buffers.endBatch(); mask = buffers.getBuffer(RenderType.waterMask()); }
            double yaw = (180 - Mth.rotLerp(partial, view.previousYaw(), view.getYRot())) * Mth.DEG_TO_RAD;
            double sin = Math.sin(yaw), cos = Math.cos(yaw);
            float y = (float) (water - camera.y) + LIFT;
            for (int face = 0; face < 2; face++)
                for (int i = 0; i < count; i += 3) {
                    for (int corner = 0; corner < 4; corner++) {
                        int index = i + (corner == 0 || corner == 3 ? 0 : face == 0 ? corner : 3 - corner);
                        double x = ring.x(index), z = ring.z(index);
                        mask.addVertex(pose, (float) (at.x - camera.x + x * cos + z * sin), y,
                                (float) (at.z - camera.z - x * sin + z * cos));
                    }
                }
        }
        if (mask != null) buffers.endBatch(RenderType.waterMask());
    }
}
