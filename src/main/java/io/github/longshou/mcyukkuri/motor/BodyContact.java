package io.github.longshou.mcyukkuri.motor;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.NestLayout;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.action.Grab;
import io.github.longshou.mcyukkuri.motor.action.Pin;
import io.github.longshou.mcyukkuri.motor.geom.ContactScan;
import io.github.longshou.mcyukkuri.motor.geom.FlightCourse;
import io.github.longshou.mcyukkuri.motor.geom.OccupancyMath;
import io.github.longshou.mcyukkuri.motor.geom.SeparationStep;
import io.github.longshou.mcyukkuri.motor.geom.SeparationStep.Turn;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** One body's response to its neighbours, at its actual travel step or, during recoil, as a checked impulse. */
public final class BodyContact {
    private final YukkuriEntity body;
    private final SeparationStep separation = new SeparationStep();
    private final ContactScan<Entity> scan = new ContactScan<>(this::include, this::canStep);
    private final Predicate<Vec> groundFooting = step -> scan.test(step.x(), 0, step.z());
    private final FlightCourse.Shove shove = new FlightCourse.Shove(this::clearShove);
    private int pressed = -1;
    private long lastRecoil = Long.MIN_VALUE;
    private AABB lastBox, queryBox;
    private double lastReach, lastHeight;

    BodyContact(YukkuriEntity body) {
        this.body = body;
    }

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
                || peer.woundComfort().helping(body) || body.woundComfort().helping(peer);
    }

    private double share(YukkuriEntity peer) {
        double own = mass(body), theirs = mass(peer);
        return Math.clamp(2 * theirs / (own + theirs), .4, SeparationStep.MAX_YIELD) * (body.life().isSleeping() ? .6 : 1);
    }

    private static double mass(YukkuriEntity body) {
        return body.getBbWidth() * body.getBbWidth() * body.getBbHeight();
    }

    private boolean include(Entity peer) {
        return peer != body && !peer.isSpectator() && (peer instanceof LivingEntity || body.canCollideWith(peer));
    }

    private void collect(double reach, double height) {
        var box = body.getBoundingBox();
        if (!box.equals(lastBox) || reach != lastReach || height != lastHeight) {
            queryBox = box.inflate(reach, height, reach);
            lastBox = box;
            lastReach = reach;
            lastHeight = height;
        }
        ((ServerLevel)body.level()).getEntities().get(queryBox, scan);
    }

    Vec3 groundStep(Vec3 motion, boolean departing, boolean driving) {
        boolean free = free();
        if (lastRecoil != Long.MIN_VALUE) {
            // Follow the coasting body until it stops, enters contact, or its own gait takes over.
            boolean walking = driving && body.onGround() && body.hurtTime == 0 && lastRecoil < body.level().getGameTime() - 1;
            if (!free || motion.horizontalDistanceSqr() == 0 || walking) lastRecoil = Long.MIN_VALUE;
        }
        boolean crowd = free && grounded() && motion.y <= 0;
        if (!crowd) {
            pressed = -1;
            if (!departing && lastRecoil == Long.MIN_VALUE) return motion;
        }
        double reach = Math.max(motion.horizontalDistance(), SeparationStep.SHOVE * SeparationStep.MAX_YIELD) + SeparationStep.RELEASE_GAP;
        var bed = crowd ? body.occupiedBed() : null;
        float distance = body.boardingDistance(bed);
        boolean boarding = distance != NestLayout.SETTLED && distance != NestLayout.INACTIVE;
        // Two feet can touch opposite rims of the largest (two-block) layout.
        if (boarding) reach = Math.max(reach, 2 + 2 * (OccupancyMath.RIM_RING + Occupancy.MARGIN));
        collect(reach, Math.max(Math.abs(motion.y), body.maxUpStep()));
        if (departing || lastRecoil != Long.MIN_VALUE) scan.guard();
        if (!crowd) return motion;
        if (boarding) {
            for (int i = 0; i < scan.neighbours.size(); i++) {
                if (scan.neighbours.get(i) instanceof YukkuriEntity peer && body.bedTurn(bed, distance, peer) == Turn.WAIT && !sharesStand(peer)) {
                    motion = new Vec3(0, motion.y, 0);
                    break;
                }
            }
        }
        var here = body.getBoundingBox();
        YukkuriEntity closest = null;
        Turn turn = Turn.ORDINARY;
        double deepest = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < scan.neighbours.size(); i++) {
            if (!(scan.neighbours.get(i) instanceof YukkuriEntity peer)) continue;
            var box = peer.getBoundingBox();
            if (box.minY >= here.maxY || box.maxY <= here.minY) continue;
            double x = body.getX() - peer.getX(), z = body.getZ() - peer.getZ();
            double width = (body.getBbWidth() + peer.getBbWidth()) * .5;
            if (SeparationStep.clear(x, z, width + SeparationStep.RELEASE_GAP, motion.x, motion.z) || sharesStand(peer)) continue;
            var order = body.bedTurn(bed, distance, peer);
            double clearance = order.clearance(width, pressed == peer.getId());
            if (SeparationStep.clear(x, z, clearance, motion.x, motion.z)) continue;
            double depth = clearance - Math.max(Math.abs(x), Math.abs(z));
            if (depth > deepest) {
                deepest = depth;
                closest = peer;
                turn = order;
            }
        }
        if (closest == null) {
            pressed = -1;
            return motion;
        }
        double x = body.getX() - closest.getX(), z = body.getZ() - closest.getZ();
        double width = (body.getBbWidth() + closest.getBbWidth()) * .5;
        var wanted = distance == NestLayout.SETTLED && turn == Turn.PASS
                ? new Vec(0, motion.y, 0) : new Vec(motion.x, motion.y, motion.z);
        var step = separation.step(turn, x, z, width, pressed == closest.getId(),
                Integer.compare(body.getId(), closest.getId()), share(closest), wanted, groundFooting);
        pressed = closest.getId();
        scan.guard();
        return new Vec3(step.x(), motion.y, step.z());
    }

    public boolean permitsResolved(Vec3 step) { return scan.permitsResolved(step.x, step.y, step.z); }
    public void finishMove() { scan.clear(); }

    /** Vanilla calls both bodies. Ground travel already handled its own displacement, without accumulating impulses. */
    public void pushedBy(YukkuriEntity peer, double vx, double vz) {
        long tick = body.level().getGameTime();
        if (grounded() || !free() || sharesStand(peer)) return;
        var bed = body.occupiedBed();
        if (body.bedTurn(bed, body.boardingDistance(bed), peer) == Turn.PASS) return;
        double x = body.getX() - peer.getX(), z = body.getZ() - peer.getZ();
        double distance = Math.hypot(x, z), width = (body.getBbWidth() + peer.getBbWidth()) * .5;
        double strength = SeparationStep.SHOVE * (.5 + .5 * Math.clamp(1 - distance / width, 0, 1)) * share(peer);
        if (shove.remaining(tick, strength) <= 0) return;
        if (distance == 0) x = Math.copySign(1, body.getId() - peer.getId());
        else { x /= distance; z /= distance; }
        collect(strength, 0);
        if (shove.choose(tick, x * strength, z * strength, vx, vz)) {
            body.nudge(shove.x(), shove.z());
            lastRecoil = tick;
        } else GameplayLog.limited(body, "move", "shove.brace", 200, "shove braced at obstruction or brink");
        scan.clear();
    }

    private boolean clearShove(double dx, double dz, double driftX, double driftZ) {
        return clearStep(dx, 0, dz) && (body.isInLiquid() || SurfaceApproach.canSteerOver(body, body.position().add(driftX, 0, driftZ)));
    }

    private boolean canStep(double dx, double dy, double dz) {
        double reach = Math.max(Math.abs(dx), Math.abs(dz));
        if (reach > lastReach) {
            // A remembered flank can be farther away than this tick's stride.
            scan.neighbours.clear();
            collect(reach, lastHeight);
        }
        return clearStep(dx, dy, dz) && (body.isInLiquid() || Motor.safeContactStep(body, new Vec3(dx, dy, dz)));
    }

    private boolean clearStep(double dx, double dy, double dz) {
        var here = body.getBoundingBox();
        var next = here.move(dx, dy, dz);
        if (body.level().getBlockCollisions(body, next).iterator().hasNext()) return false;
        for (int i = 0; i < scan.neighbours.size(); i++) {
            var neighbour = scan.neighbours.get(i);
            var box = neighbour.getBoundingBox();
            if (!next.intersects(box)) continue;
            if (body.canCollideWith(neighbour)) return false;
            if (!FlightCourse.clearsNeighbour(here.intersects(box), body.getX() - neighbour.getX(),
                    body.getZ() - neighbour.getZ(), dx, dz)) return false;
        }
        return true;
    }
}
