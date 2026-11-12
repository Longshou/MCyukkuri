package io.github.longshou.mcyukkuri.pet.exam;

import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.human.HumanWorld;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** A formal exam uses a hall podium. The real requirements of each question are checked on the candidate. */
public final class ExamHall {
    private record Cached(long time, boolean podium) {}
    private static final Map<UUID, Cached> CACHE = new HashMap<>();

    private ExamHall() {}

    public static void reset() { CACHE.clear(); }
    public static void forget(Set<UUID> ids) { CACHE.keySet().removeAll(ids); }

    public static boolean of(HumanNpc examiner) {
        if (!(examiner.level() instanceof ServerLevel level)) return false;
        long now = level.getGameTime();
        var cached = CACHE.get(examiner.getUUID());
        if (cached != null && now - cached.time < 200 && now >= cached.time) return cached.podium;
        BlockPos home = examiner.home();
        boolean podium = false;
        for (BlockPos pos : BlockPos.betweenClosed(home.offset(-24, -4, -24), home.offset(24, 4, 24))) {
            if (level.hasChunkAt(pos) && level.getBlockState(pos).is(HumanWorld.EXAM_PODIUM.get())) {
                podium = true;
                break;
            }
        }
        if (CACHE.size() > 64) CACHE.values().removeIf(e -> now - e.time >= 200);
        CACHE.put(examiner.getUUID(), new Cached(now, podium));
        return podium;
    }
}
