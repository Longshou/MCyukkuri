package io.github.longshou.mcyukkuri.motor.surface;

import net.minecraft.world.phys.Vec3;

/** One rigid pose of a host for one tick. Matrix order is {@code yaw . roll(Z) . pitch(X)}; {@code unit} is blocks per hat unit, {@code yawRad} the turn, roll and pitch in degrees. */
public record HostFrame(Vec3 origin, double unit, float yawRad, float rollDeg, float pitchDeg) {

    /** A host-local point (hat units) in world coordinates. */
    public Vec3 toWorld(Vec3 local) {
        double pitch = Math.toRadians(pitchDeg);
        double roll = Math.toRadians(rollDeg);
        double x = local.x * unit;
        double y = local.y * unit;
        double z = local.z * unit;
        double pitchedY = y * Math.cos(pitch) - z * Math.sin(pitch);
        double pitchedZ = y * Math.sin(pitch) + z * Math.cos(pitch);
        double rolledX = x * Math.cos(roll) - pitchedY * Math.sin(roll);
        double rolledY = x * Math.sin(roll) + pitchedY * Math.cos(roll);
        double cosYaw = Math.cos(yawRad);
        double sinYaw = Math.sin(yawRad);
        return new Vec3(rolledX * cosYaw + pitchedZ * sinYaw, rolledY,
                pitchedZ * cosYaw - rolledX * sinYaw).add(origin);
    }

    /** A world point in host-local hat units; the exact inverse of {@link #toWorld}. */
    public Vec3 toLocal(Vec3 world) {
        Vec3 offset = world.subtract(origin);
        double cosYaw = Math.cos(yawRad);
        double sinYaw = Math.sin(yawRad);
        double x = offset.x * cosYaw - offset.z * sinYaw;
        double z = offset.x * sinYaw + offset.z * cosYaw;
        double pitch = Math.toRadians(pitchDeg);
        double roll = Math.toRadians(rollDeg);
        double unrolledX = x * Math.cos(roll) + offset.y * Math.sin(roll);
        double unrolledY = -x * Math.sin(roll) + offset.y * Math.cos(roll);
        double unpitchedY = unrolledY * Math.cos(pitch) + z * Math.sin(pitch);
        double unpitchedZ = -unrolledY * Math.sin(pitch) + z * Math.cos(pitch);
        return new Vec3(unrolledX / unit, unpitchedY / unit, unpitchedZ / unit);
    }
}
