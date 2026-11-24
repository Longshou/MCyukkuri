package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.body.MouthPlacement;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriComponents;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.interaction.ItemContact;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.MouthOccupancy;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Set one held mouth item down at a {@link Site} with the mouth-item clip: she turns to the spot, holds still, and at the clip's
 * {@code release} marker the one item is put on the supported ground and leaves the mouth in the same tick. The site owns the
 * admission, what happens to the released item, and how the placement is reported; the item itself is {@link #released()}.
 */
public final class PlaceMouthItem implements Action {
    public static final String ID = "place_mouth_item";
    private static final String CLIP = YukkuriAction.MOUTH_ITEM_PLACE.id();

    /** Where one mouth item goes and what becomes of it there. */
    public interface Site {
        /** The point she faces and holds the item over. */
        Vec3 at();
        /** Why she may not place from where she stands; asked at the start and every tick. A site that admits any dry stand keeps this. */
        default Feasibility admission(YukkuriEntity body) { return Feasibility.pass(); }
        /** Why the mouth may not let go at {@code mouth}; asked once, at the clip's release marker. */
        Feasibility mouthAt(YukkuriEntity body, Vec3 mouth);
        /** The item has left the mouth here: mark it, keep it, log it. A site whose item needs nothing keeps this. */
        default void released(YukkuriEntity body, ItemEntity drop) {}
        /** How the finished placement names its spot. */
        String detail();
    }

    private final MouthOccupancy.Purpose purpose;
    private final Site site;
    private final ClipMarkers.Clip marks = ClipMarkers.of(CLIP);
    private final int length = marks.length();
    private final int releaseTick = marks.ticks("release", Math.max(1, length / 2));
    private long startTick;
    private int ticks;
    private ItemEntity released;

    public PlaceMouthItem(MouthOccupancy.Purpose purpose, Site site) {
        this.purpose = purpose;
        this.site = site;
    }

    /** The item this placement put down, once it has left the mouth. */
    public ItemEntity released() { return released; }

    @Override public String id() { return ID; }

    @Override public Feasibility check(YukkuriEntity body) {
        var ground = ground(body);
        if (!ground.ok()) return ground;
        var admission = site.admission(body);
        if (!admission.ok()) return admission;
        return holding(body) ? Feasibility.pass() : Feasibility.of(Reason.NO_CONTACT, 0, "mouth_empty");
    }

    private static Feasibility ground(YukkuriEntity body) {
        return body.onGround() && !body.isPassenger() && !body.isInWaterOrBubble() && !body.isOnFire()
                ? Feasibility.pass() : Feasibility.of(Reason.NO_SUPPORT, 0, "ground_required");
    }

    private boolean holding(YukkuriEntity body) {
        var mouth = body.component(YukkuriComponents.MOUTH);
        return mouth.purpose() == purpose && !mouth.items(purpose).isEmpty();
    }

    @Override public void start(YukkuriEntity body) {
        body.getNavigation().stop();
        startTick = body.level().getGameTime();
        ticks = 0;
        released = null;
    }

    @Override public Progress tick(YukkuriEntity body) {
        var ground = ground(body);
        if (!ground.ok()) return Outcome.of(ground.reason(), ground.shortfall(), ground.detail());
        var admission = site.admission(body);
        if (!admission.ok()) return Outcome.of(admission.reason(), admission.shortfall(), admission.detail());
        if (body.position().subtract(site.at()).horizontalDistanceSqr() > 0.01)
            Motor.face(body, SurfaceApproach.yaw(body.position(), site.at()));
        Motor.velocity(body, new Vec3(0, body.getDeltaMovement().y, 0), CLIP);
        if (released == null && ticks >= releaseTick) {
            var mouth = body.component(YukkuriComponents.MOUTH);
            if (mouth.purpose() != purpose || mouth.items(purpose).isEmpty())
                return Outcome.of(Reason.NO_CONTACT, 0, "mouth_empty");
            Vec3 mouthPos = MouthPlacement.position(body);
            var mouthAt = site.mouthAt(body, mouthPos);
            if (!mouthAt.ok()) return Outcome.of(mouthAt.reason(), mouthAt.shortfall(), mouthAt.detail());
            ItemStack one = mouth.stack(purpose).copyWithCount(1);
            ItemEntity drop = ItemContact.releaseSupported(body, one, 20);
            if (drop == null) return Outcome.of(Reason.NO_ROOM, 0, "drop_refused");
            mouth.takeOne(purpose);
            site.released(body, drop);
            released = drop;
        }
        if (++ticks >= length) return new Outcome.Done(site.detail());
        return new Running(length - ticks, TravelMode.PASS);
    }

    @Override public MotionPose pose() { return new MotionPose(CLIP, startTick, false); }

    @Override public String startDetail() { return site.detail() + " clipTicks=" + length; }
}
