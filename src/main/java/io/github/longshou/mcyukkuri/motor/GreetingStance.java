package io.github.longshou.mcyukkuri.motor;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import net.minecraft.world.phys.Vec3;

/** Feet for the peer greeting and its reply. The hop and bow only pose the rendered model. */
public final class GreetingStance {
    // The posed greet and nod meshes extend less than a quarter of the body height past the collision half-width.
    // Using height as well as width reserves the extra forward sweep of a tall, narrow bean body.
    private static final double LEAN_RESERVE = .25;
    private static final double ENCOUNTER = 1.5;

    public static boolean clear(YukkuriEntity actor, Vec3 at, YukkuriEntity peer) {
        return at.subtract(peer.position()).horizontalDistance() >= visualDistance(actor, peer)
                && !actor.getBoundingBox().move(at.subtract(actor.position()))
                        .inflate(SurfaceApproach.ARRIVAL, 0, SurfaceApproach.ARRIVAL).intersects(peer.getBoundingBox());
    }

    public static double visualDistance(YukkuriEntity actor, YukkuriEntity peer) {
        return front(actor) + front(peer);
    }

    /** Keep a clear nearby stand, or walk to one around the peer using the ordinary surface approach. */
    public static SurfaceApproach.Result approach(YukkuriEntity actor, YukkuriEntity peer, double speed, double height) {
        Vec3 offset = actor.position().subtract(peer.position());
        double flat = offset.horizontalDistance();
        double radius = (actor.getBbWidth() + peer.getBbWidth()) * .5 + SurfaceApproach.ARRIVAL;
        double square = flat == 0 ? radius : radius * flat / Math.max(Math.abs(offset.x), Math.abs(offset.z));
        double stand = Math.max(square, visualDistance(actor, peer)) + SurfaceApproach.ARRIVAL;
        if (actor.onGround() && Math.abs(offset.y) <= height
                && offset.length() <= Math.max(ENCOUNTER, Math.hypot(stand + SurfaceApproach.ARRIVAL, offset.y))
                && clear(actor, actor.position(), peer)) {
            SurfaceApproach.halt(actor);
            return SurfaceApproach.Result.ARRIVED;
        }
        Vec3 at = SurfaceApproach.beside(actor, peer, stand, height, Double.POSITIVE_INFINITY,
                point -> clear(actor, point, peer));
        if (at == null) return SurfaceApproach.Result.BLOCKED;
        var result = SurfaceApproach.walkResult(actor, at, peer, speed);
        if (result == SurfaceApproach.Result.ARRIVED && !clear(actor, actor.position(), peer))
            return SurfaceApproach.Result.BLOCKED;
        return result;
    }

    private static double front(YukkuriEntity body) {
        return body.getBbWidth() * .5 + body.getBbHeight() * LEAN_RESERVE;
    }

    private GreetingStance() {}
}
