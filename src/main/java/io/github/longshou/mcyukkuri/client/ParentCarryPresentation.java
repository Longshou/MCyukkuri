package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.body.MouthPlacement;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.family.ParentCarry.Hold;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.action.Grab;
import io.github.longshou.mcyukkuri.motor.action.PlaceProp;
import io.github.longshou.mcyukkuri.motor.action.TakeProp;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
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
 * Parent carry as drawn (ParentCarry owns who rides, in which hold, and where the entity is): a mouth child hangs from the
 * carrier's live lips, a brim child sits on the live brim, a tail child on the curled tail of the carrier's last rendered
 * pose, a braid-tip child at its seat with the braid bent to its scruff; all sway a little behind the carrier's turns and
 * steps and ease on and off. The carrier walks steadier under a load, bows to lift with the mouth or tail (its synced Grab),
 * plays the braid clip while its Grab reaches with the braid (the braid bent to the target), lowers a brim child with the
 * braid and a tail child with the tail, curls Chen's tails while they hold one, and carries its hat on the braid through
 * hat_off / hat_on. Only synchronized riders, the hold record, the motor action and animation clocks are read. Timings
 * and angles are project design.
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
    private static float yaw(YukkuriView view, float partial) { return Mth.rotLerp(partial, view.previousBodyYaw(), view.bodyYaw()); }
    private static void prune(Map<Integer, ?> map, float now) {
        if (map.size() < 128) return;
        map.values().removeIf(state -> now - (state instanceof Carrier c ? c.time : ((Child) state).time) > 200);
    }

    /** The body a carrier's braid reaches for right now (its Grab lifting into the brim or the braid tip), or null. */
    private static YukkuriView braidTarget(YukkuriView carrier) {
        return carrier.motorAction().equals(Grab.ID) ? ParentCarry.braidTarget(carrier) : null;
    }
    /** The clip a carrier plays while its braid reaches (the lift, or the low lean), or null. */
    static String liftClip(YukkuriView carrier) {
        var target = braidTarget(carrier);
        return target == null ? null : ParentCarry.braidLean(carrier, target) ? Grab.BRAID_LEAN : Grab.BRAID_LIFT;
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

    /** Before the carrier's model is posed: carry gait, bow, tail curl, and the braid bent to the body it reaches for or holds. */
    static void configure(YukkuriView carrier, YukkuriModel model, float partial) {
        float now = now(carrier, partial);
        String action = carrier.motorAction();
        var reaching = braidTarget(carrier);
        if (reaching != null) {
            // The braid closes on the child's scruff over the clip's reach, then keeps it while the lift runs.
            var clip = ClipMarkers.of(liftClip(carrier));
            model.setBraidReach(modelPoint(carrier, scruff(reaching, reaching.getPosition(partial), yaw(reaching, partial)), partial),
                    ease((now - carrier.motorStart()) / Math.max(1, clip.ticks("contact_closed", clip.length()))));
        } else if (carrier.isVehicle()) for (var rider : carrier.passengerViews()) {
            // The braid holds its own child, and a brim child on its way down.
            var hold = ParentCarry.hold(carrier, rider);
            if (hold != Hold.BRAID && (hold != Hold.BRIM || ParentCarry.lowering(carrier, rider, partial) < 0)) continue;
            model.setBraidReach(modelPoint(carrier, scruff(rider, ParentCarry.rawSeat(carrier, rider, partial), yaw(carrier, partial)), partial), 1);
            break;
        }
        if (action.equals(TakeProp.ID) || action.equals(PlaceProp.ID)) {
            // The hat rides the braid tip between the clip's grip and release; at those two ends it lies where the clip
            // puts it (the placed hat, the head), so it is never in two places and never jumps.
            String id = carrier.getAnimationId();
            var clip = ClipMarkers.of(id);
            int grip = clip.ticks(TakeProp.GRIP_MARKER, 0), release = clip.ticks(TakeProp.RELEASE_MARKER, clip.length() - 1);
            float q = (now - carrier.motorStart() - grip) / Math.max(1, release - grip);
            model.setHatInBraid(ease(q * 4) * ease((1 - q) * 4), HatGeometry.gripWorn(id.endsWith("_aq"), id.contains("_self")));
        }
        boolean lifting = reaching == null && action.equals(Grab.ID);
        var state = CARRIERS.get(carrier.getId());
        if (state == null && !carrier.isVehicle() && !lifting) return;
        if (state == null) { prune(CARRIERS, now); CARRIERS.put(carrier.getId(), state = new Carrier()); }
        float dt = Math.clamp(now - state.time, 0, 4);
        state.time = now;
        var riders = new HashSet<UUID>();
        float[] tails = new float[2];
        for (var rider : carrier.passengerViews()) {
            riders.add(rider.getUUID());
            var hold = ParentCarry.hold(carrier, rider);
            // A tail holds its child curled, and uncurls as it lowers it.
            if (hold.tail()) tails[hold == Hold.TAIL_LEFT ? 0 : 1] = 1 - Math.max(0, ParentCarry.lowering(carrier, rider, partial));
        }
        if (!state.riders.containsAll(riders)) state.boardAt = now;
        if (!riders.containsAll(state.riders)) state.dropAt = now;
        state.riders.clear();
        state.riders.addAll(riders);
        for (int side = 0; side < 2; side++) state.curl[side] = Mth.approach(state.curl[side], tails[side], dt / CURL_TICKS);
        float bow = lifting ? ease((now - carrier.motorStart()) / BOW_IN_TICKS) : 0;
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
     * Falls back to the plain seat when the carrier has not been drawn lately, and while the child is being set down.
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
        var hold = ParentCarry.hold(carrier, child);
        boolean down = ParentCarry.lowering(carrier, child, partial) >= 0;
        boolean live = state != null && now - state.capturedAt <= STALE && !down;
        float yaw = live ? state.yaw : yaw(carrier, partial);
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
        var facing = new Quaternionf().rotationY((180 - yaw) * Mth.DEG_TO_RAD);
        var turnTo = new Quaternionf(facing);
        float pitch, roll;
        var at = ParentCarry.rawSeat(carrier, child, partial).subtract(origin);
        var seated = new Vector3f((float) at.x, (float) at.y, (float) at.z);
        switch (hold) {
            case BRIM -> {
                // On the brim the child faces out over the brim edge with the crown at its back and neighbours at its
                // sides: it may tip outward, hardly back toward the crown, and only a little sideways.
                pitch = Math.min(.5f, me.gait * Mth.sin(phase * 2 - .7f));
                roll = Math.clamp(me.roll * .4f + .8f * me.gait * Mth.sin(phase - .7f), -1.2f, 1.2f);
                var seat = ParentCarry.brimSeat(carrier, child);
                float out = seat == null ? 0 : seat[3];
                if (live && state.hat != null && seat != null) {
                    feet = state.hat.transformPosition(new Vector3f(seat[0], seat[1], seat[2]));
                    state.hat.getNormalizedRotation(turnTo).rotateY(out * Mth.DEG_TO_RAD);
                } else {
                    feet = seated;
                    ParentCarry.brimRotation(carrier, partial, out).getNormalizedRotation(turnTo);
                }
            }
            case TAIL_LEFT, TAIL_RIGHT -> {
                pitch = 1.2f * me.gait * Mth.sin(phase * 2 - .6f);
                roll = me.roll + 2 * me.gait * Mth.sin(phase - .6f);
                var cradle = live ? state.cradle[hold == Hold.TAIL_LEFT ? 0 : 1] : null;
                if (cradle != null) {
                    feet = cradle.getTranslation(new Vector3f());
                    cradle.getNormalizedRotation(turnTo);
                } else feet = seated;
            }
            case BRAID -> {
                // At the braid tip beside the carrier: the braid is bent to this seat's scruff (configure), the child swings under it.
                feet = seated;
                pitch = 1.2f * me.gait * Mth.sin(phase * 2 - .6f);
                roll = me.roll + 2 * me.gait * Mth.sin(phase - .6f);
            }
            default -> {
                var lane = ParentCarry.mouthLane(carrier, child);
                pitch = HANG + 1.5f * me.gait * Mth.sin(phase * 2 - .9f);
                roll = me.roll + 1.5f * me.gait * Mth.sin(phase - .9f) + lane.roll();
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
                pitch = roll = 0;
            }
        }
        if (hold != Hold.MOUTH) {
            roll = Math.clamp(roll, -(TILT_BUDGET - Math.abs(pitch)), TILT_BUDGET - Math.abs(pitch));
            turnTo.rotateX(pitch * Mth.DEG_TO_RAD).rotateZ(roll * Mth.DEG_TO_RAD);
        }
        // Held over a host (the hat it came from) the feet stay on or above its surface, as the server seat does.
        double host = Occupancy.hostFloor(carrier.level(), child.getBbWidth(), origin.add(feet.x, feet.y, feet.z)) - origin.y;
        if (host > feet.y) feet.y = (float) host;
        // Taken up: from where the child stood. Off a host it first rises clear of the brim, crosses above it and comes down to
        // the seat; otherwise the mouth draws it in along the ground and then lifts it to the lips, a brim child is swung round
        // the brim edge and down onto its spot, and a tail or braid child is scooped up in a small arc.
        float q = (now - me.boardAt) / LIFT_TICKS;
        if (q < 1 && me.liftFrom != null) {
            var from = me.liftFrom.subtract(origin);
            var start = new Vector3f((float) from.x, (float) from.y, (float) from.z);
            float over = over(child, origin, start, feet);
            if (over > Math.max(start.y, feet.y)) {
                float rise = ease(q / .35f), across = ease((q - .35f) / .4f), drop = ease((q - .75f) / .25f);
                feet.set(Mth.lerp(across, start.x, feet.x), Mth.lerp(drop, Mth.lerp(rise, start.y, over), feet.y), Mth.lerp(across, start.z, feet.z));
            } else if (hold == Hold.MOUTH) {
                float across = ease(q / .7f), up = ease((q - .45f) / .55f);
                feet.set(Mth.lerp(across, start.x, feet.x), Mth.lerp(up, start.y, feet.y), Mth.lerp(across, start.z, feet.z));
            } else if (hold == Hold.BRIM) {
                // Round the carrier outside the brim edge while rising to above the spot, then in and down onto it.
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
    /** The highest host surface (above {@code origin}) the child's footprint passes over on the straight way from {@code start}
     *  to {@code end}, a little clearance added; -infinity when no host lies under that way. */
    private static float over(YukkuriView child, Vec3 origin, Vector3f start, Vector3f end) {
        float top = Float.NEGATIVE_INFINITY;
        for (int i = 0; i <= 6; i++) {
            double y = Occupancy.hostFloor(child.level(), child.getBbWidth(),
                    origin.add(Mth.lerp(i / 6f, start.x, end.x), Math.max(start.y, end.y), Mth.lerp(i / 6f, start.z, end.z))) - origin.y + .02;
            if (y > top) top = (float) y;
        }
        return top;
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
