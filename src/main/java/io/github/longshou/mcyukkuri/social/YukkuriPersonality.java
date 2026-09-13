package io.github.longshou.mcyukkuri.social;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Innate traits, gradual experience, and independent memories for each real player. */
public final class YukkuriPersonality {
    public enum Trait { CURIOSITY, SOCIABILITY, BOLDNESS, CARE, ORDER, PATIENCE }
    public static final class Memory {
        private int trust;
        private int fear;
        private int interactions;
        private int talkWait;
        public int trust() { return trust; }
        public int fear() { return fear; }
        public int interactions() { return interactions; }
        public int talkWait() { return talkWait; }
    }
    private final int[] innate = new int[6];
    private final int[] experience = new int[6];
    private final Map<UUID, Memory> memories = new HashMap<>();
    private int lessons;
    public YukkuriPersonality(UUID identity) {
        Random random = new Random(identity.getMostSignificantBits() ^ identity.getLeastSignificantBits());
        for (int i=0; i<6; i++) innate[i] = 20 + random.nextInt(61);
    }
    public int trait(Trait trait) { return Math.clamp(innate[trait.ordinal()] + experience[trait.ordinal()], 0, 100); }
    public int innate(Trait trait) { return innate[trait.ordinal()]; }
    public int experience(Trait trait) { return experience[trait.ordinal()]; }
    public int lessons() { return lessons; }
    public boolean knowsWait() { return lessons >= 3; }
    public int trust(UUID player) { Memory memory = memories.get(player); return memory == null ? 0 : memory.trust; }
    public int fear(UUID player) { Memory memory = memories.get(player); return memory == null ? 0 : memory.fear; }
    public int interactions(UUID player) { Memory memory = memories.get(player); return memory == null ? 0 : memory.interactions; }
    public void tick() { for (Memory memory : memories.values()) if (memory.talkWait > 0) memory.talkWait--; }
    private Memory memory(UUID player) {
        if (!memories.containsKey(player) && memories.size() >= 64) {
            UUID least = memories.entrySet().stream().min(java.util.Comparator.comparingInt(e -> e.getValue().interactions)).orElseThrow().getKey();
            memories.remove(least);
        }
        return memories.computeIfAbsent(player, id -> new Memory());
    }
    public void experienced(Trait trait, int amount) { int i = trait.ordinal(); experience[i] = Math.clamp(experience[i] + amount, -25, 25); }
    private void remember(UUID player, int trust, int fear) {
        if (player == null) return;
        Memory memory = memory(player);
        memory.trust = Math.clamp(memory.trust + trust, -100, 100);
        memory.fear = Math.clamp(memory.fear + fear, 0, 100);
        memory.interactions++;
    }
    public boolean talk(UUID player, boolean gentle) {
        Memory memory = memory(player);
        if (memory.talkWait > 0) return false;
        memory.talkWait = 160 + (100 - trait(Trait.PATIENCE));
        remember(player, gentle ? 3 : -6, gentle ? -2 : 8);
        experienced(Trait.SOCIABILITY, gentle ? 1 : -1);
        return true;
    }
    public void fed(UUID player) { remember(player, 8, -5); experienced(Trait.CARE, 1); }
    public void washed(UUID player) { remember(player, 4, -2); experienced(Trait.PATIENCE, 1); }
    public void hurt(UUID player) { remember(player, -20, 25); experienced(Trait.BOLDNESS, -2); experienced(Trait.PATIENCE, -1); }
    public void accessoryTaken(UUID player) { remember(player, -10, 12); }
    public void accessoryReturned(UUID player) { remember(player, 6, -6); }
    public void confiscated(UUID player){remember(player,-6,8);}
    public boolean treatment(UUID player,boolean favored){
        var memory=memory(player);if(memory.talkWait>0)return false;memory.talkWait=240;
        remember(player,favored?6:-10,favored?-3:12);experienced(Trait.PATIENCE,favored?1:-1);return true;
    }
    public void lessonCompleted(UUID player) {
        lessons = Math.min(3, lessons + 1); remember(player, 3, -1);
        experienced(Trait.ORDER, 2); experienced(Trait.PATIENCE, 2);
    }
    public int approachScore(UUID player) {
        return trait(Trait.BOLDNESS) / 2 + trait(Trait.SOCIABILITY) / 2 + trust(player) - fear(player) * 2;
    }
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); tag.putIntArray("Innate", innate); tag.putIntArray("Experience", experience); tag.putInt("Lessons", lessons);
        ListTag players = new ListTag();
        memories.forEach((id, memory) -> {
            CompoundTag value = new CompoundTag(); value.putUUID("Player", id); value.putInt("Trust", memory.trust);
            value.putInt("Fear", memory.fear); value.putInt("Interactions", memory.interactions); value.putInt("TalkWait", memory.talkWait); players.add(value);
        });
        tag.put("Players", players); return tag;
    }
    public void load(CompoundTag tag) {
        int[] born = tag.getIntArray("Innate"), learned = tag.getIntArray("Experience");
        for (int i=0; i<6; i++) {
            if (born.length == 6) innate[i] = Math.clamp(born[i], 0, 100);
            experience[i] = learned.length == 6 ? Math.clamp(learned[i], -25, 25) : 0;
        }
        lessons = Math.clamp(tag.getInt("Lessons"), 0, 3); memories.clear();
        for (Tag entry : tag.getList("Players", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag)entry;
            if (!value.hasUUID("Player") || memories.size() >= 64) continue;
            Memory memory = new Memory(); memory.trust = Math.clamp(value.getInt("Trust"), -100, 100);
            memory.fear = Math.clamp(value.getInt("Fear"), 0, 100); memory.interactions = Math.max(0, value.getInt("Interactions"));
            memory.talkWait = Math.clamp(value.getInt("TalkWait"), 0, 300); memories.put(value.getUUID("Player"), memory);
        }
    }
}
