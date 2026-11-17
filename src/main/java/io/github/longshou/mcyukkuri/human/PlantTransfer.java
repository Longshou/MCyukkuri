package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.interaction.InteractionDispatch;
import io.github.longshou.mcyukkuri.item.CarrierContents;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.shop.ShopStaff;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Moves booth stock by the ordinary staff path and the carrier actually in the clerk's hand. */
final class PlantTransfer extends Goal {
    private static final double SPEED = .9;
    // Mob goal evaluation can run on either tick parity.
    private static final int SEARCH_INTERVAL = 11;
    private final HumanNpc staff;
    private Path path;
    private BlockPos floor;
    private YukkuriEntity pickup;

    PlantTransfer(HumanNpc staff) {
        this.staff = staff;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override public boolean canUse() {
        if (!(staff.level() instanceof ServerLevel level) || !PlantReceiving.receiver(staff) || staff.hurtTime > 0
                || !staff.onGround() || staff.tickCount % SEARCH_INTERVAL != 0 || held()) return false;
        var layout = PlantSite.layout(level, staff.plantSite());
        if (layout == null) return false;
        if (!staff.blockPosition().equals(layout.boothPost())) {
            path = staff.shopPath(layout.boothPost());
            return path != null && path.canReach();
        }
        if (!CarrierContents.isBasket(staff.getMainHandItem())) return false;
        var stock = level.getEntitiesOfClass(YukkuriEntity.class, AABB.of(layout.booth()),
                animal -> layout.booth().isInside(animal.blockPosition()) && ready(animal));
        for (var animal : stock) {
            for (var box : layout.boxes()) {
                var landing = box.floor().stream().filter(at -> at.distSqr(box.stand()) > 0 && at.distSqr(box.stand()) <= 4
                        && YukkuriWorldData.clearReleaseGround(level, animal, at)).findFirst().orElse(null);
                if (landing == null) continue;
                var route = staff.shopPath(box.stand());
                if (route == null || !route.canReach()) continue;
                path = route; floor = landing; pickup = animal;
                return true;
            }
        }
        return false;
    }

    private boolean ready(YukkuriEntity animal) {
        var custody = Custody.view(animal);
        return custody.role() == Custody.Role.PLANT_STOCK && custody.ownedBy(staff.plantSite()) && animal.onGround()
                && !animal.body().dying() && !animal.isCommandPlaying() && !animal.hasReaction() && !animal.isVehicle()
                && !animal.operations().active() && InteractionDispatch.contactRefusal(animal) == null
                && InteractionDispatch.reachRefusal(staff, animal, 2.5) == null;
    }

    @Override public void start() {
        if (!staff.getNavigation().moveTo(path, SPEED)) { floor = null; return; }
        if (pickup == null) return;
        var world = YukkuriWorldData.get((ServerLevel) staff.level());
        var token = world.capture(pickup);
        if (token == null) { staff.getNavigation().stop(); floor = null; return; }
        CarrierContents.add(staff.getMainHandItem(), token, world.captiveSnapshot(token));
        staff.swing(InteractionHand.MAIN_HAND);
        GameplayLog.event(staff, "factory", "action=receive_transfer result=packed target={} token={} stand={} floor={}",
                pickup.getUUID(), token, path.getTarget().toShortString(), floor.toShortString());
    }

    @Override public boolean canContinueToUse() {
        return staff.isAlive() && staff.hurtTime == 0 && (floor == null ? !staff.getNavigation().isDone()
                : held() && (!staff.getNavigation().isDone() || atDestination()));
    }

    @Override public boolean requiresUpdateEveryTick() { return true; }

    @Override public void tick() {
        if (floor == null || !held() || !atDestination()) return;
        var basket = staff.getMainHandItem();
        var token = CarrierContents.singleToken(basket);
        var level = (ServerLevel) staff.level();
        staff.getNavigation().stop();
        var landing = floor;
        floor = null;
        var world = YukkuriWorldData.get(level);
        var animal = world.releaseOnGround(token, level, Vec3.atBottomCenterOf(landing), staff.getYRot());
        if (animal == null) {
            GameplayLog.event(staff, "factory", "action=receive_transfer result=held token={} reason=blocked_floor", token);
            return;
        }
        CarrierContents.remove(basket, token);
        staff.swing(InteractionHand.MAIN_HAND);
        if (!animal.life().sedated()) animal.playAction(YukkuriAction.BASKET_OUT.id());
        GameplayLog.event(animal, "factory", "action=receive_transfer result=placed staff={} token={} opened_from={}",
                staff.getUUID(), token, staff.blockPosition().toShortString());
    }

    private boolean atDestination() {
        return staff.onGround() && staff.blockPosition().equals(path.getTarget())
                && ShopStaff.reaches(staff, Vec3.atBottomCenterOf(floor).add(0, .2, 0), floor);
    }

    private boolean held() { return CarrierContents.singleToken(staff.getMainHandItem()) != null; }

    @Override public void stop() {
        staff.getNavigation().stop();
        if (floor != null && held()) GameplayLog.event(staff, "factory", "action=receive_transfer result=held token={} reason=interrupted",
                CarrierContents.singleToken(staff.getMainHandItem()));
        path = null; floor = null; pickup = null;
    }
}
