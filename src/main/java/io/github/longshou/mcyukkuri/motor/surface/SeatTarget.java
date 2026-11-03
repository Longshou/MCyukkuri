package io.github.longshou.mcyukkuri.motor.surface;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Motor;
import java.util.Locale;
import java.util.stream.Collectors;
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
    /** Observed geometry at lowering start or refusal; never consulted to admit or move a body. */
    public String detail(YukkuriEntity body) {
        var frame = host.frame();
        var entity = host.entity();
        var shape = Floor.shape(host, body);
        String riders = entity.getPassengers().stream().filter(p -> p instanceof YukkuriEntity).map(p -> {
            var other = (YukkuriEntity) p;
            var at = host.riders().local(other.getUUID());
            var footprint = Floor.shape(host, other);
            boolean blocks = other != body && shape.overlaps(local.x, local.y, local.z, footprint,
                    at.x, at.y, at.z, host.roomGap());
            return String.format(Locale.ROOT, "uuid=%s size=(%.5f,%.5f) local=%s yaw=%.3f blocks=%s carrier=%s",
                    other.getUUID(), other.getBbWidth(), other.getBbHeight(), at, Math.toDegrees(footprint.yaw()),
                    blocks, body.getVehicle() == other);
        }).collect(Collectors.joining(";"));
        return String.format(Locale.ROOT, "target_local=%s child_local=%s child_size=(%.5f,%.5f) child_yaw=%.3f "
                        + "footprint_yaw=%.3f host=%s origin=%s delta=%s velocity=%s yaw=%.3f->%.3f tilt=(%.3f,%.3f) gap=%.5f riders=[%s]",
                local, frame.toLocal(body.position()), body.getBbWidth(), body.getBbHeight(), body.yBodyRot,
                Math.toDegrees(shape.yaw()), entity.getUUID(), frame.origin(),
                entity.position().subtract(entity.xo, entity.yo, entity.zo), entity.getDeltaMovement(), entity.yRotO,
                entity.getYRot(), frame.rollDeg(), frame.pitchDeg(), host.roomGap(), riders);
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
