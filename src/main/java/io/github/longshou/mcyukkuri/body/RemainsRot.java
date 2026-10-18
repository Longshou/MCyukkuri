package io.github.longshou.mcyukkuri.body;

import io.github.longshou.mcyukkuri.entity.RemainsEntity;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Decay of remains and the smell of the dead (komicolle 11518847: the dead give off a stench only yukkuri can smell; stale filling
 * "陳餡" reeks). The three stages and every number are project design; the lecture gives none. Remains never vanish by themselves
 * (user decision), they only change: fresh, then stale, then rotten. Time counts only while the remains lie in the world, so a carried or
 * buried body, or one in a freezer, keeps its stage. Server logic only; the client reads the stage from the synced {@code RotStage}.
 */
public final class RemainsRot {
    public static final int FRESH = 0, STALE = 1, ROTTEN = 2;
    private RemainsRot() {}

    public static int stageFor(long rotTicks) {
        if (rotTicks >= YukkuriConfig.REMAINS_ROTTEN_TICKS.get()) return ROTTEN;
        return rotTicks >= YukkuriConfig.REMAINS_STALE_TICKS.get() ? STALE : FRESH;
    }
    public static int stage(CompoundTag remains) { return Math.clamp(remains.getInt("RotStage"), FRESH, ROTTEN); }
    public static String id(int stage) { return stage == STALE ? "stale" : stage == ROTTEN ? "rotten" : "fresh"; }
    /** How far the smell carries (blocks). Fresh remains smell of nothing. */
    public static double radius(int stage) { return stage == ROTTEN ? 12 : stage == STALE ? 6 : 0; }
    /** Unease per minute for an individual standing inside the smell (see mind/MindLife surroundings). */
    public static float pressure(int stage) { return stage == ROTTEN ? 3f : stage == STALE ? 1.5f : 0f; }

    /** The strongest smelling remains whose smell reaches {@code pos}, or null. */
    public static RemainsEntity source(ServerLevel level, Vec3 pos) {
        RemainsEntity best = null; float bestPressure = 0;
        for (var remains : level.getEntitiesOfClass(RemainsEntity.class, new net.minecraft.world.phys.AABB(pos, pos).inflate(radius(ROTTEN)))) {
            int stage = remains.stage();
            if (stage == FRESH || remains.distanceToSqr(pos) > radius(stage) * radius(stage)) continue;
            float p = pressure(stage) - (float) remains.position().distanceTo(pos) * .05f;
            if (p > bestPressure) { best = remains; bestPressure = p; }
        }
        return best;
    }
    public static float pressureAt(ServerLevel level, Vec3 pos) {
        var source = source(level, pos);
        return source == null ? 0 : pressure(source.stage());
    }
}
