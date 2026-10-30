package io.github.longshou.mcyukkuri.motor;

import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.body.MouthPlacement;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** The one place that answers "can this body reach that": the mouth and the braid tip a Grab holds with, the brim, and the grip test every water action uses ({@link #canGrip}). */
public final class ContactGeometry {
    /** The mouth's reach beyond the two body envelopes (blocks). */
    public static final double GRAB_MARGIN = .35;
    /** The height window of the grab rule, in blocks of difference between the two bodies. */
    public static final double GRAB_HEIGHT = 1.2;

    /** The two ways one body holds another: in the mouth, or at a Marisa's braid tip. */
    public enum Reach { MOUTH, BRAID }

    /** The mouth tip in world coordinates: the point the mouth pose hangs a held body from. */
    public static Vec3 mouth(YukkuriEntity body) {
        return MouthPlacement.position(body);
    }

    /** The grab gap: the horizontal distance left between the two bodies' envelopes, so 0 means they touch; the full rule is {@link #inMouthReach}. */
    public static double mouthGap(YukkuriEntity body, Entity target) {
        if (body == null || target == null) return Double.POSITIVE_INFINITY;
        double dx = target.getX() - body.getX(), dz = target.getZ() - body.getZ();
        return Math.max(0, Math.hypot(dx, dz) - (body.getBbWidth() + target.getBbWidth()) / 2);
    }

    /** Whether the mouth can reach the target now. */
    public static boolean inMouthReach(YukkuriEntity body, Entity target) {
        return body != null && target != null && mouthGap(body, target) <= GRAB_MARGIN
                && Math.abs(target.getY() - body.getY()) < GRAB_HEIGHT && body.hasLineOfSight(target);
    }

    /** The brim sample of {@code host} nearest the mouth tip, already tilted into world coordinates. */
    public static Optional<Vec3> nearestRim(YukkuriEntity body, SurfaceHost host) {
        if (body == null || host == null) return Optional.empty();
        Vec3 mouth = mouth(body);
        Vec3 nearest = null;
        double best = Double.MAX_VALUE;
        for (Vec3 point : host.rim()) {
            double distance = point.distanceToSqr(mouth);
            if (distance < best) { best = distance; nearest = point; }
        }
        return Optional.ofNullable(nearest);
    }

    /** A Marisa's braid in model units (braid_rescue.json): its root, where its tip rests, and its root in the low lean. */
    private static final Vec3[] BRAID = new Vec3[3];
    static {
        try (var in = new InputStreamReader(ContactGeometry.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/braid_rescue.json"),
                StandardCharsets.UTF_8)) {
            var data = JsonParser.parseReader(in).getAsJsonObject();
            String[] keys = {"braid_root", "braid_tip", "reach_lean_root"};
            for (int i = 0; i < 3; i++) {
                var p = data.getAsJsonArray(keys[i]);
                BRAID[i] = new Vec3(p.get(0).getAsDouble(), p.get(1).getAsDouble(), p.get(2).getAsDouble());
            }
        } catch (Exception broken) { throw new IllegalStateException("braid_rescue.json unreadable", broken); }
    }

    /** Only a Marisa has a braid to reach with. */
    public static boolean braided(YukkuriView body) { return body != null && body.getVariant() == YukkuriVariant.MARISA; }

    /** The braid tip at rest in world coordinates, or null for a body without a braid. */
    public static Vec3 braidTip(YukkuriEntity body) { return braid(body, BRAID[1]); }

    /** The braid tip at rest in model units (x the braid side, -z the face): where a braid-held body hangs. */
    public static Vec3 braidTipModel() { return BRAID[1]; }

    /** How much farther {@code target} lies than the braid holds (blocks, 0 or less in reach): the braid swung about its root or the low lean's ({@code lean}) within its length. */
    public static double braidGap(YukkuriEntity body, Entity target, boolean lean) {
        return body == null || target == null ? Double.POSITIVE_INFINITY : braidGap(body, body.position(), target, lean, true);
    }

    private static double braidGap(YukkuriEntity body, Vec3 at, Entity target, boolean lean, boolean sighted) {
        if (!braided(body)) return Double.POSITIVE_INFINITY;
        Vec3 model = BRAID[lean ? 2 : 0];
        double s = body.growthScale() * body.getScale() / 16, dx = target.getX() - at.x, dz = target.getZ() - at.z;
        double d = Math.max(1E-6, Math.hypot(dx, dz)), r = Math.hypot(model.x, model.z) * s;
        Vec3 root = at.add(dx / d * r, model.y * s, dz / d * r);
        var box = target.getBoundingBox();
        Vec3 near = new Vec3(Mth.clamp(root.x, box.minX, box.maxX), Mth.clamp(root.y, box.minY, box.maxY), Mth.clamp(root.z, box.minZ, box.maxZ));
        return !sighted || sight(body, root, near) ? root.distanceTo(near) - BRAID[1].distanceTo(BRAID[0]) * s : Double.POSITIVE_INFINITY;
    }

    /** Whether the mouth or the braid would reach {@code target} from a planned stand {@code at} (the grab checks sight itself). */
    public static boolean reachesFrom(YukkuriEntity body, Vec3 at, Entity target) {
        double gap = Math.hypot(target.getX() - at.x, target.getZ() - at.z) - (body.getBbWidth() + target.getBbWidth()) / 2;
        return gap <= GRAB_MARGIN && Math.abs(target.getY() - at.y) < GRAB_HEIGHT
                || braidGap(body, at, target, false, false) <= 0 || braidGap(body, at, target, true, false) <= 0;
    }

    /** Whether the braid reaches the target from where the body stands, upright or in the low lean. */
    public static boolean inBraidReach(YukkuriEntity body, Entity target) {
        return braidGap(body, target, false) <= 0 || braidGap(body, target, true) <= 0;
    }

    /** The yaw that turns the braid side to {@code target}: the bearing to it less the braid root's own angle off the face. */
    public static float braidYaw(YukkuriEntity body, Entity target) {
        return (float) (Math.toDegrees(Math.atan2(target.getZ() - body.getZ(), target.getX() - body.getX())) - 90
                - Math.toDegrees(Math.atan2(BRAID[0].x, -BRAID[0].z)));
    }

    /** Whether the body holds {@code point} (a brim or a bank edge): the only reach rule of the water actions. */
    public static boolean canGrip(YukkuriEntity body, Vec3 point) { return gripGap(body, point) <= 0; }

    /** How much farther {@code point} lies than mouth or braid holds (blocks, 0 or less in grip): the mouth within {@link #GRAB_MARGIN} or the braid within its length, sight free. */
    public static double gripGap(YukkuriEntity body, Vec3 point) {
        if (body == null || point == null) return Double.POSITIVE_INFINITY;
        Vec3 mouth = mouth(body), root = braid(body, BRAID[0]);
        double gap = sight(body, mouth, point) ? mouth.distanceTo(point) - GRAB_MARGIN : Double.POSITIVE_INFINITY;
        return root != null && sight(body, root, point) ? Math.min(gap, root.distanceTo(point) - root.distanceTo(braidTip(body))) : gap;
    }

    private static boolean sight(YukkuriEntity body, Vec3 from, Vec3 to) {
        var hit = body.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(to) < 1E-2;
    }

    private static Vec3 braid(YukkuriEntity body, Vec3 model) {
        return !braided(body) ? null : body.position()
                .add(model.scale(body.growthScale() * body.getScale() / 16).yRot((float) Math.toRadians(180 - body.yBodyRot)));
    }

    private ContactGeometry() {}
}
