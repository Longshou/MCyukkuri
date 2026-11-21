package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.badge.BadgeConduct;
import io.github.longshou.mcyukkuri.badge.BadgeConduct.Parent;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.pet.rules.ArcStyle;
import io.github.longshou.mcyukkuri.pet.rules.HouseRule;
import io.github.longshou.mcyukkuri.notebook.SurvivalProgress;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

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
    private final Map<UUID, Map<UUID, Integer>> records = new HashMap<>();
    private final BadgeConduct conduct = new BadgeConduct();
    public BadgeConduct conduct() { return conduct; }

    public static void eloped(YukkuriEntity pet, UUID owner) {
        var level = (ServerLevel) pet.level();
        var data = get(level);
        long day = level.getDayTime() / 24000 + 1;
        if (data.conduct.eloped(pet.getUUID(), pet.pet().ownedBy(owner), pet.pet().grade() != BadgeGrade.NONE, day,
                () -> Custody.release(pet, Custody.ReleaseReason.ELOPED, owner))) {
            data.setDirty();
            GameplayLog.event(pet, "badge", "disqualified cause=eloped owner={} day={} serial={}", owner, day, pet.pet().serial());
        }
    }
    public static void award(Player player, BadgeConduct.Case entry) {
        award(player, entry, "unauthorized_found");
        switch (entry.verdict().cause()) {
            case "exam" -> award(player, entry, "caught_at_exam");
            case "lie_exposed", "confession" -> award(player, entry, entry.verdict().cause());
        }
    }
    private static void award(Player player, BadgeConduct.Case entry, String event) {
        if (SurvivalProgress.award(player, event))
            GameplayLog.event(player, "badge", "advancement={} brood={} owner={}", event, entry.brood, player.getUUID());
    }

    public static PetPrivileges get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(PetPrivileges::new, PetPrivileges::load), NAME);
    }

    /** Keep the individual behind each earned household privilege, including while it is unloaded. */
    public static void note(YukkuriEntity pet) {
        if (!(pet.level() instanceof ServerLevel level) || !pet.pet().isPet() || pet.pet().owner() == null) return;
        var grade = pet.pet().effectiveGrade();
        if (grade == BadgeGrade.NONE) return;
        var data = get(level); var owner = pet.pet().owner();
        var pets = data.records.computeIfAbsent(owner, id -> new HashMap<>());
        int contribution = Math.min(grade.id(), BadgeGrade.GOLD.id());
        if (contribution > pets.getOrDefault(pet.getUUID(), 0)) {
            pets.put(pet.getUUID(), contribution);
            data.setDirty();
        }
    }

    /** The best of the recorded grades and the effective grades of their pets within 32 blocks. */
    public static BadgeGrade grade(Player player) {
        if (!(player.level() instanceof ServerLevel level)) return BadgeGrade.NONE;
        var data = get(level);
        int best = data.conduct.standing(data.records.getOrDefault(player.getUUID(), Map.of()));
        for (var y : level.getEntitiesOfClass(YukkuriEntity.class, player.getBoundingBox().inflate(32), e -> e.isAlive() && e.pet().isPet() && e.pet().ownedBy(player.getUUID())))
            best = Math.max(best, Math.min(y.pet().effectiveGrade().id(), BadgeGrade.GOLD.id()));
        return BadgeGrade.of(best);
    }
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

    public static int discountPercent(BadgeGrade effective) {
        return switch (effective) { case NONE, BRONZE -> 0; case SILVER -> 10; case GOLD, PLATINUM -> 20; };
    }

    private static Parent parent(YukkuriEntity y) {
        var style = y.pet().isPet() ? ArcStyle.choose(y.life().personality().temperament(), y.pet().obedience()) : null;
        return new Parent(y.getUUID(), style != null && y.pet().grade() != BadgeGrade.NONE ? y.pet().owner() : null, style);
    }
    public static void voluntaryBrood(UUID brood, YukkuriEntity mother, YukkuriEntity father) {
        var data = get((ServerLevel) mother.level());
        var a = parent(mother);
        var b = parent(father);
        long now = mother.level().getGameTime(), am = mother.pet().permitUntil(b.id()), bf = father.pet().permitUntil(a.id());
        boolean brokeHouseRule = am < now && mother.pet().conscience().binds(HouseRule.NO_SUKKIRI)
                || bf < now && father.pet().conscience().binds(HouseRule.NO_SUKKIRI);
        if (data.conduct.open(brood, a, am, b, bf, now, brokeHouseRule)) {
            data.setDirty();
            GameplayLog.event(mother, "badge", "pending brood={} mother={} father={}", brood, a, b);
            if (a.style() != null) GameplayLog.event(mother, "rules", "arc style={} brood={}", a.style(), brood);
            if (b.style() != null) GameplayLog.event(father, "rules", "arc style={} brood={}", b.style(), brood);
        }
    }
    public static void expire(ServerTickEvent.Post event) {
        var level = event.getServer().overworld();
        if (level.getGameTime() % 1200 != 0) return;
        var data = get(level);
        var families = FamilyData.get(level);
        for (var entry : List.copyOf(data.conduct.cases())) {
            if (entry.verdict() != null || !families.youngResolved(entry.brood)) continue;
            data.conduct.remove(entry);
            data.setDirty();
            GameplayLog.world(level, "badge", "pending expired brood={}", entry.brood);
        }
    }
    public void forget(Set<UUID> people, Set<UUID> broods) {
        conduct.forgetElopements(people);
        for (var entry : List.copyOf(conduct.cases()))
            if (broods.contains(entry.brood) || entry.parents.stream().anyMatch(p -> people.contains(p.id()))) conduct.remove(entry);
        records.values().forEach(pets -> pets.keySet().removeAll(people));
        records.values().removeIf(Map::isEmpty);
        setDirty();
    }

    private static CompoundTag saveParent(Parent parent) {
        var tag = new CompoundTag();
        tag.putUUID("Pet", parent.id());
        if (parent.owner() != null) tag.putUUID("Owner", parent.owner());
        if (parent.style() != null) tag.putString("Response", parent.style().name());
        return tag;
    }
    private static Parent loadParent(CompoundTag tag) {
        var owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        var style = tag.contains("Response") ? ArcStyle.valueOf(tag.getString("Response")) : owner != null ? ArcStyle.HIDE : null;
        return new Parent(tag.getUUID("Pet"), owner, style);
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var list = new ListTag();
        records.forEach((owner, pets) -> pets.forEach((pet, grade) -> {
            var c = new CompoundTag();
            c.putUUID("Player", owner); c.putUUID("Pet", pet); c.putInt("Grade", grade); list.add(c);
        }));
        tag.put("Contributions", list);
        var cases = new ListTag();
        for (var entry : conduct.cases()) {
            var c = new CompoundTag();
            c.putUUID("Brood", entry.brood);
            c.put("Mother", saveParent(entry.parents.getFirst()));
            c.put("Father", saveParent(entry.parents.getLast()));
            var verdict = entry.verdict();
            if (verdict != null) {
                c.putString("Cause", verdict.cause()); c.putLong("Day", verdict.day()); c.putUUID("Judge", verdict.judge());
            }
            cases.add(c);
        }
        tag.put("Conduct", cases);
        var elopers = new ListTag();
        conduct.elopements().forEach((pet, day) -> {
            var entry = new CompoundTag();
            entry.putUUID("Pet", pet); entry.putLong("Day", day); elopers.add(entry);
        });
        tag.put("Elopements", elopers);
        return tag;
    }
    public static PetPrivileges load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new PetPrivileges();
        for (var item : tag.getList("Elopements", 10)) {
            var entry = (CompoundTag) item;
            data.conduct.loadElopement(entry.getUUID("Pet"), entry.getLong("Day"));
        }
        for (var item : tag.getList("Contributions", 10)) {
            var c = (CompoundTag) item;
            data.records.computeIfAbsent(c.getUUID("Player"), id -> new HashMap<>()).put(c.getUUID("Pet"), c.getInt("Grade"));
        }
        for (var item : tag.getList("Conduct", 10)) {
            var c = (CompoundTag) item;
            var verdict = c.hasUUID("Judge") ? new BadgeConduct.Verdict(c.getString("Cause"), c.getLong("Day"), c.getUUID("Judge")) : null;
            data.conduct.add(new BadgeConduct.Case(c.getUUID("Brood"), loadParent(c.getCompound("Mother")), loadParent(c.getCompound("Father")), verdict));
        }
        return data;
    }
}
