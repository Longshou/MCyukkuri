package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.phys.AABB;

/** Owns both leaves of each iron door and closes the doors staff or customers opened for passage. */
public final class ShopDoors {
    private record Door(ServerLevel level, List<BlockPos> leaves) {}
    private static final Map<Door, Set<UUID>> PASSING = new HashMap<>();
    private static final Map<UUID, Door> STAFF = new HashMap<>();

    private static List<BlockPos> leaves(ServerLevel level, BlockPos at) {
        var state = level.getBlockState(at);
        if (!(state.getBlock() instanceof DoorBlock block) || block.type().canOpenByHand()) return List.of();
        if (state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
            at = at.below();
            state = level.getBlockState(at);
            if (!state.is(block)) return List.of();
        }
        var facing = state.getValue(DoorBlock.FACING);
        var hinge = state.getValue(DoorBlock.HINGE);
        var other = at.relative(hinge == DoorHingeSide.LEFT ? facing.getClockWise() : facing.getCounterClockWise());
        if (!level.hasChunkAt(other)) return List.of(at);
        var paired = level.getBlockState(other);
        if (!paired.is(block) || paired.getValue(DoorBlock.FACING) != facing
                || paired.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER || paired.getValue(DoorBlock.HINGE) == hinge) return List.of(at);
        return at.compareTo(other) < 0 ? List.of(at, other) : List.of(other, at);
    }

    public static boolean inDoorway(HumanNpc npc, BlockPos at) {
        if (at == null || !(npc.level() instanceof ServerLevel level)) return false;
        return leaves(level, at).stream().anyMatch(leaf -> doorway(leaf).intersects(npc.getBoundingBox()));
    }

    static void toggle(ServerPlayer player, ShopRegistry.Entry shop, BlockPos at, boolean egress) {
        var level = player.serverLevel();
        var leaves = leaves(level, at);
        if (leaves.isEmpty()) return; // The struck upper half can outlive a missing lower block.
        var door = new Door(level, leaves);
        boolean open = leaves.stream().anyMatch(leaf -> !level.getBlockState(leaf).getValue(DoorBlock.OPEN));
        if (egress && !open) return;
        if (!open && (PASSING.containsKey(door) || leaves.stream().anyMatch(leaf -> customerInDoorway(level, leaf)))) {
            ShopTrade.say(player, "membership.door_busy");
            GameplayLog.limited(player, "shop", "door_busy", 100, "action=door result=refused shop={} reason=doorway_occupied", shop.id);
            return;
        }
        set(door, player, open);
        // The unattended sweep closes what a customer opened once they are out of the doorway, so a pen door cannot stand open for visitors.
        if (open) ShopRegistry.get(level).openedDoors(level, leaves);
        GameplayLog.event(player, "shop", "action=door result={} shop={} door={}", open ? "opened" : "closed", shop.id, leaves.getFirst());
    }

    public static void enter(HumanNpc staff, BlockPos at) {
        if (!(staff.level() instanceof ServerLevel level)) return;
        var door = new Door(level, leaves(level, at));
        if (door.equals(STAFF.get(staff.getUUID()))) return;
        leave(staff);
        STAFF.put(staff.getUUID(), door);
        PASSING.computeIfAbsent(door, key -> new HashSet<>()).add(staff.getUUID());
        var registry = ShopRegistry.get(level);
        registry.openedDoors(level, door.leaves().stream()
                .filter(leaf -> !level.getBlockState(leaf).getValue(DoorBlock.OPEN)).toList());
        set(door, staff, true);
    }

    public static boolean holds(HumanNpc staff, BlockPos at) {
        var door = STAFF.get(staff.getUUID());
        return at != null && door != null && door.leaves().contains(at);
    }

    public static void leave(HumanNpc staff) {
        var door = STAFF.remove(staff.getUUID());
        if (door == null) return;
        var passing = PASSING.get(door);
        passing.remove(staff.getUUID());
        if (!passing.isEmpty()) return;
        PASSING.remove(door);
        var registry = ShopRegistry.get(door.level());
        if (door.leaves().stream().anyMatch(leaf -> customerInDoorway(door.level(), leaf))) return;
        var owned = door.leaves().stream().filter(leaf -> registry.staffOpenedDoor(door.level(), leaf)).toList();
        set(new Door(door.level(), owned), staff, false);
    }

    private static void set(Door door, Entity actor, boolean open) {
        for (var leaf : door.leaves()) {
            if (!door.level().hasChunkAt(leaf)) continue;
            var state = door.level().getBlockState(leaf);
            if (state.getBlock() instanceof DoorBlock block && !block.type().canOpenByHand())
                block.setOpen(actor, door.level(), state, leaf, open);
            if (!open) ShopRegistry.get(door.level()).closedDoor(door.level(), leaf);
        }
    }

    static void closeUnattended(ServerLevel level, Set<BlockPos> leaves) {
        for (var leaf : List.copyOf(leaves)) {
            if (!level.hasChunkAt(leaf) || PASSING.keySet().stream().anyMatch(d -> d.level() == level && d.leaves().contains(leaf))) continue;
            var pair = leaves(level, leaf);
            if (pair.stream().anyMatch(at -> customerInDoorway(level, at))) continue;
            set(new Door(level, pair.stream().filter(leaves::contains).toList()), null, false);
            if (pair.isEmpty()) ShopRegistry.get(level).closedDoor(level, leaf);
        }
    }

    private static boolean customerInDoorway(ServerLevel level, BlockPos leaf) {
        return !level.getEntitiesOfClass(ServerPlayer.class, doorway(leaf), player -> !player.isSpectator()).isEmpty();
    }

    private static AABB doorway(BlockPos leaf) { return new AABB(leaf).expandTowards(0, 1, 0).inflate(.35, 0, .35); }

    static void clear() { PASSING.clear(); STAFF.clear(); }
    private ShopDoors() {}
}
