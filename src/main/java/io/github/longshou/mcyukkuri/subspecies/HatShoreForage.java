package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.subspecies.boat.HullHelm.Purpose;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.settlement.Grazing;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.server.level.ServerLevel;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A captain sails to a real shore plant, turns the stern alongside it, and picks at actual mouth contact. */
public final class HatShoreForage {
    public static final int CONTACT_TICKS = 36;
    private record Target(BlockPos plant, BlockPos water, BlockState state) {}
    private final YukkuriEntity owner;
    private Target target;
    private int elapsed, contact, retry;
    private long lastContactTick = Long.MIN_VALUE;
    private String reason = "idle";
    private int foundPlants, rejectedWater, rejectedCollision, rejectedRoute;

    public HatShoreForage(YukkuriEntity owner) { this.owner = owner; }
    public boolean active() { return target != null; }
    public boolean harvesting() { return contact > 0; }
    public String activity() { return harvesting() ? Behaviors.BOAT_GATHER : Behaviors.BOAT_PADDLE; }
    public void stop() { target = null; elapsed = contact = 0; lastContactTick = Long.MIN_VALUE; }
    private ItemStack yieldFor(BlockState state) {
        ItemStack food = ItemStack.EMPTY;
        if (state.is(Blocks.SWEET_BERRY_BUSH) && state.getValue(SweetBerryBushBlock.AGE) >= 2)
            food = new ItemStack(Items.SWEET_BERRIES, state.getValue(SweetBerryBushBlock.AGE) == 3 ? 2 : 1);
        else if (Grazing.edible(state)) food = Grazing.yield(state);
        else if (Grazing.ripe(state)) {
            if (state.is(Blocks.WHEAT)) food = new ItemStack(Items.WHEAT);
            else if (state.is(Blocks.CARROTS)) food = new ItemStack(Items.CARROT);
            else if (state.is(Blocks.POTATOES)) food = new ItemStack(Items.POTATO);
            else if (state.is(Blocks.BEETROOTS)) food = new ItemStack(Items.BEETROOT);
        }
        return owner.life().acceptsFood(food) ? food : ItemStack.EMPTY;
    }
    private int fruitCount(BlockState state) { return yieldFor(state).getCount(); }
    private boolean harvest(BlockPos at, BlockState state) {
        var level = (ServerLevel) owner.level();
        if (state.is(Blocks.SWEET_BERRY_BUSH)) return level.setBlock(at, state.setValue(SweetBerryBushBlock.AGE, 1), 3);
        return Grazing.edible(state) ? Grazing.graze(level, at) : Grazing.graze(level, at, state);
    }

    /** Returns true while travelling or picking. Call only after urgent care/transport/physiology have yielded. */
    public boolean tick(HatBoatEntity boat) {
        if (owner.level().isClientSide || !owner.isAlive() || owner.isNoAi() || owner.isPanicking()
                || owner.life().isSleeping() || owner.life().mealTicks() > 0 || owner.family().laboring()
                || owner.getVehicle() != boat || !owner.getUUID().equals(boat.ownerYukkuri())
                || !owner.profile().aquatic() || !boat.isAfloat() || boat.flooding() > 0
                || !boat.cargo().paddle() || !EventHooks.canEntityGrief(owner.level(), owner)) { reason = "unavailable"; stop(); return false; }
        if (target == null) {
            if (retry-- > 0) { reason = "retry"; return false; }
            retry = 40; target = find(boat); elapsed = contact = 0;
            if (target == null) { reason = "no_reachable_crop"; return false; }
        }
        if (++elapsed > 500 || !owner.level().hasChunkAt(target.plant())
                || !owner.level().getBlockState(target.plant()).equals(target.state())
                || !HatBoatRoute.calmSurface(owner.level(), target.water())
                || !owner.aquaticLife().familyLife().allowsDestination(boat,berth(target.plant(),target.water(),boat))
                || boat.cargo().remainingCapacity() < fruitCount(target.state())
                || boat.cargoRoom() < fruitCount(target.state())
                || !boat.cargo().canStore(yieldFor(target.state()))) { reason = "source_or_capacity_changed"; boat.helm().drift(owner, Purpose.CARGO); stop(); return false; }
        Vec3 berth = berth(target.plant(), target.water(),boat);
        if (boat.position().subtract(berth).horizontalDistanceSqr() > .0025) {
            reason = "sailing_to_shore";
            contact = 0; lastContactTick = Long.MIN_VALUE;
            if (elapsed % 20 == 1 && !sailToBerth(boat, berth)) { reason = "unreachable_berth"; boat.helm().drift(owner, Purpose.CARGO); stop(); return false; }
            return true;
        }
        // The normal owner seat is at the stern. Rotate the physical hat before testing the rider's real mouth.
        float toward = SurfaceApproach.yaw(boat.position(), Vec3.atCenterOf(target.plant()));
        float stern = toward + 180;
        boat.helm().berth(owner, Purpose.CARGO, boat.position(), stern, 0, 4f);
        SurfaceApproach.face(owner, SurfaceApproach.yaw(owner.position(), Vec3.atCenterOf(target.plant())));
        if (Math.abs(Mth.wrapDegrees(stern - boat.getYRot())) > 5 || !mouthTouches(target.plant())) {
            reason = Math.abs(Mth.wrapDegrees(stern - boat.getYRot())) > 5 ? "turning_stern" : "mouth_out_of_reach_or_occluded";
            contact = 0; lastContactTick = Long.MIN_VALUE; return true;
        }
        reason = "mouth_contact";
        long now = owner.level().getGameTime();
        if (now == lastContactTick) return true;
        contact = lastContactTick != Long.MIN_VALUE && now == lastContactTick + 1 ? contact + 1 : 1;
        lastContactTick = now;
        if (contact < CONTACT_TICKS) return true;
        var level = owner.level(); var before = target.state(); var picked = target.plant(); int amount = fruitCount(before);
        // No reserved item exists before the final valid contact tick. Full capacity leaves the bush untouched.
        if (!harvest(picked, before)) { stop(); return false; }
        var fruit = yieldFor(before);
        int stored = boat.cargo().store(fruit, amount, false);
        if (stored == 0) level.setBlock(picked, before, 3);
        else if (!fruit.isEmpty()) owner.spawnAtLocation(fruit); // Defend conservation if capacity ever changes mid-operation.
        if (stored > 0) {
            owner.speak("take_food");
            GameplayLog.event(owner, "forage", "shore plant harvested source={} before={} after={} stored={} hat={}",
                    picked, before, level.getBlockState(picked), stored, boat.getUUID());
        }
        reason = stored > 0 ? "picked_real_fruit" : "cargo_rejected_rollback";
        stop(); retry = 40; return stored > 0;
    }

    private Target find(HatBoatEntity boat) {
        var level = owner.level(); var plants = new ArrayList<BlockPos>();
        foundPlants = rejectedWater = rejectedCollision = rejectedRoute = 0;
        for (var at : BlockPos.betweenClosed(boat.blockPosition().offset(-10, -1, -10), boat.blockPosition().offset(10, 1, 10))) {
            if (!level.hasChunkAt(at)) continue;
            var state = level.getBlockState(at);
            var food = yieldFor(state);
            if (!food.isEmpty() && boat.cargo().remainingCapacity() >= food.getCount()
                    && boat.cargoRoom() >= food.getCount() && boat.cargo().canStore(food)) plants.add(at.immutable());
        }
        foundPlants=plants.size();
        plants.sort(Comparator.comparingDouble(at -> at.distSqr(boat.blockPosition()) + (Grazing.edible(level.getBlockState(at)) ? 200 : 0)));
        for (var plant : plants) for (var side : Direction.Plane.HORIZONTAL) {
            var water = plant.relative(side).below();
            if (!HatBoatRoute.calmSurface(level, water)) { rejectedWater++; continue; }
            var berth = berth(plant, water, boat);
            if (!owner.aquaticLife().familyLife().allowsDestination(boat,berth)) { rejectedRoute++; continue; }
            if (!level.noCollision(boat, boat.getBoundingBox().move(berth.subtract(boat.position())))) { rejectedCollision++; continue; }
            var route = HatBoatRoute.find(boat, berth,p -> owner.aquaticLife().familyLife().allowsDestination(boat,p));
            if (route.stream().anyMatch(p -> !owner.aquaticLife().familyLife().allowsDestination(boat,p))
                    || route.isEmpty() && !finalLegClear(boat,water,berth)) { rejectedRoute++; continue; }
            return new Target(plant, water.immutable(), level.getBlockState(plant));
        }
        return null;
    }

    /** Water sits below the solid bank top. Keep the entire physical hull off the bank, not just its centre. */
    private Vec3 berth(BlockPos plant, BlockPos water,HatBoatEntity boat) {
        var surface = HatBoatRoute.surface(owner.level(), water);
        var shore = Vec3.atCenterOf(plant).subtract(surface).multiply(1,0,1).normalize();
        return surface.add(shore.scale(.5-boat.getBbWidth()*.5-.03));
    }

    private boolean sailToBerth(HatBoatEntity boat, Vec3 berth) {
        if (!owner.aquaticLife().familyLife().allowsDestination(boat,berth)) return false;
        var route = new ArrayList<>(HatBoatRoute.find(boat, berth,p -> owner.aquaticLife().familyLife().allowsDestination(boat,p)));
        if (route.stream().anyMatch(p -> !owner.aquaticLife().familyLife().allowsDestination(boat,p))) return false;
        if (route.isEmpty() && !finalLegClear(boat,target.water(),berth)) return false;
        // Surface BFS ends at a cell centre. This final waypoint stays inside that same calm water cell;
        // all movement/collisions still run through the actual hat paddling physics.
        route.add(berth);
        return boat.helm().route(owner, Purpose.CARGO, route,.0025);
    }

    /** Grid-centre BFS may reject the shore cell's centre even though its outward-offset berth is clear. */
    private boolean finalLegClear(HatBoatEntity boat,BlockPos water,Vec3 berth) {
        var current=BlockPos.containing(boat.getX(),boat.getY()-.12,boat.getZ());
        if(current.distManhattan(water)>1 || boat.position().subtract(berth).horizontalDistanceSqr()>2.25)return false;
        var level=owner.level(); int steps=Math.max(1,(int)Math.ceil(boat.position().distanceTo(berth)/.10));
        for(int i=1;i<=steps;i++) {
            var at=boat.position().lerp(berth,(double)i/steps);
            var cell=BlockPos.containing(at.x,at.y-.12,at.z);
            if(!owner.aquaticLife().familyLife().allowsDestination(boat,at)
                    ||!HatBoatRoute.calmSurface(level,cell)||!level.noCollision(boat,boat.getBoundingBox().move(at.subtract(boat.position()))))return false;
        }
        return true;
    }

    public Map<String,Object> debug(HatBoatEntity boat) {
        var value = new LinkedHashMap<String,Object>();
        value.put("reason",reason); value.put("elapsed",elapsed); value.put("contact",contact); value.put("retry",retry);
        value.put("boat",List.of(boat.getX(),boat.getY(),boat.getZ())); value.put("boatYaw",boat.getYRot());
        value.put("parent",List.of(owner.getX(),owner.getY(),owner.getZ())); value.put("bodyYaw",owner.yBodyRot); value.put("headYaw",owner.getYHeadRot());
        value.put("paddling",boat.paddling()); value.put("paddle",boat.cargo().paddle()); value.put("cargo",boat.cargo().count());
        value.put("cargoRoom",boat.cargoRoom());
        value.put("parentLocal",boat.toLocal(owner.position()).toString()); value.put("parentWidth",owner.getBbWidth()); value.put("parentHeight",owner.getBbHeight());
        value.put("aboard",owner.getVehicle()==boat); value.put("sleeping",owner.life().isSleeping()); value.put("meal",owner.life().mealTicks());
        value.put("nutrition",owner.life().nutrition()); value.put("energy",owner.life().energy()); value.put("flooding",boat.flooding());
        value.put("mobGriefing",EventHooks.canEntityGrief(owner.level(),owner));
        value.put("hullWidth",boat.getBbWidth()); value.put("hullCollisionFree",owner.level().noCollision(boat,boat.getBoundingBox()));
        value.put("foundPlants",foundPlants); value.put("rejectedWater",rejectedWater); value.put("rejectedCollision",rejectedCollision); value.put("rejectedRoute",rejectedRoute);
        if(target!=null) {
            value.put("plant",target.plant().toShortString()); value.put("source",owner.level().getBlockState(target.plant()).toString());
            value.put("berth",berth(target.plant(),target.water(),boat).toString());
            Vec3 mouth=mouth(); Vec3 point=point(target.plant(),mouth);
            value.put("mouth",mouth.toString()); value.put("touch",point.toString()); value.put("mouthGap",mouth.distanceTo(point));
            value.put("contactValid",mouthTouches(target.plant()));
        }
        return value;
    }

    /** Mouth envelope and ray use the current rider position; the boat centre alone can never harvest a plant. */
    private boolean mouthTouches(BlockPos plant) {
        Vec3 mouth=mouth(),point=point(plant,mouth);
        if (mouth.distanceToSqr(point) > .20 * .20) return false;
        var hit = owner.level().clip(new ClipContext(owner.position().add(0, owner.getBbHeight() * .5, 0), point,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(plant);
    }
    private Vec3 mouth() {
        Vec3 facing = Vec3.directionFromRotation(0, owner.getYHeadRot());
        return owner.position().add(facing.scale(owner.getBbWidth() * .5)).add(0, owner.getBbHeight() * .5, 0);
    }
    private Vec3 point(BlockPos plant,Vec3 mouth) {
        return new Vec3(Mth.clamp(mouth.x,plant.getX()+.1,plant.getX()+.9),Mth.clamp(mouth.y,plant.getY()+.1,plant.getY()+.85),Mth.clamp(mouth.z,plant.getZ()+.1,plant.getZ()+.9));
    }
}
