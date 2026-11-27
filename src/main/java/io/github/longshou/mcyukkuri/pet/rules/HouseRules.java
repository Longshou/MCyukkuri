package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.pet.PetScenes;
import io.github.longshou.mcyukkuri.perception.Cue;
import io.github.longshou.mcyukkuri.perception.Senses;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;

public final class HouseRules extends SavedData {
    private record Entry(EnumSet<HouseRule> rules, int version) {}
    private static final Entry ORIGINAL = new Entry(EnumSet.copyOf(HouseRule.DEFAULT), 0);
    private final Map<UUID, Entry> households = new HashMap<>();
    /** The rule-told bow lasts 40 ticks (Conscience.toldBy); the answer to the change starts when it ends. */
    private static final int ANSWER_AFTER = 40;

    public static HouseRules get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                new Factory<>(HouseRules::new, HouseRules::load), "mcyukkuri_house_rules");
    }

    /** The household's rules back to the original ones, without any reaction (scenario setup only). */
    public void forget(UUID owner) {
        if (households.remove(owner) != null) setDirty();
    }

    public int version(UUID owner) { return households.getOrDefault(owner, ORIGINAL).version; }
    public boolean enabled(UUID owner, HouseRule rule) { return households.getOrDefault(owner, ORIGINAL).rules.contains(rule); }
    public int mask(UUID owner) {
        int mask = 0;
        for (var rule : households.getOrDefault(owner, ORIGINAL).rules) mask |= rule.bit();
        return mask;
    }

    public void toggle(Player owner, YukkuriEntity target, HouseRule rule) {
        var id = owner.getUUID();
        var entry = households.getOrDefault(id, ORIGINAL);
        var rules = entry.rules.clone();
        if (!rules.remove(rule)) rules.add(rule);
        int version = entry.version + 1;
        households.put(id, new Entry(rules, version));
        setDirty();
        GameplayLog.event(target, "rules", "set owner={} rule={} on={} version={}", id, rule.key(), rules.contains(rule), version);
        boolean loosened = !rules.contains(rule);
        // Every pet of the owner that saw or heard the change is told it and answers it; the pet in the care screen is facing the owner.
        var witnesses = new LinkedHashMap<YukkuriEntity, Senses.Via>();
        witnesses.put(target, Senses.Via.SAW);
        int delay = 0;
        for (var witness : Senses.witnesses((ServerLevel) owner.level(), owner.getEyePosition(), null, Cue.RULE_CHANGE)) {
            if (witness.body() != target && witness.body().pet().owned(owner)) witnesses.put(witness.body(), witness.via());
        }
        for (var noticed : witnesses.entrySet()) {
            var pet = noticed.getKey();
            GameplayLog.event(pet, "rules", "change noticed rule={} via={} version={}", rule.key(), noticed.getValue(), version);
            // Each pet bows ten ticks after the one before it, then answers once its bow is over.
            PetScenes.later(pet, delay, () -> {
                if (version(id) != version) return;
                pet.pet().conscience().toldBy(owner);
                answer(owner, pet, rule, version, loosened);
            });
            delay += 10;
        }
    }

    /** One answer per pet for this change, after its bow: a newer change (version) drops it. */
    private void answer(Player owner, YukkuriEntity pet, HouseRule rule, int version, boolean loosened) {
        PetScenes.later(pet, ANSWER_AFTER, () -> {
            if (version(owner.getUUID()) == version) pet.pet().conscience().ruleChanged(owner, rule, loosened);
        });
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var list = new ListTag();
        households.forEach((id, entry) -> {
            var saved = new CompoundTag();
            saved.putUUID("Owner", id);
            saved.putInt("Version", entry.version);
            for (var rule : entry.rules) saved.putBoolean(rule.key(), true);
            list.add(saved);
        });
        tag.put("Households", list);
        return tag;
    }

    private static HouseRules load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new HouseRules();
        for (var item : tag.getList("Households", 10)) {
            var saved = (CompoundTag) item;
            var rules = EnumSet.noneOf(HouseRule.class);
            for (var rule : HouseRule.values()) if (saved.getBoolean(rule.key())) rules.add(rule);
            if (saved.hasUUID("Owner")) data.households.put(saved.getUUID("Owner"), new Entry(rules, saved.getInt("Version")));
        }
        return data;
    }
}
