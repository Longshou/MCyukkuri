package io.github.longshou.mcyukkuri.motor;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import net.minecraft.world.phys.Vec3;

/** Where a body is, derived from facts only: the vanilla vehicle relation plus the host's {@link Riders}, never stored as a second fact. */
public sealed interface AttachState
        permits AttachState.Free, AttachState.Airborne, AttachState.OnHost, AttachState.Held {

    /** On the ground or in the water, not attached to anything. */
    record Free(boolean inWater) implements AttachState {}

    /** In a Hop or Launch flight that has not touched anything yet; {@code action} is the action id. */
    record Airborne(String action) implements AttachState {}

    /** Riding a {@link SurfaceHost}; the position is the host-local spot in hat units. */
    record OnHost(SurfaceHost host, Vec3 local, float localYaw) implements AttachState {}

    /** Riding a body that carries it (an armful). */
    record Held(YukkuriEntity carrier) implements AttachState {}

    /** The {@code ATTACH_KIND} sync value: 0 Free, 1 Airborne, 2 OnHost, 3 Held. */
    default byte kind() {
        if (this instanceof Free) return 0;
        if (this instanceof Airborne) return 1;
        if (this instanceof OnHost) return 2;
        return 3;
    }
}
