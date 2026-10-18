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
    public enum Trait {
        CURIOSITY(0), BOLDNESS(2);
        private final int legacySlot;
        Trait(int legacySlot) { this.legacySlot = legacySlot; }
    }
    public enum Disposition { SELFISHNESS, AGGRESSION, ATTACHMENT, SELF_CONTROL }
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
    // Legacy wire order: curiosity, sociability, boldness, care, order, patience.
    // Keep all six slots for old saves and inheritance; four are no longer public traits.
    private final int[] innate = new int[6];
    private final int[] experience = new int[6];
    private final int[] dispositions = new int[4], learnedDispositions = new int[4], progress = new int[4];
    private final long[] nextExperience = new long[4];
    private final EnvironmentExperience environment = new EnvironmentExperience();
    private String lastDecision = "none";
    private final Map<UUID, Memory> memories = new HashMap<>();
    private int lessons;
    public YukkuriPersonality(UUID identity) {
        Random random = new Random(identity.getMostSignificantBits() ^ identity.getLeastSignificantBits());
        for (int i=0; i<6; i++) innate[i] = 20 + random.nextInt(61);
        for (int i=0; i<4; i++) dispositions[i] = (random.nextInt(101) + random.nextInt(101)) / 2;
    }
    /** Preference lookup by trait; aggression never aliases boldness. */
    public int trait(Trait trait) { return Math.clamp(innate[trait.legacySlot] + experience[trait.legacySlot], 0, 100); }
    public int disposition(Disposition value) { int i=value.ordinal(); return Math.clamp(dispositions[i]+learnedDispositions[i],0,100); }
    public int innate(Disposition value) { return dispositions[value.ordinal()]; }
    public int selfishness() { return disposition(Disposition.SELFISHNESS); }
    public int aggression() { return disposition(Disposition.AGGRESSION); }
    public int attachment() { return disposition(Disposition.ATTACHMENT); }
    public int selfControl() { return disposition(Disposition.SELF_CONTROL); }
    public EnvironmentExperience environment() { return environment; }
    public String lastDecision() { return lastDecision; }
    public void decided(String reason) { lastDecision=reason; }
    public int shareScore(float nutrition, float peerNutrition, boolean child, int affinity) {
        if(nutrition<=20)return 0;
        return Math.clamp(Math.round(50-.7f*(selfishness()-50)+.25f*(selfControl()-50)
                +Math.clamp((nutrition-peerNutrition)/2,-20,20)+(child?20:0)+Math.clamp(affinity/4,-20,20)),5,95);
    }
    public int queueDuration(float nutrition) {
        return nutrition<=15?0:Math.clamp(selfControl()+40-selfishness(),0,120);
    }
    public int escalationScore() { return Math.clamp(aggression() + (selfishness()-50)/2 - selfControl()/2,0,100); }
    public boolean protects() { return Temperament.protects(this); }
    public int affectionThreshold() { return 65 - attachment()/2; }
    public int lessonDuration() { return 150-selfControl(); }
    public int innate(Trait trait) { return innate[trait.legacySlot]; }
    public int experience(Trait trait) { return experience[trait.legacySlot]; }
    /** Original six-slot value for hereditary wire data, not a behavioral disposition lookup. */
    public int legacyInnate(int slot) { return innate[slot]; }
    public int lessons() { return lessons; }
    public boolean knowsWait() { return lessons >= 3; }
    /** Read-only view of what this individual remembers about each player (status registry). */
    public Map<UUID, Memory> knownPlayers() { return java.util.Collections.unmodifiableMap(memories); }
    public int trust(UUID player) { Memory memory = memories.get(player); return memory == null ? 0 : memory.trust; }
    public int fear(UUID player) { Memory memory = memories.get(player); return memory == null ? 0 : memory.fear; }
    public int interactions(UUID player) { Memory memory = memories.get(player); return memory == null ? 0 : memory.interactions; }
    public void tick() { environment.tick(); for (Memory memory : memories.values()) if (memory.talkWait > 0) memory.talkWait--; }
    private Memory memory(UUID player) {
        if (!memories.containsKey(player) && memories.size() >= 64) {
            UUID least = memories.entrySet().stream().min(java.util.Comparator.comparingInt(e -> e.getValue().interactions)).orElseThrow().getKey();
            memories.remove(least);
        }
        return memories.computeIfAbsent(player, id -> new Memory());
    }
    public void experienced(Trait trait, int amount) {
        int i=trait.legacySlot; experience[i]=Math.clamp(experience[i]+amount,-25,25);
    }
    public void experienced(Disposition value,int amount) {
        int i=value.ordinal(); if(amount==0||environment.clock()<nextExperience[i])return;
        nextExperience[i]=environment.clock()+1200;
        progress[i]+=Integer.signum(amount);
        if(Math.abs(progress[i])>=8) { learnedDispositions[i]=Math.clamp(learnedDispositions[i]+Integer.signum(progress[i]),-25,25); progress[i]=0; }
    }
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
        memory.talkWait = 160 + (100 - selfControl());
        remember(player, gentle ? 3 : -6, gentle ? -2 : 8);
        experienced(Disposition.ATTACHMENT, gentle ? 1 : -1);
        environment.record(player,gentle?"comfort":"scold",gentle?2:-6);
        return true;
    }
    public void fed(UUID player) { remember(player, 8, -5); if(environment.record(player,"feed",4))experienced(Disposition.SELFISHNESS, -1); }
    /** The owner answered something this pet asked for (docs/PET_SHOP.md section 12): a small step towards trust. */
    public void answeredRequest(UUID player) { remember(player, 3, -1); environment.record(player, "answered", 2); }
    public void washed(UUID player) { remember(player, 4, -2); if(environment.record(player,"wash",3))experienced(Disposition.SELF_CONTROL, 1); }
    public void hurt(UUID player) { attacked(player,true); }
    public void attacked(UUID source,boolean player) {
        if(player)remember(source,-20,25);
        if(environment.record(source,"attack",-12)) {
            experienced(Trait.BOLDNESS,-2);experienced(Disposition.SELF_CONTROL,-1);
            experienced(Disposition.AGGRESSION,1);
        }
    }
    public void accessoryTaken(UUID player) { remember(player, -10, 12); environment.record(player,"accessory_taken",-8); }
    public void accessoryReturned(UUID player) { remember(player, 6, -6); environment.record(player,"accessory_returned",4); }
    public void treasureTaken(UUID player){remember(player,-10,14);environment.record(player,"treasure_taken",-8);}
    public void confiscated(UUID player){remember(player,-6,8);environment.record(player,"confiscated",-6);}
    public boolean treatment(UUID player,boolean favored){
        var memory=memory(player);if(memory.talkWait>0)return false;memory.talkWait=240;
        remember(player,favored?6:-10,favored?-3:12);environment.record(player,favored?"care":"mistreatment",favored?3:-8);experienced(Disposition.SELF_CONTROL,favored?1:-1);return true;
    }
    public void lessonCompleted(UUID player) {
        lessons = Math.min(3, lessons + 1); remember(player, 3, -1);
        // Preserve the two former ORDER/PATIENCE calls; the second shares the first one's cooldown.
        experienced(Disposition.SELF_CONTROL, 2); experienced(Disposition.SELF_CONTROL, 2);
        decided("lesson_completed");
    }
    public int approachScore(UUID player) {
        return trait(Trait.BOLDNESS) / 2 + attachment() / 2 + trust(player) - fear(player) * 2;
    }
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); tag.putIntArray("Innate", innate); tag.putIntArray("Experience", experience); tag.putInt("Lessons", lessons);
        tag.putInt("Version",3); tag.putIntArray("DispositionInnate",dispositions); tag.putIntArray("DispositionExperience",learnedDispositions);
        tag.putIntArray("DispositionProgress",progress); tag.putLongArray("NextExperience",nextExperience); tag.put("Environment",environment.save()); tag.putString("LastDecision",lastDecision);
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
        environment.load(tag.getCompound("Environment"));
        int[] core=tag.getIntArray("DispositionInnate"), learnedCore=tag.getIntArray("DispositionExperience"), buffered=tag.getIntArray("DispositionProgress");
        long[] waits=tag.getLongArray("NextExperience");
        if(tag.getInt("Version")<2||core.length!=4) {
            core=new int[]{100-innate[3],50,innate[1],(innate[4]+innate[5])/2};
            learnedCore=new int[]{-experience[3],0,experience[1],(experience[4]+experience[5])/2};
        }
        for(int i=0;i<4;i++) {
            dispositions[i]=Math.clamp(core[i],0,100); learnedDispositions[i]=learnedCore.length==4?Math.clamp(learnedCore[i],-25,25):0;
            progress[i]=buffered.length==4?Math.clamp(buffered[i],-7,7):0;
            nextExperience[i]=waits.length==4?Math.clamp(waits[i],0,environment.clock()+1200):0;
        }
        lastDecision=tag.contains("LastDecision")?tag.getString("LastDecision"):"none";
        if(lastDecision.length()>64)lastDecision="none";
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
