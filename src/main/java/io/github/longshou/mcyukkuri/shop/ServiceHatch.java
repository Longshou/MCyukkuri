package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayDeque;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The service door of a small pen (docs/FEATURES.md, 宠物店): an iron door in the wall of a display booth or stock tank. Neither the stock nor a
 * passing player can open it by hand; staff open it to serve the animal behind it and close it again. Served means: the staff member stands on
 * the floor just outside the door, the door is open, and the animal stands on the cell just inside it, in plain sight through the doorway. It is
 * the one route for every hands-on service through a pen wall: a purchase or a recall fetching the animal into a basket, the care staff feeding
 * or treating it, and a keeper setting an animal out of his basket into the pen. Found from the blocks each time; nothing is saved.
 *
 * @param door  lower half of the door
 * @param in    from the door into the pen
 */
public record ServiceHatch(BlockPos door, Direction in) {
    /** Walkable cells a pen may have: larger enclosures are rooms people walk into, not pens served through a door. */
    private static final int PEN_CELLS = 16;
    /** How near the staff stand and the animal stand count as standing there (blocks, horizontal). */
    private static final double STAFF_AT = 1.0, ANIMAL_AT = .75;

    /** The service door of the small pen this body stands in, or null when it stands in the open or in a walk-in room. */
    public static ServiceHatch of(Entity body) { return around(body.level(), body.blockPosition()); }

    /** The service door of the small pen containing {@code cell}: a flood fill over walkable cells at that height (pen furniture counts as
     *  walkable), closed within {@link #PEN_CELLS}, with an iron door beside it. */
    public static ServiceHatch around(Level level, BlockPos cell) {
        var seen = new HashSet<BlockPos>();
        var todo = new ArrayDeque<BlockPos>();
        if (!walkable(level, cell)) return null;
        seen.add(cell.immutable()); todo.add(cell.immutable());
        ServiceHatch found = null;
        while (!todo.isEmpty()) {
            var at = todo.poll();
            for (var d : Direction.Plane.HORIZONTAL) {
                var next = at.relative(d);
                if (seen.contains(next)) continue;
                var state = level.getBlockState(next);
                if (state.is(Blocks.IRON_DOOR) && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                    if (found == null) found = new ServiceHatch(next.immutable(), d.getOpposite());
                    continue;
                }
                if (!walkable(level, next)) continue;
                if (seen.size() >= PEN_CELLS) return null; // open floor or a room
                seen.add(next.immutable()); todo.add(next.immutable());
            }
        }
        return found;
    }

    /** Room for a small body: nothing to bump into but pen furniture, headroom above and a floor below. */
    private static boolean walkable(Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        boolean free = state.getBlock() instanceof CareBlock || state.getCollisionShape(level, pos).isEmpty();
        return free && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                && !level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty();
    }

    /** Where staff stand to work the door: the floor outside it (a booth's door sits a block above the aisle). */
    public Vec3 staffStand(Level level) {
        var out = door.relative(in.getOpposite());
        for (int down = 0; down < 2 && level.getBlockState(out.below()).getCollisionShape(level, out.below()).isEmpty(); down++) out = out.below();
        return Vec3.atBottomCenterOf(out);
    }

    /** Where an animal is served: the cell just inside the door. */
    public Vec3 animalStand() { return Vec3.atBottomCenterOf(door.relative(in)); }

    public boolean open(Level level) {
        var state = level.getBlockState(door);
        return state.is(Blocks.IRON_DOOR) && state.getValue(DoorBlock.OPEN);
    }

    /** Opens or closes it the way a hand does (both halves, the vanilla door sound); {@code staff} may be gone already when it closes. */
    public void set(ServerLevel level, Entity staff, boolean open) {
        var state = level.getBlockState(door);
        if (!state.is(Blocks.IRON_DOOR) || state.getValue(DoorBlock.OPEN) == open) return;
        ((DoorBlock) state.getBlock()).setOpen(staff, level, state, door, open);
        String line = "hatch {} door={} staff_stand={} animal_stand={}";
        Object[] values = {open ? "opened" : "closed", door.toShortString(), GameplayLog.at(staffStand(level)), GameplayLog.at(animalStand())};
        if (staff == null) GameplayLog.world(level, "shop", line, values); else GameplayLog.event(staff, "shop", line, values);
    }

    /** The staff member stands at the door. */
    public boolean attended(Entity staff) {
        var stand = staffStand(staff.level());
        double dx = staff.getX() - stand.x, dz = staff.getZ() - stand.z;
        return dx * dx + dz * dz <= STAFF_AT * STAFF_AT && Math.abs(staff.getY() - stand.y) <= 1.5;
    }

    /** Served through this door right now: open, staff at it, the animal on the cell inside it and seen through the doorway. */
    public boolean serves(LivingEntity staff, YukkuriEntity animal) {
        var stand = animalStand();
        double dx = animal.getX() - stand.x, dz = animal.getZ() - stand.z;
        return open(staff.level()) && attended(staff) && dx * dx + dz * dz <= ANIMAL_AT * ANIMAL_AT
                && Math.abs(animal.getY() - stand.y) <= .5 && staff.hasLineOfSight(animal);
    }

    /** A staff member standing at this open door, or null. */
    public HumanNpc attendant(Level level) {
        if (!open(level)) return null;
        var stand = staffStand(level);
        return level.getEntitiesOfClass(HumanNpc.class, new AABB(stand, stand).inflate(STAFF_AT, 1.5, STAFF_AT), n -> n.isAlive() && attended(n))
                .stream().findFirst().orElse(null);
    }

    /** Closes any service door a staff member leaves standing open at this spot (its death or removal). */
    public static void closeBehind(ServerLevel level, Entity staff) {
        for (var pos : BlockPos.betweenClosed(staff.blockPosition().offset(-2, -1, -2), staff.blockPosition().offset(2, 2, 2))) {
            var state = level.getBlockState(pos);
            if (!state.is(Blocks.IRON_DOOR) || state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER || !state.getValue(DoorBlock.OPEN)) continue;
            ((DoorBlock) state.getBlock()).setOpen(staff, level, state, pos, false);
            GameplayLog.event(staff, "shop", "hatch closed door={} reason=staff_gone", pos.toShortString());
        }
    }
}
