package io.github.longshou.mcyukkuri.shop;

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
    private record Area(AABB floor, ShopMembership tier, BlockPos exit, Direction out) {}
    enum Service {
        AVAILABLE, LEAVE, DAY;

        String message() { return "membership.service." + ordinal(); }
    }
    private static final Map<UUID, UUID> SPOKEN = new HashMap<>();

    private static final List<Area> PET = List.of(
            area(2, 4, 8, 7, ShopMembership.PATRON, 5, 8, Direction.SOUTH),
            area(16, 4, 22, 7, ShopMembership.PATRON, 19, 8, Direction.SOUTH),
            area(2, 13, 22, 16, ShopMembership.MEMBER, 5, 12, Direction.NORTH),
            area(2, 18, 15, 29, ShopMembership.MEMBER, 16, 21, Direction.EAST),
            area(17, 18, 22, 29, ShopMembership.MEMBER, 17, 17, Direction.NORTH));
    private static final List<Area> ONI = List.of(
            area(2, 4, 6, 6, ShopMembership.PATRON, 4, 7, Direction.SOUTH),
            area(16, 8, 19, 10, ShopMembership.PATRON, 20, 8, Direction.EAST),
            area(16, 12, 19, 14, ShopMembership.PATRON, 20, 12, Direction.EAST),
            area(16, 16, 19, 18, ShopMembership.PATRON, 20, 16, Direction.EAST),
            area(2, 17, 14, 18, ShopMembership.MEMBER, 3, 16, Direction.NORTH),
            area(21, 8, 22, 19, ShopMembership.MEMBER, 21, 20, Direction.SOUTH),
            area(2, 19, 22, 23, ShopMembership.MEMBER, 7, 19, Direction.NORTH));
    private static final List<Area> LEGACY = List.of(
            area(2, 4, 4, 6, ShopMembership.PATRON, 2, 7, Direction.SOUTH),
            area(6, 4, 8, 6, ShopMembership.PATRON, 6, 7, Direction.SOUTH),
            area(16, 4, 18, 6, ShopMembership.PATRON, 16, 7, Direction.SOUTH),
            area(20, 4, 22, 6, ShopMembership.PATRON, 20, 7, Direction.SOUTH),
            area(2, 18, 4, 20, ShopMembership.PATRON, 4, 21, Direction.SOUTH),
            area(6, 18, 8, 20, ShopMembership.PATRON, 8, 21, Direction.SOUTH),
            area(2, 27, 4, 29, ShopMembership.PATRON, 4, 26, Direction.NORTH),
            area(6, 27, 8, 29, ShopMembership.PATRON, 8, 26, Direction.NORTH),
            area(2, 18, 12, 29, ShopMembership.MEMBER, 10, 17, Direction.NORTH),
            area(13, 18, 22, 29, ShopMembership.MEMBER, 14, 17, Direction.NORTH));
    private static final Map<Direction, Rotation> ROTATIONS = Map.of(Direction.NORTH, Rotation.NONE,
            Direction.EAST, Rotation.CLOCKWISE_90, Direction.SOUTH, Rotation.CLOCKWISE_180, Direction.WEST, Rotation.COUNTERCLOCKWISE_90);

    private static Area area(int x0, int z0, int x1, int z1, ShopMembership tier, int x, int z, Direction out) {
        return new Area(new AABB(x0, 2.5, z0, x1 + 1, 8, z1 + 1), tier, new BlockPos(x, 3, z), out);
    }

    static ShopMembership doorTier(ShopRegistry.Entry shop, BlockPos pos) {
        var rotation = rotation(shop);
        if (rotation == null) return null;
        var door = local(shop, rotation, pos.getCenter());
        for (var area : areas(shop))
            if (exitDoor(area, door, !shop.legacy())) return area.tier();
        return shop.legacy() ? ShopMembership.MEMBER : null;
    }

    static boolean legacyServiceDoor(ShopRegistry.Entry shop, BlockPos pos) {
        var rotation = rotation(shop);
        if (!shop.legacy() || rotation == null) return false;
        var local = BlockPos.containing(local(shop, rotation, pos.getCenter()));
        int x = local.getX(), z = local.getZ();
        return z == 17 && (x == 10 || x == 14) || x == 12 && z == 24 || x == 16 && (z == 21 || z == 26);
    }

    static boolean egress(ShopRegistry.Entry shop, ServerPlayer player, BlockPos pos) {
        var rotation = rotation(shop);
        if (rotation == null) return false;
        var feet = local(shop, rotation, player.position());
        var door = local(shop, rotation, pos.getCenter());
        return areas(shop).stream().anyMatch(area -> exitDoor(area, door, !shop.legacy())
                && feet.subtract(door).dot(Vec3.atLowerCornerOf(area.out().getNormal())) < 0);
    }

    private static boolean exitDoor(Area area, Vec3 door, boolean paired) {
        var lower = BlockPos.containing(door.x, 3, door.z);
        var first = area.exit();
        var second = first.relative(area.out().getAxis() == Direction.Axis.X ? Direction.SOUTH : Direction.EAST);
        return lower.equals(first) || paired && lower.equals(second);
    }

    private static ShopMembership restricted(ShopRegistry.Entry shop, ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator() || !shop.contains(player.position())) return null;
        var rotation = rotation(shop);
        if (rotation == null) return null;
        var tier = shop.membership(player.getUUID());
        var feet = local(shop, rotation, player.position());
        for (var area : areas(shop))
            if (!tier.allows(area.tier()) && area.floor().contains(feet)) return area.tier();
        return null;
    }

    static Service service(ServerLevel level, ShopRegistry.Entry shop, ServerPlayer player) {
        if (shop.refusesToday(level, player.getUUID())) return Service.DAY;
        return restricted(shop, player) == null ? Service.AVAILABLE : Service.LEAVE;
    }

    static boolean refuseService(ServerLevel level, ShopRegistry.Entry shop, ServerPlayer player) {
        var refusal = service(level, shop, player);
        if (refusal == Service.AVAILABLE) return false;
        ShopTrade.say(player, refusal.message());
        GameplayLog.limited(player, "shop", "service_refused", 100,
                "action=service result=refused shop={} reason={}", shop.id, refusal);
        return true;
    }

    static boolean touchStock(ServerLevel level, ShopRegistry.Entry shop, ServerPlayer player) {
        if (restricted(shop, player) != null && !shop.refusesToday(level, player.getUUID())) {
            ShopRegistry.get(level).refuseToday(level, shop, player.getUUID());
            GameplayLog.event(player, "shop", "action=service result=refused_today shop={} reason=touched_stock", shop.id);
        }
        return refuseService(level, shop, player);
    }

    static boolean needsRefusal(ShopRegistry.Entry shop, ServerPlayer player) {
        return !shop.id.equals(SPOKEN.get(player.getUUID())) && restricted(shop, player) != null;
    }

    static void turnAway(HumanNpc staff, ShopRegistry.Entry shop, ServerPlayer player) {
        SPOKEN.put(player.getUUID(), shop.id);
        ShopTrade.say(player, shop.refusesToday(player.serverLevel(), player.getUUID()) ? "membership.leave_banned" : "membership.leave", staff.getDisplayName());
        GameplayLog.event(staff, "shop", "action=service result=turned_away shop={} player={} tier={} required={}",
                shop.id, player.getUUID(), shop.membership(player.getUUID()), restricted(shop, player));
    }

    static void playerTick(ServerPlayer player, ShopRegistry registry) {
        var id = player.getUUID();
        if (!SPOKEN.containsKey(id)) return;
        var shop = registry.shopAt(player.serverLevel(), player.position());
        if (shop == null || !shop.id.equals(SPOKEN.get(id)) || restricted(shop, player) == null) SPOKEN.remove(id);
    }

    static void clear() { SPOKEN.clear(); }

    static void leaving(ServerPlayer player) { SPOKEN.remove(player.getUUID()); }
    private static List<Area> areas(ShopRegistry.Entry shop) { return shop.legacy() ? LEGACY : shop.kind == ShopKind.PET ? PET : ONI; }
    private static int clerkX(ShopRegistry.Entry shop) { return shop.legacy() ? 11 : shop.kind == ShopKind.PET ? 12 : 9; }
    private static int clerkZ(ShopRegistry.Entry shop) { return shop.kind == ShopKind.PET ? 14 : 17; }
    private static Rotation rotation(ShopRegistry.Entry shop) {
        if (!shop.legacy()) return ShopRegistry.authoredRotation(shop);
        var front = shop.legacyFront();
        return front == null ? null : ROTATIONS.get(front);
    }

    private static Vec3 local(ShopRegistry.Entry shop, Rotation rotation, Vec3 pos) {
        var delta = pos.subtract(shop.pos.getCenter());
        return new Vec3(clerkX(shop) + .5 + delta.dot(Vec3.atLowerCornerOf(rotation.rotate(Direction.EAST).getNormal())),
                3.5 + delta.y, clerkZ(shop) + .5 + delta.dot(Vec3.atLowerCornerOf(rotation.rotate(Direction.SOUTH).getNormal())));
    }

    private ShopAccess() {}
}
