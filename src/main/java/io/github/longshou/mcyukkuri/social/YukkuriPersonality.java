package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.pet.PetSkill;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/**
 * The five innate behavior axes and their bounded, gradual experience.
 *
 * Object closeness and fear deliberately live in {@link YukkuriWorldData}; storing either
 * here would create a second player-only relationship authority.
 */
public final class YukkuriPersonality {
    public enum Trait {
        INTELLIGENCE, FRIENDLINESS, BOLDNESS, ATTACHMENT, SELF_CONTROL
    }

    public enum FearBand { NORMAL, CAUTIOUS, AFRAID }

    /** Immutable five-axis birth result. It is the only trait payload a birth route should persist. */
    public record BirthProfile(int intelligence, int friendliness, int boldness, int attachment, int selfControl) {
        public BirthProfile {
            intelligence = Math.clamp(intelligence, 0, 100);
            friendliness = Math.clamp(friendliness, 0, 100);
            boldness = Math.clamp(boldness, 0, 100);
            attachment = Math.clamp(attachment, 0, 100);
            selfControl = Math.clamp(selfControl, 0, 100);
        }
        public int innate(Trait trait) {
            return switch (trait) {
                case INTELLIGENCE -> intelligence;
                case FRIENDLINESS -> friendliness;
                case BOLDNESS -> boldness;
                case ATTACHMENT -> attachment;
                case SELF_CONTROL -> selfControl;
            };
        }
        public int[] innateValues() { return new int[] {intelligence, friendliness, boldness, attachment, selfControl}; }
        public static BirthProfile fromInnateValues(int[] values) {
            if (values == null || values.length != Trait.values().length)
                throw new IllegalArgumentException("Five innate trait values are required");
            return new BirthProfile(values[0], values[1], values[2], values[3], values[4]);
        }
    }

    /** The legal interval for each axis of one visible form. */
    public static final class TraitBounds {
        private final int[] minimum;
        private final int[] maximum;
        private TraitBounds(int[] minimum, int[] maximum) {
            this.minimum = minimum.clone();
            this.maximum = maximum.clone();
        }
        public int minimum(Trait trait) { return minimum[trait.ordinal()]; }
        public int maximum(Trait trait) { return maximum[trait.ordinal()]; }
        public int clamp(Trait trait, int value) { return Math.clamp(value, minimum(trait), maximum(trait)); }
        public static TraitBounds forForm(boolean deficient, SubspeciesLife.Kind kind) {
            int[] minimum = {0, 0, 0, 0, 0};
            int[] maximum = {100, 100, 100, 100, 100};
            if (deficient) {
                maximum[Trait.INTELLIGENCE.ordinal()] = 25;
                minimum[Trait.FRIENDLINESS.ordinal()] = 75;
            } else if (kind == SubspeciesLife.Kind.WASA_REIMU) {
                maximum[Trait.FRIENDLINESS.ordinal()] = 25;
                minimum[Trait.ATTACHMENT.ordinal()] = 75;
                maximum[Trait.SELF_CONTROL.ordinal()] = 25;
            }
            return new TraitBounds(minimum, maximum);
        }
        private static TraitBounds ordinary() { return forForm(false, SubspeciesLife.Kind.NORMAL); }
    }

    private final YukkuriEntity owner;
    private final int[] innate = new int[Trait.values().length];
    private final int[] experience = new int[Trait.values().length];
    /** Real completed experiences accumulate before changing an axis by one point. */
    private final int[] experienceProgress = new int[Trait.values().length];
    private int talkWait;
    private String lastDecision = "none";

    public YukkuriPersonality(YukkuriEntity owner) {
        this.owner = Objects.requireNonNull(owner);
        UUID identity = owner.getUUID();
        Random random = new Random(identity.getMostSignificantBits() ^ identity.getLeastSignificantBits());
        for (int i = 0; i < innate.length; i++) innate[i] = random.nextInt(101);
        protectOwnerRelation();
    }

    private YukkuriWorldData relations() {
        return owner != null && owner.level() instanceof ServerLevel level ? YukkuriWorldData.get(level) : null;
    }

    private void protectOwnerRelation() {
        YukkuriWorldData data = relations();
        if (data == null || owner == null) return;
        // PetLife normally initializes before the constructor creates YukkuriLife. Keep this
        // binding safe for any superclass callback or alternate construction path that observes
        // the entity earlier; the normal personality tick will register it once PetLife exists.
        var pet = owner.pet();
        if (pet == null) return;
        UUID keeper = pet.owner();
        data.protectOwnerRelation(owner.getUUID(), keeper);
    }

    private TraitBounds bounds() {
        if (owner == null || owner.level().isClientSide) return TraitBounds.ordinary();
        return TraitBounds.forForm(owner.subspecies().congenital() != SubspeciesLife.Congenital.NORMAL, owner.subspecies().kind());
    }

    private int value(Trait trait) {
        return bounds().clamp(trait, innate[trait.ordinal()] + experience[trait.ordinal()]);
    }

    public int trait(Trait trait) { return value(trait); }
    public int intelligence() { return value(Trait.INTELLIGENCE); }
    public int friendliness() { return value(Trait.FRIENDLINESS); }
    public int boldness() { return value(Trait.BOLDNESS); }
    public int attachment() { return value(Trait.ATTACHMENT); }
    public int selfControl() { return value(Trait.SELF_CONTROL); }
    public int innate(Trait trait) { return innate[trait.ordinal()]; }
    public int experience(Trait trait) { return experience[trait.ordinal()]; }

    /** Keeps saved innate values and experience legal when a real form or scenario form changes. */
    public void constrainToForm() {
        TraitBounds bounds = bounds();
        for (Trait trait : Trait.values()) {
            int slot = trait.ordinal();
            innate[slot] = bounds.clamp(trait, innate[slot]);
            experience[slot] = Math.clamp(experience[slot], Math.max(-20, bounds.minimum(trait) - innate[slot]),
                    Math.min(20, bounds.maximum(trait) - innate[slot]));
            int effective = innate[slot] + experience[slot];
            if (effective == bounds.minimum(trait) && experienceProgress[slot] < 0
                    || effective == bounds.maximum(trait) && experienceProgress[slot] > 0) experienceProgress[slot] = 0;
        }
    }

    /** Scenario/setup write; the current form boundary is applied immediately. */
    public boolean setInnate(Trait trait, int value) {
        if (trait == null) return false;
        int bounded = bounds().clamp(trait, value);
        boolean changed = innate[trait.ordinal()] != bounded;
        innate[trait.ordinal()] = bounded;
        constrainToForm();
        return changed;
    }

    /** Scenario/setup write of an effective axis; returns false for an unavailable axis. */
    public boolean setTrait(Trait trait, int target) {
        if (trait == null) return false;
        int bounded = bounds().clamp(trait, target);
        int slot = trait.ordinal();
        int nextExperience = Math.clamp(bounded - innate[slot], -20, 20);
        int nextInnate = innate[slot] + nextExperience == bounded ? innate[slot] : bounds().clamp(trait, bounded - nextExperience);
        boolean changed = innate[slot] != nextInnate || experience[slot] != nextExperience;
        innate[slot] = nextInnate;
        experience[slot] = nextExperience;
        experienceProgress[slot] = 0;
        return changed;
    }

    /** Applies a child profile exactly once after its subspecies form has been initialized. */
    public void applyBirthProfile(BirthProfile profile) {
        if (profile == null) return;
        TraitBounds bounds = bounds();
        for (Trait trait : Trait.values()) {
            int slot = trait.ordinal();
            innate[slot] = bounds.clamp(trait, profile.innate(trait));
            experience[slot] = 0;
            experienceProgress[slot] = 0;
        }
    }

    /**
     * Deterministic inheritance shared by every birth route. Parent values are normalized inside
     * their own legal form interval before being mapped into the child's form interval.
     */
    public static BirthProfile inheritBirth(UUID childIdentity, YukkuriPersonality parentA,
            YukkuriPersonality parentB, boolean deficient, SubspeciesLife.Kind childKind) {
        TraitBounds childBounds = TraitBounds.forForm(deficient, childKind);
        int[] values = new int[Trait.values().length];
        for (Trait trait : Trait.values()) {
            double total = 0;
            int parents = 0;
            if (parentA != null) { total += parentA.normalizedInnate(trait); parents++; }
            if (parentB != null) { total += parentB.normalizedInnate(trait); parents++; }
            double normalized = parents == 0 ? .5 : total / parents;
            int min = childBounds.minimum(trait);
            Random random = new Random(childIdentity.getMostSignificantBits() ^ childIdentity.getLeastSignificantBits()
                    ^ (0x9E3779B97F4A7C15L * (trait.ordinal() + 1L)));
            double varied = Math.clamp(normalized + (random.nextInt(21) - 10) / 100.0, 0, 1);
            values[trait.ordinal()] = childBounds.clamp(trait, (int) Math.round(min + varied * (childBounds.maximum(trait) - min)));
        }
        return BirthProfile.fromInnateValues(values);
    }

    private double normalizedInnate(Trait trait) {
        TraitBounds bounds = bounds();
        int width = bounds.maximum(trait) - bounds.minimum(trait);
        return width == 0 ? .5 : Math.clamp((innate(trait) - bounds.minimum(trait)) / (double) width, 0, 1);
    }

    /**
     * Records one real, completed experience. Eight consistent units produce one bounded axis
     * change; animations, observations, and repeated use of an already-known skill must not call it.
     */
    public void experienced(Trait trait, int amount) {
        if (trait == null || amount == 0) return;
        int slot = trait.ordinal();
        experienceProgress[slot] = Math.clamp(experienceProgress[slot] + amount, -56, 56);
        while (Math.abs(experienceProgress[slot]) >= 8) {
            int direction = Integer.signum(experienceProgress[slot]);
            TraitBounds bounds = bounds();
            int lower = Math.max(-20, bounds.minimum(trait) - innate[slot]);
            int upper = Math.min(20, bounds.maximum(trait) - innate[slot]);
            int next = Math.clamp(experience[slot] + direction, lower, upper);
            if (next == experience[slot]) { experienceProgress[slot] = 0; break; }
            experience[slot] = next;
            experienceProgress[slot] -= direction * 8;
        }
    }

    /** Formula 1: familiar/urgent callers contribute the documented maximum bonuses. */
    public float helpProbability(boolean familiar, boolean urgent) {
        return Math.clamp(.10f + .008f * friendliness() + (familiar ? .10f : 0) + (urgent ? .10f : 0), .05f, .95f);
    }
    public float helpProbability(int closeness, boolean urgent) {
        return Math.clamp(.10f + .008f * friendliness() + Math.clamp(closeness / 1000f, -.10f, .10f)
                + (urgent ? .10f : 0), .05f, .95f);
    }
    /** Formula 3: one real teaching completion makes one independent draw. */
    public float learningProbability(float stress) {
        float pressurePenalty = Math.min(.15f, .003f * Math.max(stress - 30, 0));
        return Math.clamp(.15f + .0075f * intelligence() - pressurePenalty, .05f, .95f);
    }
    /** Formula 12: learned rules become deterministic from self-control 80 onward. */
    public float ruleProbability() {
        int control = selfControl();
        int[] x = {0, 20, 50, 70, 80, 100};
        float[] y = {.40f, .65f, .90f, .98f, 1f, 1f};
        for (int i = 1; i < x.length; i++)
            if (control <= x[i]) return y[i - 1] + (y[i] - y[i - 1]) * (control - x[i - 1]) / (x[i] - x[i - 1]);
        return 1f;
    }
    /** Formula 5, after a viable close, non-feared companion has been found. */
    public float companionshipProbability() { return Math.clamp(.10f + .008f * attachment(), .10f, .90f); }
    /** Formula 6, returned as simulation ticks. */
    public int waitingTicks() { return Math.round((1 + .1f * selfControl()) * 20); }
    /** Formula 13. */
    public int workSegments() { return Math.min(5, 1 + selfControl() / 25); }
    /** Formula 11 before the dependent-child attachment modifier. */
    public float exploreMultiplier() { return .50f + .01f * boldness(); }
    /** Formula 7; fear expands, but never erases, a physically grounded retreat distance. */
    public float threatDistance(int objectFear) {
        return Math.max(0, 9 - .05f * boldness() + Math.max(0, objectFear - 30) * .05f);
    }
    public float foodRecognitionProbability() { return Math.clamp(.35f + .006f * intelligence(), .35f, .95f); }

    public int trust(UUID object) { return relation(object).closeness(); }
    public int fear(UUID object) { return relation(object).fear(); }
    public FearBand fearBand(UUID object) { return FearBand.values()[relation(object).fearBand()]; }
    public long lastInteraction(UUID object) { return relation(object).lastInteraction(); }
    private YukkuriWorldData.Relation relation(UUID object) {
        YukkuriWorldData data = relations();
        // Birth assigns the reserved UUID after construction. Relations always follow that live identity.
        return data == null || object == null ? YukkuriWorldData.Relation.NONE : data.relation(owner.getUUID(), object);
    }
    /** The common behavior-event entry point; this writes the world relation authority exactly once. */
    public void relationshipChanged(UUID object, int closenessChange, int fearChange) {
        YukkuriWorldData data = relations();
        if (data != null && object != null) data.interact(owner.getUUID(), object, closenessChange, fearChange);
    }
    public void protectRelation(UUID object) {
        YukkuriWorldData data = relations();
        if (data != null) data.protectRelation(owner.getUUID(), object);
    }
    public void releaseRelation(UUID object) {
        YukkuriWorldData data = relations();
        if (data != null) data.releaseRelation(owner.getUUID(), object);
    }
    /** Called only by actual completed toileting, never cancelled or visual-only toilet actions. */
    public void relieveFear() {
        YukkuriWorldData data = relations();
        if (data != null) data.defecated(owner.getUUID());
    }

    public void tick() {
        if (talkWait > 0) talkWait--;
        protectOwnerRelation();
    }
    public String lastDecision() { return lastDecision; }
    public void decided(String reason) { lastDecision = reason == null || reason.length() > 64 ? "none" : reason; }
    public boolean knowsWait() { return owner != null && owner.pet().lessons().knows(PetSkill.WAIT); }

    // Behavior queries derived from the five axes and the world relation.
    public int shareScore(float nutrition, float peerNutrition, boolean child, int closeness) {
        if (nutrition <= 20) return 0;
        return Math.round(100 * helpProbability(closeness, child || peerNutrition < nutrition));
    }
    public int escalationScore() { return Math.round(100 * Math.clamp(.60f * (1 - friendliness() / 100f) - .003f * selfControl(), 0, .60f)); }
    public boolean protects() { return friendliness() >= Temperament.PROTECT_FRIENDLINESS; }
    public int affectionThreshold() { return 65 - attachment() / 2; }

    public boolean talk(UUID object, boolean gentle) {
        if (talkWait > 0) return false;
        talkWait = 160 + (100 - selfControl());
        relationshipChanged(object, gentle ? 3 : -6, gentle ? -2 : 8);
        return true;
    }
    public void fed(UUID object) { relationshipChanged(object, 8, -5); }
    public void answeredRequest(UUID object) { relationshipChanged(object, 3, -2); }
    public void washed(UUID object) { relationshipChanged(object, 4, -3); }
    public void hurt(UUID object) { attacked(object, false); }
    public void attacked(UUID source, boolean ignoredPlayerFlag) { relationshipChanged(source, -20, 25); }
    public void accessoryTaken(UUID object) { relationshipChanged(object, -10, 12); }
    public void accessoryReturned(UUID object) { relationshipChanged(object, 6, -6); }
    public void treasureTaken(UUID object) { relationshipChanged(object, -10, 14); }
    public void confiscated(UUID object) { relationshipChanged(object, -6, 8); }
    public boolean treatment(UUID object, boolean favored) {
        if (talkWait > 0) return false;
        talkWait = 240;
        relationshipChanged(object, favored ? 4 : -10, favored ? -3 : 12);
        return true;
    }
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Version", 1);
        tag.putIntArray("Innate", innate);
        tag.putIntArray("Experience", experience);
        tag.putIntArray("ExperienceProgress", experienceProgress);
        tag.putInt("TalkWait", talkWait);
        tag.putString("LastDecision", lastDecision);
        return tag;
    }

    /** Only the five-axis format is read. This playtest-only change deliberately has no legacy conversion. */
    public void load(CompoundTag tag) {
        int[] born = tag.getIntArray("Innate");
        int[] learned = tag.getIntArray("Experience");
        int[] progress = tag.getIntArray("ExperienceProgress");
        TraitBounds bounds = bounds();
        if (born.length == Trait.values().length)
            for (Trait trait : Trait.values()) innate[trait.ordinal()] = bounds.clamp(trait, born[trait.ordinal()]);
        for (Trait trait : Trait.values()) {
            int slot = trait.ordinal();
            innate[slot] = bounds.clamp(trait, innate[slot]);
            experience[slot] = learned.length == Trait.values().length ? Math.clamp(learned[slot], -20, 20) : 0;
            experienceProgress[slot] = progress.length == Trait.values().length ? Math.clamp(progress[slot], -7, 7) : 0;
            experience[slot] = Math.clamp(experience[slot],
                    Math.max(-20, bounds.minimum(trait) - innate[slot]),
                    Math.min(20, bounds.maximum(trait) - innate[slot]));
        }
        constrainToForm();
        talkWait = Math.clamp(tag.getInt("TalkWait"), 0, 300);
        lastDecision = tag.contains("LastDecision") ? tag.getString("LastDecision") : "none";
        if (lastDecision.length() > 64) lastDecision = "none";
    }
}
