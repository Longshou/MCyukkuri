package io.github.longshou.mcyukkuri.settlement;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Soft reservations on plant blocks being gathered, so a crowd spreads over the meadow instead of all
 * walking to the same tuft. Transient (never saved), expiring on their own, server thread only.
 */
public final class ForageClaims {
    private record Key(ResourceKey<Level> dimension, long pos) {}
    private record Claim(UUID owner, long until) {}
    private static final int HOLD = 600;
    private static final Map<Key, Claim> CLAIMS = new HashMap<>();

    private static boolean live(Claim claim, long now) { return claim != null && claim.until() > now && claim.until() <= now + HOLD; }
    /** True when somebody other than {@code by} holds a live claim on this block. */
    public static boolean taken(ServerLevel level, BlockPos pos, UUID by) {
        var claim = CLAIMS.get(new Key(level.dimension(), pos.asLong()));
        return live(claim, level.getGameTime()) && !claim.owner().equals(by);
    }
    public static boolean claim(ServerLevel level, BlockPos pos, UUID by) {
        long now = level.getGameTime();
        if (CLAIMS.size() > 512) CLAIMS.values().removeIf(claim -> !live(claim, now));
        var key = new Key(level.dimension(), pos.asLong());
        var claim = CLAIMS.get(key);
        if (live(claim, now) && !claim.owner().equals(by)) return false;
        CLAIMS.put(key, new Claim(by, now + HOLD));
        return true;
    }
    public static void release(ServerLevel level, BlockPos pos, UUID by) {
        var key = new Key(level.dimension(), pos.asLong());
        var claim = CLAIMS.get(key);
        if (claim != null && claim.owner().equals(by)) CLAIMS.remove(key);
    }
    private ForageClaims() {}
}
