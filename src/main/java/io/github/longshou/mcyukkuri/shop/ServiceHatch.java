package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.pet.PetLife;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayDeque;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The service door of a small pen (docs/FEATURES.md, 宠物店): an iron door in the wall of a display booth or stock tank. Neither the stock nor a
 * passing player can open it by hand; staff call the animal to the inside cell (its {@link Answer}) before opening the door.
 * Served means the staff member is at the outside stand and the animal is at the inside stand across the open doorway. It is
 * the one route for every hands-on service through a pen wall: a purchase or a recall fetching the animal into a basket, the care staff feeding
 * or treating it, and a keeper setting an animal out of his basket into the pen. The staff member saves its open door for reload cleanup.
 *
 * @param door  lower half of the door
 * @param in    from the door into the pen
 */
public record ServiceHatch(BlockPos door, Direction in) {
    /** Walkable cells a pen may have: larger enclosures are rooms people walk into, not pens served through a door. */
    private static final int PEN_CELLS = 16;

    /** The service door of the small pen this body stands in, or null when it stands in the open or in a walk-in room. A body pressed
     *  into the threshold wall can have its centre in the wall's cell while its feet still stand in the pen, so the cells its box covers at
     *  its own height are tried after its own; the pen stays a pen (staff wait at its door) instead of reading as open floor. */
    public static ServiceHatch of(Entity body) {
        var level = body.level();
        var own = body.blockPosition();
        var hatch = around(level, own);
        if (hatch != null || walkable(level, own)) return hatch;
        var box = body.getBoundingBox();
        for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX); x++)
            for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); z++) {
                var cell = new BlockPos(x, own.getY(), z);
                if (cell.equals(own)) continue;
                hatch = around(level, cell);
                if (hatch != null) return hatch;
            }
        return null;
    }

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

    /** Walks staff onto the stand cell itself (a plain moveTo ends a block short of it). */
    public void approach(PathfinderMob staff, double speed) {
        var nav = staff.getNavigation();
        nav.moveTo(nav.createPath(BlockPos.containing(staffStand(staff.level())), 0), speed);
    }

    /** Where an animal is served: the cell just inside the door. */
    public Vec3 animalStand() { return Vec3.atBottomCenterOf(door.relative(in)); }

    public boolean open(Level level) {
        var state = level.getBlockState(door);
        return state.is(Blocks.IRON_DOOR) && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER && state.getValue(DoorBlock.OPEN);
    }

    /** Opens or closes both halves with the vanilla door sound. Only the staff member working this door calls this. */
    public void set(ServerLevel level, HumanNpc staff, boolean open) {
        var state = level.getBlockState(door);
        if (!state.is(Blocks.IRON_DOOR) || state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER || state.getValue(DoorBlock.OPEN) == open) return;
        ((DoorBlock) state.getBlock()).setOpen(staff, level, state, door, open);
        GameplayLog.event(staff, "shop", "hatch {} door={} staff_stand={} animal_stand={}",
                open ? "opened" : "closed", door.toShortString(), GameplayLog.at(staffStand(level)), GameplayLog.at(animalStand()));
    }

    /** The staff member stands at the door: on the stand cell, square in front of the doorway. */
    public boolean attended(Entity staff) { return on(staff, staffStand(staff.level())); }

    /** The animal is at the inside stand and able to stay there while being served. */
    public boolean ready(HumanNpc staff, YukkuriEntity animal) {
        return attended(staff) && on(animal, animalStand()) && !animal.isPanicking() && !animal.isPassenger();
    }

    /** Hands-on service across the open doorway, with both bodies on their stands. */
    public boolean serves(HumanNpc staff, YukkuriEntity animal) {
        return staff.servingAt(this) && open(staff.level()) && ready(staff, animal);
    }

    /** Standing on that cell (its floor, not a block above or below). */
    private static boolean on(Entity body, Vec3 stand) {
        return Math.floor(body.getX()) == Math.floor(stand.x) && Math.floor(body.getZ()) == Math.floor(stand.z) && Math.abs(body.getY() - stand.y) <= .5;
    }

    /** A quiet animal on a shut threshold can leave it by the same supported steps as any other walk. */
    public Vec3 stepIntoPen(YukkuriEntity animal) {
        var level = animal.level();
        if (open(level) || !on(animal, animalStand()) || attendant(level) != null) return null;
        var inside = door.relative(in);
        for (Direction direction : new Direction[] {in, in.getClockWise(), in.getCounterClockWise()}) {
            var cell = inside.relative(direction);
            if (!equals(around(level, cell))) continue;
            var feet = SurfaceApproach.floor(animal, Vec3.atBottomCenterOf(cell));
            if (feet != null && SurfaceApproach.canApproach(animal, feet)) return feet;
        }
        return null;
    }

    /** The staff member calling stock from the outside stand. The door can still be shut. */
    public HumanNpc attendant(Level level) {
        var stand = staffStand(level);
        return level.getEntitiesOfClass(HumanNpc.class, new AABB(stand, stand).inflate(.5, 1, .5),
                        n -> n.isAlive() && n.servingAt(this) && attended(n))
                .stream().findFirst().orElse(null);
    }

    /**
     * The animal's side of the door, one per answer: it walks onto the cell inside the open door once, then waits there looking at the staff
     * member. Ticked by its own window answer (shop/ShopWindow) when it is free, or as its pet session director while a purchase or a keeper's
     * errand holds it (that hold keeps it from its own goals). A walk that does not arrive is not retried: the staff member's wait runs out.
     */
    public static final class Answer implements PetLife.Director, MotorOwner {
        private final ServiceHatch hatch;
        private Action walk;
        private boolean started, on = true;

        public Answer(ServiceHatch hatch) { this.hatch = hatch; }
        public ServiceHatch hatch() { return hatch; }
        /** On its way to the door. */
        public boolean walking(YukkuriEntity a) { return walk != null && a.motor().current() == walk; }

        @Override public void tick(YukkuriEntity a) {
            if (!started) {
                started = true;
                var stand = hatch.animalStand();
                double dx = a.getX() - stand.x, dz = a.getZ() - stand.z;
                var w = Walk.stand(stand, 1.1);
                if (dx * dx + dz * dz > .09 && a.motor().run(this, w)) walk = w;
                GameplayLog.event(a, "shop", "hatch answer door={} stand={} walking={} held={}", hatch.door.toShortString(), GameplayLog.at(stand),
                        walk != null, a.pet().busyWith().isEmpty() ? "-" : a.pet().busyWith());
            }
            if (walking(a)) return;
            if (walk != null) {
                if (!(a.motor().last() instanceof Outcome.Done))
                    GameplayLog.limited(a, "shop", "hatch_answer_walk", 200, "hatch answer walk ended outcome={}", a.motor().last());
                walk = null;
            }
            var staff = hatch.attendant(a.level());
            if (staff != null) a.getLookControl().setLookAt(staff, 30, 30);
        }

        @Override public void stop(YukkuriEntity a) {
            if (walk != null) a.motor().cancel(this, "hatch_done");
            walk = null; on = false;
        }

        @Override public String ownerId() { return Behaviors.WINDOW_HATCH; }
        @Override public boolean active() { return on; }
    }

}
