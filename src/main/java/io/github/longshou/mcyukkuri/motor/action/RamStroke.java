package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.geom.Effort;
import io.github.longshou.mcyukkuri.motor.geom.RamContact;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** The same authored launch, contact and recoil against any block face. The caller owns its impact. */
final class RamStroke {
    static final String CLIP = RamContact.CLIP;
    private static final ClipMarkers.Clip TIMING = ClipMarkers.of(CLIP);
    static final int LENGTH = TIMING.ticks("settled"), LAUNCH = TIMING.ticks("launch"), IMPACT = TIMING.ticks("impact");
    record Frame(double gap, double travel) {
        // Sub-milliblock arithmetic tolerance: the authored front is the contact surface.
        boolean reached() { return Math.abs(gap) <= 1.0 / 1024; }
    }

    private final Vec3 face, outward, stand;

    RamStroke(YukkuriEntity body, BlockHitResult hit) {
        face = hit.getLocation();
        outward = Vec3.atLowerCornerOf(hit.getDirection().getNormal());
        stand = body.position();
    }

    static double scale(YukkuriEntity body) { return body.growthScale() * body.getScale() / 16; }

    static BlockHitResult contact(YukkuriEntity body, BlockPos block) {
        var from = body.position().add(0, body.getBbHeight() / 2, 0);
        return body.level().clip(new ClipContext(from, new Vec3(block.getX() + .5, from.y, block.getZ() + .5),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body));
    }

    static float square(Vec3 outward) { return SurfaceApproach.yaw(Vec3.ZERO, outward.reverse()); }

    void face(YukkuriEntity body) {
        Motor.face(body, body.yBodyRot + (float) Effort.turn(body.yBodyRot, square(outward), BodyAbility.of(body).turnRate()));
    }

    Frame move(YukkuriEntity body, int phase, String id) {
        var contact = RamContact.of(body.assetId());
        // The marker falls between server ticks; sample its exact pose on the impact tick.
        double seconds = phase == IMPACT ? contact.strike() : phase == LAUNCH ? contact.launch()
                : phase == LENGTH - 1 ? contact.settled() : phase / 20.0;
        double scale = scale(body);
        double standDistance = stand.subtract(face).dot(outward);
        double distance = contact.distance(standDistance / scale, seconds) * scale;
        var target = stand.add(outward.scale(distance - standDistance));
        Motor.velocity(body, new Vec3(0, body.getDeltaMovement().y, 0), id);
        Motor.move(body, target.subtract(body.position()).multiply(1, 0, 1), id);
        return new Frame(body.position().subtract(face).dot(outward) - contact.impact() * scale, standDistance - distance);
    }
}
