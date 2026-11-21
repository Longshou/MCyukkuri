package io.github.longshou.mcyukkuri.pose;

import io.github.longshou.mcyukkuri.motor.geom.Vec;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** World-independent look of an emitted body effect. The caller supplies collision and support. */
public final class BodyEffectLook {
    public static final int AIR_TICKS = 12;
    public static final double FLOOR_LIFT = .004;

    public enum Kind {
        FILLING("textures/effects/leak_puddle.png", 600, 100),
        PUDDLE("textures/effects/leak_puddle.png", 1800, 600);

        public final String texture;
        public final int lifetime;
        private final int fadeTicks;

        Kind(String texture, int lifetime, int fadeTicks) {
            this.texture = texture;
            this.lifetime = lifetime;
            this.fadeTicks = fadeTicks;
        }

        public int alpha(long age) { return Math.round(255 * Math.clamp((lifetime - age) / (float) fadeTicks, 0, 1)); }
    }

    public record Shard(Vec velocity, float size) {}

    public static List<Shard> shards(int sourceId, long tick, String reason, float amount, float scale,
                                     Vec direction, int capacity) {
        boolean healthLoss = reason.equals("hit") || reason.equals("leak");
        float small = healthLoss ? .05f : .5f, medium = healthLoss ? .2f : 2f;
        int tier = amount <= small ? 0 : amount <= medium ? 1 : 2;
        if (reason.contains("vomit")) tier = Math.max(tier, 1);
        int count = new int[]{1, 3, 5}[tier];
        float base = Math.max(.12f, new float[]{.25f, .4f, .6f}[tier] * Math.min(2, scale));
        var random = new Random(31L * sourceId + tick + reason.hashCode() + Float.floatToIntBits(amount));
        var forward = lengthSquared(direction) < 1e-6 ? new Vec(0, 0, 1) : normal(direction);
        var shards = new ArrayList<Shard>();
        for (int i = 0; i < count && i < capacity; i++) {
            var lateral = normal(new Vec(random.nextDouble() - .5, 0, random.nextDouble() - .5))
                    .scale(.025 + .025 * random.nextDouble());
            var velocity = forward.scale(.045 + .025 * tier + .025 * random.nextDouble())
                    .add(lateral).add(new Vec(0, .035 + .018 * random.nextDouble(), 0));
            shards.add(new Shard(velocity, base / count * (1.4f + random.nextFloat())));
        }
        return shards;
    }

    public static Vec nextVelocity(Vec velocity) { return velocity.add(new Vec(0, -.012, 0)).scale(.92); }
    public static boolean touches(Vec a, Vec b) { return lengthSquared(a.sub(b)) < .16; }
    public static float traceSize(float size) { return Math.clamp(size, .12f, .6f); }
    public static float mergedSize(float previous, float size) { return Math.min(.6f, previous + size); }
    public static float halfX(float size) { return size * .5f; }
    public static float halfZ(float size) { return size * .34f; }

    public static float rimX(int i) { return (float) Math.cos(i * Math.PI / 6) * radius(i); }
    public static float rimZ(int i) { return (float) Math.sin(i * Math.PI / 6) * radius(i); }
    private static float radius(int i) { return i % 3 == 0 ? .84f : 1; }

    private static double lengthSquared(Vec v) { return v.x()*v.x() + v.y()*v.y() + v.z()*v.z(); }
    private static Vec normal(Vec v) {
        double length = Math.sqrt(lengthSquared(v));
        return length < 1e-4 ? Vec.ZERO : new Vec(v.x() / length, v.y() / length, v.z() / length);
    }

    private BodyEffectLook() {}
}
