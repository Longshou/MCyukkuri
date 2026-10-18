package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.coordination.LeaseTable;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
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

    private static Key key(ServerLevel level, BlockPos pos) { return new Key(level.getServer(), level.dimension(), pos.asLong()); }
    /** True when somebody other than {@code by} holds a live claim on this block. */
    public static boolean taken(ServerLevel level, BlockPos pos, UUID by) {
        return CLAIMS.owners(key(level, pos), level.getGameTime()).stream().anyMatch(claim -> !claim.owner.equals(by));
    }
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
    private ForageClaims() {}
}
