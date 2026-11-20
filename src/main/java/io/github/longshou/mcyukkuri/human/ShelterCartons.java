package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.interaction.ItemContact;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.settlement.ShelterPlacement;
import io.github.longshou.mcyukkuri.shop.ShopProtection;
import io.github.longshou.mcyukkuri.shop.ShopStaff;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/** The public bay has no reservation. The crate, hand, and world each own the carton in turn. */
final class ShelterCartons extends Goal {
    private static final String VOLUNTEER = "mcy_shelter_volunteer";
    private enum Leg { PICKUP, PLACE, RETURN, DROP }
    private record Obstruction(BlockPos at, int state) {}

    private final HumanNpc npc;
    private TownMarkers.ShelterSupply site;
    private Leg leg;
    private BlockPos stand;
    private Path path;
    private int elapsed;
    private boolean finished;
    private Obstruction pickupBlocked, bayBlocked, dropBlocked;

    ShelterCartons(HumanNpc npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private ServerLevel level() { return (ServerLevel) npc.level(); }

    private boolean available() {
        return npc.getTags().contains(VOLUNTEER) && npc.attitude() == HumanNpc.Attitude.CARING
                && npc.streetActive() && !npc.walking() && !level().isRaining() && !npc.isPassenger();
    }

    @Override public boolean canUse() {
        // Odd interval because goal selection runs on alternate ticks.
        if (!available() || npc.tickCount % 21 != 0 || !npc.onGround()) return false;
        site = TownMarkers.shelterSupply(level(), npc.home());
        if (holdsCarton(npc)) {
            if (site != null && canFill() && route(Leg.PLACE, bayStand())) return true;
            return returnRoute();
        }
        if (!npc.getMainHandItem().isEmpty() || site == null || unchanged(pickupBlocked)) return false;
        var crate = crate();
        return stocked(crate) && canFill() && route(Leg.PICKUP, crateStand(crate));
    }

    @Override public boolean canContinueToUse() { return !finished && available(); }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() { finished = false; walk(); }
    @Override public void stop() { npc.getNavigation().stop(); }

    private boolean canFill() {
        return !site.marker().isRemoved() && !unchanged(bayBlocked) && EventHooks.canEntityGrief(level(), npc)
                && ShelterPlacement.canPlace(level(), site.bay());
    }

    private ProduceCrateBlockEntity crate() {
        var pos = site.crate();
        return level().hasChunkAt(pos) && level().getBlockEntity(pos) instanceof ProduceCrateBlockEntity crate
                && !crate.stall() && !ShopProtection.guarded(level(), pos) ? crate : null;
    }

    private static boolean stocked(ProduceCrateBlockEntity crate) {
        return crate != null && crate.frontItem().is(ModItems.CARDBOARD_SHELTER.get());
    }

    private BlockPos crateStand(ProduceCrateBlockEntity crate) {
        return site.crate().relative(crate.getBlockState().getValue(ProduceCrateBlock.FACING)).below();
    }

    private BlockPos bayStand() { return site.bay().center().relative(site.bay().facing(), 2); }

    private boolean route(Leg next, BlockPos destination) {
        if (!level().hasChunkAt(destination) || !level().getFluidState(destination).isEmpty()
                || !level().getBlockState(destination.below()).isFaceSturdy(level(), destination.below(), Direction.UP)) return false;
        var inReach = atStand(destination);
        var route = inReach ? null : npc.getNavigation().createPath(destination, 0);
        if (!inReach && (route == null || !route.canReach())) return false;
        leg = next;
        stand = destination;
        path = route;
        return true;
    }

    private boolean returnRoute() {
        var crate = site == null ? null : crate();
        if (crate != null && route(Leg.RETURN, crateStand(crate))) return true;
        if (unchanged(dropBlocked) && npc.blockPosition().equals(dropBlocked.at())) return false;
        leg = Leg.DROP;
        path = null;
        return true;
    }

    private void walk() {
        elapsed = 0;
        npc.getNavigation().stop();
        if (path != null) npc.getNavigation().moveTo(path, .85);
    }

    private boolean atStand(BlockPos destination) {
        return npc.onGround() && !npc.isInWaterOrBubble()
                && npc.distanceToSqr(Vec3.atBottomCenterOf(destination)) < .7 * .7;
    }

    @Override public void tick() {
        if (finished || !available()) return;
        if (leg == Leg.DROP) { drop(); return; }
        var crate = crate();
        if (leg == Leg.PICKUP && (!stocked(crate) || !npc.getMainHandItem().isEmpty())) {
            finished = true;
            return;
        }
        if (leg == Leg.PLACE && (site.marker().isRemoved() || !EventHooks.canEntityGrief(level(), npc))) {
            returnCarton("site_or_permission_changed");
            return;
        }
        if (leg == Leg.RETURN && crate == null) { drop(); return; }
        if (leg != Leg.PICKUP && !holdsCarton(npc)) { finished = true; return; }

        var target = leg == Leg.PLACE ? site.bay().center() : site.crate();
        var contact = target.getCenter();
        if (atStand(stand) && ShopStaff.reaches(npc, contact, target)) {
            npc.getNavigation().stop();
            npc.getLookControl().setLookAt(contact.x, contact.y, contact.z, 30, 30);
            var toward = contact.subtract(npc.position());
            var yaw = (float) (Mth.atan2(toward.z, toward.x) * Mth.RAD_TO_DEG) - 90;
            if (Math.abs(Mth.wrapDegrees(npc.getYHeadRot() - yaw)) <= 30) {
                switch (leg) {
                    case PICKUP -> take(crate);
                    case PLACE -> place();
                    case RETURN -> putBack(crate);
                    case DROP -> throw new IllegalStateException("Drop has no contact stand");
                }
                return;
            }
        } else if (npc.getNavigation().isDone()) {
            blocked("route_ended");
            return;
        }
        if (++elapsed >= HumanStreet.ROUTE_TICKS) blocked("route_timeout");
    }

    private void take(ProduceCrateBlockEntity crate) {
        if (!canFill()) { finished = true; return; }
        int stock = crate.countItem(ModItems.CARDBOARD_SHELTER.get());
        npc.setItemInHand(InteractionHand.MAIN_HAND, crate.takeOne());
        npc.swing(InteractionHand.MAIN_HAND);
        pickupBlocked = null;
        GameplayLog.event(npc, "shelter", "carton picked up crate={} bay={} stock={}->{} hand=1", site.crate().toShortString(),
                site.bay().center().toShortString(), stock, stock - 1);
        if (route(Leg.PLACE, bayStand())) walk();
        else {
            bayBlocked = obstruction(bayStand());
            returnCarton("bay_stand_unreachable");
        }
    }

    private void place() {
        if (!ShelterPlacement.canPlace(level(), site.bay())) { returnCarton("bay_unavailable"); return; }
        var placement = ShelterPlacement.place(level(), site.bay());
        if (placement == null) {
            bayBlocked = obstruction(site.bay().center());
            returnCarton("placement_refused");
            return;
        }
        npc.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        npc.swing(InteractionHand.MAIN_HAND);
        bayBlocked = null;
        finished = true;
        GameplayLog.event(npc, "shelter", "carton placed bay={} facing={} hand=0",
                placement.shelter().getBlockPos().toShortString(), site.bay().facing());
    }

    private void blocked(String reason) {
        var at = path != null && !path.isDone() ? path.getNextNodePos() : stand;
        switch (leg) {
            case PICKUP -> {
                pickupBlocked = obstruction(at);
                finished = true;
                GameplayLog.event(npc, "shelter", "carton pickup interrupted crate={} reason={}", site.crate().toShortString(), reason);
            }
            case PLACE -> { bayBlocked = obstruction(at); returnCarton(reason); }
            case RETURN -> drop();
            case DROP -> throw new IllegalStateException("Drop has no route");
        }
    }

    private Obstruction obstruction(BlockPos at) {
        // Sample the failed approach cell and its neighbors, including a closed leaf or a body in the way.
        int state = 1;
        for (var pos : BlockPos.betweenClosed(at.offset(-1, -1, -1), at.offset(1, 2, 1)))
            state = 31 * state + (level().hasChunkAt(pos) ? level().getBlockState(pos).hashCode() : 0);
        for (var body : level().getEntitiesOfClass(LivingEntity.class, new AABB(at).inflate(.5, 1, .5),
                body -> body != npc && body.isAlive() && !body.isSpectator())) state += body.getUUID().hashCode();
        return new Obstruction(at, state);
    }

    private boolean unchanged(Obstruction failure) { return failure != null && failure.equals(obstruction(failure.at())); }

    private void returnCarton(String reason) {
        GameplayLog.event(npc, "shelter", "carton delivery interrupted bay={} reason={} hand=1",
                site.bay().center().toShortString(), reason);
        if (returnRoute()) walk();
        else finished = true;
    }

    private void putBack(ProduceCrateBlockEntity crate) {
        if (!crate.put(npc.getMainHandItem())) { drop(); return; }
        npc.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        npc.swing(InteractionHand.MAIN_HAND);
        GameplayLog.event(npc, "shelter", "carton returned crate={} hand=0", site.crate().toShortString());
        finished = true;
    }

    private void drop() {
        if (!dropHeldAtFeet(npc, "return_unavailable")) dropBlocked = obstruction(npc.blockPosition());
        finished = true;
    }

    private static boolean holdsCarton(HumanNpc npc) { return npc.getMainHandItem().is(ModItems.CARDBOARD_SHELTER.get()); }

    static boolean dropHeldAtFeet(HumanNpc npc, String reason) {
        return holdsCarton(npc) && ItemContact.hasReleaseSupport(npc, npc.position().add(0, .2, 0)) && dropHeld(npc, reason);
    }

    static void releaseOnRemoval(HumanNpc npc, String reason) {
        if (!holdsCarton(npc) || dropHeld(npc, reason)) return;
        var level = (ServerLevel) npc.level();
        YukkuriWorldData.get(level).deferDrop(level, npc.position(), npc.getMainHandItem());
        npc.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        GameplayLog.event(npc, "shelter", "carton drop deferred reason={} hand=0", reason);
    }

    private static boolean dropHeld(HumanNpc npc, String reason) {
        var drop = ItemContact.release(npc, npc.getMainHandItem(), npc.position().add(0, .2, 0), Vec3.ZERO);
        if (drop == null) return false;
        npc.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        GameplayLog.event(npc, "shelter", "carton dropped reason={} drop={} hand=0", reason, drop.getUUID());
        return true;
    }
}
