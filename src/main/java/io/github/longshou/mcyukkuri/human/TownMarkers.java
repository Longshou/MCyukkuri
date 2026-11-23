package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.play.PlayBlock;
import io.github.longshou.mcyukkuri.play.PlayItem;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.settlement.ShelterPlacement;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/** Rotated village markers bind a yard's inhabitants and place each park's shared facility. */
public final class TownMarkers {
    private static final String OWNER = "mcy_yard_owner", START = "mcy_yard_pet";
    private static final String NO_CARTON = "mcy_no_carton", SPAWNED = "mcy_yard_pet_spawned";
    private static final String PARK_PLAY = "mcy_park_play";
    private static final String SHELTER_SUPPLY = "mcy_shelter_supply";
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
        if (data.contains("Crate", Tag.TAG_LIST)) owner.registerYardCrate(offset(start, data, "Crate"));
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

    public record BadgeCounter(Marker marker, BlockPos post, BlockPos podium, BlockPos view, Direction front) {
        public boolean present(ServerLevel level) {
            var desk = post.relative(front);
            return !marker.isRemoved() && level.hasChunkAt(desk)
                    && level.getBlockState(desk).isFaceSturdy(level, desk, Direction.UP, SupportType.FULL);
        }

        public boolean publicSide(Vec3 player) {
            return player.subtract(post.getCenter()).dot(Vec3.atLowerCornerOf(front.getNormal())) > 1;
        }
    }

    public static BadgeCounter badgeCounter(ServerLevel level, BlockPos post) {
        return level.getEntitiesOfClass(Marker.class, new AABB(post).inflate(24, 4, 24),
                        marker -> marker.getTags().contains("mcy_badge_hall")).stream()
                .sorted(Comparator.comparingDouble((Marker marker) -> marker.distanceToSqr(post.getCenter()))
                        .thenComparing(Marker::getUUID))
                .map(marker -> {
                    var data = data(marker);
                    if (Stream.of("Clerk", "View").anyMatch(key -> data.getList(key, Tag.TAG_INT).size() != 3)) return null;
                    var podium = data.getList("Podium", Tag.TAG_INT).size() == 3 ? offset(marker, data, "Podium") : null;
                    return new BadgeCounter(marker, offset(marker, data, "Clerk"), podium,
                            offset(marker, data, "View"), rotation(marker).rotate(Direction.NORTH));
                })
                .filter(site -> site != null && site.post().equals(post)).findFirst().orElse(null);
    }

    /** Four walkers fit on the open side of the canopy with room left for through traffic. */
    private static final int PLAZA_CAPACITY = 4;

    public static boolean plazaLeisure(ServerLevel level) {
        // Leave the last half-minute of daylight for the walk home.
        return level.isDay() && !level.isRaining() && Math.floorMod(level.getDayTime(), HumanStreet.DAY) < 11400;
    }

    public static int plazaPause(double wariness) {
        // An eight- to sixteen-second pause gives nearby peers time to notice each other.
        return 320 - (int) (160 * wariness);
    }

    public record BadgePlaza(Marker marker, AABB paving) {
        public boolean present(ServerLevel level) {
            if (marker.isRemoved()) return false;
            // The two authored three-block benches, east of the clear edge, rotate with the lot.
            var root = marker.blockPosition();
            var facing = rotation(marker);
            for (var offset : BlockPos.betweenClosed(2, 0, -1, 4, 0, 1)) {
                if (offset.getZ() == 0) continue;
                var bench = root.offset(offset.rotate(facing));
                if (level.hasChunkAt(bench) && level.getBlockState(bench).is(HumanWorld.WOODEN_BENCH.get())) return true;
            }
            return false;
        }

        public List<YukkuriEntity> company(PathfinderMob visitor) {
            return visitor.level().getEntitiesOfClass(YukkuriEntity.class, paving,
                    y -> y != visitor && y.isAlive() && !y.isPassenger() && !y.life().isSleeping()
                            && !y.profile().dangerToYukkuri() && visitor.hasLineOfSight(y));
        }

        public Stream<BlockPos> stands(ServerLevel level, PathfinderMob visitor, int partySize) {
            // Count bodies and real paths already headed here. The path controllers retain ownership of their destinations.
            var traffic = level.getEntitiesOfClass(PathfinderMob.class, paving.inflate(30, 4, 30),
                    mob -> mob != visitor && mob.getLeashHolder() != visitor && mob.isAlive() && !mob.isPassenger()
                            && (mob instanceof YukkuriEntity || mob instanceof HumanNpc)
                            && (paving.contains(mob.position()) || aimsAtPlaza(mob)
                                    || mob.getLeashHolder() instanceof PathfinderMob leader && aimsAtPlaza(leader)));
            if (traffic.size() + partySize > PLAZA_CAPACITY) return Stream.empty();
            return BlockPos.betweenClosedStream(BlockPos.containing(paving.minX, paving.minY, paving.minZ),
                            BlockPos.containing(paving.maxX - 1, paving.minY, paving.maxZ - 1))
                    .map(BlockPos::immutable)
                    .filter(pos -> {
                        if (!level.hasChunkAt(pos) || !level.getFluidState(pos).isEmpty()
                                || !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) return false;
                        var body = visitor.getBoundingBox().move(Vec3.atBottomCenterOf(pos).subtract(visitor.position()));
                        return level.noCollision(visitor, body) && level.getEntities(visitor, body, entity -> entity.isPickable()).isEmpty()
                                && traffic.stream().noneMatch(mob -> aimsAtPlaza(mob) && body.intersects(mob.getBoundingBox()
                                .move(Vec3.atBottomCenterOf(mob.getNavigation().getTargetPos()).subtract(mob.position()))));
                    })
                    .sorted(Comparator.comparingDouble(pos -> visitor.distanceToSqr(Vec3.atBottomCenterOf(pos))));
        }

        private boolean aimsAtPlaza(PathfinderMob mob) {
            var nav = mob.getNavigation();
            var target = nav.getTargetPos();
            return !nav.isDone() && target != null && paving.contains(target.getCenter());
        }
    }

    public static BadgePlaza badgePlaza(ServerLevel level, PathfinderMob visitor, int range) {
        var here = visitor.blockPosition();
        return level.getEntitiesOfClass(Marker.class, new AABB(here).inflate(range, 3, range),
                        marker -> marker.getTags().contains("mcy_badge_plaza")
                                && here.distSqr(marker.blockPosition()) <= range * range).stream()
                .sorted(Comparator.comparingDouble((Marker marker) -> visitor.distanceToSqr(marker)).thenComparing(Marker::getUUID))
                .map(marker -> {
                    var root = marker.blockPosition();
                    var facing = rotation(marker);
                    return new BadgePlaza(marker, AABB.encapsulatingFullBlocks(
                            root.offset(new BlockPos(-3, 0, -3).rotate(facing)),
                            root.offset(new BlockPos(0, 2, 1).rotate(facing))));
                })
                .filter(site -> site.present(level)).findFirst().orElse(null);
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

    record ShelterSupply(Marker marker, BlockPos crate, ShelterPlacement.Plan bay, AABB grounds) {
        boolean allowsWalk(HumanNpc npc, Path path) {
            if (!grounds.contains(path.getEndNode().asBlockPos().getCenter())) return false;
            if (!grounds.contains(npc.position())) return true; // A displaced volunteer can walk back in.
            for (int i = 0; i < path.getNodeCount(); i++)
                if (!grounds.contains(path.getNodePos(i).getCenter())) return false;
            return true;
        }
    }

    static ShelterSupply shelterSupply(ServerLevel level, BlockPos home) {
        for (var marker : level.getEntitiesOfClass(Marker.class, new AABB(home).inflate(10, 1, 10),
                candidate -> candidate.getTags().contains(SHELTER_SUPPLY))) {
            var data = data(marker);
            if (!home.equals(offset(marker, data, "Sister")) && !home.equals(offset(marker, data, "Brother"))) continue;
            var bay = new ShelterPlacement.Plan(offset(marker, data, "Bay"), ShelterBlock.Kind.CARDBOARD,
                    rotation(marker).rotate(Direction.WEST));
            return new ShelterSupply(marker, offset(marker, data, "Crate"), bay, yardBounds(marker, data));
        }
        return null;
    }

    static boolean yardContains(ServerLevel level, BlockPos bowl, BlockPos site) {
        return yards(level, bowl).anyMatch(bounds -> bounds.contains(site.getCenter()));
    }

    static Stream<AABB> yards(ServerLevel level, BlockPos bowl) {
        return level.getEntitiesOfClass(Marker.class, new AABB(bowl).inflate(HOME_TO_START, 4, HOME_TO_START),
                marker -> marker.getTags().contains(SPAWNED)).stream().map(marker -> yardBounds(marker, data(marker)))
                .filter(bounds -> bounds.contains(bowl.getCenter()));
    }

    private static AABB yardBounds(Marker marker, CompoundTag data) {
        return AABB.encapsulatingFullBlocks(offset(marker, data, "Low"), offset(marker, data, "High"));
    }

}
