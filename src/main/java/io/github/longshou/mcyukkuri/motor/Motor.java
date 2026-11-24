package io.github.longshou.mcyukkuri.motor;
import io.github.longshou.mcyukkuri.motor.geom.NestFooting;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriGroundNavigation;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.action.Grab;
import io.github.longshou.mcyukkuri.motor.action.Launch;
import io.github.longshou.mcyukkuri.motor.geom.Ballistic;
import io.github.longshou.mcyukkuri.motor.geom.Effort;
import io.github.longshou.mcyukkuri.motor.geom.Gait;
import io.github.longshou.mcyukkuri.motor.geom.SeparationStep;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Reactions;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityMountEvent;

/** Writes and records a body's velocity, position, facing and riding; a straight step follows the caller's target and speed. */
public final class Motor {
    /** No nudge may move a body further than this in one call (blocks). */
    public static final double NUDGE_MAX = 0.25;
    /** Invariant I4: a dismount may not move the body further than this on either side of the vehicle relation (blocks). */
    public static final double DISMOUNT_MAX = 0.25;
    /** Covers the farewell line and the initial walking strides out of either contact stand. */
    private static final int CONTACT_RELEASE_TICKS = 60;

    /** True only while {@link #dismount} runs: a host's {@code getDismountLocationForPassenger} then returns the passenger's own position, so vanilla cannot move it to the vehicle. */
    public static final ThreadLocal<Boolean> DISMOUNTING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private Motor() {}

    /** Preserve gravity and step-up. Only the horizontal travel of the gait is bounded. */
    public static Vec3 limitHorizontal(Vec3 motion) {
        double distance = motion.horizontalDistance();
        if (distance <= Gait.HUMAN_WALK) return motion;
        double scale = Gait.HUMAN_WALK / distance;
        return motion.multiply(scale, 1, scale);
    }

    public static void waitOnFoot(Mob body) {
        body.getMoveControl().setWantedPosition(body.getX(), body.getY(), body.getZ(), 0);
        body.setSpeed(0);
        velocity(body, body.getDeltaMovement().multiply(0, 1, 0), "passage_wait");
    }

    /** Check the actual turn and inertia after steering, including a direct step with no path. */
    public static Vec3 groundTravel(YukkuriEntity body, Vec3 motion, boolean driving) {
        if (body.family().freeFalling() || body.motor().current() instanceof Launch) return motion;
        Vec3 limited = driving ? limitHorizontal(motion) : motion;
        limited = nestStep(body, limited);
        if (!body.level().isClientSide && limited.horizontalDistanceSqr() > 0
                && body.getNavigation() instanceof YukkuriGroundNavigation ground && !body.isInLiquid()
                && (ground.retreating() || body.isPanicking() || body.status().reactions().threat().stage() == Reactions.Threat.Stage.RETREAT)
                && (body.onGround() || motion.y <= 0) && (body.hurtTicks() == 0 || body.onGround() && motion.y <= 0)) {
            var from = body.position();
            var to = from.add(limited.x, 0, limited.z);
            if (!SurfaceApproach.canSteerOver(body, to)) {
                // Keep any safe motion along the brink while MoveControl turns back onto its route.
                if (limited.z != 0 && SurfaceApproach.canSteerOver(body, from.add(0, 0, limited.z)))
                    limited = limited.multiply(0, 1, 1);
                else if (limited.x != 0 && SurfaceApproach.canSteerOver(body, from.add(limited.x, 0, 0)))
                    limited = limited.multiply(1, 1, 0);
                else limited = limited.multiply(0, 1, 0);
                int suppressed = GameplayLog.admit(body, "move", "unsafe_drop", 100);
                if (suppressed >= 0) GameplayLog.limitedEvent(body, "move", suppressed, "avoided unsafe landing toward={} step={}",
                        GameplayLog.at(to), GameplayLog.at(limited));
            }
        }
        var beforeDeparture = limited;
        if (!body.level().isClientSide) limited = body.motor().departureStep(limited);
        if (limited != motion) body.setDeltaMovement(limited);
        if (!body.level().isClientSide) {
            var step = body.motor().contact.groundStep(limited, limited != beforeDeparture, driving);
            if (step != limited) {
                // A separating footstep does not leave shove momentum for vanilla to integrate on the next tick.
                body.setDeltaMovement(new Vec3(0, limited.y, 0));
                return step;
            }
        }
        return limited;
    }

    public static void separateAfterContact(YukkuriEntity first, YukkuriEntity second) {
        long until = first.level().getGameTime() + CONTACT_RELEASE_TICKS;
        first.motor().separatingFrom(second, until);
        second.motor().separatingFrom(first, until);
    }

    static Vec3 departureStep(YukkuriEntity body, YukkuriEntity partner, Vec3 motion) {
        var own = body.getBoundingBox();
        var other = partner.getBoundingBox();
        if (own.minY + Math.min(0, motion.y) >= other.maxY || own.maxY + Math.max(0, motion.y) <= other.minY)
            return motion;
        // The walking pose can extend past the collision box, as when each partner turns after a side press.
        double clearance = GreetingStance.visualDistance(body, partner);
        var wanted = new Vec(motion.x, motion.y, motion.z);
        var step = SeparationStep.away(body.getX() - partner.getX(), body.getZ() - partner.getZ(), clearance, wanted,
                candidate -> safeContactStep(body, new Vec3(candidate.x(), 0, candidate.z())));
        return step == wanted ? motion : new Vec3(step.x(), motion.y, step.z());
    }

    static boolean safeContactStep(YukkuriEntity body, Vec3 delta) {
        if (!nestStep(body, delta).equals(delta)) return false;
        var at = body.position().add(delta);
        return SurfaceApproach.canSteerOver(body, at) && (body.onGround() ? SurfaceApproach.stands(body, at)
                : SurfaceApproach.dryStep(body, body.position(), at));
    }

    public static void brakeFledglingFall(YukkuriEntity body) {
        var v = body.getDeltaMovement();
        if (v.y < 0 && body.fallDistance > 0 && body.childhood().nestling() && !body.family().awaitingLanding()
                && body.childhood().wingPractice() > 0)
            velocity(body, new Vec3(v.x, body.childhood().fallingHeight((float)v.y), v.z), "fledge_flap");
    }

    static Vec3 nestStep(YukkuriEntity body, Vec3 delta) {
        if (body.level().isClientSide || !NestFooting.confines(body.childhood().nestling(), body.isPassenger(),
                body.family().awaitingLanding(), body.childhood().hopping(), body.predatorFlying())) return delta;
        var footing = body.childhood().footingUnderfoot(true);
        if (footing == null) return delta;
        double radius = SurfaceApproach.supportRadius(body);
        return new Vec3(NestFooting.step(body.getX(), delta.x, footing.getX(), radius), delta.y,
                NestFooting.step(body.getZ(), delta.z, footing.getZ(), radius));
    }

    /** Path-node steering uses the same three-dimensional pace as a direct flight command. */
    public static void fly(YukkuriEntity body, Vec3 input, Vec3 controlled) {
        double drag = Ballistic.AIR_DRAG;
        if (controlled == null) {
            controlled = body.getDeltaMovement();
            if (input.lengthSqr() > 1e-8) {
                var control = body.getMoveControl();
                var toward = new Vec3(control.getWantedX(), control.getWantedY(), control.getWantedZ()).subtract(body.position());
                double pace = BodyAbility.flightPace(body, control.getSpeedModifier());
                double thrust = pace * (1 - drag);
                var drive = toward.normalize().scale(thrust);
                double ordinary = Gait.ordinaryStroke(pace);
                if (toward.lengthSqr() >= ordinary * ordinary)
                    body.motor().propulsion(drive.x, drive.y, drive.z, thrust);
                controlled = controlled.add(drive);
            }
        }
        Vec3 wanted = controlled;
        double distance = controlled.length();
        double ceiling = BodyAbility.flightCeiling(body);
        if (distance > ceiling) controlled = controlled.scale(ceiling / distance);
        if (!body.level().isClientSide) controlled = body.predator().clearTerritoryFlightStep(controlled);
        velocity(body, controlled, "predator_flight");
        Vec3 moved = move(body, controlled, "predator_flight");
        body.motor().stride.resolved(body.tickCount, wanted.x, wanted.y, wanted.z, moved.x, moved.y, moved.z);
        velocity(body, body.getDeltaMovement().scale(drag), "predator_drag");
    }

    /** A supported body's own action follows its changing underside without moving across the host. */
    public static void supportHeight(SurfaceHost host, YukkuriEntity body, double height, String cause) {
        var place = host.riders().spot(body.getUUID());
        spot(host, body, new Vec3(place.local().x, height, place.local().z), place.yaw(), cause);
    }

    /** Vanilla's old passenger snapshot may still visit a child just grabbed from the carrier's host.
     *  Keep the live grip; explicit motor releases and either body's death still dismount normally. */
    public static void mounting(EntityMountEvent event) {
        if (!event.isDismounting() || event.getLevel().isClientSide || DISMOUNTING.get()) return;
        if (event.getEntityMounting() instanceof YukkuriEntity child && child.isAlive()
                && event.getEntityBeingMounted() instanceof YukkuriEntity carrier && carrier.isAlive()
                && carrier.motor().current() instanceof Grab grab && grab.target() == child) {
            event.setCanceled(true);
            GameplayLog.limited(carrier, "body", "grip.dismount", 100, "kept active grip child={}", child.getUUID());
        }
    }

    /** A short straight ground step; vanilla still resolves collision, step-up and gravity, as for StepIn. */
    public static boolean step(YukkuriEntity body, Vec3 to, double speed) {
        return step(body, to, speed, true);
    }

    /** The same step can keep facing a contact partner when making room. */
    static boolean step(YukkuriEntity body, Vec3 to, double speed, boolean faceStep) {
        var delta=to.subtract(body.position()).multiply(1,0,1);double distance=delta.length();
        body.markDriven();
        body.getMoveControl().setWantedPosition(to.x,to.y,to.z,0);body.setSpeed(0);
        double pace=BodyAbility.of(body).walkSpeed()*BodyAbility.carrying(body)*speed*body.species().stride(speed);
        var v=limitHorizontal(distance > pace ? delta.scale(pace / distance) : delta);
        body.motor().propulsion(v.x, 0, v.z, Math.min(pace, Gait.HUMAN_WALK));
        if(faceStep&&distance>.001)face(body,SurfaceApproach.yaw(body.position(),to));
        velocity(body,new Vec3(v.x,body.getDeltaMovement().y,v.z),"straight_step");
        return SurfaceApproach.at(body,to);
    }

    /** Sets an entity's velocity and marks the impulse: body propulsion, host drift or a released item. */
    public static void velocity(Entity body, Vec3 v, String cause) {
        if (body == null || v == null) return;
        body.setDeltaMovement(v);
        body.hasImpulse = true;
    }

    /** Begin an airborne impulse before vanilla chooses this tick's friction; collision still determines actual contact. */
    public static void takeoff(YukkuriEntity body, Vec3 v, String cause) {
        Takeoff.apply(body::setOnGround, () -> velocity(body, v, cause));
    }

    /** The take-off write order, independent of Minecraft: clear ground contact before applying the airborne impulse. */
    public static final class Takeoff {
        private Takeoff() {}
        public static void apply(Consumer<Boolean> ground, Runnable impulse) {
            ground.accept(false);
            impulse.run();
        }
    }

    /** A collision-resolved move (MoverType.SELF); returns the movement that actually happened. */
    public static Vec3 move(Entity body, Vec3 delta, String cause) {
        if (body == null || delta == null) return Vec3.ZERO;
        Vec3 from = body.position();
        if (body instanceof YukkuriEntity animal) animal.moveByMotor(nestStep(animal, delta));
        else body.move(MoverType.SELF, delta);
        return body.position().subtract(from);
    }

    /** All own-facing writers share the tick's starting body yaw, so multiple requests cannot multiply the turn rate. */
    public static float turnYaw(YukkuriEntity body, float yaw) {
        return body.yBodyRotO + (float) Effort.turn(body.yBodyRotO, yaw, BodyAbility.of(body).turnRate());
    }

    /** Turns the whole body within its ability; host-derived rider facing is applied separately. */
    public static void face(YukkuriEntity body, float yaw) {
        if (body == null) return;
        applyFacing(body, turnYaw(body, yaw));
    }

    /** Aim a human's held tool from its eyes at the point of contact. */
    public static void face(LivingEntity actor, Vec3 contact) {
        actor.lookAt(EntityAnchorArgument.Anchor.EYES, contact);
    }

    /** Sets the initial pose for a system placement, including before a destination is tested at that pose. */
    public static void placementFacing(YukkuriEntity body, float yaw) {
        applyFacing(body, yaw);
        body.yRotO = body.getYRot();
        body.yBodyRotO = body.yBodyRot;
        body.yHeadRotO = body.getYHeadRot();
    }

    private static void applyFacing(YukkuriEntity body, float yaw) {
        if (Attach.of(body) instanceof AttachState.OnHost on) {
            spot(on.host(), body, on.local(), Mth.wrapDegrees(yaw - on.host().entity().getYRot()), "face");
        } else riderFacing(body, yaw);
    }

    /** Replace vanilla's instant body alignment; only the body yaw is rate-limited, the head and facing stay with their owners. */
    public static void bodyTurn(YukkuriEntity body) {
        if (body.isPassenger()) return;
        double dx = body.getX() - body.xo, dz = body.getZ() - body.zo;
        // A walker pushing into a wall still needs to turn toward its next path node.
        if (dx * dx + dz * dz > MoveControl.MIN_SPEED_SQR || body.horizontalCollision && body.zza > 0) {
            body.setYBodyRot(turnYaw(body, body.getYRot()));
            body.setYHeadRot(Mth.rotateIfNecessary(body.bodyYaw(), body.getYHeadRot(), (float) body.getMaxHeadYRot()));
        } else {
            body.setYBodyRot(turnYaw(body, body.getYHeadRot()));
        }
    }

    /** Apply the host-derived facing on both sides; it is never a second rider authority. */
    public static void riderFacing(Entity body, float yaw) {
        body.setYRot(yaw);
        if (body instanceof LivingEntity living) {
            living.setYBodyRot(yaw);
            living.setYHeadRot(yaw);
        }
    }

    /** A host-local displacement must clear the world's blocks as well as the host's other riders. */
    public static boolean clearSpot(SurfaceHost host, YukkuriEntity body, Vec3 local) {
        var delta = host.frame().toWorld(local).subtract(body.position());
        return !body.level().getBlockCollisions(body, body.getBoundingBox().expandTowards(delta).deflate(.001)).iterator().hasNext();
    }

    public static void spot(SurfaceHost host, YukkuriEntity body, Vec3 local, float yaw, String cause) {
        host.riders().put(body.getUUID(), local, yaw);
        host.entity().positionRider(body);
    }

    /** A moving host's facing and synchronized tilt; riders follow its shared frame. */
    public static void hostPose(SurfaceHost host, float yaw, float roll, float pitch) {
        host.entity().setYRot(yaw);
        host.pose(roll, pitch);
    }

    /** Places a body the system itself moves: a delivery, a ceremony, a released captive, a new body. Never a way to finish a movement that failed. */
    public static void relocate(YukkuriEntity body, Vec3 at, float yaw, String cause) {
        if (body == null || at == null) return;
        Vec3 from = body.position();
        body.setPos(at.x, at.y, at.z);
        placementFacing(body, yaw);
        body.setDeltaMovement(Vec3.ZERO);
        body.hasImpulse = true;
        // The same previous-position snapshot vanilla's moveTo takes, so the renderer does not glide the body across the gap.
        body.setOldPosAndRot();
        GameplayLog.event(body, "body", "relocate cause={} from={} to={}", cause, GameplayLog.at(from),
                GameplayLog.at(body.position()));
    }

    /** Rides the host; records {@code [body] mount} only after vanilla accepted it, then settles the moved body. */
    public static boolean mount(YukkuriEntity body, Entity host, String cause) {
        if (body == null || host == null || host == body) return false;
        if (host instanceof SurfaceHost surface && !surface.mountable(body)) return false;
        // Moving from one vehicle to another (a grab off the placed hat) leaves the old one where the body stands.
        Entity oldRoot = body.getRootVehicle();
        boolean previous = DISMOUNTING.get();
        DISMOUNTING.set(true);
        boolean mounted;
        try { mounted = body.startRiding(host, true); } finally { DISMOUNTING.set(previous); }
        if (!mounted) return false;
        GameplayLog.event(body, "body", "mount host={} cause={}", host.getUUID(), cause);
        // The mount is the transition: the moved body's own attach slot and nudge-out run here, once (settle never mounts).
        Attach.settle(body, cause);
        Entity root = body.getRootVehicle();
        if (root != oldRoot && root instanceof SurfaceHost surface) surface.loadChanged(body, "boarded");
        return true;
    }

    /** Leaves the vehicle where the body actually stands: the position stays {@code at} (or where it is when null), the velocity becomes {@code v}. Never re-seats, never rolls back. */
    public static void dismount(YukkuriEntity body, Vec3 at, Vec3 v, String cause) {
        if (body == null) return;
        Vec3 before = body.position();
        boolean previous = DISMOUNTING.get();
        DISMOUNTING.set(true);
        try { body.stopRiding(); } finally { DISMOUNTING.set(previous); }
        if (at != null) body.setPos(at.x, at.y, at.z);
        body.setDeltaMovement(v == null ? Vec3.ZERO : v);
        body.hasImpulse = true;
        GameplayLog.event(body, "body", "dismount at={} v={} cause={} passengers={}",
                GameplayLog.at(body.position()), GameplayLog.at(body.getDeltaMovement()), cause,
                body.getPassengers().stream().map(Entity::getUUID).toList());
        double moved = body.position().distanceTo(before);
        if (moved > DISMOUNT_MAX) {
            invariant(body, "I4", String.format(Locale.ROOT,
                    "dismount moved %.3f blocks (max %.2f) cause=%s", moved, DISMOUNT_MAX, cause));
        }
        // The dismount is the transition: the moved body's own attach slot and nudge-out run here, once (settle never dismounts).
        Attach.settle(body, cause);
    }

    /** Pushes a body out of a collision by at most {@link #NUDGE_MAX}; anything longer is refused and logged. */
    public static boolean nudge(YukkuriEntity body, Vec3 delta, String cause) {
        if (body == null || delta == null) return false;
        if (delta.lengthSqr() > NUDGE_MAX * NUDGE_MAX) {
            warn(body, "nudge refused length={} max={} cause={}",
                    String.format(Locale.ROOT, "%.3f", delta.length()), NUDGE_MAX, cause);
            return false;
        }
        body.setPos(body.getX() + delta.x, body.getY() + delta.y, body.getZ() + delta.z);
        return true;
    }

    /** Records a violated invariant as a WARN; the state is never "corrected" here. */
    public static void invariant(YukkuriEntity body, String id, String detail) {
        warn(body, "invariant id={} detail={}", id, detail);
    }

    /** A {@code [body]} WARN with the usual party (variant, id, uuid, tick, dimension, position). */
    public static void warn(YukkuriEntity body, String message, Object... values) {
        if (body == null || body.level().isClientSide) return;
        Object[] args = new Object[6 + values.length];
        args[0] = body.getVariant().assetId();
        args[1] = body.getId();
        args[2] = body.getUUID();
        args[3] = body.level().getGameTime();
        args[4] = body.level().dimension().location();
        args[5] = body.blockPosition().toShortString();
        System.arraycopy(values, 0, args, 6, values.length);
        MCYukkuri.LOGGER.warn("[body] {} #{} uuid={} t={} dim={} pos={} " + message, args);
    }
}
