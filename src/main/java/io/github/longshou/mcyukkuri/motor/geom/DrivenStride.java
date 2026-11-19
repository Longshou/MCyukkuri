package io.github.longshou.mcyukkuri.motor.geom;

/** One motor stroke and its collision-resolved displacement. External velocity can share any axis with the stroke. */
public final class DrivenStride {
    private int driveTick = -1, ranTick = -1;
    private double x, y, z, minimum;

    public void drive(int tick, double dx, double dy, double dz, double fullStroke) {
        driveTick = tick;
        x = dx;
        y = dy;
        z = dz;
        // A fast request clipped below an ordinary stroke is just a final adjustment.
        minimum = Gait.ordinaryStroke(fullStroke);
    }

    public void resolved(int tick, double vx, double vy, double vz, double dx, double dy, double dz) {
        if (driveTick != tick) return;
        driveTick = -1;
        double ax = driven(x, vx, dx), ay = driven(y, vy, dy), az = driven(z, vz, dz);
        if (minimum > 0 && ax * ax + ay * ay + az * az >= minimum * minimum) ranTick = tick;
    }

    private static double driven(double drive, double velocity, double moved) {
        double sign = Math.signum(drive);
        double drift = Math.max(0, (velocity - drive) * sign);
        return Math.clamp(moved * sign - drift, 0, Math.abs(drive));
    }

    public boolean ran(int tick) { return ranTick == tick; }
}
