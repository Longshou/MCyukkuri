package io.github.longshou.mcyukkuri.pet.exam;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.human.HumanWorld;
import io.github.longshou.mcyukkuri.human.TownMarkers;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Certification is offered at an examiner's hall podium. */
public final class ExamHall {
    private record Cached(long time, boolean podium) {}
    private static final Map<UUID, Cached> CACHE = new HashMap<>();

    private ExamHall() {}

    public static void reset() { CACHE.clear(); }
    public static void forget(Set<UUID> ids) { CACHE.keySet().removeAll(ids); }

    static TownMarkers.BadgeCounter counter(HumanNpc clerk) {
        return TownMarkers.badgeCounter((ServerLevel) clerk.level(), clerk.home());
    }

    static boolean servesAtCounter(HumanNpc clerk, Player player, TownMarkers.BadgeCounter counter) {
        return counter != null && counter.present((ServerLevel) clerk.level()) && clerk.counterWorking()
                && player.canInteractWithEntity(clerk, 0) && counter.publicSide(player.position());
    }

    static boolean hasPodium(ServerLevel level, TownMarkers.BadgeCounter counter) {
        return counter.present(level) && level.hasChunkAt(counter.podium())
                && level.getBlockState(counter.podium()).is(HumanWorld.EXAM_PODIUM.get());
    }

    static boolean inPublicArea(TownMarkers.BadgeCounter counter, YukkuriEntity candidate) {
        var feet = candidate.position();
        var podium = Vec3.atBottomCenterOf(counter.podium());
        // One floor level, with room beside the platform for bodies wider than its top.
        if (!candidate.onGround() || candidate.isInWaterOrBubble() || Math.abs(feet.y - podium.y) > .5
                || feet.subtract(podium).horizontalDistanceSqr() > 2.25 * 2.25) return false;
        var view = Vec3.atBottomCenterOf(counter.view()).add(0, 1.62, 0);
        return candidate.level().clip(new ClipContext(view, candidate.getEyePosition(), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, candidate)).getType() == HitResult.Type.MISS;
    }

    static boolean hasLegacyPodium(HumanNpc examiner) {
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
