package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.action.Grab;
import io.github.longshou.mcyukkuri.motor.action.Release;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import java.util.List;
import java.util.function.Supplier;

/** The same braid pickup and lowering for a child beside a seated carrier, on a bank or in water. */
public final class BraidFetch {
    public static final String REACH = "braid_rescue_reach", HOLD = "braid_rescue_hold", SETTLE = "braid_rescue_settle";
    private BraidFetch() {}
    public static int marker(String clip, String marker) { return Math.round(ClipMarkers.of(clip).markers().get(marker) * 20); }
    /** Approach until the braid reaches the child's centre; contact then tests its actual body envelope. */
    public static boolean withinReach(YukkuriEntity carrier, YukkuriEntity child) {
        var point = child.getBoundingBox().getCenter();
        return ContactGeometry.braidGap(carrier, point, false) <= 0 || ContactGeometry.braidGap(carrier, point, true) <= 0;
    }
    public static List<Supplier<Step>> steps(YukkuriEntity carrier, YukkuriEntity child, SurfaceHost destination) {
        return List.of(() -> new Step.Do(new Grab(child, destination)), () -> new Step.Do(new Release(child, destination)));
    }
}
