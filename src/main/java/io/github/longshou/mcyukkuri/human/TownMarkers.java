package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/** The template's rotated markers give each yard a fixed pet start, owner home and food bowl. */
public final class TownMarkers {
    private static final String OWNER = "mcy_yard_owner", START = "mcy_yard_pet";
    private static final String NO_CARTON = "mcy_no_carton", SPAWNED = "mcy_yard_pet_spawned";
    private static final String HIDDEN_CORNER = "mcy_hidden_corner";
    // The owner home and start of either lot are within eight horizontal blocks of each other.
    private static final int HOME_TO_START = 8;

    private TownMarkers() {}

    public static void joining(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var entity = event.getEntity();
        if (!(entity instanceof Marker && entity.getTags().contains(START))
                && !(entity instanceof HumanNpc && entity.getTags().contains(OWNER))) return;
        var server = level.getServer();
        // The join event runs before the entity is visible to the level's queries.
        server.tell(new TickTask(server.getTickCount(), () -> {
            if (level.getEntity(entity.getUUID()) != entity) return;
            if (entity instanceof Marker marker) bind(level, marker);
            else {
                var owner = (HumanNpc) entity;
                for (var marker : level.getEntitiesOfClass(Marker.class, new AABB(owner.home()).inflate(HOME_TO_START, 4, HOME_TO_START),
                        m -> m.getTags().contains(START))) bind(level, marker);
            }
        }));
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
                npc.isAlive() && npc.streetResident() && npc.attitude() == HumanNpc.Attitude.CARING
                        && npc.getTags().contains(OWNER) && npc.home().equals(home)).stream()
                .min(Comparator.comparing(HumanNpc::getUUID)).orElse(null);
        if (owner == null) return;
        var pet = HumanScenes.spawnYardPet(level, owner, start.position());
        if (pet == null) {
            GameplayLog.event(owner, "npc-care", "action=yard_pet result=spawn_refused start={}", cell.toShortString());
            return;
        }
        var bowl = offset(start, data, "Bowl");
        owner.registerYardBowl(bowl);
        sites.getFirst().addTag(SPAWNED);
        for (var site : sites.subList(1, sites.size())) site.discard();
        start.discard();
        GameplayLog.event(pet, "npc-care", "action=yard_pet result=spawned owner={} stage={} bowl={}",
                owner.getUUID(), pet.family().stageId(), bowl.toShortString());
    }

    private static CompoundTag data(Marker marker) {
        return marker.saveWithoutId(new CompoundTag()).getCompound("data");
    }

    private static BlockPos offset(Marker marker, CompoundTag data, String key) {
        var xyz = data.getList(key, Tag.TAG_INT);
        var rotation = switch (Direction.fromYRot(marker.getYRot())) {
            case NORTH -> Rotation.NONE;
            case EAST -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            case WEST -> Rotation.COUNTERCLOCKWISE_90;
            default -> throw new IllegalStateException("Marker yaw must be horizontal");
        };
        var offset = new BlockPos(xyz.getInt(0), xyz.getInt(1), xyz.getInt(2));
        return marker.blockPosition().offset(offset.rotate(rotation));
    }

    /** Scan once for the proposed carton sites; each zone anchor is within one chunk of its bounds. */
    public static List<AABB> cartonExclusions(ServerLevel level, BlockPos origin, int radius) {
        var area = new AABB(origin).inflate(radius + 16, 8, radius + 16);
        return level.getEntitiesOfClass(Marker.class, area,
                m -> m.getTags().contains(NO_CARTON) || m.getTags().contains(HIDDEN_CORNER)).stream().map(marker -> {
            if (marker.getTags().contains(HIDDEN_CORNER)) return new AABB(marker.blockPosition());
            var data = data(marker);
            return AABB.encapsulatingFullBlocks(offset(marker, data, "Low"), offset(marker, data, "High"));
        }).toList();
    }
}
