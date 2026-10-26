package io.github.longshou.mcyukkuri.client.aquatic;

import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.subspecies.HatBoatView;
import io.github.longshou.mcyukkuri.subspecies.crew.CrewState;
import io.github.longshou.mcyukkuri.subspecies.crew.CrewTransfer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The one conversion of a transfer's contact into the carrier's model space. The server publishes the contact kind and its
 * target in the frame it belongs to (a hull's, else the world), this tick and the previous one; the target is interpolated in
 * that frame and placed through the same {@link BoatPose} as the lifted feet, then taken through the inverse of the carrier's
 * own model matrix. Both samples come from the ticket, even after dismount; ordinary entity lerp never offsets the grip.
 * When the ticket ends the entry and the contact are gone: nothing here keeps a braid stretched out.
 */
public final class ContactTargets {
    /** A contact this frame: its kind and the target in the carrier's model units. */
    public record Target(CrewTransfer.Contact kind, Vector3f model) {}

    /** The contact the carrier holds now, in its model space, or null. {@code start}: the pose at the entity's render origin. */
    public static Target forCarrier(YukkuriView carrier, float partial, Matrix4f start, Matrix4f model) {
        var entry = CrewPose.transfer(carrier);
        if (entry == null || entry.contact() == CrewTransfer.Contact.NONE || entry.contactAt() == null) return null;
        // A lift's contact is the carrier's braid or mouth on the body; a climb's is the climber's own bite and hook on the rim.
        var holder = entry.mode() == CrewTransfer.Mode.LIFT ? entry.carrier() : entry.body();
        if (!carrier.getUUID().equals(holder)) return null;
        var world = world(carrier, entry, partial);
        return world == null ? null : new Target(entry.contact(), toModel(world, carrier.getPosition(partial), start, model));
    }

    /** World position of the entry's contact this frame. */
    static Vec3 world(YukkuriView viewer, CrewState.Entry entry, float partial) {
        var frame = frame(viewer, entry, partial);
        if (entry.contactFrame() != 0 && frame == null) return null;
        return point(entry.contactPrev(), entry.contactAt(), frame, partial);
    }

    /** The contact and the lifted body's feet belong to this same synchronized frame, independent of mount packets. */
    static BoatPose frame(YukkuriView viewer, CrewState.Entry entry, float partial) {
        if (entry.contactFrame() == 0) return null;
        int id = entry.contactFrame() == 1 ? entry.boatA() : entry.boatB();
        return viewer.level().getEntity(id) instanceof HatBoatView hull ? BoatPose.of(hull, partial) : null;
    }

    static Vec3 point(Vec3 prev, Vec3 at, BoatPose frame, float partial) {
        if (prev == null) prev = at;
        var local = new Vec3(Mth.lerp(partial, prev.x, at.x), Mth.lerp(partial, prev.y, at.y), Mth.lerp(partial, prev.z, at.z));
        return frame == null ? local : frame.toWorld(local);
    }

    /**
     * A world point in the model units of a body drawn with {@code model}, where {@code start} is the pose at the body's render
     * origin {@code origin} (both matrices share the camera, which cancels).
     */
    public static Vector3f toModel(Vec3 world, Vec3 origin, Matrix4f start, Matrix4f model) {
        var relative = new Vector3f((float) (world.x - origin.x), (float) (world.y - origin.y), (float) (world.z - origin.z));
        start.transformPosition(relative);
        new Matrix4f(model).invert().transformPosition(relative);
        return relative.mul(16);
    }

    private ContactTargets() {}
}
