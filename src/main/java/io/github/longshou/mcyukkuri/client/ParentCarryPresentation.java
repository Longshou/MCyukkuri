package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.body.MouthPlacement;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.family.HatCarry;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Parent carry as drawn (ParentCarry owns who rides and where the entity is): the carried child hangs from the carrier's
 * live lips, sits on the live brim or on the curled tail of the carrier's last rendered pose, sways a little behind the
 * carrier's turns and steps, and eases on and off; the carrier walks steadier under a load, bows to take up or set down a
 * child (the server's carry_lift countdown, a new rider, a rider gone) and curls Chen's tails while they hold one. Only
 * synchronized riders, behavior and animation clocks are read. Timings and angles are project design.
 */
final class ParentCarryPresentation {
    /** Child eases from the ground to its seat / from its seat to where it was set down (ticks). */
    private static final float LIFT_TICKS = 8, SET_DOWN_TICKS = 8;
    /** Carrier bows in before a lift, straightens after it, dips once after setting a child down; tails curl (ticks). */
    private static final float BOW_IN_TICKS = 6, BOW_OUT_TICKS = 8, DIP_TICKS = 10, CURL_TICKS = 8;
    /** A hanging child tips nose-down about its grip; tilt budget for every child (degrees, pitch plus roll). */
    private static final float HANG = 5, TILT_BUDGET = 12;
    /** A captured carrier pose older than this is not used (ticks); the static seat is drawn instead. */
    private static final float STALE = 3;

    private static final class Carrier {
        float time;
        final Set<UUID> riders = new HashSet<>();
        float boardAt = -1000, dropAt = -1000;
        final float[] curl = new float[2];
        float capturedAt = -1000, yaw;
        Matrix4f mouth, hat;
        final Matrix4f[] cradle = new Matrix4f[2];
    }
    private static final class Child {
        float time, freeAt = -1000, boardAt = -1000, releaseAt = -1000;
        boolean carried;
        Vec3 free, drawn, liftFrom, releaseFrom;
        float roll, rollSpeed, gait;
    }
    private static final Map<Integer, Carrier> CARRIERS = new HashMap<>();
    private static final Map<Integer, Child> CHILDREN = new HashMap<>();

    private static float now(YukkuriView view, float partial) { return view.level().getGameTime() + partial; }
    private static float ease(float q) { q = Math.clamp(q, 0, 1); return q * q * (3 - 2 * q); }
    private static double size(YukkuriView view) { return view.growthScale() * view.getScale(); }
    private static void prune(Map<Integer, ?> map, float now) {
        if (map.size() < 128) return;
        map.values().removeIf(state -> now - (state instanceof Carrier c ? c.time : ((Child) state).time) > 200);
    }

    /** Before the carrier's model is posed: carry gait, bow and tail curl. */
    static void configure(YukkuriView carrier, YukkuriModel model, float partial) {
        float now = now(carrier, partial);
        boolean lifting = carrier.getBehaviorId().equals(Behaviors.CARRY_LIFT);
        var state = CARRIERS.get(carrier.getId());
        if (state == null && !carrier.isVehicle() && !lifting) return;
        if (state == null) { prune(CARRIERS, now); CARRIERS.put(carrier.getId(), state = new Carrier()); }
        float dt = Math.clamp(now - state.time, 0, 4);
        state.time = now;
        var riders = new HashSet<UUID>();
        boolean[] tails = new boolean[2];
        for (var rider : carrier.passengerViews()) {
            riders.add(rider.getUUID());
            var seat = ParentCarry.resolve(carrier, rider);
            if (seat.mode() == ParentCarry.Mode.TAIL && seat.lane() < 2) tails[seat.lane()] = true;
        }
        if (!state.riders.containsAll(riders)) state.boardAt = now;
        if (!riders.containsAll(state.riders)) state.dropAt = now;
        state.riders.clear();
        state.riders.addAll(riders);
        for (int side = 0; side < 2; side++)
            state.curl[side] = Mth.approach(state.curl[side], tails[side] ? 1 : 0, dt / CURL_TICKS);
        float bow = lifting ? ease((now - carrier.getBehaviorStartTick()) / BOW_IN_TICKS) : 0;
        if (now - state.boardAt < BOW_OUT_TICKS) bow = Math.max(bow, 1 - ease((now - state.boardAt) / BOW_OUT_TICKS));
        if (now - state.dropAt < DIP_TICKS) bow = Math.max(bow, Mth.sin((now - state.dropAt) / DIP_TICKS * Mth.PI));
        model.setCarryPose(riders.isEmpty() ? 0 : 1, bow, state.curl[0], state.curl[1]);
        if (riders.isEmpty() && !lifting && state.curl[0] == 0 && state.curl[1] == 0 && now - state.dropAt > DIP_TICKS && now - state.boardAt > BOW_OUT_TICKS)
            CARRIERS.remove(carrier.getId());
    }

    /** Right after the carrier's model was drawn: keep its live lips, brim and tail seats relative to the carrier. */
    static void capture(YukkuriView carrier, YukkuriModel model, Matrix4f start, Matrix4f drawn, float yaw, float partial) {
        var state = CARRIERS.get(carrier.getId());
        if (state == null || !carrier.isVehicle()) return;
        // Model render space (block units) to block offsets from the carrier's feet: the camera part of the stack cancels.
        var space = new Matrix4f(start).invert().mul(drawn);
        state.mouth = ParentCarry.mouthCarrying(carrier) ? new Matrix4f(space).mul(model.mouthAttachmentPose()) : null;
        var hat = model.hatFollowPose();
        state.hat = hat == null ? null : new Matrix4f(space).scale(1 / 16f).mul(hat);
        for (int side = 0; side < 2; side++) {
            var cradle = model.carryCradle(side);
            state.cradle[side] = cradle == null ? null : new Matrix4f(space).scale(1 / 16f).mul(cradle);
        }
        state.yaw = yaw;
        state.capturedAt = now(carrier, partial);
    }

    /**
     * Pose a carried child on its carrier, from the child's own render origin. Returns the body yaw it is drawn with.
     * Falls back to the plain seat when the carrier has not been drawn lately.
     */
    static float place(YukkuriView child, YukkuriView carrier, ParentCarry.Mode mode, PoseStack pose, float partial) {
        float now = now(child, partial);
        var me = CHILDREN.get(child.getId());
        if (me == null) { prune(CHILDREN, now); CHILDREN.put(child.getId(), me = new Child()); }
        if (!me.carried) {
            me.carried = true;
            me.boardAt = now;
            me.liftFrom = me.free != null && now - me.freeAt < 4 ? me.free : null;
        }
        var state = CARRIERS.get(carrier.getId());
        boolean live = state != null && now - state.capturedAt <= STALE;
        float yaw = live ? state.yaw : Mth.rotLerp(partial, carrier.previousBodyYaw(), carrier.bodyYaw());
        var origin = carrier.getPosition(partial);

        // Sway: a damped swing behind the carrier's turning, plus a lagging copy of its step while it walks.
        float dt = Math.clamp(now - me.time, 0, 3);
        me.time = now;
        float turn = Mth.wrapDegrees(carrier.bodyYaw() - carrier.previousBodyYaw());
        float target = Math.clamp(turn * .6f, -3, 3);
        me.rollSpeed += (.15f * (target - me.roll) - .55f * me.rollSpeed) * dt;
        me.roll = Math.clamp(me.roll + me.rollSpeed * dt, -4, 4);
        me.gait = Mth.approach(me.gait, carrier.getAnimationId().equals("waddle") ? 1 : 0, dt / 6);
        float phase = (carrier.level().getGameTime() - carrier.getAnimationStartTick() + partial) / 20f / 1.2f * Mth.TWO_PI;

        Vector3f feet;
        var turnTo = new Quaternionf();
        float pitch, roll;
        switch (mode) {
            case HAT -> {
                pitch = me.gait * Mth.sin(phase * 2 - .7f);
                roll = me.roll * .75f + me.gait * Mth.sin(phase - .7f);
                var seatPoint = brimSeat(carrier, child);
                // The brim seat sits against the crown: the child may lean out over the brim, hardly in toward the crown.
                if (seatPoint != null) roll = seatPoint.x >= 0 ? Math.min(roll, .5f) : Math.max(roll, -.5f);
                if (live && state.hat != null && seatPoint != null) {
                    feet = state.hat.transformPosition(seatPoint);
                    state.hat.getNormalizedRotation(turnTo);
                } else {
                    var at = ParentCarry.rawSeat(carrier, child, partial).subtract(origin);
                    feet = new Vector3f((float) at.x, (float) at.y, (float) at.z);
                    HatCarry.riderRotation(carrier, partial).getNormalizedRotation(turnTo);
                }
            }
            case TAIL -> {
                pitch = 1.2f * me.gait * Mth.sin(phase * 2 - .6f);
                roll = me.roll + 2 * me.gait * Mth.sin(phase - .6f);
                int lane = Math.min(1, ParentCarry.resolve(carrier, child).lane());
                var cradle = live ? state.cradle[lane] : null;
                if (cradle != null) {
                    feet = cradle.getTranslation(new Vector3f());
                    cradle.getNormalizedRotation(turnTo);
                } else {
                    var at = ParentCarry.rawSeat(carrier, child, partial).subtract(origin);
                    feet = new Vector3f((float) at.x, (float) at.y, (float) at.z);
                    turnTo.rotationY((180 - yaw) * Mth.DEG_TO_RAD);
                }
            }
            default -> {
                var lane = ParentCarry.mouthLane(carrier, child);
                pitch = HANG + 1.5f * me.gait * Mth.sin(phase * 2 - .9f);
                roll = me.roll + 1.5f * me.gait * Mth.sin(phase - .9f) + lane.roll();
                roll = Math.clamp(roll, -(TILT_BUDGET - Math.abs(pitch)), TILT_BUDGET - Math.abs(pitch));
                float unit = (float) size(carrier) / 16, childUnit = (float) size(child) / 16;
                double rho = size(child) / Math.max(1e-6, size(carrier));
                var facing = new Quaternionf().rotationY((180 - yaw) * Mth.DEG_TO_RAD);
                Vector3f lip;
                if (live && state.mouth != null) lip = state.mouth.getTranslation(new Vector3f());
                else lip = MouthPlacement.anchor(carrier).add(0, 0, .35f).mul(unit).rotate(facing);
                // The bite: on the lip line at the lane's grip, a little in front of the lips.
                var bite = new Vector3f((float) ((lane.side() + lane.gripX() * rho) * unit), 0, (float) (-ParentCarry.biteForward(lane) * unit)).rotate(facing).add(lip);
                turnTo.set(facing).rotateY(-lane.fan() * Mth.DEG_TO_RAD).rotateX(-pitch * Mth.DEG_TO_RAD).rotateZ(roll * Mth.DEG_TO_RAD);
                var grip = new Vector3f((float) lane.gripX(), (float) lane.gripY(), (float) lane.gripZ()).mul(childUnit).rotate(turnTo);
                feet = bite.sub(grip);
                feet.y = Math.max(feet.y, (float) ParentCarry.MOUTH_FLOOR * unit);
                pitch = roll = 0;
            }
        }
        if (mode != ParentCarry.Mode.MOUTH) {
            roll = Math.clamp(roll, -(TILT_BUDGET - Math.abs(pitch)), TILT_BUDGET - Math.abs(pitch));
            turnTo.rotateX(pitch * Mth.DEG_TO_RAD).rotateZ(roll * Mth.DEG_TO_RAD);
        }
        // Taken up: from where the child stood. The mouth draws it in along the ground and then lifts it to the lips; a brim
        // child hops out round the brim edge and down onto its seat; a tail child is scooped up in a small arc.
        float q = (now - me.boardAt) / LIFT_TICKS;
        if (q < 1 && me.liftFrom != null) {
            var from = me.liftFrom.subtract(origin);
            var start = new Vector3f((float) from.x, (float) from.y, (float) from.z);
            switch (mode) {
                case MOUTH -> {
                    float across = ease(q / .7f), up = ease((q - .45f) / .55f);
                    feet.set(Mth.lerp(across, start.x, feet.x), Mth.lerp(up, start.y, feet.y), Mth.lerp(across, start.z, feet.z));
                }
                case HAT -> {
                    // Round the carrier outside the brim edge while rising to above the seat, then slide in and sit.
                    float seatR = (float) Math.hypot(feet.x, feet.z), fromR = (float) Math.hypot(start.x, start.z);
                    float wide = seatR + 2.6f * (float) size(carrier) / 16 + child.getBbWidth() * .6f;
                    float seatA = (float) Math.atan2(feet.z, feet.x), fromA = (float) Math.atan2(start.z, start.x);
                    float above = feet.y + child.getBbHeight() * .5f, ring = Math.max(wide, Math.min(fromR, wide * 1.5f)), r, angle, y;
                    if (q < .6f) {
                        float u = ease(q / .6f);
                        r = Mth.lerp(u, fromR, ring);
                        angle = fromA + Mth.wrapDegrees((seatA - fromA) * Mth.RAD_TO_DEG) * Mth.DEG_TO_RAD * u;
                        y = Mth.lerp(ease(q / .45f), start.y, above);
                    } else {
                        float u = ease((q - .6f) / .4f);
                        r = Mth.lerp(u, ring, seatR);
                        angle = seatA;
                        y = Mth.lerp(u, above, feet.y);
                    }
                    feet.set(r * Mth.cos(angle), y, r * Mth.sin(angle));
                }
                default -> {
                    // Scooped onto the tail early, then carried up as the tail curls.
                    float p = Math.min(1, q / .45f), e = ease(p);
                    feet.set(Mth.lerp(e, start.x, feet.x), Mth.lerp(e, start.y, feet.y) + 4 * p * (1 - p) * child.getBbHeight(),
                            Mth.lerp(e, start.z, feet.z));
                }
            }
        }
        me.drawn = origin.add(feet.x, feet.y, feet.z);
        var self = child.getPosition(partial);
        pose.translate(origin.x - self.x + feet.x, origin.y - self.y + feet.y, origin.z - self.z + feet.z);
        pose.mulPose(turnTo);
        return yaw;
    }
    /** The brim seat of this child in hat-bone model units, or null when it has no brim place. */
    private static Vector3f brimSeat(YukkuriView carrier, YukkuriView child) {
        var shape = HatCarry.shape(carrier.assetId());
        if (shape == null) return null;
        var seat = shape.getAsJsonArray("seat");
        double dz = ParentCarry.resolve(carrier, child).dz();
        return new Vector3f(seat.get(0).getAsFloat(), seat.get(1).getAsFloat(), (float) (seat.get(2).getAsFloat() + dz));
    }

    /**
     * A child on its own feet: remember where it stands (for the next lift) and, just after it was set down, ease it from
     * its last seat to where the server put it.
     */
    static void settle(YukkuriView child, PoseStack pose, float partial) {
        if (child.adult()) return;
        float now = now(child, partial);
        var me = CHILDREN.get(child.getId());
        if (me == null) { prune(CHILDREN, now); CHILDREN.put(child.getId(), me = new Child()); }
        me.time = now;
        var self = child.getPosition(partial);
        if (me.carried) {
            me.carried = false;
            me.releaseAt = now;
            me.releaseFrom = me.drawn;
            me.roll = me.rollSpeed = me.gait = 0;
        }
        me.free = self;
        me.freeAt = now;
        float q = (now - me.releaseAt) / SET_DOWN_TICKS;
        if (q < 1 && me.releaseFrom != null) {
            var back = me.releaseFrom.subtract(self).scale(1 - ease(q));
            pose.translate(back.x, back.y, back.z);
        }
    }

    private ParentCarryPresentation() {}
}
