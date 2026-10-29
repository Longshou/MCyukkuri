package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.body.MouthPlacement;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.motor.BodyDriver;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.ContactGeometry.Reach;
import io.github.longshou.mcyukkuri.motor.action.Grab;
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
 * Parent carry as drawn (ParentCarry owns who rides and where the entity is): a mouth-held child hangs from the carrier's
 * live lips, a braid-held one at its seat with the Marisa's braid bent down to its scruff; both sway a little behind the
 * carrier's turns and steps and ease on and off. The carrier walks steadier under a load, bows to take up a child with the
 * mouth (its synced Grab), reaches with the braid clip while its Grab holds the braid out, and dips after a rider is gone.
 * Only synchronized riders, the motor action, the braid slot and animation clocks are read. Timings and angles are project design.
 */
final class ParentCarryPresentation {
    /** Child eases from the ground to its seat / from its seat to where it was set down (ticks). */
    private static final float LIFT_TICKS = 8, SET_DOWN_TICKS = 8;
    /** Carrier bows in before a lift, straightens after it, dips once after setting a child down (ticks). */
    private static final float BOW_IN_TICKS = 6, BOW_OUT_TICKS = 8, DIP_TICKS = 10;
    /** A hanging child tips nose-down about its grip; tilt budget for every child (degrees, pitch plus roll). */
    private static final float HANG = 5, TILT_BUDGET = 12;
    /** A captured carrier pose older than this is not used (ticks); the static seat is drawn instead. */
    private static final float STALE = 3;

    private static final class Carrier {
        float time;
        final Set<UUID> riders = new HashSet<>();
        float boardAt = -1000, dropAt = -1000;
        float capturedAt = -1000, yaw;
        Matrix4f mouth;
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
    private static float yaw(YukkuriView view, float partial) { return Mth.rotLerp(partial, view.previousBodyYaw(), view.bodyYaw()); }
    private static void prune(Map<Integer, ?> map, float now) {
        if (map.size() < 128) return;
        map.values().removeIf(state -> now - (state instanceof Carrier c ? c.time : ((Child) state).time) > 200);
    }

    /** The body a carrier's braid reaches for right now (its Grab took the braid and nobody hangs there yet), or null. */
    private static YukkuriView braidTarget(YukkuriView carrier) {
        if (!carrier.motorAction().equals(Grab.ID) || ParentCarry.braidRider(carrier) != null) return null;
        int id = BodyDriver.braidBody(carrier.braidHold());
        return id < 0 ? null : carrier.yukkuriView(id);
    }
    /** The clip a carrier plays while its braid reaches (the lift, or the low lean), or null. */
    static String liftClip(YukkuriView carrier) {
        return braidTarget(carrier) == null ? null : BodyDriver.braidLean(carrier.braidHold()) ? Grab.BRAID_LEAN : Grab.BRAID_LIFT;
    }

    /** A child's scruff (where the mouth or the braid takes it) above its feet, the child turned to {@code yaw}. */
    private static Vector3f scruff(YukkuriView child, Vec3 feet, float yaw) {
        return new Vector3f(0, (float) ParentCarry.GRIP_Y, (float) ParentCarry.GRIP_Z).mul((float) size(child) / 16)
                .rotateY((180 - yaw) * Mth.DEG_TO_RAD).add((float) feet.x, (float) feet.y, (float) feet.z);
    }
    /** A world point in the carrier's model space (model units), the inverse of the renderer's turn and scale. */
    private static Vector3f modelPoint(YukkuriView carrier, Vector3f world, float partial) {
        var origin = carrier.getPosition(partial);
        return world.sub((float) origin.x, (float) origin.y, (float) origin.z).rotateY(-(180 - yaw(carrier, partial)) * Mth.DEG_TO_RAD)
                .mul(16 / (float) size(carrier));
    }

    /** Before the carrier's model is posed: carry gait, bow, and the braid bent to the body it reaches for or holds. */
    static void configure(YukkuriView carrier, YukkuriModel model, float partial) {
        float now = now(carrier, partial);
        var reaching = braidTarget(carrier);
        var held = carrier.isVehicle() ? ParentCarry.braidRider(carrier) : null;
        if (reaching != null) {
            // The braid closes on the child's scruff over the clip's reach, then keeps it while the lift runs.
            var clip = ClipMarkers.of(liftClip(carrier));
            model.setBraidReach(modelPoint(carrier, scruff(reaching, reaching.getPosition(partial), yaw(reaching, partial)), partial),
                    ease((now - carrier.motorStart()) / Math.max(1, clip.ticks("contact_closed", clip.length()))));
        } else if (held != null) {
            model.setBraidReach(modelPoint(carrier, scruff(held, ParentCarry.rawSeat(carrier, held, partial), yaw(carrier, partial)), partial), 1);
        }
        boolean lifting = reaching == null && carrier.motorAction().equals(Grab.ID);
        var state = CARRIERS.get(carrier.getId());
        if (state == null && !carrier.isVehicle() && !lifting) return;
        if (state == null) { prune(CARRIERS, now); CARRIERS.put(carrier.getId(), state = new Carrier()); }
        state.time = now;
        var riders = new HashSet<UUID>();
        for (var rider : carrier.passengerViews()) riders.add(rider.getUUID());
        if (!state.riders.containsAll(riders)) state.boardAt = now;
        if (!riders.containsAll(state.riders)) state.dropAt = now;
        state.riders.clear();
        state.riders.addAll(riders);
        float bow = lifting ? ease((now - carrier.motorStart()) / BOW_IN_TICKS) : 0;
        if (now - state.boardAt < BOW_OUT_TICKS) bow = Math.max(bow, 1 - ease((now - state.boardAt) / BOW_OUT_TICKS));
        if (now - state.dropAt < DIP_TICKS) bow = Math.max(bow, Mth.sin((now - state.dropAt) / DIP_TICKS * Mth.PI));
        model.setCarryPose(riders.isEmpty() ? 0 : 1, bow);
        if (riders.isEmpty() && !lifting && now - state.dropAt > DIP_TICKS && now - state.boardAt > BOW_OUT_TICKS)
            CARRIERS.remove(carrier.getId());
    }

    /** Right after the carrier's model was drawn: keep its live lips relative to the carrier. */
    static void capture(YukkuriView carrier, YukkuriModel model, Matrix4f start, Matrix4f drawn, float yaw, float partial) {
        var state = CARRIERS.get(carrier.getId());
        if (state == null || !carrier.isVehicle()) return;
        // Model render space (block units) to block offsets from the carrier's feet: the camera part of the stack cancels.
        state.mouth = ParentCarry.mouthCarrying(carrier) ? new Matrix4f(start).invert().mul(drawn).mul(model.mouthAttachmentPose()) : null;
        state.yaw = yaw;
        state.capturedAt = now(carrier, partial);
    }

    /**
     * Pose a carried child on its carrier, from the child's own render origin. Returns the body yaw it is drawn with.
     * Falls back to the plain seat when the carrier has not been drawn lately.
     */
    static float place(YukkuriView child, YukkuriView carrier, PoseStack pose, float partial) {
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
        float yaw = live ? state.yaw : yaw(carrier, partial);
        var origin = carrier.getPosition(partial);
        boolean mouth = ParentCarry.reach(carrier, child) == Reach.MOUTH;

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
        var facing = new Quaternionf().rotationY((180 - yaw) * Mth.DEG_TO_RAD);
        var turnTo = new Quaternionf(facing);
        if (mouth) {
            var lane = ParentCarry.mouthLane(carrier, child);
            float pitch = HANG + 1.5f * me.gait * Mth.sin(phase * 2 - .9f);
            float roll = me.roll + 1.5f * me.gait * Mth.sin(phase - .9f) + lane.roll();
            roll = Math.clamp(roll, -(TILT_BUDGET - Math.abs(pitch)), TILT_BUDGET - Math.abs(pitch));
            float unit = (float) size(carrier) / 16, childUnit = (float) size(child) / 16;
            double rho = size(child) / Math.max(1e-6, size(carrier));
            Vector3f lip;
            if (live && state.mouth != null) lip = state.mouth.getTranslation(new Vector3f());
            else lip = MouthPlacement.anchor(carrier).add(0, 0, .35f).mul(unit).rotate(facing);
            // The bite: on the lip line at the lane's grip, a little in front of the lips.
            var bite = new Vector3f((float) ((lane.side() + lane.gripX() * rho) * unit), 0, (float) (-ParentCarry.biteForward(lane) * unit)).rotate(facing).add(lip);
            turnTo.rotateY(-lane.fan() * Mth.DEG_TO_RAD).rotateX(-pitch * Mth.DEG_TO_RAD).rotateZ(roll * Mth.DEG_TO_RAD);
            var grip = new Vector3f((float) lane.gripX(), (float) lane.gripY(), (float) lane.gripZ()).mul(childUnit).rotate(turnTo);
            feet = bite.sub(grip);
            feet.y = Math.max(feet.y, (float) ParentCarry.MOUTH_FLOOR * unit);
        } else {
            // At the braid tip beside the carrier: the braid is bent to this seat's scruff (configure), the child swings under it.
            var at = ParentCarry.rawSeat(carrier, child, partial).subtract(origin);
            feet = new Vector3f((float) at.x, (float) at.y, (float) at.z);
            float pitch = 1.2f * me.gait * Mth.sin(phase * 2 - .6f);
            float roll = Math.clamp(me.roll + 2 * me.gait * Mth.sin(phase - .6f), -(TILT_BUDGET - Math.abs(pitch)), TILT_BUDGET - Math.abs(pitch));
            turnTo.rotateX(pitch * Mth.DEG_TO_RAD).rotateZ(roll * Mth.DEG_TO_RAD);
        }
        // Taken up: from where the child stood. The mouth draws it in along the ground and then lifts it to the lips; the
        // braid scoops it up in a small arc.
        float q = (now - me.boardAt) / LIFT_TICKS;
        if (q < 1 && me.liftFrom != null) {
            var from = me.liftFrom.subtract(origin);
            var start = new Vector3f((float) from.x, (float) from.y, (float) from.z);
            if (mouth) {
                float across = ease(q / .7f), up = ease((q - .45f) / .55f);
                feet.set(Mth.lerp(across, start.x, feet.x), Mth.lerp(up, start.y, feet.y), Mth.lerp(across, start.z, feet.z));
            } else {
                float p = Math.min(1, q / .45f), e = ease(p);
                feet.set(Mth.lerp(e, start.x, feet.x), Mth.lerp(e, start.y, feet.y) + 4 * p * (1 - p) * child.getBbHeight(),
                        Mth.lerp(e, start.z, feet.z));
            }
        }
        me.drawn = origin.add(feet.x, feet.y, feet.z);
        var self = child.getPosition(partial);
        pose.translate(origin.x - self.x + feet.x, origin.y - self.y + feet.y, origin.z - self.z + feet.z);
        pose.mulPose(turnTo);
        return yaw;
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
