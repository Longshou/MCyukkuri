package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import net.minecraft.world.phys.Vec3;

/** A running grip action's actual load-bearing contact, after check/start have settled its geometry. */
public interface LoadedContact {
    record Contact(SurfaceHost host,Vec3 point,double covered) {}
    Contact loadedContact(YukkuriEntity body);
}
