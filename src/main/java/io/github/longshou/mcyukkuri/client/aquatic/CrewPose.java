package io.github.longshou.mcyukkuri.client.aquatic;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.subspecies.HatBoatView;
import io.github.longshou.mcyukkuri.subspecies.crew.CrewState;
import io.github.longshou.mcyukkuri.subspecies.crew.CrewTransfer;
import io.github.longshou.mcyukkuri.subspecies.crew.DeckLayout;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Where a body riding a hat boat is drawn this frame: its support point in the boat's one {@link BoatPose} frame (a settled
 * rider on the synchronized deck seat, a walking or lifted body where the server moved it), its body yaw relative to the hull
 * and a small balance against the hull's tilt (at most {@link #BALANCE} degrees of roll and pitch together). A body in a
 * transfer's contact keeps no balance, so the braid or mouth meets its target, and turns by the transfer's synced yaw.
 */
public final class CrewPose {
    /** Rider compensation against the hull tilt, roll plus pitch (standards: total body tilt about 12 degrees). */
    public static final float BALANCE = 12;

    /** Null for a lifted body whose synchronized sample is in world space. */
    public final BoatPose boat;
    /** World position of the body's support point (its feet) this frame. */
    public final Vec3 at;
    /** World body yaw drawn this frame (degrees). */
    public final float bodyYaw;
    /** Compensation in the hull's yaw-aligned axes (degrees). */
    public final float balanceRoll, balancePitch;
    /** The settled seat kind, or null while moving or not seated. */
    public final DeckLayout.Kind seat;

    private CrewPose(BoatPose boat, Vec3 at, float bodyYaw, float balanceRoll, float balancePitch, DeckLayout.Kind seat) {
        this.boat = boat; this.at = at; this.bodyYaw = bodyYaw; this.balanceRoll = balanceRoll; this.balancePitch = balancePitch; this.seat = seat;
    }

    /** A lifted body's sampled pose (also after dismount), otherwise the pose in the hull it rides. */
    public static CrewPose of(YukkuriView body, float partial) {
        var entry = transfer(body);
        if (entry != null && entry.mode() == CrewTransfer.Mode.LIFT && body.getUUID().equals(entry.body()) && entry.bodyAt() != null) {
            var frame = ContactTargets.frame(body, entry, partial);
            if (entry.contactFrame() != 0 && frame == null) return null;
            var at = ContactTargets.point(entry.bodyPrev(), entry.bodyAt(), frame, partial);
            float yaw = Mth.rotLerp(partial, entry.prevBodyYaw(), entry.bodyYaw());
            return new CrewPose(frame, at, yaw, 0, 0, null);
        }
        HatBoatView hull = body.vehicleHat();
        if (hull == null) return null;
        var frame = BoatPose.of(hull, partial);
        var id = body.getUUID();
        boolean held = entry != null && entry.phase().ordinal() >= CrewTransfer.Phase.CONTACT.ordinal()
                && entry.phase().ordinal() <= CrewTransfer.Phase.SETTLE.ordinal();
        boolean moving = hull.visibleSeatMoving(id) || entry != null && entry.moving() && id.equals(entry.body());
        var seat = moving ? null : hull.visibleSeatKind(id);
        Vec3 local = seat == null ? null : hull.visibleSeat(id);
        if (local == null) local = frame.toLocalFlat(body.getPosition(partial));
        // The hull turns smoothly; the body keeps its own turn relative to the hull (seat yaw, facing a child).
        float relative = Mth.rotLerp(partial, body.previousBodyYaw() - hull.previousYaw(), body.bodyYaw() - hull.getYRot());
        float yaw = frame.yaw + relative;
        if (entry != null && id.equals(entry.body()) && entry.phase().ordinal() >= CrewTransfer.Phase.CONTACT.ordinal())
            yaw = Mth.rotLerp(partial, entry.prevBodyYaw(), entry.bodyYaw());
        float r = 0, p = 0;
        if (!held) {
            // The tilt as roll and pitch in the hull frame (the dip about its own horizontal axis, small angles).
            float tiltRoll = frame.roll + frame.dip * Mth.sin(frame.dipYaw * Mth.DEG_TO_RAD);
            float tiltPitch = frame.pitch + frame.dip * Mth.cos(frame.dipYaw * Mth.DEG_TO_RAD);
            float share = seat == null ? .5f : id.equals(hull.ownerYukkuri()) ? .7f : switch (seat) {
                case BRIM, TOILET_RIM, RESCUE_RIM -> 1f;
                default -> .8f;
            };
            r = -tiltRoll * share;
            p = -tiltPitch * share;
            var crowd = hull.visibleCrowding(id);
            if (crowd != null && Math.clamp(crowd.getInt("Mode"), 0, 4) >= 2) {
                // Pressed by a neighbour: lean away from the push (the jostle clip itself is the server's).
                r += (float) Math.copySign(Math.min(6, Math.clamp(crowd.getInt("Contacts"), 0, 200) * .7), crowd.getDouble("PushX") == 0 ? 1 : crowd.getDouble("PushX"));
                p -= 3;
            }
            float sum = Math.abs(r) + Math.abs(p);
            if (sum > BALANCE) { r *= BALANCE / sum; p *= BALANCE / sum; }
        }
        return new CrewPose(frame, frame.toWorld(local), yaw, r, p, seat);
    }

    /** The transfer entry this body takes part in (as the body or the carrier), or null. */
    public static CrewState.Entry transfer(YukkuriView body) {
        int id = body.crewBoat();
        if (id < 0 || !(body.level().getEntity(id) instanceof HatBoatView hull)) return null;
        return CrewState.entryFor(hull.crewEntries(), body.getUUID());
    }

    /**
     * Replace the body's own placement: the pose stands at {@code drawnAt} (the entity's interpolated position); afterwards
     * it is at the support point, turned to {@code bodyYaw} (normally {@link #bodyYaw}) inside the tilted, balanced hull frame.
     */
    public void apply(PoseStack pose, Vec3 drawnAt, float bodyYaw) {
        pose.translate(at.x - drawnAt.x, at.y - drawnAt.y, at.z - drawnAt.z);
        if (boat == null) {
            pose.mulPose(new Quaternionf().rotationY((180 - bodyYaw) * Mth.DEG_TO_RAD));
            return;
        }
        pose.mulPose(new Quaternionf().rotationY((180 - boat.yaw) * Mth.DEG_TO_RAD));
        pose.mulPose(boat.tilt());
        pose.mulPose(new Quaternionf().rotationZ(balanceRoll * Mth.DEG_TO_RAD).rotateX(balancePitch * Mth.DEG_TO_RAD));
        pose.mulPose(new Quaternionf().rotationY((boat.yaw - bodyYaw) * Mth.DEG_TO_RAD));
    }
}
