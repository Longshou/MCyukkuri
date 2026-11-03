package io.github.longshou.mcyukkuri.motor.surface;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Motor;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

/** A transient destination on a moving host, never a reservation or another rider table. */
public record SeatTarget(SurfaceHost host, Vec3 local) {
    public Vec3 world() { return host.frame().toWorld(local); }
    public boolean available(YukkuriEntity body) { return unavailable(body) == null; }
    /** The first actual failed check, shared by the boolean query and a running action's refusal. */
    public String unavailable(YukkuriEntity body) {
        if (host.entity().isRemoved()) return "host_removed";
        if (!host.steady()) return "host_unsteady";
        if (!host.bears(body)) return "not_borne";
        if (host.support(local, body).isEmpty()) return "no_support";
        return host.room(body, local) ? null : "room";
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
