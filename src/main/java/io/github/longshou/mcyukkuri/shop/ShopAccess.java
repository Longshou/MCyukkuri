package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Staff floor areas and their exits, in the template coordinate system. */
final class ShopAccess {
    private record Area(AABB floor, boolean pen, BlockPos exit, Direction out) {}
    record Hatch(BlockPos door, BlockPos outside, Vec3 inside, AABB room, boolean pen) {}
    enum Service {
        AVAILABLE, DAY;

        String message() { return "membership.service." + ordinal(); }
    }
    private static final Map<UUID, UUID> SPOKEN = new HashMap<>();

    private static final List<Area> PET = List.of(
            area(2, 4, 8, 7, true, 5, 8, Direction.SOUTH),
            area(16, 4, 22, 7, true, 19, 8, Direction.SOUTH),
            area(2, 13, 22, 16, false, 5, 12, Direction.NORTH),
            area(2, 13, 22, 16, false, 18, 12, Direction.NORTH),
            area(2, 18, 15, 29, false, 16, 21, Direction.EAST),
            area(17, 18, 22, 29, false, 17, 17, Direction.NORTH));
    private static final List<Area> ONI = List.of(
            area(2, 4, 6, 6, true, 4, 7, Direction.SOUTH),
            area(16, 8, 19, 10, true, 20, 8, Direction.EAST),
            area(16, 12, 19, 14, true, 20, 12, Direction.EAST),
            area(16, 16, 19, 18, true, 20, 16, Direction.EAST),
            // x3 is a leaf in both the original and the reframed counter opening.
            area(2, 17, 14, 18, false, 3, 16, Direction.NORTH),
            area(21, 8, 22, 19, false, 21, 20, Direction.SOUTH),
            area(2, 19, 22, 23, false, 7, 19, Direction.NORTH));

    private static Area area(int x0, int z0, int x1, int z1, boolean pen, int x, int z, Direction out) {
        return new Area(new AABB(x0, 2.5, z0, x1 + 1, 8, z1 + 1), pen, new BlockPos(x, 3, z), out);
    }

    static Hatch hatch(ServerLevel level, ShopRegistry.Entry shop, YukkuriEntity animal) {
        if (shop.legacy()) return ShopRegistry.legacyHatch(level, shop, animal);
        var rotation = ShopRegistry.authoredRotation(shop);
        if (rotation == null) return null;
        Hatch closest = null;
        double best = Double.MAX_VALUE;
        for (var area : shop.kind == ShopKind.PET ? PET : ONI) {
            var floor = area.floor();
            var room = new AABB(world(shop, rotation, new Vec3(floor.minX, floor.minY, floor.minZ)),
                    world(shop, rotation, new Vec3(floor.maxX, floor.maxY, floor.maxZ)));
            if (!room.intersects(animal.getBoundingBox())) continue;
            var out = rotation.rotate(area.out());
            var first = BlockPos.containing(world(shop, rotation, area.exit().getCenter()));
            if (!ShopDoors.ironLower(level, first)) continue;
            for (var door : ShopDoors.leaves(level, first)) {
                double distance = animal.distanceToSqr(door.getCenter());
                if (distance >= best) continue;
                best = distance;
                closest = new Hatch(door, door.relative(out), Vec3.atBottomCenterOf(door.relative(out.getOpposite())),
                        room, area.pen());
            }
        }
        return closest;
    }

    static Service service(ServerLevel level, ShopRegistry.Entry shop, ServerPlayer player) {
        return shop.refusesToday(level, player.getUUID()) ? Service.DAY : Service.AVAILABLE;
    }


    static boolean refuseService(ServerLevel level, ShopRegistry.Entry shop, ServerPlayer player) {
        var refusal = service(level, shop, player);
        if (refusal == Service.AVAILABLE) return false;
        ShopTrade.say(player, refusal.message());
        GameplayLog.limited(player, "shop", "service_refused", 100,
                "action=service result=refused shop={} reason={}", shop.id, refusal);
        return true;
    }

    static void offend(ServerLevel level, ShopRegistry.Entry shop, ServerPlayer player, String what) {
        var id = player.getUUID();
        if (player.isCreative() || shop.refusesToday(level, id)) return;
        boolean second = shop.id.equals(SPOKEN.put(id, shop.id));
        if (second) {
            SPOKEN.remove(id);
            ShopRegistry.get(level).refuseToday(level, shop, id);
        }
        GameplayLog.event(player, "shop", "action=offense result={} shop={} player={} what={}",
                second ? "banned" : "warned", shop.id, id, what);
        ShopStaff.warn(level, shop, player);
    }

    static void warn(HumanNpc staff, ShopRegistry.Entry shop, ServerPlayer player) {
        boolean banned = shop.refusesToday(player.serverLevel(), player.getUUID());
        ShopTrade.say(player, banned ? "protect.banned" : "protect.warn", staff.getDisplayName());
        GameplayLog.event(staff, "shop", "action=offense result=spoken shop={} player={} response={}",
                shop.id, player.getUUID(), banned ? "banned" : "warned");
    }

    static void playerTick(ServerPlayer player, ShopRegistry registry) {
        var id = player.getUUID();
        var shopId = SPOKEN.get(id);
        if (shopId == null) return;
        var shop = registry.shopAt(player.serverLevel(), player.position());
        if (shop == null || !shopId.equals(shop.id)) SPOKEN.remove(id);
    }

    static void clear() { SPOKEN.clear(); }

    static void leaving(ServerPlayer player) { SPOKEN.remove(player.getUUID()); }
    private static int clerkX(ShopRegistry.Entry shop) { return shop.kind == ShopKind.PET ? 12 : 9; }
    private static int clerkZ(ShopRegistry.Entry shop) { return shop.kind == ShopKind.PET ? 14 : 17; }

    private static Vec3 world(ShopRegistry.Entry shop, Rotation rotation, Vec3 pos) {
        return shop.pos.getCenter().add(Vec3.atLowerCornerOf(rotation.rotate(Direction.EAST).getNormal()).scale(pos.x - clerkX(shop) - .5))
                .add(0, pos.y - 3.5, 0)
                .add(Vec3.atLowerCornerOf(rotation.rotate(Direction.SOUTH).getNormal()).scale(pos.z - clerkZ(shop) - .5));
    }

    private ShopAccess() {}
}
