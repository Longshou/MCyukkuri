package io.github.longshou.mcyukkuri.p0;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;

/** Chunk generation prerequisites for isolated, uncapped GameTest arenas. */
final class GameTestChunks {
    private GameTestChunks() {}

    static void setForced(ServerLevel level, int chunkX, int chunkZ, boolean forced) {
        if (forced) {
            // ChunkMap.prepareEntityTickingChunk needs a radius-2 FULL future.
            // Resolve the ordinary chunk generation before asking an uncapped
            // test clock to wait for entity readiness. The caller must still
            // wait for isPositionEntityTicking before querying or ticking actors.
            for (int x = chunkX - 2; x <= chunkX + 2; x++) {
                for (int z = chunkZ - 2; z <= chunkZ + 2; z++) level.getChunk(x, z);
            }
        }
        level.setChunkForced(chunkX, chunkZ, forced);
    }

    static void assertTicking(GameTestHelper helper, int minX, int maxX, int minZ, int maxZ) {
        ServerLevel level = helper.getLevel();
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            helper.assertTrue(level.isPositionEntityTicking(new BlockPos(x * 16 + 8, 65, z * 16 + 8)),
                    "Waiting for fixture chunk " + x + "," + z + "; forced="
                            + level.getForcedChunks().contains(ChunkPos.asLong(x, z)) + "; tick=" + helper.getTick());
        }
    }
}
