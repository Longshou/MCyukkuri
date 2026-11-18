package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.play.PlayBlock;
import io.github.longshou.mcyukkuri.play.PlayItem;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Arrays;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/** Rotated village markers bind a yard's inhabitants and place each park's shared facility. */
public final class TownMarkers {
    private static final String OWNER = "mcy_yard_owner", START = "mcy_yard_pet";
    private static final String NO_CARTON = "mcy_no_carton", SPAWNED = "mcy_yard_pet_spawned";
    private static final String PARK_PLAY = "mcy_park_play";
    // The owner home and start of either lot are within eight horizontal blocks of each other.
    private static final int HOME_TO_START = 8;

    private TownMarkers() {}

    public static void joining(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var entity = event.getEntity();
        if (!(entity instanceof Marker && (entity.getTags().contains(START) || entity.getTags().contains(NO_CARTON)
                || entity.getTags().contains(PARK_PLAY)))
                && !(entity instanceof HumanNpc && entity.getTags().contains(OWNER))) return;
        var server = level.getServer();
        // The join event runs before the entity is visible to the level's queries.
        server.tell(new TickTask(server.getTickCount(), () -> {
            if (level.getEntity(entity.getUUID()) != entity) return;
            if (entity instanceof Marker marker) {
                if (marker.getTags().contains(PARK_PLAY)) placePark(level, marker);
                else if (marker.getTags().contains(START)) bind(level, marker);
                else for (var start : level.getEntitiesOfClass(Marker.class, new AABB(marker.blockPosition()),
                        m -> m.getTags().contains(START))) bind(level, start);
            }
            else {
                var owner = (HumanNpc) entity;
                for (var marker : level.getEntitiesOfClass(Marker.class, new AABB(owner.home()).inflate(HOME_TO_START, 4, HOME_TO_START),
                        m -> m.getTags().contains(START))) bind(level, marker);
            }
        }));
    }

    private static void placePark(ServerLevel level, Marker marker) {
        var data = data(marker);
        String id = data.getString("Kind");
        var kind = Arrays.stream(PlayBlock.Kind.values()).filter(candidate -> candidate.id.equals(id)).findFirst().orElse(null);
        var localFacing = Direction.byName(data.getString("Facing"));
        var root = marker.blockPosition();
        if (kind == null || localFacing == null || !localFacing.getAxis().isHorizontal()) {
            GameplayLog.world(level, "play", "park facility result=refused kind={} at={} facing={} reason=marker_data",
                    id, root, data.getString("Facing"));
            marker.discard();
            return;
        }
        var facing = rotation(marker).rotate(localFacing);
        if (PlayItem.positions(root, kind, facing).stream().anyMatch(pos -> !level.hasChunkAt(pos))) return;
        var facility = PlayItem.place(level, root, kind, facing);
        if (facility != null && kind == PlayBlock.Kind.POOL) facility.fill();
        marker.discard();
        GameplayLog.world(level, "play", "park facility result={} kind={} at={} facing={}",
                facility == null ? "refused" : "placed", kind, root, facing);
    }

    private static void bind(ServerLevel level, Marker start) {
        if (start.isRemoved()) return;
        var data = data(start);
        if (!data.contains("Owner", Tag.TAG_LIST)) return;
        var cell = start.blockPosition();
        var sites = level.getEntitiesOfClass(Marker.class, new AABB(cell).inflate(.1),
                m -> m.getTags().contains(NO_CARTON) && m.blockPosition().equals(cell));
        if (sites.stream().anyMatch(site -> site.getTags().contains(SPAWNED))) {
            start.discard();
            for (var site : sites) if (!site.getTags().contains(SPAWNED)) site.discard();
            return;
        }
        if (sites.isEmpty()) return;
        var home = offset(start, data, "Owner");
        var owner = level.getEntitiesOfClass(HumanNpc.class, new AABB(home).inflate(24), npc ->
                npc.isAlive() && npc.streetResident()
                        && npc.getTags().contains(OWNER) && npc.home().equals(home)).stream()
                .min(Comparator.comparing(HumanNpc::getUUID)).orElse(null);
        if (owner == null) return;
        // Each start shares a chunk with its own durable zone marker, even across a split yard.
        sites.getFirst().addTag(SPAWNED);
        for (var site : sites.subList(1, sites.size())) site.discard();
        start.discard();
        if (owner.attitude() != HumanNpc.Attitude.CARING) return;
        if (data.getInt("Slot") == 1 && !RandomSource.create(level.getSeed() ^ Mth.getSeed(home)).nextBoolean()) return;
        var pet = HumanScenes.spawnYardPet(level, owner, start.position());
        if (pet == null) {
            GameplayLog.event(owner, "npc-care", "action=yard_pet result=spawn_refused start={}", cell.toShortString());
            return;
        }
        var bowl = offset(start, data, "Bowl");
        owner.registerYardBowl(bowl);
        GameplayLog.event(pet, "npc-care", "action=yard_pet result=spawned owner={} stage={} bowl={}",
                owner.getUUID(), pet.family().stageId(), bowl.toShortString());
    }

    private static CompoundTag data(Marker marker) {
        return marker.saveWithoutId(new CompoundTag()).getCompound("data");
    }

    private static BlockPos offset(Marker marker, CompoundTag data, String key) {
        var xyz = data.getList(key, Tag.TAG_INT);
        var offset = new BlockPos(xyz.getInt(0), xyz.getInt(1), xyz.getInt(2));
        return marker.blockPosition().offset(offset.rotate(rotation(marker)));
    }

    private static Rotation rotation(Marker marker) {
        return switch (Direction.fromYRot(marker.getYRot())) {
            case NORTH -> Rotation.NONE;
            case EAST -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            case WEST -> Rotation.COUNTERCLOCKWISE_90;
            default -> throw new IllegalStateException("Marker yaw must be horizontal");
        };
    }

    static boolean yardContains(ServerLevel level, BlockPos bowl, BlockPos site) {
        return !level.getEntitiesOfClass(Marker.class, new AABB(bowl).inflate(HOME_TO_START, 4, HOME_TO_START), marker -> {
            if (!marker.getTags().contains(SPAWNED)) return false;
            var bounds = yardBounds(marker);
            return bounds.contains(bowl.getCenter()) && bounds.contains(site.getCenter());
        }).isEmpty();
    }

    private static AABB yardBounds(Marker marker) {
        var data = data(marker);
        return AABB.encapsulatingFullBlocks(offset(marker, data, "Low"), offset(marker, data, "High"));
    }

}
