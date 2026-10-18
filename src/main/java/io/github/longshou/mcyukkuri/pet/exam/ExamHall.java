package io.github.longshou.mcyukkuri.pet.exam;

import io.github.longshou.mcyukkuri.human.ExamMarkBlock;
import io.github.longshou.mcyukkuri.human.ExamPodiumBlock;
import io.github.longshou.mcyukkuri.human.ExamHideBoxBlock;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.human.HumanWorld;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.pet.Ability;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/** The exam hall as found around an examiner: functional blocks scanned within 24 blocks, so template rotation does not matter. Null fields mean "not present". */
public final class ExamHall {
    public final BlockPos podium, desk, hideBox, room, examinerSpot, gold, actor;
    public final Direction podiumFacing, hideOpen;
    public final List<BlockPos> promise, trainee, bowls, toilets, nests;
    private ExamHall(BlockPos podium, Direction podiumFacing, BlockPos desk, BlockPos hideBox, Direction hideOpen, List<BlockPos> promise, BlockPos room,
                     BlockPos examinerSpot, BlockPos gold, BlockPos actor, List<BlockPos> trainee, List<BlockPos> bowls, List<BlockPos> toilets, List<BlockPos> nests) {
        this.podium = podium; this.podiumFacing = podiumFacing; this.desk = desk; this.hideBox = hideBox; this.hideOpen = hideOpen; this.promise = promise; this.room = room;
        this.examinerSpot = examinerSpot; this.gold = gold; this.actor = actor; this.trainee = trainee; this.bowls = bowls; this.toilets = toilets; this.nests = nests;
    }

    /** Scan 24 blocks around origin (y +-4); null if there is no podium. */
    public static ExamHall find(ServerLevel level, BlockPos origin) {
        BlockPos podium = null, hide = null, room = null, spot = null, gold = null, actor = null; Direction facing = Direction.NORTH, open = Direction.NORTH;
        List<BlockPos> promise = new ArrayList<>(), trainee = new ArrayList<>();
        for (BlockPos scan : BlockPos.betweenClosed(origin.offset(-24, -4, -24), origin.offset(24, 4, 24))) {
            if (!level.hasChunkAt(scan)) continue;
            var state = level.getBlockState(scan);
            if (state.is(HumanWorld.EXAM_PODIUM.get())) { if (podium == null
                    || scan.distSqr(origin) < podium.distSqr(origin)) { podium = scan.immutable(); facing = state.getValue(ExamPodiumBlock.FACING); } }
            else if (state.is(HumanWorld.EXAM_HIDE_BOX.get())) { if (hide == null
                    || scan.distSqr(origin) < hide.distSqr(origin)) { hide = scan.immutable(); open = state.getValue(ExamHideBoxBlock.FACING); } }
            else if (state.is(HumanWorld.EXAM_MARK.get())) {
                var p = scan.immutable();
                switch (state.getValue(ExamMarkBlock.KIND)) {
                    case ROOM -> { if (room == null) room = p; }
                    case PROMISE -> promise.add(p);
                    case ACTOR -> { if (actor == null) actor = p; }
                    case GOLD -> { if (gold == null) gold = p; }
                    case TRAINEE -> trainee.add(p);
                    case EXAMINER -> { if (spot == null) spot = p; }
                }
            }
        }
        if (podium == null) return null;
        List<BlockPos> bowls = new ArrayList<>(), toilets = new ArrayList<>(), nests = new ArrayList<>();
        if (room != null) for (BlockPos scan : BlockPos.betweenClosed(room.offset(-5, -2, -5), room.offset(5, 2, 5))) {
            if (!level.hasChunkAt(scan) || !(level.getBlockEntity(scan) instanceof CareBlockEntity care)) continue;
            var p = scan.immutable();
            switch (care.kind()) { case BOWL -> bowls.add(p); case TOILET -> toilets.add(p); case NEST, MAT -> nests.add(p); }
        }
        Comparator<BlockPos> order = Comparator.comparingLong(BlockPos::asLong);
        promise.sort(order); trainee.sort(order); bowls.sort(order); toilets.sort(order); nests.sort(order);
        return new ExamHall(podium, facing, origin, hide, open, List.copyOf(promise), room, spot, gold, actor, List.copyOf(trainee), List.copyOf(bowls), List.copyOf(toilets), List.copyOf(nests));
    }

    public boolean supports(Ability section) {
        return switch (section) {
            case MANNERS -> room != null && !bowls.isEmpty() && !toilets.isEmpty() && !nests.isEmpty();
            case ACADEMICS -> podium != null;
            case WITS -> podium != null && hideBox != null && !promise.isEmpty();
            case POISE -> gold != null && actor != null;
        };
    }
    public int supportMask() { int m = 0; for (var a : Ability.values()) if (supports(a)) m |= 1 << a.ordinal(); return m; }

    private record Cached(long time, ExamHall hall) {}
    private static final Map<UUID, Cached> CACHE = new HashMap<>();
    public static void reset() { CACHE.clear(); }
    /** Hall around this examiner's desk, rescanned at most every 200 ticks. */
    public static ExamHall of(HumanNpc examiner) {
        if (!(examiner.level() instanceof ServerLevel level)) return null;
        long now = level.getGameTime(); var c = CACHE.get(examiner.getUUID());
        if (c != null && now - c.time < 200 && now >= c.time) return c.hall;
        var hall = find(level, examiner.home());
        if (CACHE.size() > 64) CACHE.values().removeIf(e -> now - e.time >= 200);
        CACHE.put(examiner.getUUID(), new Cached(now, hall)); return hall;
    }
}
