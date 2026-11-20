package io.github.longshou.mcyukkuri.family;

/** A new mouth contact or a changed supporting surface permits another idle put-down. */
record CareReleaseSite(double x, double y, double z, boolean supported) {
    boolean changedFrom(CareReleaseSite other, double tolerance) {
        double dx=x-other.x, dy=y-other.y, dz=z-other.z;
        return supported != other.supported || dx*dx+dy*dy+dz*dz > tolerance*tolerance;
    }
}
