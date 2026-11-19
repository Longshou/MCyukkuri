package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.mind.ExcitedLeak;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import io.github.longshou.mcyukkuri.network.BodyEffectPayload;
import io.github.longshou.mcyukkuri.pose.BodyEffectLook;
import io.github.longshou.mcyukkuri.pose.BodyEffectLook.Kind;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Iterator;
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
    private static final int MAX_TRACES = 128, MAX_PER_CHUNK = 32;
    private static final ArrayDeque<Fragment> FRAGMENTS = new ArrayDeque<>();
    /** In insertion order so pressure evicts the least recently refreshed trace first. */
    private static final ArrayDeque<Trace> TRACES = new ArrayDeque<>();
    private static final EnumMap<Kind, RenderType> LAYERS = new EnumMap<>(Kind.class);
    static {
        for (var kind : Kind.values()) LAYERS.put(kind, RenderType.entityTranslucent(MCYukkuri.id(kind.texture)));
    }
    private static ClientLevel clientLevel;

    private static final class Fragment {
        Vec3 position;
        Vec velocity;
        final float size;
        int age;
        Fragment(Vec3 position, Vec velocity, float size) { this.position = position; this.velocity = velocity; this.size = size; }
    }
    private static final class Trace {
        final Kind kind;
        Vec3 position;
        float size, yaw;
        long born;
        Trace(Kind kind, Vec3 position, float size, float yaw, long born) {
            this.kind = kind; this.position = position; this.size = size; this.yaw = yaw; this.born = born;
        }
    }

    /** Called on the client work queue after packet validation. No world mutation occurs here. */
    public static void accept(BodyEffectPayload payload) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        resetFor(mc.level);
        if (ExcitedLeak.PUDDLE.equals(payload.reason()) || "urine_puddle".equals(payload.reason())) {
            if (ExcitedLeak.PUDDLE.equals(payload.reason()) && mc.level.getEntity(payload.sourceId()) instanceof YukkuriView entity)
                MomentEmote.leakSweat(entity, payload.startTick());
            var outlet = new Vec3(payload.x(), payload.y(), payload.z());
            var support = grounded(mc, outlet, Kind.PUDDLE);
            if (support != null) {
                float yaw = (float) Math.atan2(payload.directionX(), payload.directionZ());
                addTrace(Kind.PUDDLE, support, payload.amount(), yaw, payload.startTick());
            }
            return;
        }
        var direction = new Vec(payload.directionX(), payload.directionY(), payload.directionZ());
        for (var shard : BodyEffectLook.shards(payload.sourceId(), payload.startTick(), payload.reason(),
                payload.amount(), payload.scale(), direction, 48 - FRAGMENTS.size()))
            FRAGMENTS.addLast(new Fragment(new Vec3(payload.x(), payload.y(), payload.z()), shard.velocity(), shard.size()));
    }

    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()) return;
        resetFor(mc.level);
        long now = mc.level.getGameTime();
        for (Iterator<Fragment> it = FRAGMENTS.iterator(); it.hasNext();) {
            var fragment = it.next();
            var velocity = fragment.velocity;
            Vec3 next = fragment.position.add(velocity.x(), velocity.y(), velocity.z());
            HitResult hit = mc.player == null ? null : mc.level.clip(new ClipContext(fragment.position, next,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            if (hit != null && hit.getType() != HitResult.Type.MISS) {
                if (hit instanceof BlockHitResult block && block.getDirection() == Direction.UP)
                    addTrace(Kind.FILLING, hit.getLocation().add(0, BodyEffectLook.FLOOR_LIFT, 0), fragment.size, 0, now);
                it.remove();
                continue;
            }
            fragment.position = next;
            fragment.velocity = BodyEffectLook.nextVelocity(velocity);
            if (++fragment.age >= BodyEffectLook.AIR_TICKS) {
                var support = grounded(mc, fragment.position, Kind.FILLING);
                if (support != null) addTrace(Kind.FILLING, support, fragment.size, 0, now);
                it.remove();
            }
        }
        TRACES.removeIf(trace -> now - trace.born >= trace.kind.lifetime);
    }

    private static void addTrace(Kind kind, Vec3 position, float size, float yaw, long now) {
        for (var it = TRACES.iterator(); it.hasNext();) {
            var trace = it.next();
            if (trace.kind != kind || !BodyEffectLook.touches(
                    new Vec(trace.position.x, trace.position.y, trace.position.z), new Vec(position.x, position.y, position.z))) continue;
            trace.position = trace.position.scale(.5).add(position.scale(.5));
            trace.size = BodyEffectLook.mergedSize(trace.size, size);
            trace.born = now;
            // Refresh insertion order too, so the recently extended puddle is not pressure-evicted.
            it.remove(); TRACES.addLast(trace);
            return;
        }
        long chunk = chunk(position);
        while (tracesIn(chunk) >= MAX_PER_CHUNK) removeOldestIn(chunk);
        while (TRACES.size() >= MAX_TRACES) TRACES.removeFirst();
        TRACES.addLast(new Trace(kind, position, BodyEffectLook.traceSize(size), yaw, now));
    }

    /** A short shard that did not hit a wall still settles on the first visible support below it. */
    private static Vec3 grounded(Minecraft mc, Vec3 position, Kind kind) {
        if (mc.level == null || mc.player == null) return null;
        boolean puddle = kind == Kind.PUDDLE;
        var hit = mc.level.clip(new ClipContext(position.add(0, .01, 0), position.add(0, puddle ? -4 : -1.5, 0),
                ClipContext.Block.COLLIDER, puddle ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE, mc.player));
        if (puddle && !mc.level.getFluidState(hit.getBlockPos()).isEmpty()) return null;
        return hit.getType() == HitResult.Type.MISS || hit.getDirection() != Direction.UP ? null
                : hit.getLocation().add(0, BodyEffectLook.FLOOR_LIFT, 0);
    }

    private static int tracesIn(long chunk) {
        int count = 0;
        for (var trace : TRACES) if (chunk(trace.position) == chunk) count++;
        return count;
    }
    private static void removeOldestIn(long chunk) {
        for (var it = TRACES.iterator(); it.hasNext();) if (chunk(it.next().position) == chunk) { it.remove(); return; }
    }
    private static long chunk(Vec3 at) { return ChunkPos.asLong(((int) Math.floor(at.x)) >> 4, ((int) Math.floor(at.z)) >> 4); }

    public static void renderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || TRACES.isEmpty() && FRAGMENTS.isEmpty()) return;
        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        for (Kind kind : Kind.values()) renderTraces(kind, mc.level, pose, camera, buffers);
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

    private static void renderTraces(Kind kind, ClientLevel level, PoseStack pose, Vec3 camera, MultiBufferSource buffers) {
        VertexConsumer splats = null;
        long now = level.getGameTime();
        for (var trace : TRACES) {
            if (trace.kind != kind) continue;
            long age = now - trace.born;
            int alpha = kind.alpha(age);
            if (alpha == 0) continue;
            if (splats == null) splats = buffers.getBuffer(LAYERS.get(kind));
            int light = LevelRenderer.getLightColor(level, BlockPos.containing(trace.position));
            pose.pushPose();
            pose.translate(trace.position.x - camera.x, trace.position.y - camera.y, trace.position.z - camera.z);
            if (kind == Kind.PUDDLE) pose.mulPose(Axis.YP.rotation(trace.yaw));
            splat(pose.last().pose(), splats, trace.size, alpha, light);
            pose.popPose();
        }
    }

    private static void splat(Matrix4f matrix, VertexConsumer out, float size, int a, int light) {
        float x = BodyEffectLook.halfX(size), z = BodyEffectLook.halfZ(size);
        for (int i = 0; i < 12; i++) {
            float ax = BodyEffectLook.rimX(i), az = BodyEffectLook.rimZ(i);
            float bx = BodyEffectLook.rimX(i + 1), bz = BodyEffectLook.rimZ(i + 1);
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
