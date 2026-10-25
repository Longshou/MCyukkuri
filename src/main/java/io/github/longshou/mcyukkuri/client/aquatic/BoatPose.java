package io.github.longshou.mcyukkuri.client.aquatic;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatView;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import io.github.longshou.mcyukkuri.subspecies.crew.DeckLayout;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * One rigid frame of a hat boat for one render frame (hat_frames.json {@code hull}): the hull, its larder, its water mask, the
 * stowed stick and every body riding in it read this same frame, so nothing aboard drifts against the hat while it turns,
 * rolls, pitches or dips. Hull-local points are hat units (x across, +z stern, y up from the waterline); the frame maps them
 * to the world as {@code origin + rotateY(180 - yaw) * tilt * (local * unit)}. The load already sinks the entity itself on
 * the server (freeboard by load); only the water mask is raised back to the waterline.
 *
 * <p>All values come from the synchronized hull: the motion record (rocking, capsize, deploy, righting, held hull), the dip,
 * the current, the stroke clock and the load. The small idle bob and the stroke nod are drawn here only (ecology design D 3.2,
 * project design); they never move a seat on the server.
 */
public final class BoatPose {
    /** Final roll of a capsized hull: turned fully over, crown up, the brim lying on the water. */
    public static final float CAPSIZED_ROLL = 180;
    private static final Map<Integer, BoatPose> CACHE = new HashMap<>();
    private static long cachedTime = Long.MIN_VALUE;
    private static float cachedPartial = Float.NaN;

    public final HatBoatView boat;
    public final HatVessel.Mode mode;
    /** World position of the hull-local origin (the waterline centre), interpolated. */
    public final Vec3 origin;
    /** Interpolated entity yaw, and the tilt in the hull's yaw-aligned frame (degrees). */
    public final float yaw, roll, pitch, dip, dipYaw;
    /** Hat scale, blocks per hat unit, crown squash and the capsize rise of the hull mesh (blocks). */
    public final float scale, unit, squash, lift;
    private final Quaternionf tilt, rotation;

    private BoatPose(HatBoatView boat, float partial) {
        this.boat = boat;
        mode = boat.visibleMode();
        origin = boat.motionPosition(partial);
        yaw = Mth.rotLerp(partial, boat.previousYaw(), boat.getYRot());
        scale = boat.hullScale();
        unit = scale / 16;
        squash = boat.hullSquash(partial);
        float r = motionRoll(boat, partial), p = motionPitch(boat, partial);
        if (calm(boat)) {
            // Idle bob (D 3.2): roll +-1 deg over 3.5 s and pitch +-0.6 deg over 2.7 s, out of step; half by night; a fuller hull
            // rolls more. Each power stroke nods the bow down a degree.
            double t = (boat.level().getGameTime() + partial) / 20.0 + Math.floorMod(boat.getId(), 97) * .37;
            float night = boat.level().getDayTime() % 24000 > 13000 && boat.level().getDayTime() % 24000 < 23000 ? .5f : 1f;
            float load = (float) DeckLayout.of(mode).rollFactor(boat.hullLoad());
            r += (float) Math.sin(t * Math.PI * 2 / 3.5) * night * load;
            p += (float) Math.sin(t * Math.PI * 2 / 2.7) * .6f * night;
            if (boat.paddling()) p -= (float) Math.max(0, Math.sin(boat.paddleCycle(partial) * Math.PI * 2));
        }
        roll = r;
        pitch = p;
        dip = Mth.clamp(boat.dipDegrees(partial), 0, HatBoatEntity.MAX_DIP);
        dipYaw = boat.dipYaw();
        tilt = new Quaternionf().rotationZ(roll * Mth.DEG_TO_RAD).rotateX(pitch * Mth.DEG_TO_RAD);
        if (dip > .01f) {
            // dipYaw is the lowered rim's direction (-sin, 0, cos) in the hull frame; a right-handed turn about (cos, 0, sin)
            // sends it down and the far rim up.
            float toward = dipYaw * Mth.DEG_TO_RAD;
            tilt.rotateAxis(dip * Mth.DEG_TO_RAD, Mth.cos(toward), 0, Mth.sin(toward));
        }
        rotation = new Quaternionf().rotationY((180 - yaw) * Mth.DEG_TO_RAD).mul(tilt);
        boolean over = boat.state() == HatBoatEntity.State.CAPSIZED || boat.selfRighting();
        // Turning over about the waterline would sink the brim a freeboard under; a capsized felt hat floats crown up with its
        // brim just awash, so it rises by nearly that freeboard as the roll goes over (hull mesh only).
        lift = over ? (float) (HatVessel.top(mode) * unit * .45f * (1 - Mth.cos(roll * Mth.DEG_TO_RAD))) : 0;
    }

    /** The one frame of this boat for this render frame. */
    public static BoatPose of(HatBoatView boat, float partial) {
        long time = boat.level().getGameTime();
        if (time != cachedTime || Float.compare(partial, cachedPartial) != 0) {
            CACHE.clear();
            cachedTime = time;
            cachedPartial = partial;
        }
        var pose = CACHE.get(boat.getId());
        if (pose == null || pose.boat != boat) CACHE.put(boat.getId(), pose = new BoatPose(boat, partial));
        return pose;
    }

    private static boolean calm(HatBoatView boat) {
        return boat.isAfloat() && !boat.rocking() && !boat.capsizing() && !boat.deploying() && !boat.selfRighting() && !boat.selfRescueHeld();
    }

    /** The tilt in the hull's yaw-aligned frame (roll about Z, pitch about X, then the dip). */
    public Quaternionf tilt() { return new Quaternionf(tilt); }
    /** World rotation of the hull frame: rotateY(180 - yaw) * tilt. */
    public Quaternionf rotation() { return new Quaternionf(rotation); }
    /** A hull-local point (hat units) in the world, tilted with the hull. */
    public Vec3 toWorld(Vec3 local) {
        var v = new Vector3f((float) local.x, (float) local.y, (float) local.z).mul(unit);
        rotation.transform(v);
        return origin.add(v.x, v.y, v.z);
    }
    /** A hull-local point in the world by yaw alone (the server's own toWorld at this frame). */
    public Vec3 toWorldFlat(Vec3 local) { return origin.add(local.scale(unit).yRot((180 - yaw) * Mth.DEG_TO_RAD)); }
    /** A world point in hull-local hat units by yaw alone (the server's toLocal at this frame). */
    public Vec3 toLocalFlat(Vec3 world) { return world.subtract(origin).yRot(-(180 - yaw) * Mth.DEG_TO_RAD).scale(1 / unit); }
    /** The waterline mask rises back by what the load sinks the entity (blocks). */
    public float sink() { return boat.visibleSink(); }
    /** Brim height above the waterline (hat units). */
    public float top() { return (float) HatVessel.top(mode); }

    /**
     * Put the pose (now at {@code drawnAt}, the position this boat's renderer stands at) into the hull frame in blocks, at the
     * hull-local origin: callers then translate to the brim top or scale to hat units.
     */
    public void applyHull(PoseStack pose, Vec3 drawnAt) {
        pose.translate(origin.x - drawnAt.x, origin.y - drawnAt.y, origin.z - drawnAt.z);
        pose.mulPose(new Quaternionf().rotationY((180 - yaw) * Mth.DEG_TO_RAD));
        if (lift != 0) pose.translate(0, lift, 0);
        pose.mulPose(new Quaternionf(tilt));
    }

    // ---------------------------------------------------------------- the server's motion curves, sampled (data in HatBoatView)
    private static double age(HatBoatView boat, CompoundTag m, String key, float partial) {
        return Math.max(0, boat.level().getGameTime() - m.getLong(key) + partial);
    }

    static float motionRoll(HatBoatView boat, float partial) {
        var m = boat.motionSnapshot();
        int side = m.getInt("Side") < 0 ? -1 : 1;
        if (boat.selfRescueHeld()) return 0;
        if (m.contains("LooseAt")) {
            double t = Math.clamp(age(boat, m, "LooseAt", partial) / 14, 0, 1);
            return (float) (side * 110 * t * t * (3 - 2 * t));
        }
        if (boat.deploying() || boat.selfRighting()) {
            double t = boat.deploying() ? Math.clamp(age(boat, m, "DeployAt", partial) / 20, 0, 1) : Math.clamp(boat.selfRightingProgress(partial), 0, 1);
            // A capsized hat floats crown up; righting turns it back from there.
            return (float) (side * (boat.deploying() ? 110 : CAPSIZED_ROLL) * (1 - t * t * (3 - 2 * t)));
        }
        if (boat.state() == HatBoatEntity.State.CAPSIZED) {
            double t = m.contains("CapAt") ? Math.clamp(age(boat, m, "CapAt", partial) / HatBoatEntity.CAPSIZE_TICKS, 0, 1) : 1;
            return (float) (side * CAPSIZED_ROLL * t * t * (3 - 2 * t));
        }
        if (!boat.rocking())
            return boat.visibleDrifting() ? (float) (Math.sin((boat.level().getGameTime() + partial) * .19) * boat.currentStrength() * 2.5) : 0;
        double age = age(boat, m, "RockAt", partial);
        return (float) (side * m.getFloat("Amplitude") * Math.exp(-age / 14) * Math.sin(age * .62));
    }

    static float motionPitch(HatBoatView boat, float partial) {
        var m = boat.motionSnapshot();
        if (boat.selfRescueHeld()) return Mth.lerp(partial, m.getFloat("PreviousPitch"), m.getFloat("HeldPitch"));
        if (m.contains("LooseAt")) {
            double t = Math.clamp(age(boat, m, "LooseAt", partial) / 14, 0, 1);
            return (float) (m.getFloat("LoosePitch") * (1 - t * t * (3 - 2 * t)));
        }
        if (boat.deploying() || boat.selfRighting()) {
            double t = boat.deploying() ? Math.clamp(age(boat, m, "DeployAt", partial) / 20, 0, 1) : Math.clamp(boat.selfRightingProgress(partial), 0, 1);
            return (float) (7 * Math.sin(t * Math.PI));
        }
        if (boat.state() == HatBoatEntity.State.CAPSIZED) {
            if (!m.contains("CapAt")) return 0;
            double t = Math.clamp(age(boat, m, "CapAt", partial) / HatBoatEntity.CAPSIZE_TICKS, 0, 1);
            return (float) (7 * Math.sin(t * Math.PI));
        }
        if (!boat.rocking()) return boat.visibleDrifting() ? (float) (Math.sin((boat.level().getGameTime() + partial) * .13) * boat.currentStrength()) : 0;
        double age = age(boat, m, "RockAt", partial);
        return (float) (m.getFloat("Amplitude") * .24 * Math.exp(-age / 14) * Math.sin(age * .44));
    }
}
