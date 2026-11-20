package io.github.longshou.mcyukkuri.motor;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.action.Grab;
import io.github.longshou.mcyukkuri.motor.action.Pin;
import io.github.longshou.mcyukkuri.motor.geom.FlightCourse;
import io.github.longshou.mcyukkuri.motor.geom.SeparationStep;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;

/** One body's response to its neighbours, at its actual travel step or, during recoil, as a checked impulse. */
public final class BodyContact {
    private static final EntityTypeTest<Entity, LivingEntity> LIVING = EntityTypeTest.forClass(LivingEntity.class);
    private final YukkuriEntity body;
    private final List<LivingEntity> neighbours = new ArrayList<>();
    private final FlightCourse.Shove shove = new FlightCourse.Shove(this::clearShove);
    private int pressed = -1;

    BodyContact(YukkuriEntity body) { this.body = body; }

    private boolean grounded() {
        return body.onGround() && !body.isInLiquid() && body.hurtTime == 0 && body.usesCrowdSpacing();
    }

    private boolean free() {
        var action = body.motor().current();
        return body.nudgeable() && !body.noPhysics && !body.pairedContact() && body.carePartnerId() < 0
                && !(action instanceof Grab || action instanceof Pin || action instanceof Pin.Held)
                && !body.woundComfort().inContact() && !body.operations().active();
    }

    private boolean sharesStand(YukkuriEntity peer) {
        return body.isPassengerOfSameVehicle(peer) || peer.noPhysics || peer.isPassenger()
                || body.nuzzlePartnerId() == peer.getId() || peer.nuzzlePartnerId() == body.getId()
                || peer.carePartnerId() == body.getId() || body.careApproach().cooperatingWith(peer)
                || body.family().courting(peer) || peer.family().courting(body)
                || peer.motor().current() instanceof Grab grip && grip.target() == body
                || peer.woundComfort().helping(body) || body.woundComfort().helping(peer)
                || body.sharesNearbyNest(peer);
    }

    private double share(YukkuriEntity peer) {
        double own = mass(body), theirs = mass(peer);
        return Math.clamp(2 * theirs / (own + theirs), .4, 1.8) * (body.life().isSleeping() ? .6 : 1);
    }

    private static double mass(YukkuriEntity body) {
        return body.getBbWidth() * body.getBbWidth() * body.getBbHeight();
    }

    private void collect(double reach) {
        neighbours.clear();
        body.level().getEntities(LIVING, body.getBoundingBox().inflate(reach, 0, reach), EntitySelector.NO_SPECTATORS, neighbours);
    }

    Vec3 groundStep(Vec3 motion) {
        if (!grounded() || motion.y > 0 || !free()) {
            pressed = -1;
            return motion;
        }
        double reach = Math.max(motion.horizontalDistance(), SeparationStep.SHOVE * 1.8) + SeparationStep.RELEASE_GAP;
        collect(reach);
        YukkuriEntity closest = null;
        double deepest = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < neighbours.size(); i++) {
            var neighbour = neighbours.get(i);
            if (!(neighbour instanceof YukkuriEntity peer) || peer == body) continue;
            double x = body.getX() - peer.getX(), z = body.getZ() - peer.getZ();
            double width = (body.getBbWidth() + peer.getBbWidth()) * .5;
            double clearance = width + (peer.getId() == pressed ? SeparationStep.RELEASE_GAP : 0);
            if (SeparationStep.clear(x, z, clearance, motion.x, motion.z) || sharesStand(peer)) continue;
            double depth = clearance - Math.max(Math.abs(x), Math.abs(z));
            if (depth > deepest) {
                deepest = depth;
                closest = peer;
            }
        }
        if (closest == null) {
            pressed = -1;
            neighbours.clear();
            return motion;
        }
        var step = SeparationStep.crowd(body.getX() - closest.getX(), body.getZ() - closest.getZ(),
                (body.getBbWidth() + closest.getBbWidth()) * .5, closest.getId() == pressed,
                Integer.compare(body.getId(), closest.getId()), share(closest), new Vec(motion.x, motion.y, motion.z),
                candidate -> clearStep(candidate.x(), candidate.z())
                        && Motor.safeContactStep(body, candidate));
        pressed = closest.getId();
        neighbours.clear();
        return new Vec3(step.x(), motion.y, step.z());
    }

    /** Vanilla calls both bodies. Ground travel already handled its own displacement, without accumulating impulses. */
    public void pushedBy(YukkuriEntity peer, double vx, double vz) {
        if (grounded() || !free() || sharesStand(peer)) return;
        double x = body.getX() - peer.getX(), z = body.getZ() - peer.getZ();
        double distance = Math.hypot(x, z), width = (body.getBbWidth() + peer.getBbWidth()) * .5;
        double strength = SeparationStep.SHOVE * (.5 + .5 * Math.clamp(1 - distance / width, 0, 1)) * share(peer);
        if (distance == 0) x = Math.copySign(1, body.getId() - peer.getId());
        else { x /= distance; z /= distance; }
        collect(strength);
        if (shove.choose(x * strength, z * strength, vx, vz)) body.nudge(shove.x(), shove.z());
        else GameplayLog.limited(body, "move", "shove.brace", 200, "shove braced at obstruction or brink");
        neighbours.clear();
    }

    private boolean clearShove(double dx, double dz, double driftX, double driftZ) {
        return clearStep(dx, dz) && (body.isInLiquid() || SurfaceApproach.canSteerOver(body, body.position().add(driftX, 0, driftZ)));
    }

    private boolean clearStep(double dx, double dz) {
        var here = body.getBoundingBox();
        var next = here.move(dx, 0, dz);
        if (!body.level().noCollision(body, next)) return false;
        for (int i = 0; i < neighbours.size(); i++) {
            var neighbour = neighbours.get(i);
            var box = neighbour.getBoundingBox();
            if (neighbour != body && next.intersects(box) && !FlightCourse.clearsNeighbour(here.intersects(box),
                    body.getX() - neighbour.getX(), body.getZ() - neighbour.getZ(), dx, dz)) return false;
        }
        return true;
    }
}
