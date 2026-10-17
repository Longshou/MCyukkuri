package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.nbt.CompoundTag;

/**
 * Everyday care of an owner's pet that is not part of the badge record: sweets, habit praise, requests, walks, harness and swaddle
 * (docs/PET_SHOP.md section 12). Server-authoritative; it only ticks while the pet is loaded and nothing here depends on frame rate.
 * All counters are per game day (24000 ticks of game time) and reset the first time the pet notices a new day.
 */
public final class PetCare {
    public static final long DAY = 24000L;
    private final YukkuriEntity animal;
    private int day = Integer.MIN_VALUE, snacks;

    public PetCare(YukkuriEntity animal) { this.animal = animal; }

    private int today() { return (int)(animal.level().getGameTime() / DAY); }
    /** Rolls the daily counters over; returns whether a new day started since the last call. */
    private boolean rollDay() {
        int now = today();
        if (day == now) return false;
        boolean first = day == Integer.MIN_VALUE;
        day = now; snacks = 0;
        return !first;
    }

    // sweets
    public int snacksToday() { rollDay(); return snacks; }
    /** Counts one sweet eaten today and returns the new count. */
    public int countSnack() { rollDay(); return ++snacks; }
    /** The pet swallowed a sweet from its owner. */
    public void snackEaten() {}

    public void tick() {
        if (animal.level().isClientSide) return;
        rollDay();
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putInt("Day", day); tag.putInt("Snacks", snacks);
        return tag;
    }
    public void load(CompoundTag tag) {
        day = tag.contains("Day") ? tag.getInt("Day") : Integer.MIN_VALUE;
        snacks = Math.clamp(tag.getInt("Snacks"), 0, 99);
    }
}
