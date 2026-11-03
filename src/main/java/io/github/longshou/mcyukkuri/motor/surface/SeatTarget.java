package io.github.longshou.mcyukkuri.motor.surface;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Motor;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

/** A transient destination on a moving host, never a reservation or another rider table. */
public record SeatTarget(SurfaceHost host, Vec3 local) {
    public Vec3 world() { return host.frame().toWorld(local); }
    public boolean available(YukkuriEntity body) {
        return !host.entity().isRemoved() && host.steady() && host.bears(body)
                && host.support(local, body).isPresent() && host.room(body, local);
    }
    /** Attach only after movement has visibly reached the live destination. */
    public boolean land(YukkuriEntity body, String cause) {
        if (!available(body) || body.position().distanceTo(world()) > .05) return false;
        if (body.getVehicle() != host.entity() && !Motor.mount(body, host.entity(), cause)) return false;
        Motor.spot(host, body, local, Mth.wrapDegrees(body.getYRot() - host.entity().getYRot()), cause);
        Motor.velocity(body, Vec3.ZERO, cause);
        return true;
    }
}
