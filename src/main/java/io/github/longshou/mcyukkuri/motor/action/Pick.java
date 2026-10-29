package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.function.Predicate;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Pick one of a loose item up with the mouth (spec 9.4 style): the item must lie in the shared mouth reach
 * ({@link ContactGeometry}); at the {@code boat_cargo_fetch} clip's {@code bite} marker one of it is handed to {@code into},
 * which keeps it where the caller says (a hat's stick). Out of reach is {@code NO_CONTACT} with the gap, so a method walks closer;
 * the item gone is {@code TARGET_GONE}; {@code into} refusing it leaves it on the ground ({@code NO_ROOM}). Never moves the body.
 */
public final class Pick implements Action {
    public static final String ID = "pick";
    private static final String CLIP = "boat_cargo_fetch";

    private final ItemEntity item;
    private final Predicate<ItemStack> into;
    private long startTick;

    public Pick(ItemEntity item, Predicate<ItemStack> into) { this.item = item; this.into = into; }

    @Override public String id() { return ID; }

    @Override public Feasibility check(YukkuriEntity body) {
        if (!item.isAlive() || item.getItem().isEmpty()) return Feasibility.of(Reason.TARGET_GONE, 0, "item_gone");
        if (!ContactGeometry.inMouthReach(body, item))
            return Feasibility.of(Reason.NO_CONTACT, Math.max(0, ContactGeometry.mouthGap(body, item) - ContactGeometry.GRAB_MARGIN), "item_out_of_reach");
        return Feasibility.pass();
    }

    @Override public void start(YukkuriEntity body) { startTick = body.level().getGameTime(); }

    @Override public Progress tick(YukkuriEntity body) {
        int bite = ClipMarkers.of(CLIP).ticks("bite", 10);
        long elapsed = body.level().getGameTime() - startTick;
        if (elapsed < bite) return new Running(bite - elapsed, TravelMode.PASS);
        var still = check(body);
        if (!still.ok()) return Outcome.of(still.reason(), still.shortfall(), still.detail());
        var one = item.getItem().copyWithCount(1);
        if (!into.test(one)) return Outcome.of(Reason.NO_ROOM, 0, "not_taken item=" + one.getItem());
        item.getItem().shrink(1);
        if (item.getItem().isEmpty()) item.discard();
        return new Outcome.Done("item=" + one.getItem() + " from=" + GameplayLog.at(item.position()));
    }

    @Override public MotionPose pose() { return new MotionPose(CLIP, startTick, false); }

    @Override public String startDetail() { return "item=" + item.getItem().getItem() + " at=" + GameplayLog.at(item.position()); }
}
