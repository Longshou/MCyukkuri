package io.github.longshou.mcyukkuri.motor.surface;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Motor;
import net.minecraft.world.phys.Vec3;

/** A transient destination on a moving host, never a reservation or another rider table. */
public record SeatTarget(SurfaceHost host, Vec3 local) {
    public Vec3 world() { return host.frame().toWorld(local); }
    public boolean available(YukkuriEntity body) {
        var now = host.seatDestination(body);
        return !host.entity().isRemoved() && host.steady() && now != null && local.equals(now.local());
    }
    /** Attach only after movement has visibly reached the live destination. */
    public boolean land(YukkuriEntity body, String cause) {
        if (!available(body) || body.position().distanceTo(world()) > .05) return false;
        if (!Motor.mount(body, host.entity(), cause)) return false;
        host.riders().put(body.getUUID(), local, body.getYRot() - host.entity().getYRot());
        Motor.velocity(body, Vec3.ZERO, cause);
        return true;
    }
}
