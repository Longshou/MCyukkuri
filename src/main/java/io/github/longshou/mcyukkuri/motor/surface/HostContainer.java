package io.github.longshou.mcyukkuri.motor.surface;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.geom.FloorShape;
import net.minecraft.world.phys.Vec3;

/**
 * The hollow of a host (the catch hat), read through the host's frame so tilt is included. The host supplies
 * the inner floor outline in blocks and the floor and rim heights in hat units; nothing here knows the hat model.
 */
public record HostContainer(SurfaceHost host, FloorShape floor, double floorLocal, double rimLocal) implements Container {
    @Override public double floorY() { return floorLocal * host.frame().unit(); }
    @Override public double rimY() { return rimLocal * host.frame().unit(); }

    @Override public Vec3 toPlane(Vec3 world) { return host.frame().toLocal(world).scale(host.frame().unit()); }

    /** Only a rider of this host stands on its surface (a catch hat has no block collision). */
    @Override public boolean supports(YukkuriEntity body) {
        if (!(Attach.of(body) instanceof AttachState.OnHost on) || on.host() != host) return false;
        return host.support(on.local(), Occupancy.FOOT * body.getBbWidth()).isPresent();
    }
}
