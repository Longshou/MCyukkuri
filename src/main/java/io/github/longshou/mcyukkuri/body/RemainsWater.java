package io.github.longshou.mcyukkuri.body;

import net.minecraft.nbt.CompoundTag;

/**
 * Remains left in water soften, then dissolve (project design; N13 and N14 only say a yukkuri that stays in water melts, single
 * stories, no times). The clock counts only ticks the remains actually spend in water; carried, buried or dry remains keep it,
 * and a stage never reverses when they dry out. Softened remains are still the same edible body; dissolving remains have lost
 * their filling to the water (no food, no centre filling) and no longer float. Remains never vanish by themselves.
 */
public final class RemainsWater {
    public static final int DRY = 0, SOFTENED = 1, DISSOLVING = 2;
    public static final String SOAK = "Soak", STAGE = "WaterStage";
    /** A quarter day in water softens the skin; a full day dissolves the body. */
    public static final long SOFTENED_TICKS = 6000, DISSOLVING_TICKS = 24000;
    private RemainsWater() {}

    public static int stageFor(long soakTicks) {
        return soakTicks >= DISSOLVING_TICKS ? DISSOLVING : soakTicks >= SOFTENED_TICKS ? SOFTENED : DRY;
    }
    public static int stage(CompoundTag remains) { return Math.clamp(remains.getInt(STAGE), DRY, DISSOLVING); }
    public static String id(int stage) { return stage == DISSOLVING ? "dissolving" : stage == SOFTENED ? "softened" : "dry"; }
}
