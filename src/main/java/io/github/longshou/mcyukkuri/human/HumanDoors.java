package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.motor.geom.DoorPassage;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Remembers the leaves opened by human navigation until the world can safely close them. */
public final class HumanDoors extends SavedData {
    // Two companion calls and the bounded wait beyond the frame.
    private static final int HOLD_LIMIT = 3 * HumanStreet.ROUTE_TICKS;
    private static final Factory<HumanDoors> FACTORY = new Factory<>(HumanDoors::new, HumanDoors::load);
    private record Opened(UUID opener, long until) {}
    private final Map<BlockPos, Opened> leaves = new HashMap<>();

    private static HumanDoors get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "mcyukkuri_human_doors");
    }

    static void opened(ServerLevel level, BlockPos at, HumanNpc opener) {
        var doors = get(level);
        doors.leaves.put(at, new Opened(opener.getUUID(), level.getGameTime() + HOLD_LIMIT));
        doors.setDirty();
    }

    static AABB frame(BlockPos at) { return new AABB(at).expandTowards(0, 1, 0); }

    public static void tick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) get(level).closeCleared(level);
    }

    private void closeCleared(ServerLevel level) {
        long now = level.getGameTime();
        for (var iterator = leaves.entrySet().iterator(); iterator.hasNext();) {
            var entry = iterator.next();
            var at = entry.getKey();
            if (!level.isPositionEntityTicking(at)) continue;
            var state = level.getBlockState(at);
            if (DoorBlock.isWoodenDoor(state) && state.getValue(DoorBlock.OPEN) && !state.getValue(DoorBlock.POWERED)) {
                var frame = frame(at);
                boolean occupied = !level.getEntitiesOfClass(Entity.class, frame, e -> e.isAlive() && !e.isSpectator()
                        && (e instanceof LivingEntity || e.canBeCollidedWith())).isEmpty();
                var owner = level.getEntity(entry.getValue().opener());
                long until = entry.getValue().until();
                boolean needed = !occupied && now < until && owner instanceof HumanNpc human && human.holdsDoor(at);
                if (!DoorPassage.close(occupied, needed, now, until)) continue;
                ((DoorBlock) state.getBlock()).setOpen(owner, level, state, at, false);
                if (owner instanceof HumanNpc human)
                    GameplayLog.event(human, "npc-care", "action=walk_door result=closed door={}", at.toShortString());
                else GameplayLog.world(level, "npc-care", "action=walk_door result=closed door={} opener={}",
                        at.toShortString(), entry.getValue().opener());
            }
            iterator.remove();
            setDirty();
        }
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var saved = new ListTag();
        leaves.forEach((at, opened) -> {
            var leaf = new CompoundTag();
            leaf.putLong("Pos", at.asLong());
            leaf.putUUID("Opener", opened.opener());
            leaf.putLong("Until", opened.until());
            saved.add(leaf);
        });
        tag.put("Leaves", saved);
        return tag;
    }

    private static HumanDoors load(CompoundTag tag, HolderLookup.Provider registries) {
        var doors = new HumanDoors();
        var saved = tag.getList("Leaves", Tag.TAG_COMPOUND);
        for (int i = 0; i < saved.size(); i++) {
            var leaf = saved.getCompound(i);
            doors.leaves.put(BlockPos.of(leaf.getLong("Pos")), new Opened(leaf.getUUID("Opener"), leaf.getLong("Until")));
        }
        return doors;
    }
}
