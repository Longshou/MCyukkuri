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
 * What a badge is good for in the shop and in the family (docs/PET_SHOP.md). One place decides, always by the effective grade
 * (a lapsed silver or gold counts one lower, a voided badge counts as none):
 * <ul>
 * <li>bronze: the harness can be bought; a breeding permit can be given (both pets need a badge); resale 60%;</li>
 * <li>silver: premium grain and pudding can be bought; matchmaking with the keeper can be booked; resale 70%;</li>
 * <li>gold: the same, resale 80%.</li>
 * </ul>
 * A player standing is the best effective grade among their pets in 32 blocks, or what was recorded when one of their pets passed an exam,
 * renewed, or was bought (so a pet that waits in a carrier or at home still counts). Bronze is kept for good, silver and gold until their expiry.
 */
public final class PetPrivileges extends SavedData {
    public static final int HARNESS = 1, BREEDING = 1, MATCHMAKING = 2;
    private static final String NAME = "mcyukkuri_pet_privileges";
    private static final long NONE = Long.MIN_VALUE, PERMANENT = -1;
    /** Per player and grade (bronze, silver, gold): none, permanent, or the game time until which the record counts. */
    private final Map<UUID, long[]> records = new HashMap<>();

    public static PetPrivileges get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(PetPrivileges::new, PetPrivileges::load), NAME);
    }

    /** Called when a pet of a player earns, renews or is bought with a badge: remembers the grade and every lower one. */
    public static void note(YukkuriEntity pet) {
        if (!(pet.level() instanceof ServerLevel level) || !pet.pet().isPet() || pet.pet().owner() == null) return;
        var grade = pet.pet().effectiveGrade();
        if (grade == BadgeGrade.NONE) return;
        long until = pet.pet().expiresAt();
        var data = get(level); var owner = pet.pet().owner();
        long[] r = data.records.computeIfAbsent(owner, id -> new long[] {NONE, NONE, NONE});
        for (int g = 1; g <= grade.id(); g++) {
            long next = g == 1 || until < 0 ? PERMANENT : until;
            if (r[g - 1] != PERMANENT && (r[g - 1] == NONE || next == PERMANENT || next > r[g - 1])) r[g - 1] = next;
        }
        data.setDirty();
    }

    /** The player standing: best of the recorded grades still valid and the effective grades of their pets within 32 blocks. */
    public static BadgeGrade grade(Player player) {
        if (!(player.level() instanceof ServerLevel level)) return BadgeGrade.NONE;
        int best = 0; long now = level.getGameTime();
        long[] r = get(level).records.get(player.getUUID());
        if (r != null) for (int g = 1; g <= 3; g++) if (r[g - 1] != NONE && (r[g - 1] == PERMANENT || r[g - 1] >= now)) best = g;
        for (var y : level.getEntitiesOfClass(YukkuriEntity.class, player.getBoundingBox().inflate(32), e -> e.isAlive() && e.pet().isPet() && e.pet().ownedBy(player.getUUID())))
            best = Math.max(best, y.pet().effectiveGrade().id());
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
        return switch (effective) { case SILVER -> 70; case GOLD -> 80; default -> 60; };
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var list = new ListTag();
        records.forEach((id, r) -> {
            var c = new CompoundTag(); c.putUUID("Player", id); c.putLongArray("Until", r.clone()); list.add(c);
        });
        tag.put("Records", list); return tag;
    }
    public static PetPrivileges load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new PetPrivileges();
        for (var item : tag.getList("Records", 10)) {
            var c = (CompoundTag) item; var until = c.getLongArray("Until");
            if (!c.hasUUID("Player") || until.length != 3) continue;
            data.records.put(c.getUUID("Player"), until);
        }
        return data;
    }
}
