package io.github.longshou.mcyukkuri.motor;

import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.body.MouthPlacement;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The one place that answers "can this body reach that" (spec 10): the mouth, the braid tip, the brim, and the grip test
 * every water action uses ({@link #canGrip}).
 */
public final class ContactGeometry {
    /** The reach beyond the two body envelopes; the margin {@code ParentCarry.inReach} used, unchanged. */
    public static final double GRAB_MARGIN = .35;
    /** The height window of the grab rule, in blocks of difference between the two bodies. */
    public static final double GRAB_HEIGHT = 1.2;

    /** The mouth tip in world coordinates: the point the mouth pose hangs a held body from. */
    public static Vec3 mouth(YukkuriEntity body) {
        return MouthPlacement.position(body);
    }

    /**
     * The grab gap: the horizontal distance left between the two bodies' envelopes, so 0 means they touch. The full rule
     * (height window and sight included) is {@link #inMouthReach}, and it reproduces {@code ParentCarry.inReach} exactly.
     */
    public static double mouthGap(YukkuriEntity body, Entity target) {
        if (body == null || target == null) return Double.POSITIVE_INFINITY;
        double dx = target.getX() - body.getX(), dz = target.getZ() - body.getZ();
        return Math.max(0, Math.hypot(dx, dz) - (body.getBbWidth() + target.getBbWidth()) / 2);
    }

    /** Whether the mouth can reach the target now: {@code ParentCarry.inReach}, moved here so there is one copy. */
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

    /** A Marisa's braid in model units (braid_rescue.json): its root and where its tip rests. */
    private static final Vec3[] BRAID = new Vec3[2];
    static {
        try (var in = new InputStreamReader(ContactGeometry.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/braid_rescue.json"),
                StandardCharsets.UTF_8)) {
            var data = JsonParser.parseReader(in).getAsJsonObject();
            for (int i = 0; i < 2; i++) {
                var p = data.getAsJsonArray(i == 0 ? "braid_root" : "braid_tip");
                BRAID[i] = new Vec3(p.get(0).getAsDouble(), p.get(1).getAsDouble(), p.get(2).getAsDouble());
            }
        } catch (Exception broken) { throw new IllegalStateException("braid_rescue.json unreadable", broken); }
    }

    /** The braid tip at rest in world coordinates, or null for a body without a braid (only a Marisa has one). */
    public static Vec3 braidTip(YukkuriEntity body) { return braid(body, BRAID[1]); }

    /** Whether the body holds {@code point} (a brim or a bank edge): the only reach rule of the water actions. */
    public static boolean canGrip(YukkuriEntity body, Vec3 point) { return gripGap(body, point) <= 0; }

    /** How much farther {@code point} lies than mouth or braid holds (blocks, 0 or less in grip): the mouth tip within
     *  {@link #GRAB_MARGIN}, or the braid swung about its root within its length, each with no block in between. */
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
        return body == null || body.getVariant() != YukkuriVariant.MARISA ? null : body.position()
                .add(model.scale(body.growthScale() * body.getScale() / 16).yRot((float) Math.toRadians(180 - body.yBodyRot)));
    }

    private ContactGeometry() {}
}
