package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.coordination.LeaseTable;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

/**
 * Soft reservations on plant blocks being gathered, so a crowd spreads over the meadow instead of all
 * walking to the same tuft. Transient (never saved), expiring on their own, server thread only.
 */
public final class ForageClaims {
    private record Key(MinecraftServer server, ResourceKey<Level> dimension, long pos) {}
    /** Identity of one attempt, including the original world when its animal later moves. */
    public static final class Claim {
        private final Key key;
        private final UUID owner;
        private Claim(Key key, UUID owner) { this.key = key; this.owner = owner; }
    }
    private static final int HOLD = 600;
    private static final LeaseTable<Key, Claim> CLAIMS = new LeaseTable<>(HOLD);

    /** The group's latest sighting excludes plant and forage sites while the threat is remembered. */
    public static boolean threatZone(YukkuriEntity forager, BlockPos site) {
        var group = forager.settlement().group();
        return group != null && threatZone(forager, site, group.threatPos, group.threat);
    }

    record Threat(BlockPos sighting, int strength) {
        static final Threat NONE = new Threat(null, 0);
        boolean excludes(YukkuriEntity forager, BlockPos site) { return threatZone(forager, site, sighting, strength); }
    }

    static Threat threat(YukkuriEntity forager) {
        var group = forager.settlement().group();
        return group == null || group.threatPos == null || group.threat <= 0
                ? Threat.NONE : new Threat(group.threatPos, group.threat);
    }

    private static boolean threatZone(YukkuriEntity forager, BlockPos site, BlockPos sighting, int strength) {
        if (sighting == null || !PlantSites.threatened(strength,
                (double)site.getX() - sighting.getX(), (double)site.getZ() - sighting.getZ())) return false;
        int suppressed = GameplayLog.admit(forager, "forage", "threat_zone", 200);
        if (suppressed >= 0) GameplayLog.limitedEvent(forager, "forage", suppressed,
                "forage skipped reason=threat_zone site={} sighting={} threat={}", site, sighting, strength);
        return true;
    }

    public static Stream<BlockPos> plantSites(YukkuriEntity forager, List<BlockPos> candidates) {
        if (candidates.isEmpty()) return Stream.empty();
        var level = (ServerLevel)forager.level();
        var server = level.getServer();
        var dimension = level.dimension();
        var owner = forager.getUUID();
        var taken = new LongOpenHashSet();
        CLAIMS.forEachLive(level.getGameTime(), (key, claim) -> {
            if (key.server == server && key.dimension.equals(dimension) && !claim.owner.equals(owner)) taken.add(key.pos);
        });
        var threat = threat(forager);
        var from = forager.position();
        // This stream is consumed within the search. Claim acquisition and in-transit threat checks stay live.
        return PlantSites.nearest(candidates, pos -> from.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5),
                pos -> !taken.contains(pos.asLong()) && !threat.excludes(forager, pos));
    }

    private static Key key(ServerLevel level, BlockPos pos) { return new Key(level.getServer(), level.dimension(), pos.asLong()); }
    public static Claim claim(ServerLevel level, BlockPos pos, UUID by) {
        long now = level.getGameTime();
        if (CLAIMS.size() > 512) CLAIMS.prune(now);
        var claim = new Claim(key(level, pos), by);
        return CLAIMS.acquireAll(List.of(claim.key), claim, now + HOLD, now) ? claim : null;
    }
    public static boolean owns(ServerLevel level, BlockPos pos, Claim claim) {
        return claim != null && claim.key.equals(key(level, pos)) && CLAIMS.owns(claim.key, claim, level.getGameTime());
    }
    public static void release(Claim claim) { if (claim != null) CLAIMS.release(claim.key, claim); }
    public static void started(ServerStartingEvent event) { CLAIMS.clear(); }
    public static void stopped(ServerStoppedEvent event) { CLAIMS.clear(); }
    public static void forget(ServerLevel level, AABB box, Set<UUID> ids) {
        CLAIMS.removeIf((key, claim) -> ids.contains(claim.owner) || key.server == level.getServer() && key.dimension.equals(level.dimension())
                && box.contains(Vec3.atCenterOf(BlockPos.of(key.pos))));
    }
    private ForageClaims() {}
}
