package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import java.util.ArrayList;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/** A captain sails to a real shore plant, turns the stern alongside it, and picks at actual mouth contact. */
public final class HatShoreForage {
    public static final int CONTACT_TICKS = 36;
    private record Target(BlockPos plant, BlockPos water, BlockState state) {}
    private final YukkuriEntity owner;
    private Target target;
    private int elapsed, contact, retry;
    private long lastContactTick = Long.MIN_VALUE;

    public HatShoreForage(YukkuriEntity owner) { this.owner = owner; }
    public boolean active() { return target != null; }
    public boolean harvesting() { return contact > 0; }
    public String activity() { return harvesting() ? "boat_gather" : "boat_paddle"; }
    public void stop() { target = null; elapsed = contact = 0; lastContactTick = Long.MIN_VALUE; }
    private int fruitCount(BlockState state) { return state.getValue(SweetBerryBushBlock.AGE) == 3 ? 2 : 1; }

    /** Returns true while travelling or picking. Call only after urgent care/transport/physiology have yielded. */
    public boolean tick(HatBoatEntity boat) {
        if (owner.level().isClientSide || !owner.isAlive() || owner.isNoAi() || owner.isPanicking()
                || owner.life().isSleeping() || owner.life().mealTicks() > 0 || owner.family().laboring()
                || owner.getVehicle() != boat || !owner.getUUID().equals(boat.ownerYukkuri())
                || !HatVessel.aquatic(owner) || !boat.isAfloat() || boat.towed() || boat.flooding() > 0
                || !boat.cargo().paddle() || !EventHooks.canEntityGrief(owner.level(), owner)) { stop(); return false; }
        if (target == null) {
            if (retry-- > 0) return false;
            retry = 40; target = find(boat); elapsed = contact = 0;
            if (target == null) return false;
        }
        if (++elapsed > 500 || !owner.level().hasChunkAt(target.plant())
                || !owner.level().getBlockState(target.plant()).equals(target.state())
                || !HatBoatRoute.calmSurface(owner.level(), target.water())
                || HatBoatCargo.CAPACITY - boat.cargo().count() < fruitCount(target.state())
                || !boat.cargo().canStore(new ItemStack(Items.SWEET_BERRIES))) { stop(); return false; }
        Vec3 berth = HatBoatRoute.surface(owner.level(), target.water());
        if (boat.position().subtract(berth).horizontalDistanceSqr() > .36) {
            contact = 0; lastContactTick = Long.MIN_VALUE;
            if (elapsed % 20 == 1 && !boat.paddleTo(berth)) { stop(); return false; }
            return true;
        }
        boat.holdPosition();
        // The normal owner seat is at the stern. Rotate the physical hat before testing the rider's real mouth.
        float toward = SurfaceApproach.yaw(boat.position(), Vec3.atCenterOf(target.plant()));
        float stern = toward + 180;
        float turn = Mth.clamp(Mth.wrapDegrees(stern - boat.getYRot()), -4, 4);
        boat.setYRot(boat.getYRot() + turn);
        SurfaceApproach.face(owner, SurfaceApproach.yaw(owner.position(), Vec3.atCenterOf(target.plant())));
        if (Math.abs(Mth.wrapDegrees(stern - boat.getYRot())) > 5 || !mouthTouches(target.plant())) { contact = 0; lastContactTick = Long.MIN_VALUE; return true; }
        long now = owner.level().getGameTime();
        if (now == lastContactTick) return true;
        contact = lastContactTick != Long.MIN_VALUE && now == lastContactTick + 1 ? contact + 1 : 1;
        lastContactTick = now;
        if (contact < CONTACT_TICKS) return true;
        var level = owner.level(); var before = target.state(); var picked = target.plant(); int amount = fruitCount(before);
        // No reserved item exists before the final valid contact tick. Full capacity leaves the bush untouched.
        if (!level.setBlock(picked, before.setValue(SweetBerryBushBlock.AGE, 1), 3)) { stop(); return false; }
        var fruit = new ItemStack(Items.SWEET_BERRIES, amount);
        int stored = boat.cargo().store(fruit, amount, false);
        if (stored == 0) level.setBlock(picked, before, 3);
        else if (!fruit.isEmpty()) owner.spawnAtLocation(fruit); // Defend conservation if capacity ever changes mid-operation.
        if (stored > 0) owner.speak("take_food");
        stop(); retry = 40; return stored > 0;
    }

    private Target find(HatBoatEntity boat) {
        var level = owner.level(); var plants = new ArrayList<BlockPos>();
        for (var at : BlockPos.betweenClosed(boat.blockPosition().offset(-10, -1, -10), boat.blockPosition().offset(10, 1, 10))) {
            if (!level.hasChunkAt(at)) continue;
            var state = level.getBlockState(at);
            if (state.is(Blocks.SWEET_BERRY_BUSH) && state.getValue(SweetBerryBushBlock.AGE) >= 2
                    && HatBoatCargo.CAPACITY - boat.cargo().count() >= fruitCount(state)
                    && boat.cargo().canStore(new ItemStack(Items.SWEET_BERRIES))) plants.add(at.immutable());
        }
        plants.sort(Comparator.comparingDouble(at -> at.distSqr(boat.blockPosition())));
        for (var plant : plants) for (var side : Direction.Plane.HORIZONTAL) {
            var water = plant.relative(side).below();
            if (!HatBoatRoute.calmSurface(level, water)) continue;
            var berth = HatBoatRoute.surface(level, water);
            if (!level.noCollision(boat, boat.getBoundingBox().move(berth.subtract(boat.position())))) continue;
            if (boat.position().subtract(berth).horizontalDistanceSqr() > .36 && HatBoatRoute.find(boat, berth).isEmpty()) continue;
            return new Target(plant, water.immutable(), level.getBlockState(plant));
        }
        return null;
    }

    /** Mouth envelope and ray use the current rider position; the boat centre alone can never harvest a plant. */
    private boolean mouthTouches(BlockPos plant) {
        Vec3 facing = Vec3.directionFromRotation(0, owner.getYHeadRot());
        Vec3 mouth = owner.position().add(facing.scale(owner.getBbWidth() * .5)).add(0, owner.getBbHeight() * .5, 0);
        Vec3 point = new Vec3(Mth.clamp(mouth.x, plant.getX() + .1, plant.getX() + .9),
                Mth.clamp(mouth.y, plant.getY() + .1, plant.getY() + .85),
                Mth.clamp(mouth.z, plant.getZ() + .1, plant.getZ() + .9));
        if (mouth.distanceToSqr(point) > .20 * .20) return false;
        var hit = owner.level().clip(new ClipContext(owner.position().add(0, owner.getBbHeight() * .5, 0), point,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(plant);
    }
}
