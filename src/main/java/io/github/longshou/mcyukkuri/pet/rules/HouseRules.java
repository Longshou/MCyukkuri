package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.pet.PetScenes;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.EnumSet;
import java.util.HashMap;
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
    private static final Entry ORIGINAL = new Entry(EnumSet.of(HouseRule.NO_SUKKIRI), 0);
    private final Map<UUID, Entry> households = new HashMap<>();

    public static HouseRules get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                new Factory<>(HouseRules::new, HouseRules::load), "mcyukkuri_house_rules");
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
        target.pet().conscience().tell(owner);
        int delay = 0;
        for (var peer : owner.level().getEntitiesOfClass(YukkuriEntity.class, owner.getBoundingBox().inflate(16),
                y -> y != target && y.pet().owned(owner) && HumanSight.sees(owner, y))) {
            PetScenes.later(peer, delay += 10, () -> {
                if (version(id) == version) peer.pet().conscience().tell(owner);
            });
        }
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
