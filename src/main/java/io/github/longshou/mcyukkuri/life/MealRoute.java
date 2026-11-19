package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.body.HairParts;
import io.github.longshou.mcyukkuri.interaction.ItemContact;
import io.github.longshou.mcyukkuri.motor.Braid;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.cognition.Ability;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/** The optional tuft route for an ordinary serving. Mouth contact remains in ItemContact. */
public record MealRoute(String id, ClipMarkers.Clip clip, float yaw, Vec3 stand) {
    @FunctionalInterface
    interface Transfer { boolean hand(Predicate<ItemStack> receiver); }
    record Serving(Supplier<Vec3> contact, Transfer transfer, Predicate<ItemStack> restore, boolean bowl, BlockPos bin) {}
    private static final int TUFT_LOSS = HairParts.lossMask("braid_lock_tuft", (float) ContactGeometry.braidTipModel().x, true);

    static boolean braidIntact(YukkuriEntity body) { return (body.body().hairLoss() & TUFT_LOSS) == 0; }

    public SurfaceApproach.Result approach(YukkuriEntity body, double speed) {
        if (SurfaceApproach.arrived(body, stand)) {
            SurfaceApproach.halt(body);
            return SurfaceApproach.Result.ARRIVED;
        }
        return SurfaceApproach.tryStraightStep(body, stand, speed) ? SurfaceApproach.Result.MOVING
                : SurfaceApproach.walkToContact(body, stand, null, speed);
    }

    public static MealRoute choose(YukkuriEntity body, Vec3 contact, float yaw, boolean bin) {
        String id = bin ? Behaviors.BRAID_EAT_BIN : Behaviors.BRAID_EAT;
        if (!ClipMarkers.tipped(body.assetId(), id) || !braidIntact(body) || Braid.use(body) != Braid.Use.FREE || !Braid.mouthFree(body)) return null;
        double chance = bin ? .85 : .25 + .25 * Mth.clamp((body.life().nutrition() - 10) / 20, 0, 1)
                + (body.skills().knows(Ability.FOOD_GUARD) ? .2 : 0);
        if (body.getRandom().nextDouble() >= chance) return null;
        var clip = ClipMarkers.tuftClip(body.assetId(), id);
        var offset = offset(body, clip, clip.markers().get("pick"), yaw);
        var feet = SurfaceApproach.floor(body, contact.subtract(offset));
        if (feet == null || !reaches(body, feet, offset, contact) || !SurfaceApproach.canApproach(body, feet)) return null;
        return new MealRoute(id, clip, yaw, feet);
    }

    boolean reaches(YukkuriEntity body, Vec3 contact) {
        return Math.abs(Mth.wrapDegrees(body.yBodyRot - yaw)) <= Face.FACE_ARRIVED
                && reaches(body, body.position(), offset(body, clip, clip.markers().get("pick"), body.yBodyRot), contact);
    }

    private static boolean reaches(YukkuriEntity body, Vec3 feet, Vec3 offset, Vec3 contact) {
        Vec3 tip = feet.add(offset);
        double reach = .12 * body.growthScale() * body.getScale();
        return tip.distanceToSqr(contact) <= reach * reach && ItemContact.clear(body, tip, contact);
    }

    Vec3 tip(YukkuriEntity body, float seconds) {
        return body.position().add(offset(body, clip, seconds, body.yBodyRot));
    }

    private static Vec3 offset(YukkuriEntity body, ClipMarkers.Clip clip, float seconds, float yaw) {
        var tip = clip.tip(seconds);
        return new Vec3(tip.x(), tip.y(), tip.z()).scale(body.growthScale() * body.getScale() / 16)
                .yRot((180 - yaw) * Mth.DEG_TO_RAD);
    }

    static <T extends BlockEntity & Container> boolean restore(YukkuriEntity body, T source, ItemStack serving) {
        if (source.isRemoved() || !body.level().hasChunkAt(source.getBlockPos())
                || body.level().getBlockEntity(source.getBlockPos()) != source) return false;
        for (int slot = 0; slot < source.getContainerSize(); slot++) {
            if (!source.canPlaceItem(slot, serving)) continue;
            var current = source.getItem(slot);
            if (current.isEmpty() || ItemStack.isSameItemSameComponents(current, serving)
                    && current.getCount() < source.getMaxStackSize(current)) {
                source.setItem(slot, serving.copyWithCount(current.getCount() + 1));
                return true;
            }
        }
        return false;
    }
}
