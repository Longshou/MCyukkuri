package io.github.longshou.mcyukkuri.subspecies.crew;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.BraidRescueGeometry;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

/**
 * The minimal adapter that keeps the present client presentation running from the hull's {@link CrewState}: it rebuilds, per entity,
 * the braid-contact and boarding-pose records that renderer reads (phase, start, tip and joint, synced body yaw). The contact layer that
 * solves the braid tip and the mouth from the {@code CrewState} contact point directly replaces this adapter; nothing else reads it.
 */
public final class CrewLegacy {
    private static final CompoundTag NONE = new CompoundTag();

    private static CrewState.Entry entry(YukkuriEntity owner, int boatId) {
        if (boatId < 0 || !(owner.level().getEntity(boatId) instanceof HatBoatEntity boat)) return null;
        return CrewState.entryFor(boat.crewEntries(), owner.getUUID());
    }

    /** The braid-contact record of a lift (cradle) the owner carries or is carried in. */
    public static CompoundTag braid(YukkuriEntity owner, int boatId) {
        var e = entry(owner, boatId);
        if (e == null || e.mode() != CrewTransfer.Mode.LIFT || e.phase().ordinal() < CrewTransfer.Phase.CONTACT.ordinal() || e.carrier() == null) return NONE;
        var tag = new CompoundTag();
        tag.putString("Phase", "CRADLE_" + (e.phase() == CrewTransfer.Phase.CONTACT ? "GRIP" : e.phase() == CrewTransfer.Phase.MOVE ? "LIFT" : e.phase() == CrewTransfer.Phase.SETTLE ? "LOWER" : "RELEASE"));
        tag.putLong("Start", e.phaseStart());
        tag.putInt("Duration", 12);
        tag.putInt("ParentId", e.carrierId());
        tag.putInt("ChildId", e.bodyId());
        tag.putInt("HatId", boatId);
        tag.putBoolean("Gripped", true);
        var level = owner.level();
        if (owner.getUUID().equals(e.carrier()) && e.contact() != CrewTransfer.Contact.MOUTH_BITE && e.contactAt() != null) {
            var tip = e.contactWorld(level, false);
            var prev = e.contactWorld(level, true);
            if (tip != null) {
                var root = BraidRescueGeometry.root(owner, 0);
                var joint = BraidRescueGeometry.joint(owner, root, tip, BraidRescueGeometry.restJoint(owner, 0));
                if (joint != null) {
                    var prevTip = prev == null ? tip : prev;
                    point(tag, "Tip", tip);
                    point(tag, "PrevTip", prevTip);
                    point(tag, "Joint", joint);
                    point(tag, "PrevJoint", joint);
                }
            }
        }
        tag.putFloat("Yaw", owner.yBodyRot);
        return tag;
    }

    /** The boarding-pose record: the synced body yaw of a body in a transfer, and the climb or hop phase. */
    public static CompoundTag boarding(YukkuriEntity owner, int boatId) {
        var e = entry(owner, boatId);
        if (e == null || !owner.getUUID().equals(e.body()) || e.phase().ordinal() < CrewTransfer.Phase.CONTACT.ordinal()) return NONE;
        var tag = new CompoundTag();
        tag.putBoolean("Active", true);
        tag.putLong("Start", e.phaseStart());
        tag.putInt("HatId", boatId);
        tag.putFloat("BodyYaw", e.bodyYaw());
        tag.putFloat("PrevBodyYaw", e.prevBodyYaw());
        if (e.mode() == CrewTransfer.Mode.CLIMB) {
            tag.putString("Phase", e.phase() == CrewTransfer.Phase.CONTACT ? "GRIP" : e.phase() == CrewTransfer.Phase.MOVE ? "LIFT" : "SETTLE");
            tag.putBoolean("Gripped", e.phase().ordinal() <= CrewTransfer.Phase.MOVE.ordinal());
        } else if (e.mode() == CrewTransfer.Mode.HOP) {
            tag.putString("Phase", e.phase() == CrewTransfer.Phase.MOVE ? "HOP" : "SETTLE");
        } else tag.putString("Phase", "SETTLE");
        tag.putInt("Duration", Math.max(1, e.phase() == CrewTransfer.Phase.MOVE ? e.moveTicks() : 12));
        return tag;
    }

    private static void point(CompoundTag tag, String key, Vec3 at) {
        tag.putDouble(key + "X", at.x);
        tag.putDouble(key + "Y", at.y);
        tag.putDouble(key + "Z", at.z);
    }

    private CrewLegacy() {}
}
