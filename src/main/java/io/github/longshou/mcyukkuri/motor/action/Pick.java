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
import net.minecraft.world.phys.Vec3;

/**
 * Pick one item up with the mouth (spec 9.4 style), from a loose stack or from a {@link Source} a host keeps (a hat's stored
 * portion): it must lie in the shared mouth reach ({@link ContactGeometry}); at the {@code boat_cargo_fetch} clip's {@code bite}
 * marker one of it is handed to {@code into}, which keeps it where the caller says (a hat's stick, a meal, the care food), and only
 * then leaves where it lay. Out of reach is {@code NO_CONTACT} with the gap, so a method walks closer; the item gone is
 * {@code TARGET_GONE}; {@code into} refusing it leaves it where it is ({@code NO_ROOM}). Never moves the body.
 */
public final class Pick implements Action {
    public static final String ID = "pick";
    private static final String CLIP = "boat_cargo_fetch";

    /** Where one item is taken from: what it is (empty once gone), how far past the mouth's reach it lies, and its removal. */
    public interface Source {
        ItemStack item();
        /** Blocks beyond the mouth's reach (0 or less: in reach). */
        double gap(YukkuriEntity body);
        /** Takes the one away from where it lies, once {@code into} kept it. */
        void take();
        Vec3 at();
    }

    private final Source source;
    private final Predicate<ItemStack> into;
    private long startTick;

    public Pick(ItemEntity item, Predicate<ItemStack> into) { this(loose(item), into); }

    public Pick(Source source, Predicate<ItemStack> into) { this.source = source; this.into = into; }

    private static Source loose(ItemEntity item) {
        return new Source() {
            @Override public ItemStack item() { return item.isAlive() ? item.getItem() : ItemStack.EMPTY; }
            @Override public double gap(YukkuriEntity body) {
                return ContactGeometry.inMouthReach(body, item) ? 0 : Math.max(1E-3, ContactGeometry.mouthGap(body, item) - ContactGeometry.GRAB_MARGIN);
            }
            @Override public void take() { item.getItem().shrink(1); if (item.getItem().isEmpty()) item.discard(); }
            @Override public Vec3 at() { return item.position(); }
        };
    }

    @Override public String id() { return ID; }

    @Override public Feasibility check(YukkuriEntity body) {
        if (source.item().isEmpty()) return Feasibility.of(Reason.TARGET_GONE, 0, "item_gone");
        double gap = source.gap(body);
        return gap > 0 ? Feasibility.of(Reason.NO_CONTACT, gap, "item_out_of_reach") : Feasibility.pass();
    }

    @Override public void start(YukkuriEntity body) { startTick = body.level().getGameTime(); }

    @Override public Progress tick(YukkuriEntity body) {
        int bite = ClipMarkers.of(CLIP).ticks("bite", 10);
        long elapsed = body.level().getGameTime() - startTick;
        if (elapsed < bite) return new Running(bite - elapsed, TravelMode.PASS);
        var still = check(body);
        if (!still.ok()) return Outcome.of(still.reason(), still.shortfall(), still.detail());
        var one = source.item().copyWithCount(1);
        if (!into.test(one)) return Outcome.of(Reason.NO_ROOM, 0, "not_taken item=" + one.getItem());
        source.take();
        return new Outcome.Done("item=" + one.getItem() + " from=" + GameplayLog.at(source.at()));
    }

    @Override public MotionPose pose() { return new MotionPose(CLIP, startTick, false); }

    @Override public String startDetail() { return "item=" + source.item().getItem() + " at=" + GameplayLog.at(source.at()); }
}
