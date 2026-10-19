package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.network.BodyEffectPayload;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Matrix4f;

/** Short-lived client shards and merged sticky traces for an already-completed body event. */
public final class BodyEffectsRenderer {
    private static final int TRACE_LIFETIME = 600, FADE_TICKS = 100, MAX_TRACES = 128, MAX_PER_CHUNK = 32;
    private static final ArrayDeque<Fragment> FRAGMENTS = new ArrayDeque<>();
    /** In insertion order so pressure evicts the least recently refreshed trace first. */
    private static final LinkedHashMap<Long, Trace> TRACES = new LinkedHashMap<>();
    private static ClientLevel clientLevel;

    private static final class Fragment {
        Vec3 position, velocity;
        final float size;
        int age;
        Fragment(Vec3 position, Vec3 velocity, float size) { this.position = position; this.velocity = velocity; this.size = size; }
    }
    private static final class Trace {
        final long key;
        Vec3 position;
        float size;
        long born;
        Trace(long key, Vec3 position, float size, long born) {
            this.key = key; this.position = position; this.size = size; this.born = born;
        }
    }

    /** Called on the client work queue after packet validation. No world mutation occurs here. */
    public static void accept(BodyEffectPayload payload) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        resetFor(mc.level);
        boolean healthLoss = payload.reason().equals("hit") || payload.reason().equals("leak");
        float small = healthLoss ? .05f : .5f, medium = healthLoss ? .2f : 2f;
        int tier = payload.amount() <= small ? 0 : payload.amount() <= medium ? 1 : 2;
        if (payload.reason().contains("vomit")) tier = Math.max(tier, 1);
        int count = new int[]{1, 3, 5}[tier];
        float base = Math.max(.12f, new float[]{.25f, .4f, .6f}[tier] * Math.min(2, payload.scale()));
        var random = new Random(31L * payload.sourceId() + payload.startTick() + payload.reason().hashCode() + Float.floatToIntBits(payload.amount()));
        Vec3 direction = new Vec3(payload.directionX(), payload.directionY(), payload.directionZ());
        if (direction.lengthSqr() < 1e-6) direction = new Vec3(0, 0, 1); else direction = direction.normalize();
        for (int i = 0; i < count && FRAGMENTS.size() < 48; i++) {
            Vec3 lateral = new Vec3(random.nextDouble() - .5, 0, random.nextDouble() - .5).normalize().scale(.025 + .025 * random.nextDouble());
            Vec3 velocity = direction.scale(.045 + .025 * tier + .025 * random.nextDouble()).add(lateral).add(0, .035 + .018 * random.nextDouble(), 0);
            FRAGMENTS.addLast(new Fragment(new Vec3(payload.x(), payload.y(), payload.z()), velocity, base / count * (1.4f + random.nextFloat())));
        }
    }

    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()) return;
        resetFor(mc.level);
        long now = mc.level.getGameTime();
        for (Iterator<Fragment> it = FRAGMENTS.iterator(); it.hasNext();) {
            var fragment = it.next();
            Vec3 next = fragment.position.add(fragment.velocity);
            HitResult hit = mc.player == null ? null : mc.level.clip(new ClipContext(fragment.position, next,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            if (hit != null && hit.getType() != HitResult.Type.MISS) {
                if (hit instanceof BlockHitResult block && block.getDirection() == Direction.UP)
                    addTrace(hit.getLocation().add(0, .004, 0), fragment.size, now);
                it.remove();
                continue;
            }
            fragment.position = next;
            fragment.velocity = fragment.velocity.add(0, -.012, 0).scale(.92);
            if (++fragment.age >= 12) {
                var support = grounded(mc, fragment.position);
                if (support != null) addTrace(support, fragment.size, now);
                it.remove();
            }
        }
        TRACES.values().removeIf(trace -> now - trace.born >= TRACE_LIFETIME);
    }

    private static void addTrace(Vec3 position, float size, long now) {
        for (Iterator<Map.Entry<Long, Trace>> it = TRACES.entrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            var trace = entry.getValue();
            if (trace.position.distanceToSqr(position) >= .16) continue;
            trace.position = trace.position.scale(.5).add(position.scale(.5));
            trace.size = Math.min(.6f, trace.size + size);
            trace.born = now;
            // Refresh insertion order too, so the recently extended puddle is not pressure-evicted.
            it.remove(); TRACES.put(trace.key, trace);
            return;
        }
        long chunk = ChunkPos.asLong(((int) Math.floor(position.x)) >> 4, ((int) Math.floor(position.z)) >> 4);
        while (tracesIn(chunk) >= MAX_PER_CHUNK) removeOldestIn(chunk);
        while (TRACES.size() >= MAX_TRACES) TRACES.remove(TRACES.keySet().iterator().next());
        long key = BlockPos.containing(position).asLong() ^ (now << 1) ^ TRACES.size();
        TRACES.put(key, new Trace(key, position, Math.clamp(size, .12f, .6f), now));
    }

    /** A short shard that did not hit a wall still settles on the first visible support below it. */
    private static Vec3 grounded(Minecraft mc, Vec3 position) {
        if (mc.level == null || mc.player == null) return null;
        var hit = mc.level.clip(new ClipContext(position.add(0, .01, 0), position.add(0, -1.5, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.MISS || hit.getDirection() != Direction.UP ? null : hit.getLocation().add(0, .004, 0);
    }

    private static int tracesIn(long chunk) {
        int count = 0;
        for (var trace : TRACES.values()) if (chunk(trace.position) == chunk) count++;
        return count;
    }
    private static void removeOldestIn(long chunk) {
        for (Iterator<Map.Entry<Long, Trace>> it = TRACES.entrySet().iterator(); it.hasNext();) if (chunk(it.next().getValue().position) == chunk) { it.remove(); return; }
    }
    private static long chunk(Vec3 at) { return ChunkPos.asLong(((int) Math.floor(at.x)) >> 4, ((int) Math.floor(at.z)) >> 4); }

    public static void renderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || TRACES.isEmpty() && FRAGMENTS.isEmpty()) return;
        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer splats = buffers.getBuffer(RenderType.entityTranslucent(MCYukkuri.id("textures/props/leaked_filling.png")));
        long now = mc.level.getGameTime();
        for (var trace : TRACES.values()) {
            float fade = Math.clamp((TRACE_LIFETIME - (now - trace.born)) / (float) FADE_TICKS, 0f, 1f);
            if (fade <= 0) continue;
            int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(trace.position));
            pose.pushPose();
            pose.translate(trace.position.x - camera.x, trace.position.y - camera.y, trace.position.z - camera.z);
            splat(pose.last().pose(), splats, trace.size, fade, light);
            pose.popPose();
        }
        for (var fragment : FRAGMENTS) {
            int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(fragment.position));
            pose.pushPose();
            pose.translate(fragment.position.x - camera.x, fragment.position.y - camera.y, fragment.position.z - camera.z);
            pose.scale(fragment.size, fragment.size, fragment.size);
            PropModels.render("leaked_filling", 1, pose, buffers, light, 0);
            pose.popPose();
        }
        buffers.endBatch();
    }

    private static void splat(Matrix4f matrix, VertexConsumer out, float size, float alpha, int light) {
        float x = size * .5f, z = size * .34f;
        int a = Math.round(255 * alpha);
        for (int i = 0; i < 12; i++) {
            double angle = i * Math.PI / 6, next = (i + 1) * Math.PI / 6;
            float radius = i % 3 == 0 ? .84f : 1, nextRadius = (i + 1) % 3 == 0 ? .84f : 1;
            float ax = (float) Math.cos(angle) * radius, az = (float) Math.sin(angle) * radius;
            float bx = (float) Math.cos(next) * nextRadius, bz = (float) Math.sin(next) * nextRadius;
            vertex(matrix, out, 0, 0, 0, .5f, .5f, a, light);
            vertex(matrix, out, x * ax, 0, z * az, .5f + .5f * ax, .5f + .5f * az, a, light);
            vertex(matrix, out, x * bx, 0, z * bz, .5f + .5f * bx, .5f + .5f * bz, a, light);
            vertex(matrix, out, 0, 0, 0, .5f, .5f, a, light);
        }
    }
    private static void vertex(Matrix4f matrix, VertexConsumer out, float x, float y, float z, float u, float v, int alpha, int light) {
        out.addVertex(matrix, x, y, z).setColor(255, 255, 255, alpha).setUv(u, v).setOverlay(0).setLight(light).setNormal(0, 1, 0);
    }

    public static void unload(LevelEvent.Unload event) { if (event.getLevel() == clientLevel) clear(); }
    private static void resetFor(ClientLevel level) { if (clientLevel != level) { clear(); clientLevel = level; } }
    private static void clear() { FRAGMENTS.clear(); TRACES.clear(); clientLevel = null; }
    private BodyEffectsRenderer() {}
}
