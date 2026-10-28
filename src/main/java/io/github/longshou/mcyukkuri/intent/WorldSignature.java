package io.github.longshou.mcyukkuri.intent;

import java.util.Set;
import net.minecraft.world.phys.Vec3;

/**
 * The world facts a method choice is keyed on (spec 11.2): target and host quantised to 0.5 blocks, the blocking
 * entity ids, and a 200 tick time bucket. A method excluded under one signature becomes selectable again once the
 * signature changes. A value: equal signatures exclude the same methods.
 */
public record WorldSignature(long targetX, long targetY, long targetZ, long hostX, long hostY, long hostZ,
                             Set<Integer> blockers, long bucket) {
    /** Ticks per signature bucket. */
    public static final long BUCKET_TICKS = 200;
    /** Encoded coordinate of an absent position; no real half-block lands here. */
    private static final long ABSENT = Long.MIN_VALUE;

    public WorldSignature {
        blockers = blockers == null ? Set.of() : Set.copyOf(blockers);
    }

    /** {@code hostPos} may be null (no host); the blockers and the tick bucket always take part. */
    public static WorldSignature of(Vec3 target, Vec3 hostPos, Set<Integer> blockerIds, long gameTime) {
        return new WorldSignature(half(target, 0), half(target, 1), half(target, 2),
                half(hostPos, 0), half(hostPos, 1), half(hostPos, 2),
                blockerIds, Math.floorDiv(gameTime, BUCKET_TICKS));
    }

    /** A signature with no world facts beyond the tick bucket. */
    public static WorldSignature of(long gameTime) {
        return of(null, null, Set.of(), gameTime);
    }

    /** The quantised coordinate in half blocks, or {@link #ABSENT} for a null position. */
    private static long half(Vec3 p, int axis) {
        if (p == null) return ABSENT;
        double v = axis == 0 ? p.x : axis == 1 ? p.y : p.z;
        return Math.round(v * 2);
    }
}
