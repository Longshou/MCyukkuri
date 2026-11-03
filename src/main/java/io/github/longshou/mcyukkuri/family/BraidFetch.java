package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Grab;
import io.github.longshou.mcyukkuri.motor.action.Release;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** The same braid pickup and lowering for a child beside a seated carrier, on a bank or in water. */
public final class BraidFetch {
    public static final String REACH = "braid_rescue_reach", HOLD = "braid_rescue_hold", SETTLE = "braid_rescue_settle";
    private BraidFetch() {}
    public static int marker(String clip, String marker) { return Math.round(ClipMarkers.of(clip).markers().get(marker) * 20); }
    /** Approach the child's centre; Grab checks the actual body envelope at contact. */
    public static boolean withinReach(YukkuriEntity carrier, YukkuriEntity child) {
        var point = child.getBoundingBox().getCenter();
        return ContactGeometry.braidGap(carrier, point, false) <= 0 || ContactGeometry.braidGap(carrier, point, true) <= 0;
    }
    /** Face the child, let it settle within reach, then the shared lift and lowering. */
    public static List<Supplier<Step>> steps(YukkuriEntity carrier, YukkuriEntity child, SurfaceHost destination) {
        var steps = new ArrayList<>(lift(carrier, child, destination));
        steps.add(() -> new Step.Do(new Release(child, destination)));
        return steps;
    }

    /** Pickup does not choose where the child will eventually be put down. */
    public static List<Supplier<Step>> lift(YukkuriEntity carrier, YukkuriEntity child, SurfaceHost host) {
        return List.of(() -> turn(carrier, child), () -> settle(carrier, child), () -> new Step.Do(new Grab(child, host)));
    }

    /** The carrier is turned to the child before the braid reaches: a hull swinging round under the reach clip pushes the child out of it. */
    private static Step turn(YukkuriEntity carrier, YukkuriEntity child) {
        float yaw = ContactGeometry.braidYaw(carrier, child);
        return Math.abs(Mth.wrapDegrees(yaw - carrier.bodyYaw())) > Face.FACE_ARRIVED ? new Step.Do(new Face(yaw)) : null;
    }

    /** The braid makes contact one clip after it starts, so the child must be unable to leave its reach in that time: wait for it
     *  (at most one clip) while it is still moving relative to the carrier's hull; then the Grab's own contact check decides. */
    private static Step settle(YukkuriEntity carrier, YukkuriEntity child) {
        if (steady(carrier, child)) return null;
        long from = carrier.level().getGameTime();
        int clip = marker(REACH, "contact");
        return new Step.Wait(() -> steady(carrier, child) || carrier.level().getGameTime() - from >= clip, clip + 1, "faller_settles");
    }

    /** The child's gap to the braid (as the Grab's contact check reads it) plus what it moved from the carrier's host last tick,
     *  held for the clip, is still within reach. */
    private static boolean steady(YukkuriEntity carrier, YukkuriEntity child) {
        double gap = Math.min(ContactGeometry.braidGap(carrier, child, false), ContactGeometry.braidGap(carrier, child, true));
        Entity host = Attach.of(carrier) instanceof AttachState.OnHost on ? on.host().entity() : carrier;
        double drift = tick(child).subtract(tick(host)).horizontalDistance();
        return gap + drift * marker(REACH, "contact") <= 0;
    }

    /** What the entity moved in its last tick. */
    private static Vec3 tick(Entity entity) { return entity.position().subtract(entity.xo, entity.yo, entity.zo); }
}
