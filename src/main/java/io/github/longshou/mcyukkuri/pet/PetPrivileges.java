package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * What a badge is good for in the shop and in the family (docs/FEATURES.md).
 * <ul>
 * <li>bronze: the harness can be bought; a breeding permit can be given (both pets need a badge); resale 60%;</li>
 * <li>silver: premium grain and pudding can be bought; matchmaking with the keeper can be booked; resale 70%;</li>
 * <li>gold: the same, resale 80%.</li>
 * </ul>
 * Player standing is the best effective grade among their pets within 32 blocks or the permanent grade earned through
 * a pet's certification or purchase.
 */
public final class PetPrivileges extends SavedData {
    public static final int HARNESS = 1, BREEDING = 1, MATCHMAKING = 2;
    private static final String NAME = "mcyukkuri_pet_privileges";
    private final Map<UUID, boolean[]> records = new HashMap<>();

    public static PetPrivileges get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(PetPrivileges::new, PetPrivileges::load), NAME);
    }

    /** Remembers the grade and every lower one when a pet earns a badge or is bought with one. */
    public static void note(YukkuriEntity pet) {
        if (!(pet.level() instanceof ServerLevel level) || !pet.pet().isPet() || pet.pet().owner() == null) return;
        var grade = pet.pet().effectiveGrade();
        if (grade == BadgeGrade.NONE) return;
        var data = get(level); var owner = pet.pet().owner();
        boolean[] r = data.records.computeIfAbsent(owner, id -> new boolean[3]);
        for (int g = 0; g < Math.min(grade.id(), r.length); g++) r[g] = true;
        data.setDirty();
    }

    /** The best of the recorded grades and the effective grades of their pets within 32 blocks. */
    public static BadgeGrade grade(Player player) {
        if (!(player.level() instanceof ServerLevel level)) return BadgeGrade.NONE;
        int best = 0;
        boolean[] r = get(level).records.get(player.getUUID());
        if (r != null) for (int g = 0; g < r.length; g++) if (r[g]) best = g + 1;
        for (var y : level.getEntitiesOfClass(YukkuriEntity.class, player.getBoundingBox().inflate(32), e -> e.isAlive() && e.pet().isPet() && e.pet().ownedBy(player.getUUID())))
            best = Math.max(best, Math.min(y.pet().effectiveGrade().id(), BadgeGrade.GOLD.id()));
        return BadgeGrade.of(best);
    }
    /** Whether the player may use something that needs this grade (0 is always allowed). */
    public static boolean allows(Player player, int needed) { return needed <= 0 || grade(player).id() >= needed; }

    /** A breeding permit needs a badge on both pets. */
    public static boolean mayPermitBreeding(YukkuriEntity a, YukkuriEntity b) {
        return a.pet().effectiveGrade().id() >= BREEDING && b.pet().effectiveGrade().id() >= BREEDING;
    }
    /** Matchmaking with the keeper is booked for a silver pet. */
    public static boolean mayMatchmake(YukkuriEntity pet) { return pet.pet().effectiveGrade().id() >= MATCHMAKING; }

    /** Share of the quote the shop pays when buying a pet back, in percent. */
    public static int resalePercent(BadgeGrade effective) {
        return switch (effective) { case NONE, BRONZE -> 60; case SILVER -> 70; case GOLD, PLATINUM -> 80; };
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var list = new ListTag();
        records.forEach((id, r) -> {
            int mask = 0;
            for (int g = 0; g < r.length; g++) if (r[g]) mask |= 1 << g;
            var c = new CompoundTag();
            c.putUUID("Player", id); c.putInt("Grades", mask); list.add(c);
        });
        tag.put("Records", list); return tag;
    }
    public static PetPrivileges load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new PetPrivileges();
        for (var item : tag.getList("Records", 10)) {
            var c = (CompoundTag) item;
            if (!c.hasUUID("Player")) continue;
            var grades = new boolean[3];
            if (c.contains("Grades")) {
                int mask = c.getInt("Grades");
                for (int g = 0; g < grades.length; g++) grades[g] = (mask & 1 << g) != 0;
            } else {
                var earned = c.getLongArray("Until");
                for (int g = 0; g < Math.min(earned.length, grades.length); g++) grades[g] = earned[g] != Long.MIN_VALUE;
            }
            data.records.put(c.getUUID("Player"), grades);
        }
        return data;
    }
}
