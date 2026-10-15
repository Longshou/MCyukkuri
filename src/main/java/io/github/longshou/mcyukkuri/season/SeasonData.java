package io.github.longshou.mcyukkuri.season;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** One server-wide calendar, stored only in the overworld. Reading never advances time. */
public final class SeasonData extends SavedData {
    public static final String DATA_NAME = "mcyukkuri_seasons";
    public static final int DEFAULT_DAYS = 7, MAX_DAYS = 365;
    public static final long DAY_TICKS = 24_000;
    public enum Season { SPRING, SUMMER, AUTUMN, WINTER }
    private static final Season[] ORDER = Season.values();
    public record Snapshot(Season season, int day, int daysPerSeason, long ticksIntoSeason) {}
    private boolean initialized;
    private long lastDayTime, phaseTicks;
    private int daysPerSeason = DEFAULT_DAYS;

    public static SeasonData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                new Factory<>(SeasonData::new, SeasonData::load), DATA_NAME);
    }
    public Snapshot snapshot() {
        long length = daysPerSeason * DAY_TICKS, within = phaseTicks % length;
        return new Snapshot(season(), (int) (within / DAY_TICKS) + 1,
                daysPerSeason, within);
    }
    public Season season() { return ORDER[(int) (phaseTicks / (daysPerSeason * DAY_TICKS))]; }

    /** Called once by the server clock, independent of entities, chunks and dimension ticks. */
    public void sample(long dayTime, boolean daylightCycle, int configuredDays) {
        int days = Math.clamp(configuredDays, 1, MAX_DAYS);
        if (!initialized) {
            initialized = true; lastDayTime = dayTime; daysPerSeason = days; phaseTicks = 0;
            setDirty(); return; // Existing worlds begin in spring, with no historical catch-up.
        }
        boolean changed = false;
        if (days != daysPerSeason) {
            long oldLength = daysPerSeason * DAY_TICKS, newLength = days * DAY_TICKS;
            // Preserve the current season and its fractional progress (round down < 1 tick).
            // Both lengths are bounded, so this product is below 8e13, safely inside long.
            phaseTicks = phaseTicks / oldLength * newLength + phaseTicks % oldLength * newLength / oldLength;
            daysPerSeason = days; changed = true;
        }
        if (dayTime != lastDayTime) {
            if (daylightCycle && dayTime > lastDayTime) {
                long cycle = 4 * daysPerSeason * DAY_TICKS;
                // Do not subtract arbitrary signed timestamps: MIN_VALUE -> MAX_VALUE overflows.
                // A bounded modular difference gives the exact phase even for that huge jump.
                long delta = Math.floorMod(Math.floorMod(dayTime, cycle) - Math.floorMod(lastDayTime, cycle), cycle);
                phaseTicks = (phaseTicks + delta) % cycle;
            }
            // Backward commands and changes while paused only rebase; nothing is queued.
            lastDayTime = dayTime; changed = true;
        }
        if (changed) setDirty();
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Version", 1); tag.putBoolean("Initialized", initialized);
        tag.putLong("LastDayTime", lastDayTime); tag.putLong("PhaseTicks", phaseTicks);
        tag.putInt("DaysPerSeason", daysPerSeason);
        return tag;
    }
    public static SeasonData load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new SeasonData();
        // Missing, future-version or malformed data restarts conservatively on the next sample.
        if (tag.getInt("Version") != 1 || !tag.getBoolean("Initialized")
                || !tag.contains("LastDayTime", Tag.TAG_LONG) || !tag.contains("PhaseTicks", Tag.TAG_LONG)
                || !tag.contains("DaysPerSeason", Tag.TAG_INT)) return data;
        int days = tag.getInt("DaysPerSeason"); long phase = tag.getLong("PhaseTicks");
        if (days < 1 || days > MAX_DAYS || phase < 0 || phase >= 4 * days * DAY_TICKS) return data;
        data.initialized = true; data.lastDayTime = tag.getLong("LastDayTime");
        data.daysPerSeason = days; data.phaseTicks = phase;
        return data;
    }
}
